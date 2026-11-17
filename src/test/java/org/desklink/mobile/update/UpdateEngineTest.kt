/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.update

import org.desklink.mobile.update.Signer.NOW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The check, the cache and the state together, against a loopback server and a fake clock. */
@RunWith(RobolectricTestRunner::class)
class UpdateEngineTest {
    private var nowMs = NOW * 1000
    private var unmetered = true
    private val store = newStore()

    private fun engine(url: String, debug: Boolean = true, code: Int = 20300) =
        UpdateEngine(UpdateBuild(Version(0, 3, 0), code, "0.3.0", url, debug), store, UpdateChecker("0.3.0", true, 2), { Signer.keys }, { nowMs }, { unmetered })

    private fun manifest(payload: String = Signer.payload()) = Signer.envelope(payload)

    @Test fun aVerifiedAnswerIsCachedWithItsEtag() = LocalServer { LocalServer.Reply(200, mapOf("ETag" to "\"e1\""), manifest()) }.use { s ->
        val e = engine(s.url)
        assertEquals(UpdateEngine.Outcome.Fresh, e.refresh())
        assertEquals("\"e1\"", store.etag)
        assertEquals(NOW, store.lastIssuedAt)
        assertTrue(e.state() is UpdateState.Available)
        assertEquals(UpdateState.UpToDate, engine(s.url, code = 20400).state())
    }

    @Test fun anUnchangedManifestKeepsTheCache() = LocalServer { r -> if (r.headers["if-none-match"] != null) LocalServer.Reply(304) else LocalServer.Reply(200, mapOf("ETag" to "\"e1\""), manifest()) }.use { s ->
        val e = engine(s.url); e.refresh(); nowMs += 3_600_000
        assertEquals(UpdateEngine.Outcome.Fresh, e.refresh())
        assertEquals(NOW * 1000 + 3_600_000, store.lastChecked)
        assertTrue(e.state() is UpdateState.Available)
    }

    @Test fun aBadSignatureIsCachedNowhereAndNotEvenItsEtag() = LocalServer { LocalServer.Reply(200, mapOf("ETag" to "\"bad\""), Signer.envelope(Signer.payload(), tamperSig = true)) }.use { s ->
        val e = engine(s.url)
        assertEquals(UpdateEngine.Outcome.Failed(FailReason.SIGNATURE), e.refresh())
        assertNull(store.etag); assertNull(store.cachedEnvelope); assertEquals(0, store.lastIssuedAt)
        assertEquals("signature", store.failReason); assertEquals(1, store.failStreak); assertEquals(UpdateState.UpToDate, e.state())
    }

    @Test fun aReplayedOlderManifestIsRefusedAfterANewerOne() {
        var body = manifest()
        LocalServer { LocalServer.Reply(200, body = body) }.use { s ->
            val e = engine(s.url)
            e.refresh()
            body = Signer.envelope(Signer.payload(issuedAt = NOW - 100, beta = Signer.component("0.9.0", 29000)))
            assertEquals(UpdateEngine.Outcome.Failed(FailReason.REPLAY), e.refresh())
            assertEquals(20400, (e.state() as UpdateState.Available).component.versionCode) // the good cache stands
        }
    }

    @Test fun aFailureStreakSetsTheBackoffAndSuccessClearsIt() {
        var code = 500
        LocalServer { LocalServer.Reply(code, body = manifest()) }.use { s ->
            val e = engine(s.url)
            e.refresh(); nowMs += 1000; e.refresh()
            assertEquals(2, store.failStreak); assertEquals(NOW * 1000, store.failedSince)
            assertEquals(3 * 3_600_000L, e.nextDelayMs())
            code = 200; nowMs += 1000; e.refresh()
            assertEquals(0, store.failStreak); assertEquals(0, store.failedSince)
        }
    }

    @Test fun disabledAndEmptyUrlAreFullyInert() {
        LocalServer { LocalServer.Reply(200, body = manifest()) }.use { s ->
            store.enabled = false
            val e = engine(s.url)
            assertEquals(UpdateEngine.Outcome.Skipped, e.refresh()); assertEquals(UpdateState.Disabled, e.state())
            assertTrue(s.requests.isEmpty())
            assertTrue(e.refresh(manual = true) is UpdateEngine.Outcome.Fresh) // "Check for updates" still works
        }
        val none = engine("")
        assertEquals(UpdateEngine.Outcome.Skipped, none.refresh(manual = true)); assertEquals(UpdateState.NotConfigured, none.state())
    }

    @Test fun meteredNetworkWaitsForWifiButAnOpenOrAButtonDoesNot() = LocalServer { LocalServer.Reply(200, body = manifest()) }.use { s ->
        val e = engine(s.url); unmetered = false
        assertEquals(UpdateEngine.Outcome.Skipped, e.refresh()); assertTrue(s.requests.isEmpty())
        assertTrue(e.refresh(atOpen = true) is UpdateEngine.Outcome.Fresh)
        store.wifiOnly = false; assertTrue(e.refresh() is UpdateEngine.Outcome.Fresh)
    }

    @Test fun theManualButtonIsRateLimited() = LocalServer { LocalServer.Reply(200, body = manifest()) }.use { s ->
        val e = engine(s.url)
        assertTrue(e.refresh(manual = true) is UpdateEngine.Outcome.Fresh)
        assertEquals(UpdateEngine.Outcome.Skipped, e.refresh(manual = true))
        nowMs += 5_000; assertTrue(e.refresh(manual = true) is UpdateEngine.Outcome.Fresh)
    }

    @Test fun releaseBuildsHaveNoRuntimeOverride() {
        store.debugUrl = "http://127.0.0.1:9/x"
        assertEquals("https://u.web.app/latest.json", engine("https://u.web.app/latest.json", debug = false).url())
        assertEquals("http://127.0.0.1:9/x", engine("https://u.web.app/latest.json", debug = true).url())
    }

    @Test fun anExpiredCacheOffersNothing() = LocalServer { LocalServer.Reply(200, body = manifest()) }.use { s ->
        val e = engine(s.url); e.refresh(); nowMs += 22L * 86_400_000
        assertEquals(UpdateState.UpToDate, e.state())
    }

    @Test fun theChannelDefaultsToBetaOnZeroDotXAndTheStoredChoiceWins() {
        assertEquals(Channel.BETA, store.channel(Version(0, 3, 0))); assertEquals(Channel.STABLE, store.channel(Version(1, 0, 0)))
        store.setChannel(Channel.STABLE); assertEquals(Channel.STABLE, store.channel(Version(0, 3, 0)))
    }

    @Test fun theInstallIdIsLocalRandomAndStable() {
        val a = store.installId(); assertEquals(16, a.size); assertTrue(a.contentEquals(store.installId()))
        assertTrue(!a.contentEquals(newStore().installId()))
    }
}
