package org.desklink.mobile.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri

object SettingsIntentFactory {
    fun buildRequestedIntent(intentAction: String?, intentUrl: String?): Intent {
        require(!intentAction.isNullOrBlank()) { "intentAction must not be blank" }

        return if (!intentUrl.isNullOrEmpty()) {
            Intent(intentAction, intentUrl.toUri())
        } else {
            Intent(intentAction)
        }
    }

    fun buildAppDetailsIntent(packageName: String): Intent {
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:$packageName".toUri()
        )
    }

    fun bestAvailableIntent(
        requested: Intent,
        packageName: String,
        canResolve: (Intent) -> Boolean
    ): Intent {
        return if (canResolve(requested)) {
            requested
        } else {
            buildAppDetailsIntent(packageName)
        }
    }

    fun bestAvailableIntent(context: Context, requested: Intent): Intent {
        return bestAvailableIntent(
            requested = requested,
            packageName = context.packageName,
            canResolve = { it.resolveActivity(context.packageManager) != null }
        )
    }
}
