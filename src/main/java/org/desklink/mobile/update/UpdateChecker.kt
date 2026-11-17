/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.update

import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The anonymous conditional GET of the signed manifest (docs/UPDATES.md 4.2). Its own OkHttp client, apart from WebRTC and
 * relay: no cookie jar, no cache, no authenticator, no automatic redirects, no retry. The request carries exactly
 * User-Agent `DeskLink-update-check/<version>`, Accept, Accept-Encoding: identity (so the size cap counts wire bytes) and
 * If-None-Match: no identifier of any kind. https only; http only for the debug loopback ([debugLoopback]). Never throws.
 */
internal class UpdateChecker(private val appVersion: String, private val debugLoopback: Boolean, private val timeoutS: Long = TIMEOUT_S, client: OkHttpClient = OkHttpClient()) {
    sealed interface Result {
        data object NotModified : Result
        data class Body(val bytes: ByteArray, val etag: String?) : Result
        data class Failed(val reason: FailReason) : Result
    }

    private val http = client.newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(timeoutS, TimeUnit.SECONDS).readTimeout(timeoutS, TimeUnit.SECONDS)
        .callTimeout(timeoutS, TimeUnit.SECONDS)
        .cache(null).build()

    fun fetch(url: String, etag: String?): Result {
        var current = url
        try {
            for (hop in 0..MAX_REDIRECTS) {
                if (!allowed(current)) return Result.Failed(FailReason.URL)
                val req = Request.Builder().url(current)
                    .header("User-Agent", "DeskLink-update-check/$appVersion")
                    .header("Accept", "application/json")
                    .header("Accept-Encoding", "identity")
                    .apply { if (etag != null) header("If-None-Match", etag) }
                    .build()
                http.newCall(req).execute().use { resp ->
                    when {
                        resp.code == 304 -> return Result.NotModified
                        resp.isRedirect -> {
                            val loc = resp.header("Location") ?: return Result.Failed(FailReason.URL)
                            val next = runCatching { URI(current).resolve(loc).toString() }.getOrNull() ?: return Result.Failed(FailReason.URL)
                            // Same host only, and https stays https.
                            if (URI(next).host?.lowercase() != URI(current).host?.lowercase() || (URI(current).scheme == "https" && URI(next).scheme != "https")) {
                                return Result.Failed(FailReason.URL)
                            }
                            current = next
                        }
                        resp.code != 200 -> return Result.Failed(FailReason.HTTP)
                        else -> return readCapped(resp.body.source(), resp.header("ETag"))
                    }
                }
            }
            return Result.Failed(FailReason.URL) // redirect limit
        } catch (_: InterruptedIOException) {
            return Result.Failed(FailReason.TIMEOUT)
        } catch (_: IOException) {
            return Result.Failed(FailReason.OFFLINE)
        } catch (_: RuntimeException) {
            return Result.Failed(FailReason.URL)
        }
    }

    private fun readCapped(source: okio.BufferedSource, etag: String?): Result {
        // Stops at the cap: one byte past it is enough to know the body is too big, and nothing more is read.
        if (source.request(UpdateVerifier.MAX_BYTES.toLong() + 1)) return Result.Failed(FailReason.OVERSIZED)
        return Result.Body(source.readByteArray(), etag?.takeIf { it.length <= 256 })
    }

    private fun allowed(url: String): Boolean {
        val u = runCatching { URI(url) }.getOrNull() ?: return false
        if (u.userInfo != null) return false
        return when (u.scheme) {
            "https" -> u.host != null
            "http" -> debugLoopback && u.host in LOOPBACK_HOSTS
            else -> false
        }
    }

    companion object {
        const val TIMEOUT_S = 10L
        const val MAX_REDIRECTS = 3
        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "10.0.2.2")
    }
}
