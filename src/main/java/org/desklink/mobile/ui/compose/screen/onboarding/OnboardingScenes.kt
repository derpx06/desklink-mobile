/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.screen.onboarding

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.MonoFamily
import org.desklink.mobile.ui.compose.tokens

// The five animated scenes of the tour. Each is drawn on a fixed 328 x 350 dp canvas (the design's) that the screen scales to
// fit its card. One looping phase (0..1, 4.5 s) drives every moving part, so a loop is seamless and "Remove animations" is one
// fixed phase: nothing here animates by itself.

const val SceneWidth = 328
const val SceneHeight = 350
private const val PeriodMs = 4500

/** The loop's phase as a function, so only the layers that read it are redrawn. A fixed one when the person asked for less motion. */
@Composable
fun rememberScenePhase(still: Boolean): () -> Float {
    if (still) return { StillPhase }
    val p = rememberInfiniteTransition(label = "scene").animateFloat(0f, 1f, infiniteRepeatable(tween(PeriodMs, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    return { p.value }
}

/** Where a still scene rests: chips mid-float, packets spread along their line. */
private const val StillPhase = 0.18f

private fun frac(x: Float) = x - kotlin.math.floor(x)
private fun wave(phase: Float, offset: Float) = sin(2.0 * PI * (phase + offset)).toFloat()

private enum class Tone { Accent, Mint }

@Composable
private fun Chip(@DrawableRes icon: Int, label: String, phase: () -> Float, offset: Float, modifier: Modifier, tone: Tone = Tone.Accent, mono: Boolean = false) {
    val t = tokens
    val density = androidx.compose.ui.platform.LocalDensity.current
    val (bg, fg) = when (tone) {
        Tone.Accent -> t.accentContainer to t.onAccentContainer
        Tone.Mint -> t.mint.copy(alpha = 0.18f).compositeOver(t.surface) to t.mintText
    }
    Row(
        modifier
            .graphicsLayer { translationY = -with(density) { 7.dp.toPx() } * (0.5f + 0.5f * wave(phase(), offset)) }
            .height(40.dp)
            .background(t.surface, CircleShape)
            .border(1.dp, t.hairline, CircleShape)
            .padding(start = 6.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(28.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        }
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = t.text, maxLines = 1, fontFamily = if (mono) MonoFamily else null)
    }
}

@Composable
private fun Blob(color: Color, alpha: Float, phase: () -> Float, offset: Float, modifier: Modifier) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(
        modifier
            .size(230.dp)
            .graphicsLayer {
                val s = 0.5f + 0.5f * wave(phase(), offset)
                translationX = with(density) { 30.dp.toPx() } * s
                translationY = -with(density) { 20.dp.toPx() } * s
            }
            .drawBehind { drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent))) },
    )
}

@Composable
private fun SceneLaptop(w: Dp, modifier: Modifier = Modifier) {
    val t = tokens
    val frame = max(3f, w.value / 38f).dp
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .size(w, w * 0.64f)
                .clip(RoundedCornerShape(w * 0.07f))
                .background(Brush.linearGradient(listOf(Color(0xFF4A5B86), Color(0xFF151A28))))
                .border(frame, t.text, RoundedCornerShape(w * 0.07f)),
        ) {
            fun box(x: Float, y: Float, bw: Float, bh: Float, c: Color) =
                drawRoundRect(c, Offset(size.width * x, size.height * y), Size(size.width * bw, size.height * bh), CornerRadius(4.dp.toPx()))
            box(0.09f, 0.14f, 0.50f, 0.42f, Color(0xFFDFE5FF).copy(alpha = 0.16f))
            box(0.57f, 0.30f, 0.34f, 0.52f, Color(0xFFDFE5FF).copy(alpha = 0.28f))
            drawRoundRect(Color(0xFF3FD1A2).copy(alpha = 0.8f), Offset(size.width * 0.09f, size.height * 0.88f - 4.dp.toPx()), Size(size.width * 0.30f, 4.dp.toPx()), CornerRadius(2.dp.toPx()))
        }
        Box(Modifier.width(w * 1.24f).height(max(6f, w.value / 15f).dp).background(t.text, RoundedCornerShape(bottomStart = w / 14, bottomEnd = w / 14)))
    }
}

