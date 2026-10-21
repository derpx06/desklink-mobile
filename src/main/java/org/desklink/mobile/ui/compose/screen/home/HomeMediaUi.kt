/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.screen.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.desklink.mobile.R
import org.desklink.mobile.plugins.mpris.MediaControl
import org.desklink.mobile.ui.compose.MonoFamily
import org.desklink.mobile.ui.compose.tokens

/** The desk's current player (MPRIS) as Home shows it; null hides the card. */
@Immutable
data class HomeMedia(
    val player: String,
    val players: List<String>,
    val title: String,
    val artist: String,
    /** What the button shows: the tapped state while the desk has not answered. */
    val playing: Boolean,
    val pending: Boolean,
    val canPlayPause: Boolean,
    val canNext: Boolean,
    val canPrevious: Boolean,
    val canSeek: Boolean,
    val lengthMs: Long,
    val positionMs: Long,
    /** Wall-clock time of [positionMs]. */
    val positionAt: Long,
    /** What the desk last said; the position only advances on this. */
    val reportedPlaying: Boolean,
    /** 0..100, or null when the player has none. */
    val volume: Int?,
    val art: ImageBitmap?,
    val desk: String,
    val subtitleFallback: String,
) {
    val subtitle get() = listOf(artist, player).filter(String::isNotBlank).distinct().joinToString(" · ").ifEmpty { subtitleFallback }
}

/** Under the status card, playing or paused: artwork, title, seek bar and controls. Tap opens the sheet. */
@Composable
internal fun MediaCard(media: HomeMedia, actions: HomeActions) {
    val t = tokens
    var sheet by rememberSaveable { mutableStateOf(false) }
    val openLabel = stringResource(R.string.home_media_open)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(t.surface)
            .border(1.dp, t.hairline, RoundedCornerShape(24.dp))
            .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 6.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable(role = Role.Button, onClickLabel = openLabel) { sheet = true }
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Artwork(media, 56.dp, 12.dp)
            // Fixed height and single marquee lines: a longer title never moves anything.
            Column(Modifier.weight(1f).height(44.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(media.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.basicMarquee())
                Text(media.subtitle, fontSize = 13.sp, color = t.textDim, maxLines = 1, modifier = Modifier.basicMarquee())
            }
        }
        SeekBar(media, actions.onSeek, compact = true)
        Controls(media, actions, playSize = 52.dp)
    }
    if (sheet) NowPlayingSheet(media, actions) { sheet = false }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NowPlayingSheet(media: HomeMedia, actions: HomeActions, onDismiss: () -> Unit) {
    val t = tokens
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = t.surface2,
        dragHandle = { androidx.compose.material3.BottomSheetDefaults.DragHandle(width = 32.dp, height = 4.dp, color = t.hairlineStrong) },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.home_media_now_playing, media.desk),
                fontSize = 14.sp,
                color = t.textDim,
                modifier = Modifier.semantics { heading() },
            )
            if (media.players.size > 1) {
                val pickerLabel = stringResource(R.string.home_media_player)
                FlowRow(
                    Modifier.semantics { contentDescription = pickerLabel },
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    media.players.forEach { name ->
                        FilterChip(selected = name == media.player, onClick = { actions.onSelectPlayer(name) }, label = { Text(name) })
                    }
                }
            }
            Artwork(media, 0.dp, 24.dp, Modifier.fillMaxWidth().widthIn(max = 320.dp).aspectRatio(1f))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(media.title, fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.basicMarquee())
                Text(media.subtitle, fontSize = 15.sp, color = t.textDim, maxLines = 1, modifier = Modifier.basicMarquee())
            }
            SeekBar(media, actions.onSeek, compact = false)
            Controls(media, actions, playSize = 72.dp)
            media.volume?.let { level -> VolumeRow(level, actions.onVolume) }
        }
    }
}

/** The cover when the desk gives an http(s) one; otherwise a gradient of the title's own colour. */
@Composable
private fun Artwork(media: HomeMedia, size: Dp, corner: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(corner)
    val box = (if (size > 0.dp) modifier.size(size) else modifier).clip(shape)
    val art = media.art
    if (art != null) {
        Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = box)
    } else {
        val hue = (media.title.hashCode().toLong() and 0xffff).toFloat() % 360f
        Box(
            box.background(Brush.linearGradient(listOf(Color.hsv(hue, 0.45f, 0.62f), Color.hsv((hue + 48f) % 360f, 0.6f, 0.32f)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.sym_music_note), contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(if (size > 0.dp) 24.dp else 72.dp))
        }
    }
}

