/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.screen.home

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.desklink.mobile.R
import org.desklink.mobile.plugins.mpris.MediaColors
import org.desklink.mobile.plugins.mpris.MediaControl
import org.desklink.mobile.ui.compose.MonoFamily
import org.desklink.mobile.ui.compose.components.rememberReducedMotion
import org.desklink.mobile.ui.compose.tokens
import kotlin.math.abs

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
    /** The cover's colour (ARGB), when it has one. */
    val vibrant: Int? = null,
    /** Changes with the cover (or, without one, the title): what the artwork animates on. */
    val artKey: String = title,
    /** The feature link is down: the desk's last report, frozen, controls off. */
    val stale: Boolean = false,
    /** "Waiting for <desk>…", shown in place of the subtitle while [stale]. */
    val waiting: String = "",
) {
    val subtitle get() = if (stale && waiting.isNotEmpty()) waiting else listOf(artist, player).filter(String::isNotBlank).distinct().joinToString(" · ").ifEmpty { subtitleFallback }
}

/** The card's tint and the accent from the cover, animated between tracks; tokens when unreadable. */
private class MediaPalette(val cardTop: Color, val accent: Color, val onAccent: Color)

@Composable
private fun rememberPalette(vibrant: Int?): MediaPalette {
    val t = tokens
    val scheme = remember(vibrant, t.isDark) {
        MediaColors.scheme(vibrant, t.surface.toArgb(), t.text.toArgb(), t.textDim.toArgb(), t.bg.toArgb(), t.isDark)
    }
    val top by animateColorAsState(scheme?.let { Color(it.cardTop) } ?: t.surface, tween(500), label = "cardTop")
    val accent by animateColorAsState(scheme?.let { Color(it.accent) } ?: t.text, tween(500), label = "accent")
    return MediaPalette(top, accent, t.bg)
}

// ---- the card ---------------------------------------------------------------------------------------------

/**
 * Under the status card, playing or paused: artwork, title, seek bar and controls. Swipe the
 * artwork or title for the next/previous track; tap them to open the player.
 */
@Composable
internal fun MediaCard(media: HomeMedia, actions: HomeActions, artBounds: MutableState<Rect?>, onOpen: () -> Unit) {
    val t = tokens
    val palette = rememberPalette(media.vibrant)
    val reduced = rememberReducedMotion()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val swipe = remember { Animatable(0f) }
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val openLabel = stringResource(R.string.home_media_open)
    val nextLabel = stringResource(R.string.mpris_next)
    val previousLabel = stringResource(R.string.mpris_previous)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(palette.cardTop, t.surface)))
            .border(1.dp, t.hairline, RoundedCornerShape(24.dp))
            .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 6.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .pointerInput(media.canNext, media.canPrevious) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val dx = swipe.value
                            when {
                                dx < -threshold && media.canNext -> actions.onNext()
                                dx > threshold && media.canPrevious -> actions.onPrevious()
                                else -> null
                            }?.let { haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) }
                            scope.launch { swipe.animateTo(0f, if (reduced) snap() else spring(dampingRatio = 0.7f)) }
                        },
                        onDragCancel = { scope.launch { swipe.animateTo(0f) } },
                    ) { change, dx ->
                        change.consume()
                        scope.launch { swipe.snapTo(swipe.value + dx * 0.6f) }
                    }
                }
                .clickable(role = Role.Button, onClickLabel = openLabel, onClick = onOpen)
                .semantics(mergeDescendants = true) {
                    customActions = listOfNotNull(
                        CustomAccessibilityAction(nextLabel) { actions.onNext(); true }.takeIf { media.canNext },
                        CustomAccessibilityAction(previousLabel) { actions.onPrevious(); true }.takeIf { media.canPrevious },
                    )
                }
                .graphicsLayer {
                    translationX = swipe.value
                    alpha = 1f - (abs(swipe.value) / (threshold * 3)).coerceAtMost(0.5f)
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Where the player's artwork grows from, and shrinks back to.
            Box(Modifier.size(56.dp).onGloballyPositioned { artBounds.value = it.boundsInRoot() }) { Artwork(media, 12.dp, reduced) }
            // Fixed height: a longer title or a new track never moves anything.
            Column(Modifier.weight(1f).height(44.dp)) {
                Titles(media, reduced, titleSize = 16, subtitleSize = 13, alignCenter = false)
            }
        }
        SeekBar(media, palette, actions.onSeek, compact = true)
        Controls(media, actions, palette, playSize = 52.dp, reduced)
    }
}

