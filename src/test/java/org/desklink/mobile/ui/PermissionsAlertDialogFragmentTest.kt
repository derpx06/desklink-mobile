package org.desklink.mobile.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionsAlertDialogFragmentTest {
    @Test
    fun emptyPermissionListIsRejectedBeforeCallingAndroidPermissionApi() {
        assertFalse(PermissionsAlertDialogFragment.hasValidPermissions(emptyArray()))
    }

    @Test
    fun nullOrBlankPermissionIsRejectedBeforeCallingAndroidPermissionApi() {
        assertFalse(PermissionsAlertDialogFragment.hasValidPermissions(arrayOf(null)))
        assertFalse(PermissionsAlertDialogFragment.hasValidPermissions(arrayOf("")))
        assertFalse(PermissionsAlertDialogFragment.hasValidPermissions(arrayOf("   ")))
    }

    @Test
    fun nonBlankRuntimePermissionListIsAccepted() {
        assertTrue(
            PermissionsAlertDialogFragment.hasValidPermissions(
                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
            )
        )
    }
}
