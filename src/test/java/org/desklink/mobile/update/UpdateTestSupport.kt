/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.update

import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.desklink.mobile.MockSharedPreference
import org.json.JSONArray
import org.json.JSONObject

/** Test signing with the shared published TEST key test1 (src/test/resources/shared/update). */
internal object Signer {
    val sk: ByteArray = secret("test1")

    fun secret(kid: String): ByteArray = run {
        val pem = File("src/test/resources/shared/update/$kid.private.pem").readLines().filter { !it.startsWith("#") && !it.startsWith("-----") }.joinToString("")
        Base64.getDecoder().decode(pem).takeLast(32).toByteArray()
    }
    val pk: ByteArray = ByteArray(32).also { Ed25519.generatePublicKey(sk, 0, it, 0) }
    val keys = mapOf("test1" to pk)
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    fun envelope(payload: String, kid: String = "test1", v: Int = 1, tamperSig: Boolean = false, signWith: String = kid): ByteArray {
        val bytes = payload.toByteArray()
        val sig = ByteArray(64)
        val key = secret(if (signWith == "k9") "test1" else signWith)
        Ed25519.sign(key, 0, bytes, 0, bytes.size, sig, 0)
        if (tamperSig) sig[0] = (sig[0].toInt() xor 1).toByte()
        return JSONObject().put("v", v).put("kid", kid).put("payload", b64.encodeToString(bytes)).put("sig", b64.encodeToString(sig)).toString().toByteArray()
    }

    fun payload(
        issuedAt: Long = NOW, expires: Long = NOW + 21 * 86400, schema: Int = 1,
        beta: JSONObject? = component("0.4.0", 20400), stable: JSONObject? = null,
    ): String {
        val ch = JSONObject()
        if (beta != null) ch.put("beta", JSONObject().put("android", beta))
        if (stable != null) ch.put("stable", JSONObject().put("android", stable))
        return JSONObject().put("schemaVersion", schema).put("issuedAt", issuedAt).put("expires", expires).put("channels", ch).toString()
    }

    fun component(version: String, code: Int, rollout: Int = 100, minSupported: String? = null, notes: JSONArray? = null): JSONObject = JSONObject()
        .put("version", version).put("versionCode", code).put("rollout", rollout)
        .apply { if (minSupported != null) put("minSupported", minSupported) }
        .put("notes", notes ?: JSONArray().put(JSONObject().put("title", "Faster reconnects").put("detail", "Back online in seconds.").put("glyph", "refresh")))
        .put("downloads", JSONObject().put("apk", JSONObject().put("url", "https://github.com/derpx06/desklink-mobile/releases/download/v$version/desklink-$version.apk").put("sha256", "0".repeat(64))).put("releaseUrl", "https://github.com/derpx06/desklink-mobile/releases/tag/v$version"))

    const val NOW = 1_795_000_000L
}

internal fun newStore() = UpdateStore(MockSharedPreference())

/** A tiny HTTP/1.1 server on loopback: one canned reply per request, every request recorded. */
internal class LocalServer(private val handler: (Request) -> Reply) : AutoCloseable {
    class Request(val path: String, val headers: Map<String, String>)
    class Reply(val code: Int = 200, val headers: Map<String, String> = emptyMap(), val body: ByteArray = ByteArray(0), val delayMs: Long = 0, val trickle: Boolean = false)

    private val ss = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    val requests = CopyOnWriteArrayList<Request>()
    val url get() = "http://127.0.0.1:${ss.localPort}/latest.json"
    fun url(path: String) = "http://127.0.0.1:${ss.localPort}$path"

    init {
        Thread { while (!ss.isClosed) { val s = runCatching { ss.accept() }.getOrNull() ?: break; Thread { serve(s) }.apply { isDaemon = true }.start() } }.apply { isDaemon = true }.start()
    }

    private fun serve(s: Socket) { runCatching {
        s.use {
            val inp = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val first = inp.readLine() ?: return
            val h = LinkedHashMap<String, String>()
            while (true) { val l = inp.readLine() ?: break; if (l.isEmpty()) break; h[l.substringBefore(':').lowercase()] = l.substringAfter(':').trim() }
            val req = Request(first.split(' ')[1], h).also { requests += it }
            val r = handler(req)
            if (r.delayMs > 0) Thread.sleep(r.delayMs)
            val out = s.getOutputStream()
            val head = StringBuilder("HTTP/1.1 ${r.code} X\r\nContent-Length: ${r.body.size}\r\nConnection: close\r\n")
            r.headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
            out.write(head.append("\r\n").toString().toByteArray(Charsets.ISO_8859_1))
            if (r.trickle) { for (b in r.body) { out.write(b.toInt()); out.flush(); Thread.sleep(300) } } else out.write(r.body)
            out.flush()
        }
    } }

    override fun close() = ss.close()
}