/** The cover, crossfading and settling in from a slight scale when the track changes. */
@Composable
private fun BoxScope.Artwork(media: HomeMedia, corner: Dp, reduced: Boolean) {
    AnimatedContent(
        targetState = Triple(media.artKey, media.art, media.title),
        contentKey = { it.first },
        transitionSpec = {
            if (reduced) EnterTransition.None togetherWith ExitTransition.None
            else (fadeIn(tween(350)) + scaleIn(tween(350), initialScale = 0.9f)) togetherWith fadeOut(tween(250))
        },
        modifier = Modifier.matchParentSize(),
        label = "artwork",
    ) { (_, art, title) ->
        val shape = RoundedCornerShape(corner)
        if (art != null) {
            Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(shape))
        } else {
            val hue = (title.hashCode().toLong() and 0xffff).toFloat() % 360f
            Box(
                Modifier.fillMaxSize().clip(shape).background(Brush.linearGradient(listOf(Color.hsv(hue, 0.45f, 0.62f), Color.hsv((hue + 48f) % 360f, 0.6f, 0.32f)))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.sym_music_note), contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.fillMaxSize(0.42f))
            }
        }
    }
}

/** Title over "artist · player", sliding in on a new track; the bars move while it plays. */
@Composable
private fun Titles(media: HomeMedia, reduced: Boolean, titleSize: Int, subtitleSize: Int, alignCenter: Boolean) {
    val t = tokens
    AnimatedContent(
        targetState = media.title to media.subtitle,
        transitionSpec = {
            if (reduced) EnterTransition.None togetherWith ExitTransition.None
            else (slideInVertically(tween(300)) { it / 3 } + fadeIn(tween(300))) togetherWith
                (slideOutVertically(tween(220)) { -it / 3 } + fadeOut(tween(220))) using SizeTransform(clip = true)
        },
        label = "titles",
    ) { (title, subtitle) ->
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = if (alignCenter) Alignment.CenterHorizontally else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, fontSize = titleSize.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.basicMarquee())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Equalizer(media.reportedPlaying && !reduced, t.textDim)
                Text(subtitle, fontSize = subtitleSize.sp, color = t.textDim, maxLines = 1, modifier = Modifier.basicMarquee())
            }
        }
    }
}

/** Three bars that move while the desk plays, still otherwise. Drawn, not recomposed, per frame. */
@Composable
private fun Equalizer(moving: Boolean, color: Color) {
    val heights = if (moving) {
        val transition = rememberInfiniteTransition(label = "eq")
        listOf(420, 300, 520).mapIndexed { i, period ->
            transition.animateFloat(0.25f, 1f, infiniteRepeatable(tween(period), RepeatMode.Reverse, initialStartOffset = androidx.compose.animation.core.StartOffset(i * 120)), label = "bar$i")
        }
    } else {
        null
    }
    Canvas(Modifier.size(12.dp)) {
        val bar = size.width / 5
        repeat(3) { i ->
            val h = size.height * (heights?.get(i)?.value ?: listOf(0.35f, 0.6f, 0.45f)[i])
            drawRect(color, Offset(i * bar * 2, size.height - h), androidx.compose.ui.geometry.Size(bar, h))
        }
    }
}

// ---- seek bar ---------------------------------------------------------------------------------------------

