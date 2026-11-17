/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.update

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner

/** The shared table (docs/update/fixtures/cases.json, byte-identical copy): the same runs the desk and the Python verifier pass. */
@RunWith(RobolectricTestRunner::class)
class UpdateSharedCasesTest {
    private val dir = File("src/test/resources/shared/update")
    private val table = JSONObject(File(dir, "cases.json").readText())
    private val defaults = table.getJSONObject("defaults")
    private val keys: Map<String, ByteArray> = JSONObject(File(dir, "keys.json").readText()).getJSONArray("keys").let { a ->
        (0 until a.length()).associate { a.getJSONObject(it).getString("kid") to UpdateVerifier.base64Url(a.getJSONObject(it).getString("public"))!! }
    }

    private fun field(run: JSONObject, name: String): Any = if (run.has(name)) run.get(name) else defaults.get(name)


    private fun name(s: UpdateState): String = when (s) {
        UpdateState.UpToDate -> "UpToDate"
        is UpdateState.Available -> "Offer:${s.component.version}"
        is UpdateState.Required -> "Required:${s.component.version}"
        is UpdateState.Skipped -> "Skipped:${s.component.version}"
        is UpdateState.Snoozed -> "Snoozed:${s.component.version}"
        else -> error("unexpected $s")
    }

