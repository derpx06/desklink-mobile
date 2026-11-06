/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import org.desklink.mobile.BuildConfig
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * While a screen that shows secrets is composed (terminal output, notes, the pairing code, the session password), the window
 * is secure: no screenshot or screen recording of it by another app, and a blank tile in the recents list. Counted per
 * activity, so two such screens (a tab and a dialog) do not switch it off for each other.
 */
object SecureWindows {
    private val users = HashMap<Activity, Int>()

    /** Settings > Advanced > Debug, debug builds only: a developer can screenshot Files, Notes and Terminals over adb. */
    const val KEY_ALLOW_SCREENSHOTS = "debug_allow_screenshots"

    /** The one rule: the bypass needs a debug build *and* the switch. [debug] is `BuildConfig.DEBUG`, a constant false in release. */
    fun bypassed(debug: Boolean, switchOn: Boolean) = debug && switchOn

    private fun bypassed(activity: Activity) = BuildConfig.DEBUG && bypassed(
        BuildConfig.DEBUG,
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(activity).getBoolean(KEY_ALLOW_SCREENSHOTS, false),
    )

    fun enter(activity: Activity) {
        if (bypassed(activity)) return
        val first = (users[activity] ?: 0) == 0
        users[activity] = (users[activity] ?: 0) + 1
        if (first) apply(activity, secure = true)
    }

    fun leave(activity: Activity) {
        val left = (users[activity] ?: return) - 1
        if (left <= 0) {
            users.remove(activity)
            apply(activity, secure = false)
        } else {
            users[activity] = left
        }
    }

    fun count(activity: Activity) = users[activity] ?: 0

    private fun apply(activity: Activity, secure: Boolean) {
        if (secure) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) runCatching { activity.setRecentsScreenshotEnabled(!secure) }
    }
}

@Composable
fun SecureWindow() {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        SecureWindows.enter(activity)
        onDispose { SecureWindows.leave(activity) }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