/** Advances locally between the desk's position pushes; seeks with SetPosition on release. */
@Composable
private fun SeekBar(media: HomeMedia, palette: MediaPalette, onSeek: (Long) -> Unit, compact: Boolean) {
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
            accent = palette.accent,
            dragging = dragging,
            bubble = MediaControl.clock(shown),
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

/** A 4 dp track; the thumb grows while dragged and shows the time above it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    enabled: Boolean,
    accent: Color,
    modifier: Modifier,
    dragging: Boolean = false,
    bubble: String? = null,
) {
    val t = tokens
    val colors = SliderDefaults.colors(
        thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = t.hairlineStrong,
        disabledThumbColor = t.textDim, disabledActiveTrackColor = t.textDim, disabledInactiveTrackColor = t.hairline,
    )
    val thumb by animateDpAsState(if (dragging) 22.dp else 12.dp, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "thumb")
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        enabled = enabled,
        colors = colors,
        modifier = modifier,
        thumb = {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(thumb).background(if (enabled) accent else t.textDim, CircleShape))
                if (bubble != null) {
                    AnimatedVisibility(dragging, enter = fadeIn() + scaleIn(initialScale = 0.6f), exit = fadeOut()) {
                        Text(
                            bubble,
                            fontFamily = MonoFamily,
                            fontSize = 12.sp,
                            color = t.bg,
                            modifier = Modifier
                                .wrapContentSize(unbounded = true)
                                .offset(y = (-30).dp)
                                .background(t.text, RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        },
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

// ---- controls ---------------------------------------------------------------------------------------------

@Composable
private fun Controls(media: HomeMedia, actions: HomeActions, palette: MediaPalette, playSize: Dp, reduced: Boolean) {
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
        PressButton(stringResource(R.string.mpris_previous), media.canPrevious, 48.dp, Color.Transparent, reduced, tap(actions.onPrevious)) {
            Icon(painterResource(R.drawable.sym_skip_previous), contentDescription = null, tint = if (media.canPrevious) t.text else t.hairlineStrong, modifier = Modifier.size(28.dp))
        }
        val playLabel = stringResource(if (media.playing) R.string.mpris_pause else R.string.mpris_play)
        // Tapped, the desk not yet answering: a little lighter, never frozen.
        val pendingAlpha by animateFloatAsState(if (media.pending) 0.65f else 1f, label = "pending")
        PressButton(playLabel, media.canPlayPause, playSize, if (media.canPlayPause) palette.accent else t.hairlineStrong, reduced, tap(actions.onPlayPause), Modifier.graphicsLayer { alpha = pendingAlpha }) {
            PlayPauseIcon(media.playing, palette.onAccent, playSize * 0.42f, reduced)
        }
        PressButton(stringResource(R.string.mpris_next), media.canNext, 48.dp, Color.Transparent, reduced, tap(actions.onNext)) {
            Icon(painterResource(R.drawable.sym_skip_next), contentDescription = null, tint = if (media.canNext) t.text else t.hairlineStrong, modifier = Modifier.size(28.dp))
        }
    }
}

/** A round button that springs down while pressed. */
@Composable
private fun PressButton(
    label: String,
    enabled: Boolean,
    size: Dp,
    background: Color,
    reduced: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && !reduced) 0.86f else 1f,
        spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(background)
            .clickable(interactionSource = interaction, indication = ripple(), enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/**
 * Pause bars morphing into the play triangle: each bar becomes one half of it, corner by corner,
 * in a 24-unit box. Only the drawing reads the animation, so nothing recomposes per frame.
 */
@Composable
private fun PlayPauseIcon(playing: Boolean, color: Color, size: Dp, reduced: Boolean) {
    val toPlay by animateFloatAsState(if (playing) 0f else 1f, if (reduced) snap() else spring(dampingRatio = 0.8f, stiffness = 500f), label = "morph")
    Canvas(Modifier.size(size)) {
        val unit = this.size.minDimension / 24f
        val p = toPlay
        fun quad(from: FloatArray, to: FloatArray) = Path().apply {
            for (i in 0 until 4) {
                val x = lerp(from[i * 2], to[i * 2], p) * unit
                val y = lerp(from[i * 2 + 1], to[i * 2 + 1], p) * unit
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        drawPath(quad(PAUSE_LEFT, PLAY_LEFT), color)
        drawPath(quad(PAUSE_RIGHT, PLAY_RIGHT), color)
    }
}

private val PAUSE_LEFT = floatArrayOf(6f, 5f, 10f, 5f, 10f, 19f, 6f, 19f)
private val PAUSE_RIGHT = floatArrayOf(14f, 5f, 18f, 5f, 18f, 19f, 14f, 19f)
private val PLAY_LEFT = floatArrayOf(8f, 5f, 13.5f, 8.5f, 13.5f, 15.5f, 8f, 19f)
private val PLAY_RIGHT = floatArrayOf(13.5f, 8.5f, 19f, 12f, 19f, 12f, 13.5f, 15.5f)

// ---- now playing ------------------------------------------------------------------------------------------

/**
 * "Now playing on <desk>", over Home: the card's artwork grows from where it sits on the card into
 * place (and back on close), on a blurred copy of the cover (API 31+; a tinted gradient below).
 * Back, the chevron or a downward drag closes it.
 *
 * The expand is drawn, not laid out: one 0→1 progress moves and scales the artwork from
 * [cardArt] to its own place in a graphics layer, and fades the rest in.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NowPlayingOverlay(media: HomeMedia?, actions: HomeActions, cardArt: State<Rect?>, open: Boolean, onClose: () -> Unit) {
    val t = tokens
    val reduced = rememberReducedMotion()
    // Kept through the closing animation after the card goes away.
    var last by remember { mutableStateOf(media) }
    if (media != null) last = media
    val progress = remember { Animatable(0f) }
    LaunchedEffect(open, reduced) {
        val target = if (open) 1f else 0f
        if (reduced) progress.snapTo(target) else progress.animateTo(target, spring(dampingRatio = 0.86f, stiffness = 360f))
    }
    val visible by remember { derivedStateOf { open || progress.value > 0f } }
    val shown = last
    if (!visible || shown == null) return
    BackHandler(open, onClose)
    val palette = rememberPalette(shown.vibrant)
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val closeAt = with(LocalDensity.current) { 140.dp.toPx() }
    var artTarget by remember { mutableStateOf<Rect?>(null) }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = progress.value }.background(t.bg)) { Backdrop(shown, palette) }
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = drag.value }
                .draggable(
                    rememberDraggableState { dy -> scope.launch { drag.snapTo((drag.value + dy).coerceAtLeast(0f)) } },
                    Orientation.Vertical,
                    onDragStopped = {
                        if (drag.value > closeAt) onClose()
                        scope.launch { drag.animateTo(0f, spring()) }
                    },
                )
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // Everything but the artwork fades in after it has started to move.
            val fade = Modifier.graphicsLayer { alpha = ((progress.value - 0.35f) / 0.65f).coerceIn(0f, 1f) }
            Row(fade.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.sym_expand_more), contentDescription = stringResource(R.string.home_media_close), tint = t.text)
                }
                Text(
                    stringResource(R.string.home_media_now_playing, shown.desk),
                    fontSize = 14.sp,
                    color = t.textDim,
                    modifier = Modifier.weight(1f).padding(end = 48.dp).semantics { heading() },
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            if (shown.players.size > 1) {
                val pickerLabel = stringResource(R.string.home_media_player)
                FlowRow(
                    fade.semantics { contentDescription = pickerLabel },
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    shown.players.forEach { name ->
                        FilterChip(selected = name == shown.player, onClick = { actions.onSelectPlayer(name) }, label = { Text(name) })
                    }
                }
            }
            // Full size while it plays, gently smaller when paused.
            val artScale by animateFloatAsState(if (shown.playing) 1f else 0.86f, if (reduced) snap() else spring(dampingRatio = 0.7f, stiffness = 300f), label = "artScale")
            Box(
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 340.dp)
                    .aspectRatio(1f)
                    .onGloballyPositioned { artTarget = it.boundsInRoot() }
                    .graphicsLayer {
                        val scale = lerp(1f, artScale, progress.value)
                        scaleX = scale
                        scaleY = scale
                    },
            ) {
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            val p = progress.value
                            val from = cardArt.value
                            val to = artTarget
                            if (from != null && to != null && to.width > 0f && p < 1f) {
                                transformOrigin = TransformOrigin(0f, 0f)
                                translationX = (from.left - to.left) * (1 - p)
                                translationY = (from.top - to.top) * (1 - p)
                                scaleX = lerp(from.width / to.width, 1f, p)
                                scaleY = lerp(from.height / to.height, 1f, p)
                            }
                        }
                        .shadow(24.dp, RoundedCornerShape(24.dp), ambientColor = palette.accent, spotColor = palette.accent),
                ) { Artwork(shown, 24.dp, reduced) }
            }
            Column(fade, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Titles(shown, reduced, titleSize = 22, subtitleSize = 15, alignCenter = true)
                SeekBar(shown, palette, actions.onSeek, compact = false)
                Controls(shown, actions, palette, playSize = 72.dp, reduced)
                shown.volume?.let { level -> VolumeRow(level, palette, actions.onVolume) }
            }
        }
    }
}

/** The cover blurred behind everything (API 31+), or the cover's colour fading into the page. */
@Composable
private fun Backdrop(media: HomeMedia, palette: MediaPalette) {
    val t = tokens
    val art = media.art
    if (art != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(72.dp).graphicsLayer { alpha = 0.7f })
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(t.bg.copy(alpha = 0.45f), t.bg.copy(alpha = 0.92f)))))
    } else {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(palette.cardTop, t.bg))))
    }
}

@Composable
private fun VolumeRow(level: Int, palette: MediaPalette, onVolume: (Int) -> Unit) {
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
            accent = palette.accent,
            dragging = dragging,
            modifier = Modifier.weight(1f).semantics {
                contentDescription = label
                stateDescription = "${if (dragging) (dragValue * 100).toInt() else level}%"
            },
        )
        Icon(painterResource(R.drawable.sym_volume_up), contentDescription = null, tint = t.textDim, modifier = Modifier.size(20.dp))
    }
}
