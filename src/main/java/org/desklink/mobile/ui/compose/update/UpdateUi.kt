/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.desklink.mobile.BuildConfig
import org.desklink.mobile.R
import org.desklink.mobile.update.Channel
import org.desklink.mobile.update.Component
import org.desklink.mobile.update.FailReason
import org.desklink.mobile.update.InstallSource
import org.desklink.mobile.update.UpdateEngine
import org.desklink.mobile.update.UpdateOpenCoordinator
import org.desklink.mobile.update.UpdateRuntime
import org.desklink.mobile.update.UpdateState
import org.desklink.mobile.update.Version

/** What the Updates controls show about the last check. */
internal sealed interface CheckStatus {
    data object Idle : CheckStatus
    data object Checking : CheckStatus
    data class UpToDate(val version: String, val checkedAt: String) : CheckStatus
    data object Failed : CheckStatus
}

/** The Settings > About > Updates controls, as plain data. */
internal data class UpdatesSettingsModel(
    val configured: Boolean,
    val enabled: Boolean,
    val wifiOnly: Boolean,
    val channel: Channel,
    /** The manifest has a stable entry, so Stable can be picked. */
    val stableSelectable: Boolean,
    val status: CheckStatus,
    val skippedVersion: String?,
)

internal class UpdatesSettingsActions(
    val onCheck: () -> Unit,
    val onEnabled: (Boolean) -> Unit,
    val onWifiOnly: (Boolean) -> Unit,
    val onChannel: (Channel) -> Unit,
    val onUndoSkip: () -> Unit,
)

/**
 * The glue between the update module and its two places on screen: the sheet (hosted by MainActivity) and the Settings
 * controls. It is the only UI-facing caller of the module. Nothing here posts a notification, a badge or a card.
 */
