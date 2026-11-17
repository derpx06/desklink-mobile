/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.update

import org.desklink.mobile.update.UpdateChecker.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
/** The fetch against a real loopback HTTP server: every outcome, and exactly what is sent (docs/UPDATES.md 4.2, 7.5). */
class UpdateCheckerTest {
    private val ok = ByteArray(100) { 'a'.code.toByte() }
    private fun checker(loopback: Boolean = true, timeoutS: Long = 10) = UpdateChecker("0.3.0", loopback, timeoutS)
    private fun failed(r: Result) = (r as Result.Failed).reason

    @Test fun okBodyAndEtag() = LocalServer { LocalServer.Reply(200, mapOf("ETag" to "\"abc\""), ok) }.use {
        val r = checker().fetch(it.url, null) as Result.Body
        assertEquals(100, r.bytes.size); assertEquals("\"abc\"", r.etag)
    }

    @Test fun conditionalGetAnswers304() = LocalServer { LocalServer.Reply(304) }.use {
        assertEquals(Result.NotModified, checker().fetch(it.url, "\"abc\""))
        assertEquals("\"abc\"", it.requests.single().headers["if-none-match"])
    }

    @Test fun onlyTheAllowedHeadersAreSentAndNoIdentifier() = LocalServer { LocalServer.Reply(200, body = ok) }.use {
        checker().fetch(it.url, "\"e\"")
        val h = it.requests.single().headers
        assertEquals(setOf("host", "user-agent", "accept", "accept-encoding", "if-none-match", "connection"), h.keys)
        assertEquals("DeskLink-update-check/0.3.0", h["user-agent"])
        assertEquals("/latest.json", it.requests.single().path) // no query string
    }

    @Test fun noEtagMeansNoConditionalHeader() = LocalServer { LocalServer.Reply(200, body = ok) }.use {
        checker().fetch(it.url, null); assertTrue("if-none-match" !in it.requests.single().headers)
    }

    @Test fun anOversizedBodyIsRefused() = LocalServer { LocalServer.Reply(200, body = ByteArray(UpdateVerifier.MAX_BYTES + 1)) }.use {
        assertEquals(FailReason.OVERSIZED, failed(checker().fetch(it.url, null)))
    }

    @Test fun exactlyTheCapIsAccepted() = LocalServer { LocalServer.Reply(200, body = ByteArray(UpdateVerifier.MAX_BYTES)) }.use {
        assertEquals(UpdateVerifier.MAX_BYTES, (checker().fetch(it.url, null) as Result.Body).bytes.size)
    }

    @Test fun serverErrorsAreHttpFailures() {
        for (code in listOf(404, 500, 503)) LocalServer { LocalServer.Reply(code) }.use { assertEquals(FailReason.HTTP, failed(checker().fetch(it.url, null))) }
    }

    @Test fun aRedirectLoopStopsAtThreeHops() = LocalServer { LocalServer.Reply(302, mapOf("Location" to "/again")) }.use {
        assertEquals(FailReason.URL, failed(checker().fetch(it.url, null)))
        assertEquals(4, it.requests.size) // the first request and three follows
    }

    @Test fun aSameHostRedirectIsFollowed() = LocalServer { r -> if (r.path == "/latest.json") LocalServer.Reply(301, mapOf("Location" to "/moved.json")) else LocalServer.Reply(200, body = ok) }.use {
        assertEquals(100, (checker().fetch(it.url, null) as Result.Body).bytes.size)
    }

    @Test fun aRedirectToAnotherHostOrWithoutLocationIsRefused() {
        LocalServer { LocalServer.Reply(302, mapOf("Location" to "http://localhost:1/x")) }.use { assertEquals(FailReason.URL, failed(checker().fetch(it.url, null))) }
        LocalServer { LocalServer.Reply(302) }.use { assertEquals(FailReason.URL, failed(checker().fetch(it.url, null))) }
    }

    @Test fun aSlowServerTimesOut() = LocalServer { LocalServer.Reply(200, body = ByteArray(20), trickle = true) }.use {
        val t = System.nanoTime()
        assertEquals(FailReason.TIMEOUT, failed(checker(timeoutS = 1).fetch(it.url, null)))
        assertTrue(System.nanoTime() - t < 4_000_000_000L)
    }

    @Test fun plainHttpIsRefusedOutsideDebugLoopback() {
        assertEquals(FailReason.URL, failed(checker(loopback = false).fetch("http://127.0.0.1:1/x", null)))
        assertEquals(FailReason.URL, failed(checker().fetch("http://example.com/x", null)))
        assertEquals(FailReason.URL, failed(checker().fetch("ftp://127.0.0.1/x", null)))
        assertEquals(FailReason.URL, failed(checker().fetch("http://u:p@127.0.0.1:1/x", null)))
    }

    @Test fun aClosedPortIsOffline() = LocalServer { LocalServer.Reply() }.let { s -> val url = s.url; s.close(); assertEquals(FailReason.OFFLINE, failed(checker().fetch(url, null))) }
}
