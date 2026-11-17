/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.update

import java.io.File
import java.math.BigInteger
import java.util.Base64
import kotlin.random.Random
import org.desklink.mobile.update.Signer.NOW
import org.desklink.mobile.update.UpdateVerifier.Verdict
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The verification order and every verdict, with the strict profile (docs/UPDATES.md 2.1, 2.2, 2.6). Robolectric only builds test JSON. */
@RunWith(RobolectricTestRunner::class)
class UpdateVerifierTest {
    private fun verify(body: ByteArray, now: Long = NOW, lastSeen: Long = 0, keys: Map<String, ByteArray> = Signer.keys) = UpdateVerifier.verify(body, keys, now, lastSeen)
    private fun rejected(v: Verdict) = (v as Verdict.Rejected).reason
    private fun beta(v: Verdict) = (v as? Verdict.Ok)?.payload?.channels?.get(Channel.BETA)
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    /** An envelope whose payload text is exactly [raw] and whose signature is real: for payloads JSONObject would normalise. */
    private fun signedRaw(raw: String) = Signer.envelope(raw)

    @Test fun aSignedManifestIsAccepted() {
        val p = (verify(Signer.envelope(Signer.payload())) as Verdict.Ok).payload
        assertEquals(20400, p.channels[Channel.BETA]!!.versionCode)
        assertNull(p.channels[Channel.STABLE]) // a missing channel is nothing to offer, not an error
    }

    @Test fun aWrongSignatureIsRefused() = assertEquals(FailReason.SIGNATURE, rejected(verify(Signer.envelope(Signer.payload(), tamperSig = true))))

    @Test fun aChangedPayloadIsRefused() {
        val env = JSONObject(String(Signer.envelope(Signer.payload())))
        env.put("payload", b64.encodeToString(Signer.payload(beta = Signer.component("9.9.9", 99999)).toByteArray()))
        assertEquals(FailReason.SIGNATURE, rejected(verify(env.toString().toByteArray())))
    }

    @Test fun anUnknownKeyIdVersionOrKeyIsRefused() {
        assertEquals(FailReason.SIGNATURE, rejected(verify(Signer.envelope(Signer.payload(), kid = "k9"))))
        assertEquals(FailReason.PARSE, rejected(verify(Signer.envelope(Signer.payload(), v = 2))))
        assertEquals(FailReason.SIGNATURE, rejected(verify(Signer.envelope(Signer.payload()), keys = emptyMap())))
        assertEquals(FailReason.SIGNATURE, rejected(verify(Signer.envelope(Signer.payload()), keys = mapOf("test1" to ByteArray(31)))))
        assertEquals(FailReason.SIGNATURE, rejected(verify(Signer.envelope(Signer.payload(), signWith = "test2"), keys = Signer.keys))) // signed by another key
    }

    @Test fun keyRotationAcceptsTheNextKeyAndRetiringDropsIt() {
        val both = mapOf("test1" to Signer.pk, "test2" to ByteArray(32).also { org.bouncycastle.math.ec.rfc8032.Ed25519.generatePublicKey(Signer.secret("test2"), 0, it, 0) })
        val signedByNext = Signer.envelope(Signer.payload(), kid = "test2")
        assertTrue(verify(signedByNext, keys = both) is Verdict.Ok)
        assertEquals(FailReason.SIGNATURE, rejected(verify(signedByNext, keys = mapOf("test1" to Signer.pk)))) // a client without k2 refuses
        assertTrue(verify(Signer.envelope(Signer.payload()), keys = both) is Verdict.Ok)
    }

    @Test fun theEnvelopeHasExactlyTheFourKeys() {
        val ok = JSONObject(String(Signer.envelope(Signer.payload())))
        assertTrue(verify(ok.toString().toByteArray()) is Verdict.Ok)
        assertEquals(FailReason.PARSE, rejected(verify(ok.put("extra", 1).toString().toByteArray())))
        assertEquals(FailReason.PARSE, rejected(verify(JSONObject(String(Signer.envelope(Signer.payload()))).also { it.remove("kid") }.toString().toByteArray())))
        assertEquals(FailReason.PARSE, rejected(verify(String(Signer.envelope(Signer.payload())).replace("\"v\":1", "\"v\":true").toByteArray())))
        assertEquals(FailReason.PARSE, rejected(verify(String(Signer.envelope(Signer.payload())).replace("\"v\":1", "\"v\":1.0").toByteArray())))
    }

