/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer

/**
 * The process's one audio module (F1). Built explicitly so that:
 * - playout is media, in stereo, and follows Android's route (speaker, Bluetooth, earbuds);
 * - the microphone is VOICE_COMMUNICATION with the hardware echo canceller and noise suppressor;
 * - the phone's own sound (AudioPlaybackCapture) can stand in for the microphone: with the
 *   recorder disabled the module opens no AudioRecord at all and takes its 10 ms buffers from
 *   [onBuffer] — no microphone, no microphone indicator.
 *
 * Whether anything records or plays at all is each peer's `setAudioRecording`/`setAudioPlayout`,
 * both off until an audio session asks (see [WebRtcTransport]).
 */
object PhoneAudioEngine {
    private const val TAG = "DeskLink/Audio"

    enum class Source { NONE, MIC, SYSTEM }

    @Volatile private var module: JavaAudioDeviceModule? = null
    @Volatile private var source = Source.NONE
    @Volatile private var capture: AudioRecord? = null
    @Volatile private var projection: MediaProjection? = null
    @Volatile var muted = false

    fun create(context: Context): JavaAudioDeviceModule = JavaAudioDeviceModule.builder(context.applicationContext)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setSampleRate(48_000)
        .setUseStereoOutput(true)
        .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        .setUseHardwareAcousticEchoCanceler(true)
        .setUseHardwareNoiseSuppressor(true)
        .setAudioBufferCallback(::onBuffer)
        .createAudioDeviceModule()
        // Idle opens no AudioRecord even if libwebrtc starts recording: measured on API 36, a
        // peer's setAudioRecording(false) before negotiation did not stop it from starting.
        .also { it.setAudioRecordEnabled(false) }
        .also { module = it }

    /** The microphone feeds the audio track (opened only once a peer records). */
    fun useMicrophone() {
        releaseCapture()
        source = Source.MIC
        module?.setAudioRecordEnabled(true)
    }

    /** The phone's own media/game sound feeds the audio track, through [mediaProjection]. */
    fun usePhoneSound(mediaProjection: MediaProjection) {
        releaseCapture()
        readErrorLogged = false
        projection = mediaProjection
        source = Source.SYSTEM
        module?.setAudioRecordEnabled(false)
    }

    /** Idle: no microphone and nothing captured, whatever the peer's recording switch says. */
    fun reset() {
        releaseCapture()
        source = Source.NONE
        module?.setAudioRecordEnabled(false)
    }

    /**
     * The module's recording thread, every 10 ms. For the microphone the buffer already holds
     * its samples; for the phone's sound it is filled here (silence until capture runs).
     */
    private fun onBuffer(buffer: ByteBuffer, format: Int, channels: Int, sampleRate: Int, reported: Int, captureTimeNs: Long): Long {
        // The module sends the whole buffer on (its capacity, one 10 ms frame). With the recorder off
        // it reports 0 bytes read, so sizing by the report filled and paced nothing: the thread
        // spun and native memory grew ~140 MB/s until the process aborted (measured).
        val bytes = buffer.capacity()
        if (source == Source.MIC) {
            if (muted) silence(buffer, 0, bytes)
            return captureTimeNs
        }
        // With the recorder off nothing paces this thread: it asked for 10 ms and got it back at
        // once, over and over, and the encoder queue grew ~30 MB/s until the data channels starved
        // and the desk rebuilt the peer (measured). Each buffer now takes its real time.
        if (source == Source.SYSTEM) {
            val record = capture ?: openCapture(channels, sampleRate)
            val read = if (muted || record == null) -1 else record.read(buffer, bytes, AudioRecord.READ_BLOCKING)
            if (read < 0 && !readErrorLogged) {
                readErrorLogged = true
                Log.w(TAG, "Phone sound capture read returned $read")
            }
            if (read < bytes) silence(buffer, maxOf(read, 0), bytes)
        } else {
            silence(buffer, 0, bytes)
        }
        pace(bytes, channels, sampleRate)
        return captureTimeNs
    }

    @Volatile private var readErrorLogged = false
    private var nextSlotNs = 0L

    /** Sleeps until this buffer's slot has passed, so filled buffers go out in real time. */
    private fun pace(bytes: Int, channels: Int, sampleRate: Int) {
        val durationNs = bytes.toLong() * 1_000_000_000L / (2L * maxOf(channels, 1) * maxOf(sampleRate, 1))
        val now = System.nanoTime()
        if (nextSlotNs < now - durationNs * 4) nextSlotNs = now // fell behind (or first buffer): restart the clock
        nextSlotNs += durationNs
        val wait = nextSlotNs - now
        if (wait > 0) runCatching { Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt()) }
    }

    private fun silence(buffer: ByteBuffer, from: Int, to: Int) {
        for (i in from until to) buffer.put(i, 0)
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun openCapture(channels: Int, sampleRate: Int): AudioRecord? {
        capture?.let { return it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val mediaProjection = projection ?: return null
        return runCatching {
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                // Never DeskLink itself: the desk's sound playing here would go straight back.
                .excludeUid(android.os.Process.myUid())
                .build()
            val mask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(mask)
                .build()
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setAudioPlaybackCaptureConfig(config)
                .setBufferSizeInBytes(maxOf(AudioRecord.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT), sampleRate / 10 * 2 * channels))
                .build()
                .also { it.startRecording() }
        }.onFailure { Log.w(TAG, "Phone sound capture did not start", it) }.getOrNull()?.also { capture = it }
    }

    @Synchronized
    private fun releaseCapture() {
        capture?.let { runCatching { it.stop() }; it.release() }
        capture = null
        projection = null
    }
}
