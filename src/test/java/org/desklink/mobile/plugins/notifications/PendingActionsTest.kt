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

    private data class A(val title: String?, val usable: Boolean = true)

    private fun sendable(vararg actions: A, redacted: Boolean = false) =
        PendingActions.sendable(actions.toList(), redacted, { it.title }, { it.usable }).map { it.first }

    @Test
    fun onlyButtonsWithALabelAnIntentAndADistinctLabelAreSent() {
        // Android 15's redaction blanks every title: those were the empty buttons on the desk.
        assertEquals(emptyList<String>(), sendable(A(""), A(""), A("")))
        assertEquals(listOf("Archive", "Mark read"), sendable(A(" Archive\n"), A(null), A("  "), A("Reply", usable = false), A("Mark read"), A("Archive")))
    }

    @Test
    fun aRedactedNotificationSendsNoButtonsEvenWithLabels() {
        assertEquals(emptyList<String>(), sendable(A("Archive"), A("Delete"), redacted = true))
    }

    @Test
    fun aPressIsFoundByTheLabelAsTheDeskSendsItBack() {
        val actions = PendingActions<Pair<String, A>>()
        actions.replace("n", PendingActions.sendable(listOf(A(" Archive "), A("Delete")), false, { it.title }, { it.usable }))
        // The desk strips control characters and trims what it sends.
        assertEquals(" Archive ", actions.find("n") { it.first == PendingActions.label("Archive ") }?.second?.title)
        assertNull(actions.find("n") { it.first == PendingActions.label("") })
    }
}
