/*
 * SPDX-FileCopyrightText: 2018 Nicolas Fella <nicolas.fella@gmx.de>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.mprisreceiver

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import org.apache.commons.lang3.StringUtils

internal class MprisReceiverPlayer(
    val controller: MediaController,
    val name: String?,
) {

    fun isPlaying(): Boolean {
        val state = controller.playbackState ?: return false
        return state.state == PlaybackState.STATE_PLAYING
    }

    fun canPlay(): Boolean {
        val state = controller.playbackState ?: return false
        if (state.state == PlaybackState.STATE_PLAYING) return true
        return (state.actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE)) != 0L
    }

    fun canPause(): Boolean {
        val state = controller.playbackState ?: return false
        if (state.state == PlaybackState.STATE_PAUSED) return true
        return (state.actions and (PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)) != 0L
    }

    fun canGoPrevious(): Boolean {
        val state = controller.playbackState ?: return false
        return (state.actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L
    }

    fun canGoNext(): Boolean {
        val state = controller.playbackState ?: return false
        return (state.actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L
    }

    fun canSeek(): Boolean {
        val state = controller.playbackState ?: return false
        return (state.actions and PlaybackState.ACTION_SEEK_TO) != 0L
    }

    fun playPause() {
        if (this.isPlaying()) {
            controller.transportControls.pause()
        } else {
            controller.transportControls.play()
        }
    }

    val album: String
        get() {
            val metadata = controller.metadata ?: return ""
            return metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        }

    val artist: String
        get() {
            val metadata = controller.metadata ?: return ""
            return StringUtils.firstNonEmpty<String?>(
                metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                metadata.getString(MediaMetadata.METADATA_KEY_AUTHOR),
                metadata.getString(MediaMetadata.METADATA_KEY_WRITER)
            ) ?: ""
        }

    val title: String
        get() {
            val metadata = controller.metadata ?: return ""
            return StringUtils.firstNonEmpty<String?>(
                metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ) ?: ""
        }

    fun previous() {
        controller.transportControls.skipToPrevious()
    }

    fun next() {
        controller.transportControls.skipToNext()
    }

    fun play() {
        controller.transportControls.play()
    }

    fun pause() {
        controller.transportControls.pause()
    }

    fun stop() {
        controller.transportControls.stop()
    }

    var volume: Int
        get() {
            val info = controller.playbackInfo
            if (info.maxVolume == 0) return 0
            return 100 * info.currentVolume / info.maxVolume
        }
        set(volume) {
            val info = controller.playbackInfo
            // Use rounding for the volume, since most devices don't have a very large range
            val unroundedVolume = info.maxVolume * volume / 100.0 + 0.5
            controller.setVolumeTo(unroundedVolume.toInt(), 0)
        }

    /** Where the track is now, not where it was when the app last updated its state. */
    var position: Long
        get() {
            val state = controller.playbackState ?: return 0
            return positionNow(
                state.position, state.lastPositionUpdateTime, state.playbackSpeed,
                state.state == PlaybackState.STATE_PLAYING, android.os.SystemClock.elapsedRealtime(), length,
            )
        }
        set(position) {
            controller.transportControls.seekTo(clampSeek(position, length))
        }

    val length: Long
        get() {
            val metadata = controller.metadata ?: return 0
            return metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        }

    val metadata: MediaMetadata?
        get() = controller.metadata

    companion object {
        /** A reported position moved on to [now] while playing (at its speed), within the track. */
        @JvmStatic
        fun positionNow(position: Long, updatedAt: Long, speed: Float, playing: Boolean, now: Long, length: Long): Long {
            if (position < 0) return 0 // PLAYBACK_POSITION_UNKNOWN
            val moved = if (playing && updatedAt > 0) ((now - updatedAt).coerceAtLeast(0) * speed).toLong() else 0L
            return clampSeek(position + moved, length)
        }

        /** A seek target kept inside the track (unbounded above when the length is unknown). */
        @JvmStatic
        fun clampSeek(target: Long, length: Long): Long =
            if (length > 0) target.coerceIn(0, length) else target.coerceAtLeast(0)
    }
}
