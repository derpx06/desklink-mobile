/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingActionsTest {
    @Test
    fun anUpdateReplacesTheButtonsAndOneWithNoneLeftKeepsNone() {
        val actions = PendingActions<String>()
        actions.replace("n", listOf("Archive", "Mute"))
        assertEquals("Mute", actions.find("n") { it == "Mute" })
        actions.replace("n", listOf("Archive"))
        assertNull(actions.find("n") { it == "Mute" })
        actions.replace("n", emptyList())
        assertNull(actions.find("n") { it == "Archive" })
    }

    @Test
    fun aPressWhileTheMainThreadKeepsUpdatingNeverThrows() {
        val actions = PendingActions<String>()
        val writer = Thread { repeat(20_000) { actions.replace("n", listOf("a$it", "b$it")) } }
        writer.start()
        repeat(20_000) { actions.find("n") { it.startsWith("b") } }
        writer.join()
        actions.remove("n")
        assertNull(actions.find("n") { true })
    }
}
