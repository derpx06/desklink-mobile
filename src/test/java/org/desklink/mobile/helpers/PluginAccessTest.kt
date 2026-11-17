/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import io.mockk.every
import io.mockk.mockk
import org.desklink.mobile.plugins.Plugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An access granted while a computer is connected (a runtime permission from a desk's Allow, notification access from its
 * settings page) loads the plugin that waited for it, without a link rebuild; nothing changed means nothing reloads.
 */
class PluginAccessTest {
    private class Gate(var required: Boolean = false, var optional: Boolean = true)

    private fun plugin(gate: Gate) = mockk<Plugin> {
        every { checkRequiredPermissions() } answers { gate.required }
        every { checkOptionalPermissions() } answers { gate.optional }
    }

    private fun waiting(
        required: Map<String, Plugin> = emptyMap(),
        optional: Map<String, Plugin> = emptyMap(),
        enabled: Set<String> = required.keys + optional.keys,
        loaded: Set<String> = emptySet(),
    ) = PluginAccess.waiting({ it in enabled }, loaded, required, optional)

    /** The contacts case: Allow turns the setting on first, Android's dialog grants READ_CONTACTS after. */
    @Test
    fun aPluginWaitingForAPermissionIsReloadedOnceItIsGranted() {
        val contacts = Gate(required = false)
        val map = mapOf("ContactsPlugin" to plugin(contacts))
        assertTrue("still denied: nothing to do", waiting(required = map).isNone())
        contacts.required = true
        val change = waiting(required = map)
        assertEquals(setOf("ContactsPlugin"), change.reload)
        assertTrue("a plugin that never loaded needs no destroy", change.recreate.isEmpty())
    }

    /** No churn: a plugin that is not enabled, or whose access has not changed, is never reloaded (every onStart asks). */
    @Test
    fun nothingChangedOrNotEnabledReloadsNothing() {
        val granted = Gate(required = true)
        assertTrue(waiting(required = mapOf("Calls" to plugin(granted)), enabled = emptySet()).isNone())
        assertTrue(waiting().isNone())
        assertTrue(waiting(required = mapOf("Calls" to plugin(Gate(required = false)))).isNone())
    }

    /** Telephony's notification listener is added only in onCreate: gaining notification access recreates it. */
    @Test
    fun aLoadedPluginThatGainedItsOptionalAccessIsRecreated() {
        val telephony = Gate(required = true, optional = false)
        val map = mapOf("TelephonyPlugin" to plugin(telephony))
        assertTrue(waiting(optional = map, loaded = setOf("TelephonyPlugin")).isNone())
        telephony.optional = true
        val change = waiting(optional = map, loaded = setOf("TelephonyPlugin"))
        assertEquals(setOf("TelephonyPlugin"), change.recreate)
        assertEquals(setOf("TelephonyPlugin"), change.reload)
        // Not loaded at all: the required access is the news, not this.
        assertTrue(waiting(optional = map, loaded = emptySet()).isNone())
    }
}
