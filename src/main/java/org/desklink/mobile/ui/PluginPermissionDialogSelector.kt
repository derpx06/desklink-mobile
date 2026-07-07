package org.desklink.mobile.ui

import androidx.fragment.app.DialogFragment
import org.desklink.mobile.plugins.Plugin

object PluginPermissionDialogSelector {
    fun requiredPermissionDialog(plugin: Plugin): DialogFragment {
        return plugin.permissionExplanationDialog
    }

    fun optionalPermissionDialog(plugin: Plugin): AlertDialogFragment {
        return plugin.optionalPermissionExplanationDialog
    }
}
