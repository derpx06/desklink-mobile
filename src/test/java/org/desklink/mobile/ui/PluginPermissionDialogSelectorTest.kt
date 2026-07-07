package org.desklink.mobile.ui

import androidx.fragment.app.DialogFragment
import org.desklink.mobile.plugins.Plugin
import org.junit.Assert.assertSame
import org.junit.Test

class PluginPermissionDialogSelectorTest {
    @Test
    fun requiredPermissionRowsUseTheRequiredPermissionDialog() {
        val plugin = FakePlugin()

        assertSame(
            plugin.requiredDialog,
            PluginPermissionDialogSelector.requiredPermissionDialog(plugin)
        )
    }

    @Test
    fun optionalPermissionRowsUseTheOptionalPermissionDialog() {
        val plugin = FakePlugin()

        assertSame(
            plugin.optionalDialog,
            PluginPermissionDialogSelector.optionalPermissionDialog(plugin)
        )
    }

    private class FakePlugin : Plugin() {
        val requiredDialog = DialogFragment()
        val optionalDialog = object : AlertDialogFragment() {}

        override val displayName: String = "Fake"
        override val description: String = "Fake plugin"
        override val supportedPacketTypes: Array<String> = emptyArray()
        override val outgoingPacketTypes: Array<String> = emptyArray()
        override val permissionExplanationDialog: DialogFragment
            get() = requiredDialog
        override val optionalPermissionExplanationDialog: AlertDialogFragment
            get() = optionalDialog
    }
}
