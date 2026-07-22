/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import android.content.Context
import org.webrtc.PeerConnectionFactory
import java.util.concurrent.atomic.AtomicBoolean

/** Process-level WebRTC runtime. Native resources are initialized once. */
object WebRtcRuntime {
    private val initialized = AtomicBoolean(false)
    @Volatile private var factory: PeerConnectionFactory? = null

    @Synchronized
    fun initialize(context: Context): PeerConnectionFactory {
        factory?.let { return it }
        if (initialized.compareAndSet(false, true)) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions(),
            )
        }
        return PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
            .also { factory = it }
    }

    @Synchronized
    fun shutdown() {
        factory?.dispose()
        factory = null
        if (initialized.compareAndSet(true, false)) {
            PeerConnectionFactory.stopInternalTracingCapture()
            PeerConnectionFactory.shutdownInternalTracer()
        }
    }
}
