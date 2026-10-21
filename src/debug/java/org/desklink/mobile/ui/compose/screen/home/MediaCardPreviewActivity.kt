/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose.screen.home

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
import org.desklink.mobile.ui.compose.DeskLinkTheme
import org.desklink.mobile.ui.compose.tokens

/**
 * Debug builds only: the Home media card with made-up tracks, for the screenshot/clipping matrix
 * (`am start -n org.desklink.mobile.debug/org.desklink.mobile.ui.compose.screen.home.MediaCardPreviewActivity`).
 */
class MediaCardPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val playing = intent.getBooleanExtra("playing", false)
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
}