    @Test fun theKidIsLookedUpBeforeThePayloadTypesLikeTheReferenceVerifier() {
        // dl_update.py: a non-string kid is parse; an unknown kid is signature whatever payload/sig hold; a known kid with a
        // non-string or badly encoded payload/sig is signature too.
        assertEquals(FailReason.PARSE, rejected(verify("""{"v":1,"kid":5,"payload":"AAAA","sig":"AAAA"}""".toByteArray())))
        assertEquals(FailReason.SIGNATURE, rejected(verify("""{"v":1,"kid":"nope","payload":5,"sig":null}""".toByteArray())))
        assertEquals(FailReason.SIGNATURE, rejected(verify("""{"v":1,"kid":"test1","payload":5,"sig":"AAAA"}""".toByteArray())))
        assertEquals(FailReason.SIGNATURE, rejected(verify("""{"v":1,"kid":"test1","payload":"AAAA","sig":[]}""".toByteArray())))
    }

    @Test fun malformedEnvelopesAreRefusedNotThrown() {
        for (s in listOf("", "{", "[]", "null", """{"v":1}""")) assertEquals(s, FailReason.PARSE, rejected(verify(s.toByteArray())))
        for (s in listOf("""{"v":1,"kid":"test1","payload":"!!","sig":"!!"}""", """{"v":1,"kid":"test1","payload":"AAAA","sig":"AAAA"}""")) assertEquals(s, FailReason.SIGNATURE, rejected(verify(s.toByteArray())))
    }

    @Test fun duplicateKeysBomAndBadUtf8AreRefused() {
        val good = String(Signer.envelope(Signer.payload()))
        assertEquals(FailReason.PARSE, rejected(verify(good.replaceFirst("{", "{\"v\":1,").toByteArray()))) // duplicate "v"
        assertEquals(FailReason.PARSE, rejected(verify(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + good.toByteArray())))
        assertEquals(FailReason.PARSE, rejected(verify(good.toByteArray() + byteArrayOf(0xC3.toByte())))) // invalid UTF-8 / trailing junk
        // And inside the SIGNED payload: a duplicate key is a parse failure, not "the last one wins".
        val raw = Signer.payload()
        assertEquals(FailReason.PARSE, rejected(verify(signedRaw(raw.replaceFirst("{", "{\"issuedAt\":1,")))))
        assertEquals(FailReason.PARSE, rejected(verify(signedRaw(raw.replaceFirst("{", "{\"a\":NaN,")))))
        assertEquals(FailReason.PARSE, rejected(verify(signedRaw(raw.replaceFirst("{", "{\"a\":\"\\ud800\",")))))
    }

    @Test fun deepNestingIsRefusedWithoutOverflowingTheStack() {
        assertEquals(FailReason.PARSE, rejected(verify("[".repeat(60_000).toByteArray())))
        assertEquals(FailReason.PARSE, rejected(verify(("{\"a\":".repeat(10_000)).toByteArray())))
        assertEquals(FailReason.PARSE, rejected(verify(signedRaw("[".repeat(5_000) + "]".repeat(5_000)))))
        assertTrue(StrictJson.parse("[".repeat(32).plus("]".repeat(32)).toByteArray()) != null)
        assertNull(StrictJson.parse("[".repeat(33).plus("]".repeat(33)).toByteArray()))
    }

    @Test fun base64IsStrictAndCanonical() {
        assertEquals(3, UpdateVerifier.base64Url("AAEC")!!.size)
        assertNull(UpdateVerifier.base64Url("AAEC=")); assertNull(UpdateVerifier.base64Url("AA+C")); assertNull(UpdateVerifier.base64Url("AAE C"))
        assertNull(UpdateVerifier.base64Url("A")); assertNull(UpdateVerifier.base64Url("AB")) // trailing bits not zero
    }

    @Test fun aMalleableSignatureWithSPlusLIsRefused() {
        val l = BigInteger("7237005577332262213973186563042994240857116359379907606001950938285454250989")
        val payload = Signer.payload().toByteArray()
        val sig = ByteArray(64).also { org.bouncycastle.math.ec.rfc8032.Ed25519.sign(Signer.sk, 0, payload, 0, payload.size, it, 0) }
        val s = BigInteger(1, sig.copyOfRange(32, 64).reversedArray()) + l
        val sLe = s.toByteArray().reversedArray().copyOf(32) // S + L still fits in 32 bytes for a low S
        val bad = sig.copyOf().also { sLe.copyInto(it, 32) }
        val env = JSONObject().put("v", 1).put("kid", "test1").put("payload", b64.encodeToString(payload)).put("sig", b64.encodeToString(bad)).toString().toByteArray()
        assertEquals(FailReason.SIGNATURE, rejected(verify(env)))
    }

