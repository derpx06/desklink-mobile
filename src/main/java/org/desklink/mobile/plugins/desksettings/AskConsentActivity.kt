/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.plugins.desksettings

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.DeskLinkTheme
import org.desklink.mobile.ui.compose.PrimaryButton
import org.desklink.mobile.ui.compose.tokens

/**
 * Allow on a desk's ask (the notification's action or the app's banner): the person already said yes, so the per-desk setting goes
 * on and what Android must grant is asked for at once, on this small screen: the runtime permission dialog, or the special-access
 * page (notification access, Do Not Disturb, accessibility). Then the desk hears the new state and this screen goes.
 */
class AskConsentActivity : ComponentActivity() {
    private var feature: Asks.Feature? = null
    private var deskId: String? = null

    /** A special-access page was opened: the next resume is the person coming back from it. */
    private var awaySpecial = false

    private val request = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        org.desklink.mobile.helpers.PermissionRecovery.onResult(this, it.values.any { granted -> granted })
        done()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val desk = intent.getStringExtra(Asks.EXTRA_DESK)
        val feature = Asks.Feature.of(intent.getStringExtra(Asks.EXTRA_KEY))
        val device = desk?.let { runCatching { DeskLinkApplication.getInstance().getDevice(it) }.getOrNull() }
        if (feature == null || device == null || !device.isPaired) return finish()
        this.feature = feature
        deskId = desk
        awaySpecial = savedInstanceState?.getBoolean(STATE_AWAY) ?: false
        awayPaused = awaySpecial
        val title = getString(R.string.ask_prompt, device.name, getString(feature.phrase))
        val next = getString(if (feature.kind == Asks.Kind.PERMISSION) R.string.ask_consent_permission else R.string.ask_consent_special)
        setContent { DeskLinkTheme(this) { AskConsentContent(title, next, ::done) } }
        if (savedInstanceState != null) return
        Asks.forget(this, desk, feature.key)
        // The person tapped Allow: the switch for that desk goes on now; Android's part follows.
        val context = applicationContext
        org.desklink.mobile.helpers.ThreadHelper.execute {
            runCatching { Asks.turnOnSetting(context, device, feature) }
            settingOn.countDown()
        }
        when {
            feature == Asks.Feature.GLIDE -> {
                org.desklink.mobile.plugins.inputdevicesreceiver.InputDevicesReceiverPlugin.answered(this, desk, allow = true)
                if (org.desklink.mobile.plugins.mousereceiver.MouseReceiverService.instance == null) {
                    org.desklink.mobile.plugins.mousereceiver.AccessibilitySetupActivity.open(this)
                }
                done()
            }
            feature == Asks.Feature.CONTROL -> {
                // Only the accessibility setup (disclosure, then Android's page): nothing is turned on from here.
                if (!org.desklink.mobile.plugins.mousereceiver.AccessibilitySetupActivity.running()) {
                    org.desklink.mobile.plugins.mousereceiver.AccessibilitySetupActivity.open(this)
                }
                done()
            }
            feature.kind == Asks.Kind.PERMISSION && feature.permissions().any { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED } ->
                org.desklink.mobile.helpers.PermissionRecovery.requestOrExplain(this, feature.permissions().toTypedArray(), request::launch)
            feature.specialAction() != null && !specialGranted(feature) -> {
                awaySpecial = runCatching { startActivity(Intent(feature.specialAction())) }.isSuccess
                if (!awaySpecial) done()
            }
            feature == Asks.Feature.PHOTOS && org.desklink.mobile.plugins.gallery.GalleryWatcher.folders(this, desk).isEmpty() -> {
                // The folders it watches are picked by hand: the computer's Files settings, where the picker is.
                startActivity(
                    Intent(this, org.desklink.mobile.ui.MainActivity::class.java)
                        .putExtra(org.desklink.mobile.ui.MainActivity.EXTRA_SETTINGS_PAGE, org.desklink.mobile.ui.compose.screen.settings.SettingsPage.FILES.route)
                        .putExtra(org.desklink.mobile.ui.MainActivity.EXTRA_DEVICE_ID, desk)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                )
                done()
            }
            else -> done()
        }
    }

    private fun specialGranted(feature: Asks.Feature) = when (feature) {
        Asks.Feature.NOTIFICATIONS, Asks.Feature.MEDIA -> org.desklink.mobile.plugins.notifications.NotificationReceiver.hasReadNotificationsPermission(this)
        Asks.Feature.DND -> org.desklink.mobile.plugins.battery.DndSync.canSet(this)
        else -> true
    }

    override fun onResume() {
        super.onResume()
        // Back from the special-access page: whatever the person chose there, the desk hears it.
        if (awaySpecial && awayPaused && !isFinishing) {
            awaySpecial = false
            window.decorView.post { done() }
        }
    }

    /** The special-access page covered this screen once (the first resume comes before it does). */
    private var awayPaused = false

    override fun onPause() {
        super.onPause()
        if (awaySpecial) awayPaused = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_AWAY, awaySpecial)
    }

    /** The setting went on (off the main thread): the report waits for it, so it says what is true. */
    private val settingOn = java.util.concurrent.CountDownLatch(1)

    private fun done() {
        val latch = settingOn
        deskId?.let { desk -> org.desklink.mobile.helpers.ThreadHelper.execute { latch.await(5, java.util.concurrent.TimeUnit.SECONDS); Asks.report(desk) } }
        if (!isFinishing) finish()
    }

    companion object {
        private const val STATE_AWAY = "awaySpecial"
    }
}

/** The small screen under Android's own dialog: who asks for what, and what comes next. */
@Composable
fun AskConsentContent(title: String, next: String, onDone: () -> Unit) {
    val t = tokens
    Column(
        Modifier.fillMaxSize().background(t.bg).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, androidx.compose.ui.Alignment.CenterVertically),
    ) {
        Text(title, fontSize = 22.sp, lineHeight = 28.sp, color = t.text)
        Text(next, fontSize = 15.sp, lineHeight = 21.sp, color = t.textDim)
        PrimaryButton(androidx.compose.ui.res.stringResource(R.string.ask_consent_done), onDone)
    }
}
