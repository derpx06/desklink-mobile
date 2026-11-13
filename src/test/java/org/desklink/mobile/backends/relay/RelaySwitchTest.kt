/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.backends.relay

import android.app.Application
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.desklink.mobile.BuildConfig
import org.desklink.mobile.webrtc.WebRtcConnectionPath
import org.desklink.mobile.webrtc.shown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * 1.3 ships LAN-only: `BuildConfig.RELAY_ENABLED` is false, and with it off nothing relay-related runs or shows, whatever
 * the stored prefs say (they are kept untouched).
 */
@RunWith(RobolectricTestRunner::class)
class RelaySwitchTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun storedRelayPrefsReachNothingWhileTheRelayIsCompiledOut() {
        assumeFalse(BuildConfig.RELAY_ENABLED)
        PreferenceManager.getDefaultSharedPreferences(app).edit {
            putBoolean(RelaySettings.KEY_ENABLED, true)
            putString(RelaySettings.KEY_URL, "wss://relay.example.org/v1")
            putBoolean(RelaySettings.KEY_USE_TURN, true)
            putBoolean(RelaySettings.KEY_FORCE_RELAY, true)
            putBoolean(RelaySettings.KEY_FORCE_TURN, true)
        }
        assertFalse("no relay connection is attempted", RelaySettings.enabled(app))
        assertFalse(RelaySettings.configured(app))
        assertFalse("no TURN, user-configured included", RelaySettings.useTurn(app))
        assertFalse("LAN links are never dropped", RelaySettings.forceRelay(app))
        assertFalse(RelaySettings.forceTurn(app))
        assertFalse(RelaySettings.shouldRun(RelaySettings.enabled(app), RelaySettings.url(app), hasPairedDevices = true))
        // Kept as stored: no migration.
        assertEquals("wss://relay.example.org/v1", PreferenceManager.getDefaultSharedPreferences(app).getString(RelaySettings.KEY_URL, null))
    }

    @Test
    fun noSurfaceNamesTheInternetOrARelayWhileCompiledOut() {
        for (path in WebRtcConnectionPath.entries) assertEquals(path, path.shown(relayEnabled = true))
        assertEquals(WebRtcConnectionPath.LAN, WebRtcConnectionPath.LAN.shown(relayEnabled = false))
        assertEquals(null, WebRtcConnectionPath.DIRECT.shown(relayEnabled = false))
        assertEquals(null, WebRtcConnectionPath.TURN.shown(relayEnabled = false))
        assertEquals(null, (null as WebRtcConnectionPath?).shown(relayEnabled = false))
    }

    /** The onboarding promises nothing off this network. */
    @Test
    fun onboardingCopyHasNoOffNetworkPromise() {
        assumeFalse(BuildConfig.RELAY_ENABLED)
        val root = File("src/main").takeIf { it.exists() } ?: File("desklink-mobile/src/main")
        val strings = root.resolve("res/values").listFiles { f -> f.name.endsWith(".xml") }.orEmpty().flatMap { file ->
            Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(file.readText())
                .map { it.groupValues[1] to it.groupValues[2] }.toList()
        }.toMap()
        val used = root.resolve("java/org/desklink/mobile/ui/compose/screen/onboarding").walk().filter { it.extension == "kt" }
            .flatMap { Regex("""R\.string\.(\w+)""").findAll(it.readText()).map { m -> m.groupValues[1] } }.toSet()
        assertFalse(used.isEmpty())
        for (id in used) {
            val words = strings[id]?.lowercase() ?: continue
            for (promise in listOf("away from home", "anywhere", "internet", "relay", "another network", "mobile data")) {
                assertFalse("\"$promise\" in $id: $words", promise in words)
            }
        }
    }
}
