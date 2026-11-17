/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.update

import java.math.BigInteger
import org.bouncycastle.math.ec.rfc8032.Ed25519

/**
 * Envelope verification (docs/UPDATES.md 2.1, 2.2, 2.6). Order: size cap, strict-JSON envelope with exactly the keys
 * v/kid/payload/sig and `v == 1`, key by `kid`, canonical base64url, STRICT Ed25519 over the decoded payload bytes as served
 * (BouncyCastle rfc8032 on every API level, plus an explicit S < L check), then the payload (strict JSON, schema, clock,
 * lifetime, replay). Nothing in the payload is looked at before the signature passes. A malformed component is dropped on
 * its own; the others are still decided. Pure: no I/O, no clock of its own. Never throws: any Throwable is a rejection.
 */
internal object UpdateVerifier {
    const val MAX_BYTES = 64 * 1024
    private const val FUTURE_TOLERANCE = 3_600L
    private const val MAX_LIFETIME = 22 * 86_400L

    sealed interface Verdict {
        data class Ok(val payload: Payload) : Verdict
        data class Rejected(val reason: FailReason) : Verdict
    }

    /**
     * @param keys public keys by `kid` (32 bytes each)
     * @param nowSec epoch seconds
     * @param lastSeenIssuedAt the newest `issuedAt` of a fully verified manifest seen on this install (0 = none)
     */
    fun verify(body: ByteArray, keys: Map<String, ByteArray>, nowSec: Long, lastSeenIssuedAt: Long): Verdict = try {
        if (body.size > MAX_BYTES) reject(FailReason.OVERSIZED) else when (val e = openEnvelope(body, keys)) {
            is Opened.Bytes -> parsePayload(e.payload, nowSec, lastSeenIssuedAt)
            is Opened.Refused -> reject(e.reason)
        }
    } catch (_: Throwable) {
        reject(FailReason.PARSE) // StackOverflowError and friends are Errors, not Exceptions
    }

    private fun reject(r: FailReason) = Verdict.Rejected(r)

    private fun num(v: Any?): Long? = v as? Long

    private sealed interface Opened {
        class Bytes(val payload: ByteArray) : Opened
        class Refused(val reason: FailReason) : Opened
    }

    /** The signed payload bytes, or why not: a malformed envelope (shape, non-string kid) is `parse`; once the kid is known (as in dl_update.py) an unknown kid, a non-string or badly encoded payload/sig and a bad signature are all `signature`. */
    private fun openEnvelope(body: ByteArray, keys: Map<String, ByteArray>): Opened {
        val bad = Opened.Refused(FailReason.SIGNATURE)
        val env = StrictJson.parse(body) as? Map<*, *> ?: return Opened.Refused(FailReason.PARSE)
        if (env.keys != setOf("v", "kid", "payload", "sig") || num(env["v"]) != 1L) return Opened.Refused(FailReason.PARSE)
        val pk = keys[env["kid"] as? String ?: return Opened.Refused(FailReason.PARSE)]?.takeIf { it.size == 32 } ?: return bad
        val msg = base64Url(env["payload"] as? String ?: return bad) ?: return bad
        val sig = base64Url(env["sig"] as? String ?: return bad)?.takeIf { it.size == 64 } ?: return bad
        return if (sCanonical(sig) && Ed25519.verify(sig, 0, pk, 0, msg, 0, msg.size)) Opened.Bytes(msg) else bad
    }

    // RFC 8032 requires S < L; a verifier that skips it accepts a second, malleable encoding of the same signature.
    private val L = BigInteger("7237005577332262213973186563042994240857116359379907606001950938285454250989")

    private fun sCanonical(sig: ByteArray): Boolean = BigInteger(1, sig.copyOfRange(32, 64).reversedArray()) < L

