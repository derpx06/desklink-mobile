/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.gallery

import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class GalleryFolderLostTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val id = "0123456789abcdef0123456789abcdef"

    @Test
    fun aFolderThatCannotBeReadIsSaidOnceAndRemembered() {
        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
        assertFalse(GalleryWatcher.isLost(context, id, shots = true))
        GalleryWatcher.noteLost(context, id, "Desk", shots = true)
        assertTrue(GalleryWatcher.isLost(context, id, shots = true))
        assertFalse("the other folder is its own", GalleryWatcher.isLost(context, id, shots = false))
        val first = manager.allNotifications.size
        assertEquals(1, first)
        // Asked again (every minute while connected): not posted twice.
        GalleryWatcher.noteLost(context, id, "Desk", shots = true)
        assertEquals(first, manager.allNotifications.size)
    }
}
