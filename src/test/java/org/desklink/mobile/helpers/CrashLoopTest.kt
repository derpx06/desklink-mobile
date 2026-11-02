/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CrashLoopTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun threeWithinTwoMinutesIsALoopAndSlowOnesAreNot() {
        assertTrue(CrashLoop.looping(listOf(0, 30_000, 60_000), now = 61_000))
        assertFalse(CrashLoop.looping(listOf(0, 30_000), now = 61_000))
        assertFalse("spread out over ten minutes", CrashLoop.looping(listOf(0, 300_000, 600_000), now = 601_000))
        assertFalse("old ones fall out of the window", CrashLoop.looping(listOf(0, 1_000, 2_000), now = 400_000))
    }

    @Test
    fun recordedCrashesPutTheAppInSafeModeUntilTheAppIsOpened() {
        CrashLoop.clear(app)
        val t0 = 1_000_000L
        CrashLoop.recordCrash(app, t0); CrashLoop.recordCrash(app, t0 + 10_000)
        assertFalse(CrashLoop.safeMode(app, t0 + 20_000))
        CrashLoop.recordCrash(app, t0 + 20_000)
        assertTrue(CrashLoop.safeMode(app, t0 + 21_000))
        assertFalse("two minutes later it is over", CrashLoop.safeMode(app, t0 + 200_000))
        CrashLoop.clear(app)
        assertFalse(CrashLoop.safeMode(app, t0 + 21_000))
    }

    @Test
    fun aServiceCreatedInSafeModeSetsNothingUp() {
        CrashLoop.clear(app)
        val t = System.currentTimeMillis()
        repeat(3) { CrashLoop.recordCrash(app, t + it) }
        val controller = org.robolectric.Robolectric.buildService(org.desklink.mobile.BackgroundService::class.java).create()
        assertTrue("no instance was registered", org.desklink.mobile.BackgroundService.instance == null)
        controller.destroy()
        CrashLoop.clear(app)
    }

    @Test
    fun onceLoadedTheBreakerAnswersFromMemoryAndNeverTouchesThePreferencesOnTheCallingThread() {
        val t = System.currentTimeMillis()
        app.getSharedPreferences("crash_loop", android.content.Context.MODE_PRIVATE).edit()
            .putString("times", listOf(t - 30_000, t - 20_000, t - 10_000).joinToString(",")).commit()
        CrashLoop.resetForTest()
        CrashLoop.preload(app)
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        // A context that fails on any preferences access: the answer must come from memory.
        val bare = io.mockk.mockk<android.content.Context>()
        io.mockk.every { bare.getSharedPreferences(any(), any()) } throws IllegalStateException("main thread must not read")
        while (!CrashLoop.safeMode(app, t) && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("three crashes in the last minute from the stored file", CrashLoop.safeMode(bare, t))
        assertFalse("and two minutes on it is over", CrashLoop.safeMode(bare, t + 3 * 60_000))
        CrashLoop.clear(app)
        assertFalse(CrashLoop.safeMode(bare, t))
    }

    @Test
    fun aClearNeverWipesACrashRecordedAfterIt() {
        CrashLoop.clear(app)
        val t = System.currentTimeMillis()
        repeat(3) { CrashLoop.recordCrash(app, t + it) }
        Thread.sleep(200) // the background half of the clear has run
        CrashLoop.resetForTest()
        CrashLoop.preload(app)
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while (!CrashLoop.safeMode(app, t + 10) && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("what the file holds after a restart still counts", CrashLoop.safeMode(app, t + 10))
        CrashLoop.clear(app)
    }
}
