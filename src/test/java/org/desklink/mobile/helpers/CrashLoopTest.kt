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
}
