/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.update

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random

/** What this build is: its display version, its monotonic code (compared, not the name), and where to ask. */
/** [version] is null when the build's own version name is malformed (a build bug): nothing is then ever offered. */
internal data class UpdateBuild(val version: Version?, val versionCode: Int, val versionName: String, val url: String, val debug: Boolean)

/** The check, the cache and the decision for one install. All I/O goes through the injected pieces so the rules test on a JVM. */
internal class UpdateEngine(
    private val build: UpdateBuild,
    private val store: UpdateStore,
    private val checker: UpdateChecker,
    private val keys: () -> Map<String, ByteArray>,
    private val clock: () -> Long,
    private val unmetered: () -> Boolean,
) {
    private val lock = ReentrantLock()
    private var lastManualAt = 0L

    sealed interface Outcome {
        data object Skipped : Outcome // disabled, unconfigured, metered, rate limited
        data object Fresh : Outcome // a verified answer (200 or 304) is in the cache
        data class Failed(val reason: FailReason) : Outcome
    }

    /** The address in use: the build's, or in a debug build a stored test address. Release builds have no override. */
    fun url(): String = (if (build.debug) store.debugUrl?.takeIf { it.isNotBlank() } else null) ?: build.url

    fun configured() = url().isNotBlank()

    /** The cache is old (or empty): older than 24 h. */
    fun cacheAgeMs(): Long? = store.lastChecked.takeIf { it > 0 }?.let { clock() - it }

    fun cachePayload(): Payload? {
        val env = store.cachedEnvelope ?: return null
        // Verified again on load; lastSeen 0 because the cache is the newest thing seen by definition.
        val v = UpdateVerifier.verify(env.toByteArray(Charsets.UTF_8), keys(), clock() / 1000, 0)
        return (v as? UpdateVerifier.Verdict.Ok)?.payload
    }

    /** The state for the person right now, from the cache only (no network). */
    fun state(manual: Boolean = false): UpdateState {
        if (!configured()) return UpdateState.NotConfigured
        if (!manual && !store.enabled) return UpdateState.Disabled
        val installed = build.version ?: return UpdateState.UpToDate
        val p = cachePayload() ?: return UpdateState.UpToDate
        return UpdateDecider.decide(installed, build.versionCode, store.channel(installed), p, store.prefsForDecision(), store.installId(), clock(), manual)
    }

    /**
     * One check. [manual] is the button; [atOpen] a check made because the app was opened: both ignore the Wi-Fi setting
     * (the person is here) and `enabled` only for [manual]. A background check obeys both.
     */
    fun refresh(manual: Boolean = false, atOpen: Boolean = false): Outcome = lock.withLock {
        if (!configured()) return Outcome.Skipped
        if (!manual && !store.enabled) return Outcome.Skipped
        if (!manual && !atOpen && store.wifiOnly && !unmetered()) return Outcome.Skipped
        val now = clock()
        if (manual) {
            if (now - lastManualAt < MANUAL_GAP_MS) return Outcome.Skipped
            lastManualAt = now
        }
        val result = try {
            checker.fetch(url(), store.etag)
        } catch (_: Exception) {
            UpdateChecker.Result.Failed(FailReason.URL)
        }
        when (result) {
            UpdateChecker.Result.NotModified -> {
                store.lastAttempt = now
                if (cachePayload() != null) {
                    store.lastChecked = now
                    clearFailure()
                    Outcome.Fresh
                } else {
                    // "Not modified" about a cache that no longer verifies (expired): it is not fresh, and the next check refetches.
                    store.etag = null
                    fail(FailReason.EXPIRED, now)
                }
            }
            is UpdateChecker.Result.Failed -> fail(result.reason, now)
            is UpdateChecker.Result.Body -> {
                when (val v = UpdateVerifier.verify(result.bytes, keys(), now / 1000, store.lastIssuedAt)) {
                    // The ETag is stored only after the body fully verified; a bad body is cached nowhere.
                    is UpdateVerifier.Verdict.Ok -> {
                        // Never beyond now: a manifest dated in the future must not lock out every real one after it.
                        store.recordAccepted(String(result.bytes, Charsets.UTF_8), result.etag, minOf(v.payload.issuedAt, now / 1000), now)
                        Outcome.Fresh
                    }
                    is UpdateVerifier.Verdict.Rejected -> fail(v.reason, now)
                }
            }
        }
    }

    private fun fail(reason: FailReason, now: Long): Outcome {
        store.lastAttempt = now
        if (store.failedSince == 0L) store.failedSince = now
        store.failReason = reason.code
        store.failStreak = store.failStreak + 1
        return Outcome.Failed(reason)
    }

    private fun clearFailure() {
        store.failedSince = 0
        store.failReason = null
        store.failStreak = 0
    }

    /** Delay before the next automatic check: a day +-10 % after success, 1/3/6/12/24 h while failing (section 4.2). */
    fun nextDelayMs(random: Random = Random.Default): Long = scheduleDelayMs(store.failStreak, random)

    /** How long the background loop waits before its first check: from the last attempt, so restarting the service never refetches early. */
    fun initialDelayMs(random: Random = Random.Default): Long =
        store.lastAttempt.takeIf { it > 0 }?.let { maxOf(0L, it + nextDelayMs(random) - clock()) } ?: 0L

    // The person's three choices about the offered version.
    fun later(c: Component) {
        store.snoozedVersion = c.version.toString()
        store.snoozedUntil = clock() + LATER_MS
    }

    fun skip(c: Component) {
        store.skippedVersion = c.version.toString()
    }

    fun undoSkip() {
        store.skippedVersion = null
    }

    companion object {
        const val MANUAL_GAP_MS = 5_000L
        const val LATER_MS = 24 * 3_600_000L
        const val DAY_MS = LATER_MS
        private val BACKOFF_H = longArrayOf(1, 3, 6, 12, 24)

        fun scheduleDelayMs(failStreak: Int, random: Random): Long {
            if (failStreak > 0) return BACKOFF_H[minOf(failStreak, BACKOFF_H.size) - 1] * 3_600_000L
            val jitter = (random.nextDouble() * 0.2 - 0.1) // +-10 %
            return (DAY_MS * (1 + jitter)).toLong()
        }
    }
}