    @Test fun overTheCapIsRefusedBeforeAnythingIsRead() {
        assertEquals(FailReason.OVERSIZED, rejected(verify(ByteArray(UpdateVerifier.MAX_BYTES + 1))))
        assertEquals(FailReason.PARSE, rejected(verify(ByteArray(UpdateVerifier.MAX_BYTES)))) // exactly at the cap goes on (and fails as junk)
    }

    @Test fun theBodyIsLookedAtOnlyAfterTheSignature() {
        assertEquals(FailReason.PARSE, rejected(verify(Signer.envelope("not json"))))
        assertEquals(FailReason.SIGNATURE, rejected(verify(Signer.envelope("not json", tamperSig = true))))
    }

    @Test fun schemaAndTimeRules() {
        assertEquals(FailReason.SCHEMA, rejected(verify(Signer.envelope(Signer.payload(schema = 2)))))
        assertEquals(FailReason.PARSE, rejected(verify(Signer.envelope(Signer.payload(schema = 0)))))
        assertEquals(FailReason.PARSE, rejected(verify(signedRaw("""{"schemaVersion":1,"issuedAt":"1","expires":2,"channels":{}}"""))))
        assertEquals(FailReason.PARSE, rejected(verify(signedRaw("""{"schemaVersion":1.0,"issuedAt":$NOW,"expires":${NOW + 100},"channels":{}}"""))))
        assertEquals(FailReason.SCHEMA, rejected(verify(signedRaw("""{"schemaVersion":2,"issuedAt":"x","expires":null}"""))))
        assertEquals(FailReason.EXPIRED, rejected(verify(Signer.envelope(Signer.payload(expires = NOW - 1)))))
        assertTrue(verify(Signer.envelope(Signer.payload(issuedAt = NOW - 100, expires = NOW))) is Verdict.Ok) // exactly at expiry is still good
        assertEquals(FailReason.REPLAY, rejected(verify(Signer.envelope(Signer.payload(issuedAt = NOW - 10)), lastSeen = NOW)))
        assertTrue(verify(Signer.envelope(Signer.payload(issuedAt = NOW)), lastSeen = NOW) is Verdict.Ok)
        assertTrue(verify(Signer.envelope(Signer.payload(issuedAt = NOW + 3600, expires = NOW + 3600 + 86400))) is Verdict.Ok) // one hour ahead is the bound
        assertEquals(FailReason.EXPIRED, rejected(verify(Signer.envelope(Signer.payload(issuedAt = NOW + 3601, expires = NOW + 3601 + 86400)))))
        assertTrue(verify(Signer.envelope(Signer.payload(issuedAt = NOW, expires = NOW + 22 * 86400))) is Verdict.Ok)
        assertEquals(FailReason.EXPIRED, rejected(verify(Signer.envelope(Signer.payload(issuedAt = NOW, expires = NOW + 22 * 86400 + 1))))) // a lifetime over 22 days
    }

    @Test fun aBadComponentIsDroppedAndTheOtherIsStillDecided() {
        val v = verify(Signer.envelope(Signer.payload(beta = Signer.component("0.04.0", 20400), stable = Signer.component("1.0.0", 30000))))
        assertNull(beta(v)); assertEquals(30000, (v as Verdict.Ok).payload.channels[Channel.STABLE]!!.versionCode)
        for (bad in listOf("0.4", "0.4.0-rc1", "0.4.0+b1", "0.4.0.1", "٠.4.0", "99999999999.0.0", "0.4.0 ")) assertNull(bad, beta(verify(Signer.envelope(Signer.payload(beta = Signer.component(bad, 20400))))))
        for (code in listOf(0, -5)) assertNull(beta(verify(Signer.envelope(Signer.payload(beta = Signer.component("0.4.0", code))))))
        for (extra in listOf("\"rollout\":-1", "\"rollout\":101", "\"rollout\":1.5", "\"rollout\":\"5\"", "\"rollout\":true", "\"minSupported\":\"0.5.0\"", "\"minSupported\":\"x\"", "\"versionCode\":\"20400\"", "\"versionCode\":4294967296", "\"versionCode\":2100000001")) {
            val code = if (extra.startsWith("\"versionCode\"")) "" else "\"versionCode\":20400,"
            val raw = """{"schemaVersion":1,"issuedAt":$NOW,"expires":${NOW + 100},"channels":{"beta":{"android":{"version":"0.4.0",$code$extra}}}}"""
            assertNull(extra, beta(verify(signedRaw(raw))))
        }
        assertEquals(null, beta(verify(signedRaw("""{"schemaVersion":1,"issuedAt":$NOW,"expires":${NOW + 100},"channels":{"beta":{"android":{"version":"0.4.0"}}}}"""))))
    }

