/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioBusyTest {
    @Test
    fun oneComputerAtATimeHearsThisPhone() {
        assertFalse("nobody is sending", PhoneAudio.busyWith(null, "desk-a"))
        assertFalse("the same computer asking again replaces its own session", PhoneAudio.busyWith("desk-a", "desk-a"))
        assertTrue("a second computer is refused while the first is heard", PhoneAudio.busyWith("desk-a", "desk-b"))
    }
}
