/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import java.security.MessageDigest

/**
 * The benchmark tests judge themselves against this machine, not against a fixed MB/s: the sender and receiver are bounded by
 * SHA-256, so "at least a fraction of raw SHA-256 on the same host" fails on a regression and never on a slow CI box.
 */
object BenchBaseline {
    /** Raw SHA-256 throughput of this host, MB/s (best of three 64 MiB runs). */
    val sha256MBps: Double by lazy {
        val chunk = ByteArray(1 shl 20)
        (1..3).maxOf {
            val digest = MessageDigest.getInstance("SHA-256")
            val start = System.nanoTime()
            repeat(64) { digest.update(chunk) }
            64.0 * (1 shl 20) / 1e6 / ((System.nanoTime() - start) / 1e9)
        }
    }
}