    @Test fun notesAreObjectsAndABrokenNoteIsSkipped() {
        val notes = JSONArray()
        repeat(9) { notes.put(JSONObject().put("title", "Note $it").put("detail", "d").put("glyph", if (it == 0) "nope" else "shield")) }
        notes.put("a string is not a note").put(JSONObject().put("detail", "no title"))
        notes.put(JSONObject().put("title", "x".repeat(41))).put(JSONObject().put("title", "ok").put("detail", "y".repeat(81)))
        notes.put(JSONObject().put("title", "a<b")).put(JSONObject().put("title", "line\nbreak")).put(JSONObject().put("title", "bidi\u202e")).put(JSONObject().put("title", "zero\u200bwidth"))
        val c = beta(verify(Signer.envelope(Signer.payload(beta = Signer.component("0.4.0", 20400, notes = notes)))))!!
        assertEquals(8, c.notes.size)
        assertEquals("star", c.notes[0].glyph) // unknown glyph falls back
        assertEquals("shield", c.notes[1].glyph)
        assertTrue(beta(verify(Signer.envelope(Signer.payload(beta = Signer.component("0.4.0", 20400, notes = JSONArray())))))!!.notes.isEmpty())
    }

    @Test fun numbersAreIntegerTokensOnlyAndTheWholePayloadFailsOtherwise() {
        for (n in listOf("-0", "100.0", "5e1", "1e999", "01", "-1", "1E2")) {
            val raw = """{"schemaVersion":1,"issuedAt":$NOW,"expires":${NOW + 100},"x":$n,"channels":{}}"""
            assertEquals(n, FailReason.PARSE, rejected(verify(signedRaw(raw))))
        }
        // An integer above i64 is valid JSON but out of range for the field that reads it.
        assertEquals(FailReason.PARSE, rejected(verify(signedRaw("""{"schemaVersion":1,"issuedAt":99999999999999999999,"expires":1,"channels":{}}"""))))
        // null is not absent.
        for (extra in listOf("\"rollout\":null", "\"minSupported\":null")) {
            val raw = """{"schemaVersion":1,"issuedAt":$NOW,"expires":${NOW + 100},"channels":{"beta":{"android":{"version":"0.4.0","versionCode":20400,$extra}}}}"""
            assertNull(extra, beta(verify(signedRaw(raw))))
        }
    }

    @Test fun unknownPayloadFieldsAreIgnoredAtEveryLevel() {
        val raw = """{"schemaVersion":1,"issuedAt":$NOW,"expires":${NOW + 100},"extra":{"a":1},"channels":{"x":1,"beta":{"y":2,"android":{"version":"0.4.0","versionCode":20400,"z":[1]}}}}"""
        assertEquals(20400, beta(verify(signedRaw(raw)))!!.versionCode)
    }

    @Test fun duplicateKeysAreFoundAfterUnescapingAndWhenNested() {
        assertEquals(FailReason.PARSE, rejected(verify(signedRaw("""{"schemaVersion":1,"issuedAt":$NOW,"expires":${NOW + 100},"channels":{"b\u0065ta":{},"beta":{}}}"""))))
        assertEquals(FailReason.PARSE, rejected(verify(String(Signer.envelope(Signer.payload())).replace("\"kid\":", "\"k\\u0069d\":\"x\",\"kid\":").toByteArray())))
    }

