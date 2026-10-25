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
            Toast.makeText(context, R.string.permission_blocked_hint, Toast.LENGTH_LONG).show()
            runCatching {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return false
        }
        markAsked(context, permissions.toList())
        launch(permissions)
        return true
    }
}
