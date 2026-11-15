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

    /** Told, on the encoder thread, the frame size at which a hardware encoder failed. */
    public interface FailureListener {
        void onHardwareEncoderFailure(int width, int height);
    }

    // ponytail: one listener process-wide (the one capturing transport); a
    // per-encoder route if two captures ever run at once.
    @Nullable private static volatile FailureListener failureListener;

    public static void setFailureListener(@Nullable FailureListener listener) {
        failureListener = listener;
    }

    public static synchronized void clearFailureListener(FailureListener listener) {
        if (failureListener == listener) failureListener = null;
    }

    static boolean isFailure(VideoCodecStatus status) {
        return status == VideoCodecStatus.ERROR
            || status == VideoCodecStatus.FALLBACK_SOFTWARE
            || status == VideoCodecStatus.ERR_SIZE
            || status == VideoCodecStatus.MEMORY;
    }

    private static void reportFailure(VideoCodecStatus status, int width, int height) {
        FailureListener listener = failureListener;
        if (listener == null || !isFailure(status)) return;
        listener.onHardwareEncoderFailure(width, height);
    }

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
            FixedRateClock clock = null;
            if (encoder instanceof HardwareVideoEncoder) {
                try {
                    Field field = HardwareVideoEncoder.class.getDeclaredField("mediaCodecWrapperFactory");
                    field.setAccessible(true);
                    field.set(encoder, new LowLatencyCodecFactory());
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // The encoder still works, just with libwebrtc's settings.
                    Logging.w(TAG, "Low-latency encoder configuration unavailable", e);
                }
                try {
                    Field field = HardwareVideoEncoder.class.getDeclaredField("bitrateAdjuster");
                    field.setAccessible(true);
                    // Only libwebrtc's plain adjuster (Qualcomm and most others); the Exynos ones scale bitrate themselves.
                    if (field.get(encoder).getClass() == BaseBitrateAdjuster.class) {
                        clock = new FixedRateClock();
                        field.set(encoder, clock);
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    clock = null;
                    Logging.w(TAG, "Fixed-rate encoder clock unavailable", e);
                }
            }
            return encoder == null ? null : new FailureReportingEncoder(encoder, clock);
        }
    }

    /** Passes everything through; reports failed init/encode calls with their size. */
    private static final class FailureReportingEncoder implements VideoEncoder {
        private final VideoEncoder inner;
        @Nullable private final FixedRateClock clock;

        FailureReportingEncoder(VideoEncoder inner, @Nullable FixedRateClock clock) {
            this.inner = inner;
            this.clock = clock;
        }

        @Override
        public VideoCodecStatus initEncode(Settings settings, Callback callback) {
            if (clock != null) clock.pin(settings.maxFramerate);
            VideoCodecStatus status = inner.initEncode(settings, callback);
            reportFailure(status, settings.width, settings.height);
            return status;
        }

        @Override
        public VideoCodecStatus encode(VideoFrame frame, EncodeInfo info) {
            VideoCodecStatus status = inner.encode(frame, info);
            if (isFailure(status)) {
                reportFailure(status, frame.getBuffer().getWidth(), frame.getBuffer().getHeight());
            }
            return status;
        }

        @Override public long createNative(long webrtcEnvRef) { return inner.createNative(webrtcEnvRef); }
        @Override public boolean isHardwareEncoder() { return inner.isHardwareEncoder(); }
        @Override public VideoCodecStatus release() { return inner.release(); }
        @Override public VideoCodecStatus setRateAllocation(BitrateAllocation allocation, int framerate) {
            return inner.setRateAllocation(allocation, framerate);
        }
        @Override public VideoCodecStatus setRates(RateControlParameters parameters) { return inner.setRates(parameters); }
        @Override public ScalingSettings getScalingSettings() { return inner.getScalingSettings(); }
        @Override public ResolutionBitrateLimits[] getResolutionBitrateLimits() { return inner.getResolutionBitrateLimits(); }
        @Override public String getImplementationName() { return inner.getImplementationName(); }
        @Override public EncoderInfo getEncoderInfo() { return inner.getEncoderInfo(); }
    }

    /**
     * Keeps the encoder's frame clock at the stream's maximum frame rate.
     *
     * <p>libwebrtc's {@code HardwareVideoEncoder} stamps frame N+1 at frame N plus
     * 1/{@link BitrateAdjuster#getAdjustedFramerateFps}, and its {@link BaseBitrateAdjuster} takes
     * that rate from libwebrtc's one-second input-rate estimate. A still screen sends 6-9 fps
     * (refinement), so for the first second of a scroll its 55 fps frames reached the encoder
     * stamped ~130 ms apart, and a rate control that budgets by timestamp gave each of them several
     * times its share. Measured on the RMX5070 (c2.qti.avc.encoder): target 10-12 Mbps, ~17 Mbps
     * encoded, the pacer 1.1-1.9 s behind at every scroll; stamped at the maximum rate it holds the
     * target and the pacer stays under ~100 ms at the start of a scroll and ~10 ms in it. A still
     * screen's few frames then get a smaller share each; refinement (5 repeats) still settles on a
     * fine picture. Bitrate changes are followed as before.
     */
    static final class FixedRateClock extends BaseBitrateAdjuster {
        private double maxFramerateFps;

        /** The stream's maximum frame rate, from each initEncode (it changes with the path). */
        void pin(int maxFramerate) {
            if (maxFramerate > 0) maxFramerateFps = maxFramerate;
        }

        @Override
        public void setTargets(int targetBitrateBps, double targetFramerateFps) {
            super.setTargets(targetBitrateBps, maxFramerateFps > 0 ? maxFramerateFps : targetFramerateFps);
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
