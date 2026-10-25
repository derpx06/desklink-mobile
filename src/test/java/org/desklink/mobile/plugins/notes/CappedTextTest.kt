/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notes

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CappedTextTest {
    private val scope = SaverScope { true }

    @Test
    fun aBigNoteIsNotPutInTheSavedStateBundle() {
        with(CappedText) {
            assertEquals("short", scope.save("short"))
            assertNull(scope.save("x".repeat(SAVED_STATE_CAP + 1)))
        }
    }
}
