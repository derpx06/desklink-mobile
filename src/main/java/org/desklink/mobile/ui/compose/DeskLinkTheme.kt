/*
 * SPDX-FileCopyrightText: 2024 Albert Vaca Cintora <albertvaka@gmail.com>
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.PreferenceManager
import org.desklink.mobile.R
import org.desklink.mobile.ui.ThemeUtil

/**
 * DeskLink's tokens, identical in both apps. Dark is the signature look (near-black, macky-like);
 * light is warm paper. The terminal stays dark in both.
 */
@Immutable
data class DeskLinkColors(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val hairline: Color,
    val hairlineStrong: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    val accent: Color,
    val accentPressed: Color,
    val onAccent: Color,
    /** Connected and success, and nothing else. */
    val mint: Color,
    /** Attention: the cursor, an unread count. */
    val coral: Color,
    val red: Color,
    /** Pairing in progress or wanted. */
    val amber: Color,
    val terminalBg: Color,
    val terminalFg: Color,
)

val DarkTokens = DeskLinkColors(
    isDark = true,
    bg = Color(0xFF0B0C0E),
    surface = Color(0xFF131418),
    surface2 = Color(0xFF1A1C21),
    hairline = Color(0xFF262931),
    hairlineStrong = Color(0xFF33363F),
    text = Color(0xFFEDEDEF),
    textDim = Color(0xFF9A9CA4),
    textFaint = Color(0xFF6B6E77),
    accent = Color(0xFF6E8CFF),
    accentPressed = Color(0xFF5A78F0),
    onAccent = Color(0xFF0B0C0E),
    mint = Color(0xFF34D1A0),
    coral = Color(0xFFFF7A62),
    red = Color(0xFFFF5C5C),
    amber = Color(0xFFFFC24D),
    terminalBg = Color(0xFF050506),
    terminalFg = Color(0xFFE6E6E6),
)

val LightTokens = DeskLinkColors(
    isDark = false,
    bg = Color(0xFFF6F6F4),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF0F0EE),
    hairline = Color(0xFFE4E4E0),
    hairlineStrong = Color(0xFFD6D6D1),
    text = Color(0xFF121317),
    textDim = Color(0xFF5E616A),
    textFaint = Color(0xFF8C8F97),
    accent = Color(0xFF3B6CF6),
    accentPressed = Color(0xFF2F5BE0),
    onAccent = Color(0xFFFFFFFF),
    mint = Color(0xFF13A57A),
    coral = Color(0xFFE8573F),
    red = Color(0xFFD93636),
    amber = Color(0xFFC98A00),
    // The terminal is the hero object: it stays dark on paper too.
    terminalBg = Color(0xFF0B0C0E),
    terminalFg = Color(0xFFE6E6E6),
)

val LocalDeskLinkColors = staticCompositionLocalOf { LightTokens }

/** The tokens of the current theme. */
val tokens: DeskLinkColors
    @Composable get() = LocalDeskLinkColors.current