@Composable
private fun ScenePhone(w: Dp, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val t = tokens
    val frame = max(3f, w.value / 16f).dp
    val shape = RoundedCornerShape(w * 0.2f)
    Box(modifier.size(w, w * 1.9f).clip(shape).background(t.bg).border(frame, t.text, shape).padding(frame), content = content)
}

@Composable
private fun Tiles(n: Int, modifier: Modifier = Modifier) {
    val t = tokens
    Canvas(modifier.fillMaxSize()) {
        val left = size.width * 0.1f
        val top = size.height * 0.18f
        val gw = size.width * 0.8f
        val gh = size.height * 0.72f
        val gap = 4.dp.toPx()
        val rows = (n + 1) / 2
        val cw = (gw - gap) / 2
        val ch = (gh - gap * (rows - 1)) / rows
        for (i in 0 until n) {
            drawRoundRect(
                if (i == 0) t.accent else t.accentContainer,
                Offset(left + (i % 2) * (cw + gap), top + (i / 2) * (ch + gap)), Size(cw, ch), CornerRadius(6.dp.toPx()),
            )
        }
    }
}

// ---- 1: welcome ---------------------------------------------------------------------------------------

@Composable
private fun BoxScope.SceneWelcome(phase: () -> Float) {
    val t = tokens
    Blob(t.accent, 0.35f, phase, 0f, Modifier.align(Alignment.TopStart).offset((-60).dp, (-30).dp))
    Blob(t.mint, 0.35f, phase, 0.45f, Modifier.align(Alignment.BottomEnd).offset(70.dp, 60.dp))
    Row(Modifier.align(Alignment.TopStart).offset(y = 116.dp).fillMaxWidth().padding(horizontal = 26.dp), verticalAlignment = Alignment.CenterVertically) {
        SceneLaptop(124.dp)
        // The link: three packets travelling from the computer to the phone.
        Box(Modifier.weight(1f).height(8.dp).padding(horizontal = 6.dp).drawBehind {
            drawLine(t.mint.copy(alpha = 0.45f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2.dp.toPx())
            for (i in 0..2) {
                val f = frac(phase() * 3 + i * 0.36f)
                val a = if (f < 0.15f) f / 0.15f else if (f > 0.85f) (1 - f) / 0.15f else 1f
                drawCircle(t.mint.copy(alpha = 0.35f * a), 7.dp.toPx(), Offset(size.width * f, size.height / 2))
                drawCircle(t.mint.copy(alpha = a), 4.dp.toPx(), Offset(size.width * f, size.height / 2))
            }
        })
        ScenePhone(62.dp) { Tiles(6) }
    }
    Chip(R.drawable.sym_desktop_windows, stringResource(R.string.ob_chip_screen), phase, 0f, Modifier.align(Alignment.TopStart).offset(16.dp, 22.dp))
    Chip(R.drawable.sym_terminal, stringResource(R.string.ob_chip_terminals), phase, 0.15f, Modifier.align(Alignment.TopEnd).offset((-14).dp, 46.dp))
    Chip(R.drawable.sym_upload_file, stringResource(R.string.ob_chip_files), phase, 0.3f, Modifier.align(Alignment.BottomStart).offset(28.dp, (-30).dp))
    Chip(R.drawable.sym_content_paste, stringResource(R.string.ob_chip_clipboard), phase, 0.45f, Modifier.align(Alignment.BottomEnd).offset((-22).dp, (-52).dp))
}

// ---- 2: control -----------------------------------------------------------------------------------------

private val CursorPath = listOf(
    floatArrayOf(0f, 0.30f, 0.62f), floatArrayOf(0.25f, 0.62f, 0.30f), floatArrayOf(0.35f, 0.62f, 0.30f),
    floatArrayOf(0.60f, 0.44f, 0.50f), floatArrayOf(0.75f, 0.44f, 0.50f), floatArrayOf(1f, 0.30f, 0.62f),
)

/** The pointer's place at [p] (fractions of the screen): the design's keyframes, linear between them. */
internal fun cursorAt(p: Float): Pair<Float, Float> {
    val b = CursorPath.indexOfLast { it[0] <= p }.coerceAtMost(CursorPath.lastIndex - 1)
    val a = CursorPath[b]
    val c = CursorPath[b + 1]
    val k = ((p - a[0]) / (c[0] - a[0])).coerceIn(0f, 1f)
    return (a[1] + (c[1] - a[1]) * k) to (a[2] + (c[2] - a[2]) * k)
}

@Composable
private fun BoxScope.SceneControl(phase: () -> Float) {
    val t = tokens
    Blob(t.accent, 0.35f, phase, 0f, Modifier.align(Alignment.TopStart).offset((-50).dp, 40.dp))
    Blob(Color(0xFF8F9CCE), 0.4f, phase, 0.3f, Modifier.align(Alignment.TopEnd).offset(80.dp, (-40).dp))
    ScenePhone(168.dp, Modifier.align(Alignment.TopCenter).offset(y = 16.dp)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF4A5B86), Color(0xFF151A28))))) {
            val w = maxWidth
            val h = maxHeight
            // A window and a terminal on the computer's screen.
            Column(Modifier.offset(w * 0.08f, h * 0.10f).size(w * 0.62f, h * 0.34f).background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(8.dp))) {
                Row(Modifier.fillMaxWidth().height(12.dp).background(Color(0xFFECECE8), RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)).padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    listOf(Color(0xFFFF7C66), Color(0xFFF5B84A), Color(0xFF3FD1A2)).forEach { Box(Modifier.size(5.dp).background(it, CircleShape)) }
                }
                listOf(70, 50, 60).forEach { Box(Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp).fillMaxWidth(it / 100f).height(4.dp).background(Color(0xFFD4D4CF), RoundedCornerShape(2.dp))) }
            }
            Column(Modifier.offset(w * 0.40f, h * 0.38f).size(w * 0.52f, h * 0.30f).background(Color(0xFF0E0F11), RoundedCornerShape(8.dp)).border(1.dp, Color(0xFF262931), RoundedCornerShape(8.dp)).padding(6.dp)) {
                listOf(60 to Color(0xFF8A8D96), 80 to Color(0xFF3FD1A2), 40 to Color(0xFF8A8D96)).forEachIndexed { i, (pct, c) ->
                    Box(Modifier.padding(top = if (i == 0) 2.dp else 5.dp).fillMaxWidth(pct / 100f).height(3.dp).background(c, RoundedCornerShape(2.dp)))
                }
            }
            // The phone's own keys: keyboard, touchpad, paste.
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(h * 0.22f).background(Color(0xFF0E0F11).copy(alpha = 0.55f)), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                listOf(R.drawable.sym_keyboard, R.drawable.sym_touchpad_mouse, R.drawable.sym_content_paste).forEach {
                    Box(Modifier.size(30.dp).background(Color.White.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(painterResource(it), contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                val p = phase()
                val (cx, cy) = cursorAt(p)
                val at = Offset(size.width * cx, size.height * cy)
                // A tap ripple where the pointer rests.
                val r = ((p - 0.26f) / 0.14f).coerceIn(0f, 1f)
                if (r > 0f && r < 1f) drawCircle(Color(0xFFA9B8FF).copy(alpha = 0.6f * (1 - r)), 22.dp.toPx() * r, Offset(size.width * 0.62f + 6.dp.toPx(), size.height * 0.30f + 8.dp.toPx()))
                val path = Path().apply {
                    val sx = 14.dp.toPx()
                    val sy = 20.dp.toPx()
                    moveTo(at.x, at.y)
                    lineTo(at.x + sx, at.y + sy * 0.62f)
                    lineTo(at.x + sx * 0.55f, at.y + sy * 0.66f)
                    lineTo(at.x + sx * 0.78f, at.y + sy)
                    lineTo(at.x + sx * 0.62f, at.y + sy)
                    lineTo(at.x + sx * 0.40f, at.y + sy * 0.70f)
                    lineTo(at.x, at.y + sy * 0.92f)
                    close()
                }
                drawPath(path, Color.White)
            }
        }
    }
    Chip(R.drawable.sym_public, stringResource(R.string.ob_chip_wifi), phase, 0.1f, Modifier.align(Alignment.TopStart).offset(10.dp, 70.dp), Tone.Mint)
    Chip(R.drawable.sym_touchpad_mouse, stringResource(R.string.ob_chip_touchpad), phase, 0.4f, Modifier.align(Alignment.BottomEnd).offset((-8).dp, (-56).dp))
}