    @Test fun theFrozenTextListIsExact() {
        fun ok(cp: Int) = PlainText.valid(String(Character.toChars(cp)), 5) != null
        for (cp in listOf(0x00, 0x1F, 0x7F, 0x85, 0x9F, 0x00AD, 0x061C, 0x180E, 0x200B, 0x200F, 0x202A, 0x2028, 0x2029, 0x2060, 0x2064, 0x2066, 0x206F, 0xFEFF, 0xFFF9, 0x110BD, 0x13430, 0x13440, 0x1BCA0, 0x1D173, 0xE0001, 0xE0020, 0xE007F, '<'.code, '>'.code, '&'.code)) assertFalse(Integer.toHexString(cp), ok(cp))
        for (cp in listOf(0x20, 0x7E, 0xA0, 0xAE, 0x0378, 0x3000, 0x2065, 0x200A, 0x2010, 0xE000, 0xFFFE, 0x1F600, 0x0301, 0xE0000, 0xE0080)) assertTrue(Integer.toHexString(cp), ok(cp))
        assertNull(PlainText.valid("a\u200Db", 10)) // a ZWJ sequence is skipped
        assertEquals(2, "e\u0301".codePointCount(0, 2)); assertNull(PlainText.valid("e\u0301e", 2))
    }

    @Test fun releasedIsARealDate() {
        fun rel(d: String) = beta(verify(signedRaw("""{"schemaVersion":1,"issuedAt":$NOW,"expires":${NOW + 100},"channels":{"beta":{"android":{"version":"0.4.0","versionCode":20400,"released":"$d"}}}}""")))!!.released
        assertEquals("2024-02-29", rel("2024-02-29")); assertEquals("2000-02-29", rel("2000-02-29")); assertEquals("9999-12-31", rel("9999-12-31"))
        for (bad in listOf("2026-02-29", "2100-02-29", "0000-01-01", "2026-13-01", "2026-1-01", "\u0662026-01-01", "2026-01-01 ")) assertNull(bad, rel(bad))
    }

    @Test fun versionPartsAreAsciiAndBounded() {
        assertEquals(Version(2147483647, 0, 0), Version.parse("2147483647.0.0")); assertNull(Version.parse("2147483648.0.0"))
        assertNull(Version.parse("\u0660.1.0")); assertNull(Version.parseLenient("\u0660.1.0")); assertTrue(Version(0, 10, 0) > Version(0, 9, 0))
    }

    @Test fun lengthsCountUnicodeScalarsNotUtf16Units() {
        val forty = "\uD83D\uDE00".repeat(40) // 40 scalars, 80 UTF-16 units
        assertEquals(forty, PlainText.valid(forty, 40))
        assertNull(PlainText.valid(forty + "a", 40))
        assertNull(PlainText.valid("", 40)); assertNull(PlainText.valid(5, 40))
    }

    @Test fun urlsOutsideThePinnedShapesAreDropped() {
        val c = Signer.component("0.4.0", 20400)
        c.getJSONObject("downloads").getJSONObject("apk").put("url", "https://evil.example/d.apk")
        c.getJSONObject("downloads").put("releaseUrl", "https://github.com:8443/x")
        c.put("notesUrl", "http://github.com/x")
        val got = beta(verify(Signer.envelope(Signer.payload(beta = c))))!!
        assertNull(got.apkUrl); assertNull(got.releaseUrl); assertNull(got.notesUrl)
        assertNull(beta(verify(Signer.envelope(Signer.payload(beta = Signer.component("0.4.0", 20400).also { it.getJSONObject("downloads").getJSONObject("apk").put("url", "https://github.com/derpx06/desklink-mobile/releases/download/v0.5.0/desklink-0.5.0.apk") }))))!!.apkUrl) // another version
        assertTrue(beta(verify(Signer.envelope(Signer.payload())))!!.apkUrl != null)
    }

    @Test fun neverThrowsWhateverTheBytes() {
        // Deterministic byte-flip, truncation and splice fuzz over every shared manifest.
        val rnd = Random(20260217)
        val keys = Signer.keys
        val files = File("src/test/resources/shared/update/manifests").listFiles()!!.filter { it.length() < 10_000 }.sortedBy { it.name }
        var runs = 0
        for (f in files) {
            val orig = f.readBytes()
            val cases = ArrayList<ByteArray>()
            repeat(40) { cases += orig.copyOf().also { b -> if (b.isNotEmpty()) b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() } }
            repeat(6) { cases += orig.copyOf(rnd.nextInt(orig.size + 1)) }
            cases += orig + orig
            for (b in cases) { UpdateVerifier.verify(b, keys, NOW, 0); runs++ }
        }
        assertTrue(runs > 2000)
        assertFalse(files.isEmpty())
    }
}