private fun schemeOf(t: DeskLinkColors): ColorScheme {
    // Accent at 14% over the card surface: the tint of icon squircles and latched keys.
    val tint = t.accent.copy(alpha = 0.14f).compositeOver(t.surface)
    val onTint = if (t.isDark) Color(0xFFC9D4FF) else Color(0xFF1C3A99)
    return if (t.isDark) {
        darkColorScheme(
            primary = t.accent, onPrimary = t.onAccent, primaryContainer = tint, onPrimaryContainer = onTint,
            secondary = t.textDim, onSecondary = t.bg, secondaryContainer = tint, onSecondaryContainer = onTint,
            tertiary = t.mint, onTertiary = t.bg, tertiaryContainer = t.mint.copy(alpha = 0.16f).compositeOver(t.surface), onTertiaryContainer = t.mint,
            error = t.red, onError = t.bg, errorContainer = t.red.copy(alpha = 0.16f).compositeOver(t.surface), onErrorContainer = t.red,
            background = t.bg, onBackground = t.text, surface = t.bg, onSurface = t.text,
            surfaceVariant = t.surface2, onSurfaceVariant = t.textDim, outline = t.hairlineStrong, outlineVariant = t.hairline,
            inverseSurface = t.text, inverseOnSurface = t.surface, inversePrimary = LightTokens.accent, surfaceTint = t.accent,
            surfaceBright = Color(0xFF1F2127), surfaceDim = t.bg, surfaceContainerLowest = Color(0xFF08090A),
            surfaceContainerLow = t.surface, surfaceContainer = t.surface, surfaceContainerHigh = t.surface2,
            surfaceContainerHighest = Color(0xFF22242A),
        )
    } else {
        lightColorScheme(
            primary = t.accent, onPrimary = t.onAccent, primaryContainer = tint, onPrimaryContainer = onTint,
            secondary = t.textDim, onSecondary = t.surface, secondaryContainer = tint, onSecondaryContainer = onTint,
            tertiary = t.mint, onTertiary = t.surface, tertiaryContainer = t.mint.copy(alpha = 0.14f).compositeOver(t.surface), onTertiaryContainer = Color(0xFF07533C),
            error = t.red, onError = t.surface, errorContainer = t.red.copy(alpha = 0.12f).compositeOver(t.surface), onErrorContainer = Color(0xFF7A1010),
            background = t.bg, onBackground = t.text, surface = t.bg, onSurface = t.text,
            surfaceVariant = t.surface2, onSurfaceVariant = t.textDim, outline = t.hairlineStrong, outlineVariant = t.hairline,
            inverseSurface = Color(0xFF2A2B30), inverseOnSurface = t.bg, inversePrimary = DarkTokens.accent, surfaceTint = t.accent,
            surfaceBright = t.surface, surfaceDim = t.hairline, surfaceContainerLowest = t.surface,
            surfaceContainerLow = t.surface, surfaceContainer = t.surface, surfaceContainerHigh = t.surface2,
            surfaceContainerHighest = Color(0xFFE9E9E6),
        )
    }
}

private fun Color.compositeOver(background: Color): Color {
    val a = alpha
    return Color(
        red = red * a + background.red * (1 - a),
        green = green * a + background.green * (1 - a),
        blue = blue * a + background.blue * (1 - a),
        alpha = 1f,
    )
}

/** JetBrains Mono (OFL-1.1, bundled) for anything technical: paths, codes, sizes, terminal text. */
val MonoFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

/** The few styles the system type scale lacks. */
object DeskLinkType {
    /** The device name in the hero: 32/700, tight. */
    val hero = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)

    /** Section titles: 13/600 in the dim text color, sentence case. */
    val section = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)

    /** A card's title: 15/600. */
    val cardTitle = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)

    /** Empty and error states: 17/600. */
    val stateTitle = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)

    /** Technical text: "Local network · 12 ms · 82%", "120×40". */
    val mono = TextStyle(fontFamily = MonoFamily, fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = "tnum")

    /** Terminal previews: 11sp mono. */
    val monoSmall = TextStyle(fontFamily = MonoFamily, fontSize = 11.sp, lineHeight = 15.sp)
}

private val DeskLinkShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Body text at 15sp, as the spec has it; the rest is the stock Material 3 scale. */
private val DeskLinkTypography = Typography().run { copy(bodyLarge = bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp)) }

/** DeskLink's own palette unless the person turned on wallpaper colors (Android 12+). */
fun deskLinkColorScheme(context: Context, dark: Boolean): ColorScheme {
    val dynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(ThemeUtil.KEY_DYNAMIC_COLORS, false)
    return when {
        dynamic && dark -> dynamicDarkColorScheme(context)
        dynamic -> dynamicLightColorScheme(context)
        else -> schemeOf(if (dark) DarkTokens else LightTokens)
    }
}

@Composable
fun DeskLinkTheme(context: Context, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    DeskLinkMaterialTheme(deskLinkColorScheme(context, dark), if (dark) DarkTokens else LightTokens, content)
}

/** The always-dark scheme of the remote screen, whatever the phone's theme. */
@Composable
fun DeskLinkDarkTheme(context: Context, content: @Composable () -> Unit) =
    DeskLinkMaterialTheme(deskLinkColorScheme(context, dark = true), DarkTokens, content)

/**
 * Also sets the content color: text placed straight on a screen, outside any Surface, would
 * otherwise default to black, unreadable on a dark background.
 */
@Composable
private fun DeskLinkMaterialTheme(scheme: ColorScheme, colors: DeskLinkColors, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = DeskLinkTypography, shapes = DeskLinkShapes) {
        CompositionLocalProvider(
            LocalDeskLinkColors provides colors,
            LocalContentColor provides scheme.onSurface,
            content = content,
        )
    }
}