    private fun parsePayload(bytes: ByteArray, nowSec: Long, lastSeen: Long): Verdict {
        val p = StrictJson.parse(bytes) as? Map<*, *> ?: return reject(FailReason.PARSE)
        // Failure order: parse, schema, types, expired (future, window, expiry), replay.
        val schema = num(p["schemaVersion"])
        if (schema == null || schema < 1) return reject(FailReason.PARSE)
        if (schema > 1) return reject(FailReason.SCHEMA)
        val issuedAt = num(p["issuedAt"])
        val expires = num(p["expires"])
        if (issuedAt == null || expires == null) return reject(FailReason.PARSE) // JSON has no negative integer token
        return when {
            // A clock-skewed `issuedAt` is reported as `expired`: the documented code set has no clock code.
            issuedAt > nowSec + FUTURE_TOLERANCE -> reject(FailReason.EXPIRED)
            expires - issuedAt !in 1..MAX_LIFETIME -> reject(FailReason.EXPIRED)
            nowSec > expires -> reject(FailReason.EXPIRED)
            issuedAt < lastSeen -> reject(FailReason.REPLAY)
            else -> {
                val channels = p["channels"] as? Map<*, *>
                Verdict.Ok(Payload(issuedAt, expires, Channel.entries.associateWith { ch -> component(((channels?.get(ch.key) as? Map<*, *>)?.get("android")) as? Map<*, *>) }))
            }
        }
    }

    /** One release, or null when anything about it is malformed (that component is dropped, nothing else is affected). */
    private fun component(o: Map<*, *>?): Component? {
        if (o == null) return null
        val version = Version.parse(o["version"] as? String) ?: return null
        val code = num(o["versionCode"])?.takeIf { it in 1..2_100_000_000 }?.toInt() ?: return null
        val rollout = if (o.containsKey("rollout")) num(o["rollout"])?.takeIf { it in 0..100 }?.toInt() ?: return null else 100
        val min = if (o.containsKey("minSupported")) Version.parse(o["minSupported"] as? String)?.takeIf { it <= version } ?: return null else Version(0, 0, 0)
        val downloads = o["downloads"] as? Map<*, *>
        val apk = downloads?.get("apk") as? Map<*, *>
        val v = version.toString()
        return Component(
            version = version,
            versionCode = code,
            released = (o["released"] as? String)?.takeIf { realDate(it) },
            minSupported = min,
            requiredReason = PlainText.valid(o["requiredReason"], Component.MAX_REASON),
            rollout = rollout,
            notes = notes(o["notes"] as? List<*>),
            notesUrl = UrlAllowlist.release(o["notesUrl"] as? String, v),
            size = num(o["size"]),
            apkUrl = UrlAllowlist.apk(apk?.get("url") as? String, v),
            apkSha256 = (apk?.get("sha256") as? String)?.takeIf { Regex("[0-9a-f]{64}").matches(it) },
            releaseUrl = UrlAllowlist.release(downloads?.get("releaseUrl") as? String, v),
        )
    }

    private fun realDate(s: String): Boolean =
        Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(s) && runCatching { java.time.LocalDate.parse(s) }.getOrNull()?.let { it.year >= 1 } == true

    /** Objects `{title, detail?, glyph?}`: a note that breaks a rule is skipped, then the first 8 are kept. */
    private fun notes(a: List<*>?): List<Note> = a.orEmpty().mapNotNull { n ->
        val m = n as? Map<*, *> ?: return@mapNotNull null
        val title = PlainText.valid(m["title"], Component.MAX_TITLE) ?: return@mapNotNull null
        val d = m["detail"]
        val detail = if (d == null || d == "") null else PlainText.valid(d, Component.MAX_DETAIL) ?: return@mapNotNull null
        Note(title, detail, (m["glyph"] as? String)?.takeIf { it in Component.GLYPHS } ?: "star")
    }.take(Component.MAX_NOTES)

    /** Strict, canonical base64url: the URL alphabet only, no padding, no whitespace, zero trailing bits; null on anything else. */
    fun base64Url(s: String): ByteArray? {
        if (s.length % 4 == 1) return null
        var acc = 0
        var bits = 0
        val out = java.io.ByteArrayOutputStream(s.length * 3 / 4)
        for (ch in s) {
            val v = when (ch) {
                in 'A'..'Z' -> ch - 'A'
                in 'a'..'z' -> ch - 'a' + 26
                in '0'..'9' -> ch - '0' + 52
                '-' -> 62
                '_' -> 63
                else -> return null
            }
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((acc shr bits) and 0xff)
            }
        }
        return if (acc and ((1 shl bits) - 1) == 0) out.toByteArray() else null
    }
}
