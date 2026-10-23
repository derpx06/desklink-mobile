/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile

import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The desk has no Bluetooth transport, so the phone ships none: no provider, no permission, no setting. */
@RunWith(RobolectricTestRunner::class)
class BluetoothRemovedTest {
    @Test
    fun noBluetoothTransportClassesSurvive() {
        for (name in listOf("BluetoothLinkProvider", "BluetoothLink", "ConnectionMultiplexer")) {
            assertThrows(ClassNotFoundException::class.java) { Class.forName("org.desklink.mobile.backends.bluetooth.$name") }
        }
    }

    @Test
    fun theManifestAsksForNoBluetoothPermission() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val requested = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toList()
        assertEquals(emptyList<String>(), requested.filter { it.contains("BLUETOOTH", ignoreCase = true) })
    }
}