internal class UpdateUi(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Anything else is up (a dialog, a pairing or transfer prompt, a permission step, the first-run tour): the sheet waits for the next open. */
    private val blocked: () -> Boolean,
) {
    /** No update address in this build (and no debug override to look for): nothing is created, read or run. */
    private val inert = BuildConfig.DEFAULT_UPDATE_URL.isBlank() && !BuildConfig.DEBUG

    private val holder by lazy { UpdateRuntime.get(context) }
    private val engine: UpdateEngine get() = holder.engine
    private val store get() = holder.store
    // blocked() reads the activity's screen state and fragment manager: it is evaluated on the main thread, never on the IO thread the coordinator runs on.
    private val coordinator by lazy { UpdateOpenCoordinator(engine, store, scope) { withContext(Dispatchers.Main) { blocked() } } }

    /** The sheet showing now, or null. */
    private class Showing(val state: UpdateState, val model: UpdateSheetModel)
    private var showing by mutableStateOf<Showing?>(null)
    private var version by mutableIntStateOf(0)
    private var status by mutableStateOf<CheckStatus>(CheckStatus.Idle)

    val sheetModel: UpdateSheetModel? get() = showing?.model

    val sheetActions = UpdateSheetActions(
        onPrimary = { showing?.let { open(it.model) }; dismiss(skip = false) },
        onNotNow = { dismiss(skip = false) },
        onSkip = { dismiss(skip = true) },
        onCopyChecksum = { sha ->
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("SHA-256", sha))
            // Android 13 and later show their own "copied" confirmation.
            if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(context, R.string.update_checksum_copied, Toast.LENGTH_SHORT).show()
        },
    )

    /** A launcher launch of the main activity: decided once, from the cache, off the main thread. */
    fun onOpen(openId: String) {
        if (inert) return
        if (!runCatching { engine.configured() }.getOrDefault(false)) return
        scope.launch(Dispatchers.IO) {
            val state = runCatching { coordinator.onOpen(openId) }.getOrNull() ?: return@launch
            show(state)
        }
    }

    private fun show(state: UpdateState) {
        val c = when (state) { is UpdateState.Available -> state.component; is UpdateState.Required -> state.component; else -> return }
        showing = Showing(state, model(c, state is UpdateState.Required))
    }

    private fun model(c: Component, required: Boolean): UpdateSheetModel {
        val source = InstallSource.detect(context)
        return UpdateSheetModel(
            version = c.version.toString(),
            installed = Version.parseLenient(BuildConfig.VERSION_NAME)?.toString() ?: BuildConfig.VERSION_NAME,
            beta = store.channel(Version.parseLenient(BuildConfig.VERSION_NAME) ?: Version(0, 0, 0)) == Channel.BETA,
            notes = c.notes,
            source = source,
            sizeText = c.size?.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) },
            sha256 = c.apkSha256,
            required = required,
            requiredReason = c.requiredReason,
            requiredMajorMinor = "${c.version.major}.${c.version.minor}",
        )
    }

    private fun dismiss(skip: Boolean) {
        val s = showing ?: return
        showing = null
        // Off the main thread: the store writes preferences.
        scope.launch(Dispatchers.IO) {
            if (skip) coordinator.skip(s.state) else coordinator.notNow(s.state)
            version++
        }
    }

    /** The one action for how DeskLink was installed: a store page or the release page. Nothing is downloaded or installed here. */
    private fun open(m: UpdateSheetModel) {
        val c = (showing?.state as? UpdateState.Available)?.component ?: (showing?.state as? UpdateState.Required)?.component
        val web = when (m.source) {
            InstallSource.GOOGLE_PLAY -> "https://play.google.com/store/apps/details?id=$PACKAGE"
            InstallSource.FDROID -> "https://f-droid.org/packages/$PACKAGE/"
            InstallSource.GITHUB -> c?.apkUrl ?: c?.releaseUrl ?: RELEASES
            InstallSource.OTHER_STORE -> c?.releaseUrl ?: RELEASES
        }
        // The store link is built here from this app's own package id, never taken from the manifest.
        val intents = buildList {
            if (m.source == InstallSource.GOOGLE_PLAY) add(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE")))
            add(Intent(Intent.ACTION_VIEW, Uri.parse(web)))
        }.map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        val opened = intents.any { runCatching { context.startActivity(it) }.isSuccess }
        if (!opened) Toast.makeText(context, R.string.update_no_browser, Toast.LENGTH_SHORT).show()
    }

    // --- Settings

    fun settingsModel(): UpdatesSettingsModel {
        if (inert) return UpdatesSettingsModel(false, false, false, Channel.BETA, false, CheckStatus.Idle, null)
        @Suppress("UNUSED_EXPRESSION") version // re-read after a change
        val configured = engine.configured()
        val installed = Version.parseLenient(BuildConfig.VERSION_NAME) ?: Version(0, 0, 0)
        return UpdatesSettingsModel(
            configured = configured,
            enabled = store.enabled,
            wifiOnly = store.wifiOnly,
            channel = store.channel(installed),
            stableSelectable = configured && engine.cachePayload()?.channels?.get(Channel.STABLE) != null,
            status = status,
            skippedVersion = store.skippedVersion,
        )
    }

    val settingsActions = UpdatesSettingsActions(
        onCheck = ::manualCheck,
        onEnabled = { store.enabled = it; version++ },
        onWifiOnly = { store.wifiOnly = it; version++ },
        onChannel = { store.setChannel(it); version++ },
        onUndoSkip = { engine.undoSkip(); version++ },
    )

    /** "Check for updates": ignores rollout, skip and Later; an update found opens the sheet at once. */
    private fun manualCheck() {
        if (status == CheckStatus.Checking) return
        val before = status
        status = CheckStatus.Checking
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val outcome = engine.refresh(manual = true)
                    outcome to engine.state(manual = true)
                }.getOrNull()
            }
            val (outcome, state) = result ?: (UpdateEngine.Outcome.Failed(FailReason.URL) to UpdateState.UpToDate)
            status = when {
                outcome is UpdateEngine.Outcome.Failed -> CheckStatus.Failed
                state is UpdateState.Available || state is UpdateState.Required -> { show(state); CheckStatus.Idle }
                // Nothing was asked (too soon after the last check): never claim "up to date" from a stale cache; the old status stays.
                outcome is UpdateEngine.Outcome.Skipped -> before.takeUnless { it == CheckStatus.Checking } ?: CheckStatus.Idle
                else -> CheckStatus.UpToDate(BuildConfig.VERSION_NAME.substringBefore('-'), checkedText())
            }
            version++
        }
    }

    /** Short, so the row keeps to one line beside its button: the time today, the date otherwise. */
    private fun checkedText(): String = System.currentTimeMillis().let {
        DateUtils.formatDateTime(context, it, if (DateUtils.isToday(it)) DateUtils.FORMAT_SHOW_TIME else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL)
    }

    private companion object {
        const val PACKAGE = "org.desklink.mobile"
        const val RELEASES = "https://github.com/derpx06/desklink-mobile/releases"
    }
}