    @Test fun everyCaseGivesTheExpectedVerdict() {
        val cases = table.getJSONArray("cases")
        var runs = 0
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val body = File(dir, case.getString("file")).readBytes()
            val list = case.getJSONArray("runs")
            for (j in 0 until list.length()) {
                val run = list.getJSONObject(j)
                val label = "${case.getString("id")}/${run.getString("name")}"
                val now = (field(run, "now") as Number).toLong()
                val last = (field(run, "lastSeenIssuedAt") as Number).toLong()
                val expect = run.get("expect").let { if (it is JSONObject) it.getString("android") else it as String }
                val kids = field(run, "keys") as JSONArray
                val usable = keys.filterKeys { k -> (0 until kids.length()).any { kids.getString(it) == k } }
                val v = UpdateVerifier.verify(body, usable, now, last)
                val got = when (v) {
                    is UpdateVerifier.Verdict.Rejected -> "Ignore:${v.reason.code}"
                    is UpdateVerifier.Verdict.Ok -> {
                        val inst = field(run, "installed") as JSONObject
                        val prefs = field(run, "prefs") as JSONObject
                        val id = UpdateStore.unhex(field(run, "installId") as String)!!
                        // A malformed LOCAL version is a build bug: nothing is offered.
                        val installed = Version.parse(inst.getString("android"))
                        if (installed == null) "UpToDate" else name(UpdateDecider.decide(
                            installed, inst.getInt("androidCode"), Channel.of(field(run, "channel") as String)!!,
                            v.payload,
                            UpdatePrefs(prefs.optString("skipped").ifEmpty { null }, prefs.optString("snoozedVersion").ifEmpty { null }, prefs.optLong("snoozedUntil") * 1000),
                            id, now * 1000, field(run, "manual") as Boolean,
                        ))
                    }
                }
                assertEquals(label, expect, got)
                runs++
                if (v is UpdateVerifier.Verdict.Ok) checkExtras(label, run, v.payload)
            }
        }
        assertTrue("table ran $runs runs", runs > 60)
    }

    private fun checkExtras(label: String, run: JSONObject, p: Payload) {
        val notes = (p.channels[Channel.BETA] ?: p.channels[Channel.STABLE])?.notes ?: return
        // expectNotes is the DESKTOP component's list; expectNotesAndroid (when present) is this component's.
        when (val e = if (run.has("expectNotesAndroid")) run.opt("expectNotesAndroid") else run.opt("expectNotes")) {
            is JSONArray -> {
                assertEquals(label, e.length(), notes.size)
                for (k in 0 until e.length()) {
                    val n = e.getJSONObject(k)
                    assertEquals(label, n.getString("title"), notes[k].title)
                    assertEquals(label, if (n.isNull("detail")) null else n.getString("detail"), notes[k].detail)
                    assertEquals(label, n.getString("glyph"), notes[k].glyph)
                }
            }
            is JSONObject -> { assertTrue(label, notes.size <= e.getInt("maxCount")); assertTrue(label, notes.all { it.title.length <= e.getInt("maxTitle") }) }
        }
        val comp = p.channels[Channel.BETA] ?: p.channels[Channel.STABLE] ?: return
        if (run.has("expectRequiredReason")) assertEquals(label, run.opt("expectRequiredReason").takeUnless { it == JSONObject.NULL }, comp.requiredReason)
        if (run.has("expectReleased")) assertEquals(label, run.opt("expectReleased").takeUnless { it == JSONObject.NULL }, comp.released)
        val urls = run.optJSONObject("expectUrls") ?: return
        for (key in urls.keys()) {
            if (!key.startsWith("android.")) continue
            val got = when (key.removePrefix("android.")) { "notesUrl" -> comp.notesUrl; "releaseUrl" -> comp.releaseUrl; "apk" -> comp.apkUrl; else -> continue }
            assertEquals("$label $key", urls.getString(key) == "kept", got != null)
        }
    }

    @Test fun bucketVectors() {
        val a = table.getJSONArray("bucketVectors")
        for (i in 0 until a.length()) {
            val b = a.getJSONObject(i)
            assertEquals(b.toString(), b.getInt("bucket"), UpdateDecider.bucket(UpdateStore.unhex(b.getString("installId"))!!, b.getString("component"), b.getString("version")))
        }
    }

    @Test fun urlAllowlist() {
        val a = table.getJSONArray("urlChecks")
        for (i in 0 until a.length()) {
            val c = a.getJSONArray(i)
            assertEquals(c.toString(), c.getBoolean(1), UrlAllowlist.any(c.getString(0)))
        }
    }

    @Test fun urlContextChecks() {
        val a = table.getJSONArray("urlContextChecks")
        for (i in 0 until a.length()) {
            val r = a.getJSONArray(i)
            fun str(k: Int) = if (r.isNull(k)) null else r.getString(k)
            assertEquals(r.toString(), r.getBoolean(4), UrlAllowlist.context(str(0), str(1), str(2), str(3)))
        }
    }

    @Test fun installIdChecks() {
        val a = table.getJSONArray("installIdChecks")
        for (i in 0 until a.length()) {
            val r = a.getJSONArray(i)
            val parsed = UpdateStore.unhex(r.getString(0))?.takeIf { it.size == 16 }
            assertEquals(r.toString(), r.getBoolean(1), parsed != null)
        }
    }

    @Test fun theRatchetStoresTheSmallerOfIssuedAtAndNow() {
        val a = table.getJSONArray("ratchetVectors")
        for (i in 0 until a.length()) {
            val r = a.getJSONObject(i)
            assertEquals(r.toString(), r.getLong("stored"), minOf(r.getLong("issuedAt"), r.getLong("now")))
        }
    }

    @Test fun theSharedVectorVerifiesAndTheCompiledTestKeysEqualTheFixtureKeys() {
        val vec = JSONObject(File(dir, "vector.json").readText())
        val pk = UpdateVerifier.base64Url(vec.getString("publicKeyBase64url"))!!
        val msg = UpdateVerifier.base64Url(vec.getString("payloadBase64url"))!!
        val sig = UpdateVerifier.base64Url(vec.getString("signatureBase64url"))!!
        assertTrue(org.bouncycastle.math.ec.rfc8032.Ed25519.verify(sig, 0, pk, 0, msg, 0, msg.size))
        msg[0] = (msg[0].toInt() xor 1).toByte()
        assertTrue(!org.bouncycastle.math.ec.rfc8032.Ed25519.verify(sig, 0, pk, 0, msg, 0, msg.size))
        val fixtureKeys = JSONObject(File(dir, "keys.json").readText()).getJSONArray("keys")
        for (i in 0 until fixtureKeys.length()) assertEquals(fixtureKeys.getJSONObject(i).getString("public"), DebugKeys.KEYS[fixtureKeys.getJSONObject(i).getString("kid")])
    }
}