// ---- 3: terminals ---------------------------------------------------------------------------------------

private val TerminalLines = listOf(
    "$ cd ~/project" to Color(0xFFE6E6E6),
    "$ make release" to Color(0xFFE6E6E6),
    "> building…" to Color(0xFF8A8D96),
    "BUILD SUCCESSFUL" to Color(0xFF3FD1A2),
    "$ ssh pi@raspberrypi" to Color(0xFFE6E6E6),
    "continue connecting (yes/no)?" to Color(0xFFE6E6E6),
)

@Composable
private fun BoxScope.SceneTerminals(phase: () -> Float, still: Boolean) {
    val t = tokens
    Blob(t.coral, 0.3f, phase, 0f, Modifier.align(Alignment.BottomStart).offset((-60).dp, 60.dp))
    Blob(t.accent, 0.35f, phase, 0.5f, Modifier.align(Alignment.TopEnd).offset(60.dp, (-60).dp))
    // The lines appear one after another, once: a finite animation, nothing loops.
    val shown = remember { Animatable(if (still) TerminalLines.size.toFloat() else 0f) }
    LaunchedEffect(still) { if (!still) shown.animateTo(TerminalLines.size.toFloat(), tween(TerminalLines.size * 380, 300, LinearEasing)) else shown.snapTo(TerminalLines.size.toFloat()) }
    Column(
        Modifier.align(Alignment.TopCenter).offset(y = 26.dp).padding(horizontal = 16.dp).fillMaxWidth()
            .background(Color(0xFF0E0F11), RoundedCornerShape(20.dp)).border(1.dp, Color(0xFF262931), RoundedCornerShape(20.dp)).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(">_", fontFamily = MonoFamily, fontSize = 10.sp, color = Color(0xFFA9B8FF), modifier = Modifier.background(Color(0xFF262D52), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
            Text("zsh", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE6E6E6), modifier = Modifier.weight(1f))
            Box(Modifier.size(7.dp).background(Color(0xFF3FD1A2), CircleShape))
        }
        Column(Modifier.padding(top = 12.dp)) {
            TerminalLines.forEachIndexed { i, (text, color) ->
                val a = (shown.value - i).coerceIn(0f, 1f)
                Text(text, fontFamily = MonoFamily, fontSize = 11.sp, lineHeight = 18.sp, color = color, maxLines = 1, modifier = Modifier.graphicsLayer { alpha = a })
            }
        }
        val keys = (shown.value - 5f).coerceIn(0f, 1f)
        Row(Modifier.padding(top = 12.dp).graphicsLayer { alpha = keys }, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(38.dp).background(Color(0xFFA9B8FF), CircleShape), contentAlignment = Alignment.Center) { Text("yes", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF14204F)) }
            Box(Modifier.weight(1f).height(38.dp).border(1.dp, Color(0xFF38383D), CircleShape), contentAlignment = Alignment.Center) { Text("no", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE6E6E6)) }
            Box(Modifier.size(38.dp).background(Color(0xFFFF7C66), CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.sym_mic), contentDescription = null, tint = Color(0xFF34201C), modifier = Modifier.size(18.dp))
            }
        }
    }
    Chip(R.drawable.sym_terminal, stringResource(R.string.ob_chip_same_terminal), phase, 0.2f, Modifier.align(Alignment.BottomStart).offset(30.dp, (-18).dp), Tone.Mint)
}

