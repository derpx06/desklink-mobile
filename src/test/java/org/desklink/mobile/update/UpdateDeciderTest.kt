/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The decision table (docs/UPDATES.md 4.3, 2.3). The Android client compares versionCode, never the name. */
class UpdateDeciderTest {
    private val id = ByteArray(16) { it.toByte() }
    private val v030 = Version(0, 3, 0)

    private fun comp(version: String, code: Int, rollout: Int = 100, min: String = "0.0.0") =
        Component(Version.parse(version)!!, code, null, Version.parse(min)!!, null, rollout, emptyList(), null, null, null, null, null)

    private fun payload(beta: Component? = null, stable: Component? = null) = Payload(0, 0, mapOf(Channel.BETA to beta, Channel.STABLE to stable))

    private fun decide(p: Payload, channel: Channel = Channel.BETA, prefs: UpdatePrefs = UpdatePrefs(), now: Long = 1000, manual: Boolean = false, code: Int = 20300) =
        UpdateDecider.decide(v030, code, channel, p, prefs, id, now, manual)

    @Test fun semverOrder() {
        assertTrue(Version(0, 3, 0) < Version(0, 4, 0)); assertTrue(Version(0, 4, 0) < Version(1, 0, 0)); assertTrue(Version(0, 9, 9) < Version(0, 10, 0))
        assertEquals(null, Version.parse("0.3")); assertEquals(null, Version.parse("v0.3.0")); assertEquals(null, Version.parse("0.3.0-rc1"))
        assertEquals(Version(0, 3, 0), Version.parseLenient("0.3.0-debug"))
    }

    @Test fun missingChannelOrComponentIsUpToDate() {
        assertEquals(UpdateState.UpToDate, decide(payload()))
        assertEquals(UpdateState.UpToDate, decide(payload(stable = null, beta = null), Channel.STABLE))
    }

    @Test fun theCodeDecidesNotTheName() {
        // A higher NAME with the same code is nothing; a lower name with a higher code is an update.
        assertEquals(UpdateState.UpToDate, decide(payload(beta = comp("0.9.0", 20300))))
        assertTrue(decide(payload(beta = comp("0.3.0", 20301))) is UpdateState.Available)
        assertEquals(UpdateState.UpToDate, decide(payload(beta = comp("0.3.0", 20300))))
        assertEquals(UpdateState.UpToDate, decide(payload(beta = comp("0.2.0", 20200)))) // never a downgrade
    }

    @Test fun betaSeesANewerStableButStableNeverSeesBeta() {
        val beta = comp("0.4.0", 20400); val stable = comp("1.0.0", 30000)
        assertEquals(UpdateState.Available(stable), decide(payload(beta, stable)))
        assertEquals(UpdateState.Available(beta), decide(payload(beta, comp("0.3.5", 20305))))
        assertEquals(UpdateState.UpToDate, decide(payload(beta = beta), Channel.STABLE))
        assertEquals(UpdateState.Available(stable), decide(payload(beta = beta, stable = stable), Channel.STABLE))
    }

    @Test fun requiredIgnoresPartialRolloutSkipAndSnooze() {
        val c = comp("0.4.0", 20400, rollout = 1, min = "0.4.0")
        val prefs = UpdatePrefs("0.4.0", "0.4.0", 9_999_999)
        assertEquals(UpdateState.Required(c), decide(payload(beta = c), prefs = prefs))
        assertEquals(UpdateState.UpToDate, decide(payload(beta = comp("0.4.0", 20400, rollout = 0, min = "0.4.0")), prefs = prefs, manual = true)) // a pause stops even this
        assertEquals(UpdateState.UpToDate, decide(payload(beta = comp("0.3.0", 20300, min = "0.3.0")))) // installed == min: fine
    }

    @Test fun rolloutBoundaries() {
        val b = UpdateDecider.bucket(id, "android", "0.4.0")
        assertTrue(b in 0..99)
        assertEquals(UpdateState.UpToDate, decide(payload(beta = comp("0.4.0", 20400, rollout = 0))))
        assertEquals(UpdateState.UpToDate, decide(payload(beta = comp("0.4.0", 20400, rollout = b)))) // equal is NOT offered
        assertTrue(decide(payload(beta = comp("0.4.0", 20400, rollout = b + 1))) is UpdateState.Available)
        assertTrue(decide(payload(beta = comp("0.4.0", 20400, rollout = 100))) is UpdateState.Available)
        assertEquals(UpdateState.UpToDate, decide(payload(beta = comp("0.4.0", 20400, rollout = 0)), manual = true)) // 0 is a pause, even for the button
        assertTrue(decide(payload(beta = comp("0.4.0", 20400, rollout = 1)), manual = true) is UpdateState.Available) // a manual check ignores 1..99
    }

    @Test fun bucketIsStableAndPerRelease() {
        assertEquals(UpdateDecider.bucket(id, "android", "0.4.0"), UpdateDecider.bucket(id, "android", "0.4.0"))
        // A pinned vector: sha256(00..0f || "|android|0.4.0"), first four bytes big-endian, mod 100.
        val h = java.security.MessageDigest.getInstance("SHA-256").digest(id + "|android|0.4.0".toByteArray())
        assertEquals((((h[0].toLong() and 255) shl 24 or ((h[1].toLong() and 255) shl 16) or ((h[2].toLong() and 255) shl 8) or (h[3].toLong() and 255)) % 100).toInt(), UpdateDecider.bucket(id, "android", "0.4.0"))
        val spread = (0 until 200).map { UpdateDecider.bucket(ByteArray(16) { i -> (it + i).toByte() }, "android", "0.4.0") }.toSet()
        assertTrue(spread.size > 40)
    }

    @Test fun skipSnoozeAndManual() {
        val c = comp("0.4.0", 20400)
        assertEquals(UpdateState.Skipped(c), decide(payload(beta = c), prefs = UpdatePrefs(skippedVersion = "0.4.0")))
        assertTrue(decide(payload(beta = c), prefs = UpdatePrefs(skippedVersion = "0.4.0"), manual = true) is UpdateState.Available)
        assertTrue(decide(payload(beta = comp("0.5.0", 20500)), prefs = UpdatePrefs(skippedVersion = "0.4.0")) is UpdateState.Available) // newer clears the skip
        assertEquals(UpdateState.Snoozed(c, 2000), decide(payload(beta = c), prefs = UpdatePrefs(snoozedVersion = "0.4.0", snoozedUntil = 2000), now = 1999))
        assertTrue(decide(payload(beta = c), prefs = UpdatePrefs(snoozedVersion = "0.4.0", snoozedUntil = 2000), now = 2000) is UpdateState.Available)
        assertTrue(decide(payload(beta = comp("0.5.0", 20500)), prefs = UpdatePrefs(snoozedVersion = "0.4.0", snoozedUntil = 9999)) is UpdateState.Available)
    }

    @Test fun backoffLadderAndJitter() {
        val r = kotlin.random.Random(1)
        val h = 3_600_000L
        assertEquals(listOf(1L, 3, 6, 12, 24, 24), (1..6).map { UpdateEngine.scheduleDelayMs(it, r) / h })
        repeat(50) { assertTrue(UpdateEngine.scheduleDelayMs(0, r) in (21.6 * h).toLong()..(26.4 * h).toLong()) }
    }
}
