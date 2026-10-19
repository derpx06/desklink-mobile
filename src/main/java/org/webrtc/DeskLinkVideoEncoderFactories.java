/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.webrtc;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.view.Surface;
import androidx.annotation.Nullable;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;

/**
 * libwebrtc's hardware encoders, configured for a live mirror.
 *
 * <p>libwebrtc configures MediaCodec with no latency settings, so an encoder may
 * hold several frames before emitting any. Screen capture only produces frames
 * when the screen changes, so the last frames of every movement stayed inside
 * the encoder until the next change pushed them out: on a phone the time from
 * capture to network was 150–230 ms, and taps landed before the picture showed
 * them. This asks every hardware encoder for one-in-one-out operation with the
 * standard Android keys (plus a vendor key where one is known), and falls back
 * to libwebrtc's own configuration if a codec refuses them.
 *
 * <p>In {@code org.webrtc} because the hooks it needs are package-private.
 */
public final class DeskLinkVideoEncoderFactories {
    private static final String TAG = "DeskLinkEncoders";

    private DeskLinkVideoEncoderFactories() {}

    /** Hardware first (low-latency), libwebrtc's software encoders as fallback. */
    public static VideoEncoderFactory create(EglBase.Context sharedContext, boolean enableH264HighProfile) {
        return new DefaultVideoEncoderFactory(new LowLatencyHardwareFactory(sharedContext, enableH264HighProfile));
    }

    /** The keys added to an encoder's format; the original format is not modified. */
    static void applyLowLatency(MediaFormat format) {
        // Realtime scheduling priority (API 23).
        format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        if (Build.VERSION.SDK_INT >= 29) {
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            // At most one input frame in flight before its output is ready.
            format.setInteger(MediaFormat.KEY_LATENCY, 1);
        }
        // Qualcomm's own switch; other codecs ignore unknown vendor keys.
        format.setInteger("vendor.qti-ext-enc-low-latency.enable", 1);
    }

    private static final class LowLatencyHardwareFactory extends HardwareVideoEncoderFactory {
        LowLatencyHardwareFactory(EglBase.Context sharedContext, boolean enableH264HighProfile) {
            super(sharedContext, /* enableIntelVp8Encoder= */ false, enableH264HighProfile);
        }

        @Nullable
        @Override
        public VideoEncoder createEncoder(VideoCodecInfo input) {
            VideoEncoder encoder = super.createEncoder(input);
            if (encoder instanceof HardwareVideoEncoder) {
                try {
                    Field field = HardwareVideoEncoder.class.getDeclaredField("mediaCodecWrapperFactory");
                    field.setAccessible(true);
                    field.set(encoder, new LowLatencyCodecFactory());
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // The encoder still works, just with libwebrtc's settings.
                    Logging.w(TAG, "Low-latency encoder configuration unavailable", e);
                }
            }
            return encoder;
        }
    }

    private static final class LowLatencyCodecFactory implements MediaCodecWrapperFactory {
        private final MediaCodecWrapperFactory inner = new MediaCodecWrapperFactoryImpl();

        @Override
        public MediaCodecWrapper createByCodecName(String name) throws IOException {
            return new LowLatencyCodec(inner, name, inner.createByCodecName(name));
        }
    }

    private static final class LowLatencyCodec implements MediaCodecWrapper {
        private final MediaCodecWrapperFactory factory;
        private final String name;
        private MediaCodecWrapper codec;

        LowLatencyCodec(MediaCodecWrapperFactory factory, String name, MediaCodecWrapper codec) {
            this.factory = factory;
            this.name = name;
            this.codec = codec;
        }

        @Override
        public void configure(MediaFormat format, Surface surface, MediaCrypto crypto, int flags) {
            if (Build.VERSION.SDK_INT < 29 || (flags & MediaCodec.CONFIGURE_FLAG_ENCODE) == 0) {
                codec.configure(format, surface, crypto, flags);
                return;
            }
            MediaFormat tuned = new MediaFormat(format);
            applyLowLatency(tuned);
            try {
                codec.configure(tuned, surface, crypto, flags);
                Logging.d(TAG, "Encoder configured for low latency: " + tuned);
            } catch (RuntimeException refused) {
                // A codec may reject a key it does not know; it must still encode.
                Logging.w(TAG, "Encoder refused low-latency keys; using defaults", refused);
                codec.release();
                try {
                    codec = factory.createByCodecName(name);
                } catch (IOException e) {
                    throw new IllegalStateException("Could not recreate encoder " + name, e);
                }
                codec.configure(format, surface, crypto, flags);
            }
        }

        @Override public void start() { codec.start(); }
        @Override public void flush() { codec.flush(); }
        @Override public void stop() { codec.stop(); }
        @Override public void release() { codec.release(); }
        @Override public int dequeueInputBuffer(long timeoutUs) { return codec.dequeueInputBuffer(timeoutUs); }
        @Override public void queueInputBuffer(int index, int offset, int size, long presentationTimeUs, int flags) {
            codec.queueInputBuffer(index, offset, size, presentationTimeUs, flags);
        }
        @Override public int dequeueOutputBuffer(MediaCodec.BufferInfo info, long timeoutUs) {
            return codec.dequeueOutputBuffer(info, timeoutUs);
        }
        @Override public void releaseOutputBuffer(int index, boolean render) { codec.releaseOutputBuffer(index, render); }
        @Override public MediaFormat getInputFormat() { return codec.getInputFormat(); }
        @Override public MediaFormat getOutputFormat() { return codec.getOutputFormat(); }
        @Override public MediaFormat getOutputFormat(int index) { return codec.getOutputFormat(index); }
        @Override public ByteBuffer getInputBuffer(int index) { return codec.getInputBuffer(index); }
        @Override public ByteBuffer getOutputBuffer(int index) { return codec.getOutputBuffer(index); }
        @Override public Surface createInputSurface() { return codec.createInputSurface(); }
        @Override public void setParameters(Bundle params) { codec.setParameters(params); }
        @Override public MediaCodecInfo getCodecInfo() { return codec.getCodecInfo(); }
    }
}
