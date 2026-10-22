/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import android.content.Context
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import java.util.concurrent.atomic.AtomicBoolean

/** Process-level WebRTC runtime. Native resources are initialized once. */
object WebRtcRuntime {
    private val initialized = AtomicBoolean(false)
    @Volatile private var factory: PeerConnectionFactory? = null
    @Volatile private var eglBase: EglBase? = null

    @Synchronized
    fun initialize(context: Context): PeerConnectionFactory {
        factory?.let { return it }
        if (initialized.compareAndSet(false, true)) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .setFieldTrials(FIELD_TRIALS)
                    .createInitializationOptions(),
            )
        }
        val egl = eglBase ?: EglBase.create().also { eglBase = it }
        // Without these the factory has **no video codecs at all**. The Android
        // JNI maps a null Java encoder factory to a null *native* factory with
        // no builtin fallback (`sdk/android/src/jni/pc/video.cc`), so attaching
        // a video track to a factory built without them yields either a
        // codec-less video m-line or a native crash.
        //
        // That is the real cause of the "malformed, codec-less video answer"
        // that `includeScreenTrackInInitialOffer` was disabled to avoid. It was
        // read as a peer-interop problem; it is local configuration, and it was
        // harmless only for as long as nothing ever attached a video track.
        //
        // The `EglBase.Context` is not optional either: it is what enables
        // surface-texture encoding, so MediaProjection frames go GPU-to-GPU
        // instead of being read back to CPU memory and re-uploaded. At
        // 1080x2400x30 that is the difference between working and not. If the
        // context is rejected the factory silently disables texture support and
        // only says so in logcat.
        return PeerConnectionFactory.builder()
            .setOptions(iceNetworkOptions())
            // F1: media playout in stereo, VOICE_COMMUNICATION mic, and the phone's own sound as a
            // stand-in source. Nothing opens until a peer turns recording/playout on.
            .setAudioDeviceModule(PhoneAudioEngine.create(context))
            .setVideoEncoderFactory(
                // libwebrtc's encoders, with hardware ones in low-latency mode.
                org.webrtc.DeskLinkVideoEncoderFactories.create(
                    egl.eglBaseContext,
                    /* enableH264HighProfile = */ true,
                ),
            )
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
            .also { factory = it }
    }

    /**
     * Gather ICE candidates on every interface the kernel has, not only the
     * networks Android reports.
     *
     * libwebrtc's Android network monitor lists `ConnectivityManager` networks
     * that have internet capability. A laptop on this phone's hotspot reaches
     * it through the tethering interface, which is not such a network, so the
     * phone offered only loopback and mobile-data candidates and the direct
     * link failed every time while the LAN bootstrap kept working. Without the
     * monitor libwebrtc enumerates interfaces itself (refreshed every 2 s), the
     * hotspot included. Loopback candidates never reach the desk, so they are
     * dropped.
     */
    internal fun iceNetworkOptions() = PeerConnectionFactory.Options().apply {
        disableNetworkMonitor = true
        networkIgnoreMask = PeerConnectionFactory.Options.ADAPTER_TYPE_LOOPBACK
    }

    /**
     * V3: no playout delay. A screen is not a call: frames are shown the moment they decode,
     * never smoothed into a jitter buffer target (local, no wire).
     */
    internal const val FIELD_TRIALS = "WebRTC-ForcePlayoutDelay/min_ms:0,max_ms:0/"

    @Synchronized
    fun eglContext(context: Context): EglBase.Context {
        initialize(context)
        return requireNotNull(eglBase).eglBaseContext
    }

    @Synchronized
    fun shutdown() {
        factory?.dispose()
        factory = null
        eglBase?.release()
        eglBase = null
        if (initialized.compareAndSet(true, false)) {
            PeerConnectionFactory.stopInternalTracingCapture()
            PeerConnectionFactory.shutdownInternalTracer()
        }
    }
}
