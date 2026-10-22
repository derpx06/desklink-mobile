/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose.screen.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import org.desklink.mobile.plugins.mpris.MediaControl
import org.desklink.mobile.plugins.mpris.MediaColors
import org.desklink.mobile.plugins.mpris.MprisMediaSession
import org.desklink.mobile.ui.compose.DeskLinkTheme
import org.desklink.mobile.ui.compose.tokens

/**
 * Debug builds only: the Home media card with made-up tracks, for the screenshot/clipping matrix
 * (`am start -n org.desklink.mobile.debug/org.desklink.mobile.ui.compose.screen.home.MediaCardPreviewActivity`).
 * With `--ez notify true` it also posts the media notification and session for a made-up desk
 * player (`--ez playing`, `--ez stale`, `--ez art`, `--ez canSeek`, `--el positionMs`).
 */
class MediaCardPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val playing = intent.getBooleanExtra("playing", false)
        if (intent.getBooleanExtra("notify", false)) previewNotification(playing)
        fun media(title: String, artist: String, skip: Boolean) = HomeMedia(
            player = "Chrome", players = listOf("Chrome"), title = title, artist = artist,
            playing = playing, pending = false, canPlayPause = true, canNext = skip, canPrevious = skip,
            canSeek = true, lengthMs = 245_000, positionMs = 61_000, positionAt = System.currentTimeMillis(),
            reportedPlaying = playing, volume = 60, art = null, desk = "Desk", subtitleFallback = "",
        )
        val noop = HomeActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        setContent {
            val bounds = remember { mutableStateOf<Rect?>(null) }
            DeskLinkTheme(this) {
                Column(
                    Modifier.fillMaxSize().background(tokens.bg).statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    MediaCard(media("Long Distance Lullabies For The Late Night Drive Home (Extended Remix)", "Kenny Gunderman and The Very Long Band Name", true), noop, bounds) {}
                    MediaCard(media("Hey", "Kenny Gunderman", false), noop, bounds) {}
                    MediaCard(media("Gyp ÅÉ jqy Ég — Ŋ", "Ågq ýj", true), noop, bounds) {}
                }
            }
        }
    }

    private fun previewNotification(playing: Boolean) {
        val stale = intent.getBooleanExtra("stale", false)
        val art = if (intent.getBooleanExtra("art", true)) fakeCover() else null
        val length = 245_000L
        MprisMediaSession.instance.preview(
            this,
            MprisMediaSession.NowPlaying(
                deviceId = "preview", player = "Spotify", desk = "Manas's laptop",
                title = "Midnight City", artist = "M83", album = "Hurry Up, We're Dreaming", lengthMs = length,
                transport = MediaControl.transport(
                    playing = playing, stale = stale, lastPositionMs = intent.getLongExtra("positionMs", 61_000),
                    lastPositionAt = System.currentTimeMillis(), lengthMs = length, now = System.currentTimeMillis(),
                    canPlay = true, canPause = true, canPrevious = true, canNext = true, canSeek = intent.getBooleanExtra("canSeek", true),
                ),
                stale = stale,
                art = art,
                color = art?.let { MediaColors.vibrant(IntArray(16 * 16).also { px -> Bitmap.createScaledBitmap(art, 16, 16, true).getPixels(px, 0, 16, 0, 0, 16, 16) }) },
            ),
        )
    }

    /** A 640 px cover (downscaled to 512 by the session): a dusk gradient with a sun. */
    private fun fakeCover(): Bitmap {
        val side = 640f
        return Bitmap.createBitmap(side.toInt(), side.toInt(), Bitmap.Config.ARGB_8888).also { bmp ->
            val c = Canvas(bmp)
            c.drawRect(0f, 0f, side, side, Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, side, intArrayOf(0xFF1B1446.toInt(), 0xFF7A2A8C.toInt(), 0xFFF2685B.toInt()), null, Shader.TileMode.CLAMP)
            })
            c.drawCircle(side / 2, side * 0.62f, side * 0.22f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(side / 2, side * 0.62f, side * 0.22f, 0xFFFFE08A.toInt(), 0xFFF79A4B.toInt(), Shader.TileMode.CLAMP)
            })
            c.drawRect(0f, side * 0.74f, side, side, Paint().apply { color = 0xFF120C2E.toInt() })
        }
    }
}
