package org.desklink.mobile

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class BrandingResourcesTest {
    @Test
    fun publicProductResourcesUseDeskLink() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        assertEquals("DeskLink", context.getString(R.string.app_name))
        assertEquals("DeskLink", context.getString(R.string.product_name))
        assertEquals("DeskLink connection service", context.getString(R.string.connection_service_name))
        assertEquals("Connected devices", context.getString(R.string.connected_devices_channel))
        assertEquals("Pairing requests", context.getString(R.string.pairing_channel))
        assertEquals("File transfers", context.getString(R.string.file_transfer_channel))
    }
}
