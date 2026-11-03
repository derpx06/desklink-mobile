/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PerPeerStateTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val desk = "11111111-aaaa-bbbb-cccc-111111111111"
    private val other = "22222222-aaaa-bbbb-cccc-222222222222"

    // Keys as the writers build them, for both computers, in every registered store.
    private fun keysFor(store: String, id: String): List<String> = when (store) {
        PerPeerState.LABELS -> listOf(PerPeerState.labelKey(id, "session-1"))
        PerPeerState.SURFACES -> listOf(PerPeerState.seenKey(id), PerPeerState.finishedKey(id))
        PerPeerState.SHARE_QUEUE -> listOf(id, "attempts_$id")
        PerPeerState.DND_RESTORE -> listOf(id)
        else -> error("a store without a key list in this test: $store")
    }

    @Test
    fun unpairClearsEveryRegisteredStoreForThatComputerOnly() {
        for (store in PerPeerState.stores) {
            val prefs = app.getSharedPreferences(store.prefsName, Context.MODE_PRIVATE)
            prefs.edit { listOf(desk, other).forEach { id -> keysFor(store.prefsName, id).forEach { putString(it, if (store.prefsName == PerPeerState.DND_RESTORE) "1,2" else "x") } } }
        }
        PeerState.forget(app, desk)
        for (store in PerPeerState.stores) {
            val keys = app.getSharedPreferences(store.prefsName, Context.MODE_PRIVATE).all.keys
            assertEquals(store.prefsName, emptyList<String>(), keys.filter { desk in it })
            assertTrue("${store.prefsName} keeps the other computer's", keysFor(store.prefsName, other).all { it in keys })
        }
    }

    @Test
    fun everyPreferencesFileTheAppOpensIsEitherPerComputerAndRegisteredOrSaidToBeNot() {
        val named = Regex("""getSharedPreferences\(\s*"([^"]+)"""")
        val constants = Regex("""(?:const val|static final String) (\w+)(?::\s*String)?\s*=\s*"([^"]+)"""")
        val viaConstant = Regex("""getSharedPreferences\(\s*([A-Za-z_][A-Za-z0-9_.]*)\s*,""")
        val known = PerPeerState.stores.map { it.prefsName }.toSet() + PerPeerState.NOT_PER_PEER.keys
        val sources = File("src/main/java").walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.map { it.readText() }.toList()
        // `const val NAME = "file"` / `static final String NAME = "file"`: a bare name is looked up in its own file, a qualified one anywhere.
        val everywhere = sources.flatMap { constants.findAll(it).map { m -> m.groupValues[1] to m.groupValues[2] } }.toMap()
        val found = sources.flatMap { text ->
            val local = constants.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
            named.findAll(text).map { it.groupValues[1] } +
                viaConstant.findAll(text).mapNotNull { m ->
                    val ref = m.groupValues[1]
                    if ('.' in ref) everywhere[ref.substringAfterLast('.')] else local[ref]
                }
        }.toSet()
        // A name that is not a preferences-file constant (a variable holding a per-device name such as "<id>_<plugin>_preferences") is
        // checked by its own tests; only constants with a known value are held to the list.
        assertEquals("a preferences file opened by name that is not registered in PerPeerState", emptySet<String>(), found - known)
        assertTrue("the walk resolves constants (it saw ${found.size} files)", "desklink_owed_pair_false" in found)
    }
}
