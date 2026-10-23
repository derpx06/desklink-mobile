/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notifications

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The listener asks about every notification on the main thread: that must be a memory lookup, never a query. */
@RunWith(RobolectricTestRunner::class)
class AppDatabaseCacheTest {
    private fun fresh(): AppDatabase {
        AppDatabase::class.java.getDeclaredField("_instance").apply { isAccessible = true }.set(null, null)
        return AppDatabase.getInstance(ApplicationProvider.getApplicationContext<Application>())
    }

    private fun loaded(db: AppDatabase) = AppDatabase::class.java.getDeclaredField("cacheLoaded").apply { isAccessible = true }.getBoolean(db)

    @Test
    fun theMainThreadAnswersWithTheDefaultUntilTheTablesAreLoadedAndDoesNotQuery() {
        val db = fresh()
        assertFalse(loaded(db))
        assertTrue(db.isEnabled("com.example.app"))
        assertFalse("Google's notifications are off by default", db.isEnabled("com.google.android.googlequicksearchbox"))
        assertFalse(db.getPrivacy("com.example.app", AppDatabase.PrivacyOptions.BLOCK_CONTENTS))
        assertFalse("nothing was read from the database", loaded(db))
    }

    @Test
    fun whatIsWrittenIsAnsweredFromMemoryAndSurvivesALoad() {
        val db = fresh()
        db.setEnabled("com.example.chat", false)
        db.setPrivacy("com.example.chat", AppDatabase.PrivacyOptions.BLOCK_IMAGES, true)
        assertTrue(loaded(db)) // a write loads first, so it never starts from a blank value
        assertFalse(db.isEnabled("com.example.chat"))
        assertTrue(db.isEnabled("com.example.other"))
        assertTrue(db.getPrivacy("com.example.chat", AppDatabase.PrivacyOptions.BLOCK_IMAGES))
        assertFalse(db.getPrivacy("com.example.chat", AppDatabase.PrivacyOptions.BLOCK_CONTENTS))
        // A new process: the database has it, the preload brings it back.
        val again = fresh()
        again.preload()
        assertFalse(again.isEnabled("com.example.chat"))
        assertEquals(true, again.getPrivacy("com.example.chat", AppDatabase.PrivacyOptions.BLOCK_IMAGES))
    }
}