/** Advances locally between the desk's position pushes; seeks with SetPosition on release. */
@Composable
private fun SeekBar(media: HomeMedia, onSeek: (Long) -> Unit, compact: Boolean) {
    val t = tokens
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(media.reportedPlaying, media.positionAt) {
        while (media.reportedPlaying) {
            now = System.currentTimeMillis()
            delay(250)
        }
        now = System.currentTimeMillis()
    }
    val position = MediaControl.position(media.positionMs, media.positionAt, media.reportedPlaying, media.lengthMs, now)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val length = media.lengthMs.coerceAtLeast(0)
    val shown = if (dragging) (dragValue * length).toLong() else position
    val label = stringResource(R.string.home_media_position)
    val value = stringResource(R.string.home_media_position_value, MediaControl.clock(shown), MediaControl.clock(length))
    Column(Modifier.fillMaxWidth()) {
        ThinSlider(
            value = if (length > 0) (shown.toFloat() / length).coerceIn(0f, 1f) else 0f,
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                if (dragging && length > 0) onSeek((dragValue * length).toLong())
                dragging = false
            },
            enabled = media.canSeek && length > 0,
            modifier = Modifier.fillMaxWidth().height(if (compact) 32.dp else 40.dp).semantics {
                contentDescription = label
                stateDescription = value
            },
        )
        Row(Modifier.fillMaxWidth()) {
            Text(MediaControl.clock(shown), fontFamily = MonoFamily, fontSize = 11.sp, color = t.textDim)
            Spacer(Modifier.weight(1f))
            Text(if (length > 0) MediaControl.clock(length) else "–:––", fontFamily = MonoFamily, fontSize = 11.sp, color = t.textDim)
        }
    }
}

@Composable
private fun Controls(media: HomeMedia, actions: HomeActions, playSize: Dp) {
    val t = tokens
    val haptic = LocalHapticFeedback.current
    fun tap(action: () -> Unit) = {
        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
        action()
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
    ) {
        RoundButton(R.drawable.sym_skip_previous, stringResource(R.string.mpris_previous), media.canPrevious, 48.dp, tap(actions.onPrevious))
        val playLabel = stringResource(if (media.playing) R.string.mpris_pause else R.string.mpris_play)
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(playSize)
                    .clip(CircleShape)
                    .background(if (media.canPlayPause) t.text else t.hairlineStrong)
                    .clickable(enabled = media.canPlayPause, role = Role.Button, onClickLabel = playLabel, onClick = tap(actions.onPlayPause))
                    .semantics { contentDescription = playLabel }
                    // Tapped, the desk not yet answering: dimmed, never frozen.
                    .alpha(if (media.pending) 0.7f else 1f),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(if (media.playing) R.drawable.sym_pause else R.drawable.sym_play_arrow), contentDescription = null, tint = t.bg, modifier = Modifier.size(playSize * 0.5f))
            }
            if (media.pending) CircularProgressIndicator(Modifier.size(playSize + 6.dp), color = t.textDim, strokeWidth = 2.dp)
        }
        RoundButton(R.drawable.sym_skip_next, stringResource(R.string.mpris_next), media.canNext, 48.dp, tap(actions.onNext))
    }
}

@Composable
private fun RoundButton(@DrawableRes icon: Int, label: String, enabled: Boolean, size: Dp, onClick: () -> Unit) {
    val t = tokens
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = if (enabled) t.text else t.hairlineStrong, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun VolumeRow(level: Int, onVolume: (Int) -> Unit) {
    val t = tokens
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(level / 100f) }
    val label = stringResource(R.string.mpris_volume)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(R.drawable.sym_volume_mute), contentDescription = null, tint = t.textDim, modifier = Modifier.size(20.dp))
        ThinSlider(
            value = if (dragging) dragValue else level / 100f,
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                onVolume((dragValue * 100).toInt())
                dragging = false
            },
            enabled = true,
            modifier = Modifier.weight(1f).semantics {
                contentDescription = label
                stateDescription = "${if (dragging) (dragValue * 100).toInt() else level}%"
            },
        )
        Icon(painterResource(R.drawable.sym_volume_up), contentDescription = null, tint = t.textDim, modifier = Modifier.size(20.dp))
    }
}

/** A 4 dp track with a small round thumb; the stock Material 3 slider is a 16 dp bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThinSlider(value: Float, onValueChange: (Float) -> Unit, onValueChangeFinished: () -> Unit, enabled: Boolean, modifier: Modifier) {
    val t = tokens
    val colors = SliderDefaults.colors(
        thumbColor = t.text, activeTrackColor = t.text, inactiveTrackColor = t.hairlineStrong,
        disabledThumbColor = t.textDim, disabledActiveTrackColor = t.textDim, disabledInactiveTrackColor = t.hairline,
    )
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        enabled = enabled,
        colors = colors,
        modifier = modifier,
        thumb = { Box(Modifier.size(14.dp).background(if (enabled) t.text else t.textDim, CircleShape)) },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                modifier = Modifier.height(4.dp),
                enabled = enabled,
                colors = colors,
                drawStopIndicator = null,
                thumbTrackGapSize = 0.dp,
            )
        },
    )
}
