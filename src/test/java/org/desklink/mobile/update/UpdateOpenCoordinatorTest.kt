/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.update

import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.desklink.mobile.update.Signer.NOW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Show on open with a fake clock (docs/UPDATES.md 4.1, 4.5): once per open, 24 h, Skip, Required, the 3.5 s budget. */
@RunWith(RobolectricTestRunner::class)
class UpdateOpenCoordinatorTest {
    private var nowMs = NOW * 1000
    private var blocked = false
    private val store = newStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var opens = 0

    private fun coordinator(url: String, body: ByteArray?): Pair<UpdateEngine, UpdateOpenCoordinator> {
        val e = UpdateEngine(UpdateBuild(Version(0, 3, 0), 20300, "0.3.0", url, true), store, UpdateChecker("0.3.0", true, 10), { Signer.keys }, { nowMs }, { true })
        if (body != null) { store.cachedEnvelope = String(body); store.lastChecked = nowMs; store.lastIssuedAt = NOW }
        return e to UpdateOpenCoordinator(e, store, scope) { blocked }
    }
    private fun open(c: UpdateOpenCoordinator) = runBlocking { c.onOpen("open-${opens++}") }
    private val available = Signer.envelope(Signer.payload())
    private val required = Signer.envelope(Signer.payload(beta = Signer.component("0.4.0", 20400, minSupported = "0.4.0")))

    @Test fun aFreshCacheIsUsedInstantlyWithNoNetwork() = LocalServer { LocalServer.Reply(500) }.use { s ->
        val (_, c) = coordinator(s.url, available)
        assertTrue(open(c) is UpdateState.Available); assertTrue(s.requests.isEmpty())
    }

    @Test fun atMostOncePerOpen() = LocalServer { LocalServer.Reply(500) }.use { s ->
        val (_, c) = coordinator(s.url, available)
        assertTrue(runBlocking { c.onOpen("same") } is UpdateState.Available)
        assertNull(runBlocking { c.onOpen("same") })
    }

    @Test fun laterHoldsItBackForADay() = LocalServer { LocalServer.Reply(200, body = available) }.use { s ->
        val (_, c) = coordinator(s.url, available)
        c.notNow(open(c)!!)
        assertNull(open(c)); nowMs += 23 * 3_600_000L; assertNull(open(c))
        nowMs += 3_600_000L; assertTrue(open(c) is UpdateState.Available)
    }

    @Test fun skipSilencesThatVersionOnly() = LocalServer { LocalServer.Reply(200, body = available) }.use { s ->
        val (e, c) = coordinator(s.url, available)
        c.skip(open(c)!!)
        nowMs += 5 * 86_400_000L; store.lastChecked = nowMs
        assertNull(open(c))
        store.cachedEnvelope = String(Signer.envelope(Signer.payload(issuedAt = NOW + 4 * 86400, beta = Signer.component("0.5.0", 20500), expires = NOW + 24 * 86400)))
        assertTrue(open(c) is UpdateState.Available); e.undoSkip()
    }

    @Test fun requiredShowsAtEveryOpenAndIsNotSnoozable() = LocalServer { LocalServer.Reply(500) }.use { s ->
        val (_, c) = coordinator(s.url, required)
        repeat(3) { val st = open(c)!!; assertTrue(st is UpdateState.Required); c.notNow(st); c.skip(st) }
    }

    @Test fun nothingWhileTheScreenIsBusyAndTheOpenIsNotRetried() = LocalServer { LocalServer.Reply(500) }.use { s ->
        val (_, c) = coordinator(s.url, available)
        blocked = true; assertNull(open(c)); blocked = false
        assertTrue(open(c) is UpdateState.Available) // the NEXT open is a fresh chance; this one was not retried
    }

    @Test fun disabledAndUnconfiguredShowNothing() {
        val (_, c) = coordinator("http://127.0.0.1:1/x", available); store.enabled = false
        assertNull(open(c))
        val (_, c2) = coordinator("", available); store.enabled = true
        assertNull(open(c2))
    }

    @Test fun upToDateOrEmptyCacheShowsNothing() {
        val (_, c) = coordinator("http://127.0.0.1:1/x", null)
        assertNull(open(c))
    }

    @Test fun aStaleCacheIsRefreshedWithinTheBudgetAndTheNewAnswerIsUsed() = LocalServer { LocalServer.Reply(200, body = Signer.envelope(Signer.payload(issuedAt = NOW + 100, beta = Signer.component("0.5.0", 20500), expires = NOW + 100 + 20 * 86400)), delayMs = 300) }.use { s ->
        val (_, c) = coordinator(s.url, available); nowMs += 2 * 86_400_000L
        store.lastChecked = NOW * 1000 // stale
        val st = open(c) as UpdateState.Available
        assertEquals(20500, st.component.versionCode)
    }

    @Test fun aLateAnswerIsStoredForNextTimeNeverShownLate() = LocalServer { LocalServer.Reply(200, body = Signer.envelope(Signer.payload(issuedAt = NOW + 100, beta = Signer.component("0.5.0", 20500), expires = NOW + 100 + 20 * 86400)), delayMs = 4_500) }.use { s ->
        val (_, c) = coordinator(s.url, available); nowMs += 2 * 86_400_000L
        val t = System.nanoTime()
        val st = open(c) as UpdateState.Available
        assertTrue(System.nanoTime() - t < 4_200_000_000L) // gave up at the budget
        assertEquals(20400, st.component.versionCode) // the old cache, not the late answer
        Thread.sleep(2_000)
        assertEquals(20500, (UpdateEngine(UpdateBuild(Version(0, 3, 0), 20300, "0.3.0", s.url, true), store, UpdateChecker("0.3.0", true), { Signer.keys }, { nowMs }, { true }).state() as UpdateState.Available).component.versionCode)
    }

    @Test fun aFailedRefreshFallsBackToTheCache() = LocalServer { LocalServer.Reply(500) }.use { s ->
        val (_, c) = coordinator(s.url, available); nowMs += 3 * 86_400_000L; store.lastChecked = NOW * 1000
        // The cached manifest expired meanwhile (21 days) is not the case here; it is merely old.
        assertTrue(open(c) is UpdateState.Available)
    }

    @Test fun onlyALauncherLaunchOfANewInstanceIsAnOpen() {
        val l = setOf(Intent.CATEGORY_LAUNCHER)
        assertTrue(UpdateOpenCoordinator.isLauncherOpen(Intent.ACTION_MAIN, l, hasSavedState = false))
        assertFalse(UpdateOpenCoordinator.isLauncherOpen(Intent.ACTION_MAIN, l, hasSavedState = true)) // rotation
        assertFalse(UpdateOpenCoordinator.isLauncherOpen(Intent.ACTION_SEND, l, false)) // a share
        assertFalse(UpdateOpenCoordinator.isLauncherOpen(Intent.ACTION_VIEW, null, false)) // deep link, QR
        assertFalse(UpdateOpenCoordinator.isLauncherOpen(Intent.ACTION_MAIN, emptySet(), false)) // a notification tap or the service
    }
}
