/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose.update

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import org.desklink.mobile.ui.compose.tokens

/**
 * The sheet's picture (docs/UPDATES.md 6.3, 6.4): a phone with an up arrow inside orbit rings and sparkles, drawn on a Canvas
 * (360x150 units) from the app's tokens, so it follows light, dark and high contrast. No drawable, no raster. Decorative.
 * With the system's high-contrast text on, the gradient and glow go: a solid accent card and an outlined phone.
 */
@Composable
internal fun UpdateIllustrationCard(modifier: Modifier = Modifier, warning: Boolean = false) {
    val t = tokens
    val context = LocalContext.current
    val highContrast = remember {
        runCatching { Settings.Secure.getInt(context.contentResolver, "high_text_contrast_enabled", 0) == 1 }.getOrDefault(false)
    }
    // White and black here are the picture's own light and shadow (a phone body, a glow), not text: the card's colour comes from the tokens.
    // A warning is a deeper amber so the pale phone and its arrow keep their contrast in dark mode too.
    val base = if (warning) lerp(t.amber, Color.Black, 0.3f) else t.accent
    val ink = lerp(base, Color.Black, if (warning) 0.6f else 0.28f)
    Canvas(
        // At most 150 dp tall, however wide the sheet is (a tablet): the drawing keeps its proportions and is centred.
        modifier.fillMaxWidth().layout { m, c ->
            val h = minOf((c.maxWidth * 150f / 360f).toInt(), 150.dp.roundToPx())
            val p = m.measure(androidx.compose.ui.unit.Constraints.fixed(c.maxWidth, h))
            layout(p.width, p.height) { p.place(0, 0) }
        }.clip(RoundedCornerShape(24.dp)).clearAndSetSemantics { },
    ) {
        val s = minOf(size.width / 360f, size.height / 150f)
        val dx = (size.width - 360f * s) / 2f
        fun p(x: Float, y: Float) = Offset(dx + x * s, y * s)
        if (highContrast) {
            drawRect(base)
        } else {
            drawRect(Brush.linearGradient(listOf(lerp(base, Color.White, if (warning) 0.15f else 0.3f), base), start = Offset.Zero, end = Offset(size.width, size.height)))
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.38f), Color.Transparent), center = p(180f, 80f), radius = 88f * s), radius = 88f * s, center = p(180f, 80f))
        }
        for (r in listOf(52f, 80f)) drawCircle(Color.White.copy(alpha = 0.26f), radius = r * s, center = p(180f, 80f), style = Stroke(1.5f * s))
        // The phone: body, screen, arrow, home dot.
        drawRoundRect(Color.White.copy(alpha = 0.97f), p(150f, 24f), Size(60f * s, 106f * s), CornerRadius(15f * s))
        if (highContrast) drawRoundRect(ink, p(150f, 24f), Size(60f * s, 106f * s), CornerRadius(15f * s), style = Stroke(2f * s))
        drawRoundRect(base.copy(alpha = 0.14f), p(156f, 34f), Size(48f * s, 80f * s), CornerRadius(9f * s))
        arrow(s, dx, ink)
        drawCircle(ink.copy(alpha = 0.5f), 3f * s, p(180f, 122f))
        for ((x, y, r, a) in listOf(SP(70f, 34f, 3f, .7f), SP(296f, 30f, 2.5f, .6f), SP(318f, 98f, 3.5f, .55f), SP(46f, 104f, 2.5f, .6f), SP(250f, 122f, 2f, .5f))) {
            drawCircle(Color.White.copy(alpha = a), r * s, p(x, y))
        }
    }
}

private data class SP(val x: Float, val y: Float, val r: Float, val a: Float)

private fun DrawScope.arrow(s: Float, dx: Float, color: Color) {
    val path = Path().apply {
        moveTo(dx + 180f * s, 100f * s); lineTo(dx + 180f * s, 62f * s)
        moveTo(dx + 167f * s, 75f * s); lineTo(dx + 180f * s, 62f * s); lineTo(dx + 193f * s, 75f * s)
    }
    drawPath(path, color, style = Stroke(6.5f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
