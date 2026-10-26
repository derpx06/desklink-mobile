/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import org.desklink.mobile.R

/**
 * An "Allow" button that does nothing is the worst answer. Once Android stops asking (the person said no twice, or "don't ask
 * again") a request returns "denied" at once with no dialog; from then on the only way is the app's own permission page, so that
 * is where the button goes, with one line saying why.
 */
object PermissionRecovery {
    private const val PREFS = "permissions_asked"

    /** Pure: the system will not ask again when we asked before and it offers no rationale. */
    fun mustOpenSettings(askedBefore: Boolean, showsRationale: Boolean): Boolean = askedBefore && !showsRationale

    @Volatile private var launchedAt = 0L

    /** Pure: an answer that comes back this fast with nothing granted was not asked of the person: the system had already said no for good. */
    fun systemDidNotAsk(elapsedMs: Long, anyGranted: Boolean): Boolean = !anyGranted && elapsedMs in 0..FAST_ANSWER_MS

    const val FAST_ANSWER_MS = 350L

    /**
     * Call with the result of a request made through [requestOrExplain]. If the system gave its "no" without asking (it remembered
     * one from before this app knew, e.g. after the app's data was cleared), the app's permission page opens now, not on the next tap.
     */
    fun onResult(context: Context, anyGranted: Boolean, now: Long = android.os.SystemClock.elapsedRealtime()) {
        val started = launchedAt
        launchedAt = 0L
        if (started != 0L && systemDidNotAsk(now - started, anyGranted)) openSettings(context)
    }

    fun openSettings(context: Context) {
        Toast.makeText(context, R.string.permission_blocked_hint, Toast.LENGTH_LONG).show()
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun markAsked(context: Context, permissions: Collection<String>) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { permissions.forEach { putBoolean(it, true) } }

    private fun asked(context: Context, permission: String) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(permission, false)

    fun activityOf(context: Context): Activity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    /** The permissions of [permissions] that are missing and that Android will no longer ask for. */
    fun blocked(context: Context, permissions: Collection<String>): List<String> {
        val activity = activityOf(context) ?: return emptyList()
        return permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED &&
                mustOpenSettings(asked(context, it), activity.shouldShowRequestPermissionRationale(it))
        }
    }

    /**
     * Asks through [launch], or, when Android will not ask any more, opens the app's permission page and says so.
     * Returns whether it asked (false: it opened the page).
     */
    fun requestOrExplain(context: Context, permissions: Array<String>, launch: (Array<String>) -> Unit): Boolean {
        if (blocked(context, permissions.toList()).isNotEmpty()) {
            openSettings(context)
            return false
        }
        markAsked(context, permissions.toList())
        launchedAt = android.os.SystemClock.elapsedRealtime()
        launch(permissions)
        return true
    }
}