// ---- 4: share -------------------------------------------------------------------------------------------

@Composable
private fun BoxScope.SceneShare(phase: () -> Float) {
    val t = tokens
    Blob(t.mint, 0.4f, phase, 0f, Modifier.align(Alignment.TopStart).offset((-40).dp, (-40).dp))
    Blob(t.accent, 0.35f, phase, 0.3f, Modifier.align(Alignment.BottomEnd).offset(60.dp, 40.dp))
    SceneLaptop(132.dp, Modifier.align(Alignment.TopEnd).offset((-14).dp, 34.dp))
    ScenePhone(66.dp, Modifier.align(Alignment.BottomStart).offset(30.dp, (-18).dp)) { Tiles(4) }
    // Three files leave the phone one after another and shrink into the computer.
    val cards = listOf(
        Triple(R.drawable.sym_image, Brush.linearGradient(listOf(Color(0xFF4A5B86), Color(0xFF151A28))), Color(0xFFDFE5FF)),
        Triple(R.drawable.sym_description, Brush.linearGradient(listOf(t.accentContainer, t.accentContainer)), t.onAccentContainer),
        Triple(R.drawable.sym_folder_zip, Brush.linearGradient(listOf(t.accentContainer, t.accentContainer)), t.onAccentContainer),
    )
    val density = androidx.compose.ui.platform.LocalDensity.current
    cards.forEachIndexed { i, (icon, brush, fg) ->
        Box(
            Modifier.align(Alignment.TopStart).offset(40.dp, 214.dp).size(44.dp, 54.dp)
                .graphicsLayer {
                    val q = frac(phase() + i / 3f)
                    translationX = with(density) { 190.dp.toPx() } * q
                    translationY = -with(density) { 176.dp.toPx() } * q
                    scaleX = 1f - 0.5f * q
                    scaleY = 1f - 0.5f * q
                    rotationZ = -8f * (1 - q)
                    alpha = min(1f, q / 0.1f) * min(1f, (1 - q) / 0.12f)
                }
                .background(brush, RoundedCornerShape(10.dp)).border(2.dp, t.surface, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(painterResource(icon), contentDescription = null, tint = fg, modifier = Modifier.size(22.dp)) }
    }
    Chip(R.drawable.sym_content_paste, "git push origin main", phase, 0.1f, Modifier.align(Alignment.TopStart).offset(12.dp, 22.dp), mono = true)
    Chip(R.drawable.sym_download, stringResource(R.string.ob_chip_saved), phase, 0.4f, Modifier.align(Alignment.TopEnd).offset((-16).dp, 168.dp), Tone.Mint)
}

// ---- 5: private -----------------------------------------------------------------------------------------

@Composable
private fun BoxScope.ScenePrivate(phase: () -> Float) {
    val t = tokens
    Blob(t.accent, 0.45f, phase, 0f, Modifier.align(Alignment.TopStart).offset(60.dp, 40.dp))
    Blob(t.mint, 0.35f, phase, 0.6f, Modifier.align(Alignment.BottomEnd).offset(40.dp, 60.dp))
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(size.width / 2, 168.dp.toPx())
        for (i in 0..2) {
            val q = frac(phase() + i / 3f)
            drawCircle(t.accent.copy(alpha = 0.7f * (1 - q)), 75.dp.toPx() * (0.7f + 1.2f * q), c, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
    }
    Box(
        Modifier.align(Alignment.TopCenter).offset(y = 106.dp).size(124.dp).background(t.accentContainer, CircleShape).border(6.dp, t.surface, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(painterResource(R.drawable.sym_encrypted), contentDescription = null, tint = t.onAccentContainer, modifier = Modifier.size(56.dp)) }
    Chip(R.drawable.sym_encrypted, stringResource(R.string.ob_chip_encrypted), phase, 0.05f, Modifier.align(Alignment.TopStart).offset(14.dp, 24.dp), Tone.Mint)
    Chip(R.drawable.sym_devices, stringResource(R.string.ob_chip_paired), phase, 0.2f, Modifier.align(Alignment.TopEnd).offset((-12).dp, 252.dp))
    Chip(R.drawable.sym_person, stringResource(R.string.ob_chip_no_account), phase, 0.35f, Modifier.align(Alignment.BottomStart).offset(22.dp, (-20).dp))
}

/** The scene of tour page [page] (0..4) on its 328 x 350 canvas. Decorative: the page's words say everything it shows. */
@Composable
fun OnboardingScene(page: Int, still: Boolean, modifier: Modifier = Modifier) {
    val phase = rememberScenePhase(still)
    Box(modifier.requiredSize(SceneWidth.dp, SceneHeight.dp)) {
        when (page) {
            0 -> SceneWelcome(phase)
            1 -> SceneControl(phase)
            2 -> SceneTerminals(phase, still)
            3 -> SceneShare(phase)
            else -> ScenePrivate(phase)
        }
    }
}
