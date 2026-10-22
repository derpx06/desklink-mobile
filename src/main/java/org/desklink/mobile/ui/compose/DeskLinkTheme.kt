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
import androidx.compose.ui.graphics.compositeOver
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
 * DeskLink's tokens: the design's phone palette (`pl` light, `pd` dark), value for value. Colour
 * has one meaning each: accent = actions, mint = connected, coral = the desk (or another device)
 * is using this one, amber = waiting on you, red = stop or error. The terminal stays dark in both.
 */
@Immutable
data class DeskLinkColors(
    val isDark: Boolean,
    /** `w`: the window. */
    val bg: Color,
    /** `c`: cards. */
    val surface: Color,
    /** `s`: sheets, key caps and other raised secondary surfaces. */
    val surface2: Color,
    /** `l`: card edges and dividers. */
    val hairline: Color,
    /** `b`: outlines of buttons and sheet handles. */
    val hairlineStrong: Color,
    /** `t`, `t2`: text. */
    val text: Color,
    val textDim: Color,
    /** `t3`: never text (under 4.5:1); offline glyphs and disabled icons only. */
    val textFaint: Color,
    /** `a`, `at`, `ao`: actions, accent text, text on an accent fill. */
    val accent: Color,
    val accentText: Color,
    val onAccent: Color,
    /** `ac`, `aco`: tonal accent fills and their text. */
    val accentContainer: Color,
    val onAccentContainer: Color,
    /** `m`: connected and success, and nothing else. */
    val mint: Color,
    /** Mint dark enough to be text (the light `m` is 3.5:1 on a card). */
    val mintText: Color,
    /** `o`, `ot`, `ob`: the desk is using this phone. Always paired with an End. */
    val coral: Color,
    val coralText: Color,
    val coralContainer: Color,
    /** `k`: the faint fill behind icons. */
    val fill: Color,
    /** `sw`: a switch track when off. */
    val switchOff: Color,
    /** `inv`, `invt`: snackbars and other inverted surfaces. */
    val inverse: Color,
    val onInverse: Color,
    /** `rd`: stop, error, unpair. */
    val red: Color,
    /** Waiting on you: a permission to grant, a pairing to answer. */
    val amber: Color,
    val terminalBg: Color,
    val terminalFg: Color,
)

val LightTokens = DeskLinkColors(
    isDark = false,
    bg = Color(0xFFF7F7F4),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFECECE8),
    hairline = Color(0x12000000),
    hairlineStrong = Color(0xFFD4D4CF),
    text = Color(0xFF18181B),
    textDim = Color(0xFF5F5F67),
    textFaint = Color(0xFF8F8F97),
    accent = Color(0xFF3256D6),
    accentText = Color(0xFF2A4CC4),
    onAccent = Color(0xFFFFFFFF),
    accentContainer = Color(0xFFE2E8FB),
    onAccentContainer = Color(0xFF1B2F7A),
    mint = Color(0xFF0F9B72),
    mintText = Color(0xFF0B7A59),
    coral = Color(0xFFE5533D),
    coralText = Color(0xFFB9402B),
    coralContainer = Color(0xFFFBEBE7),
    fill = Color(0x0D000000),
    switchOff = Color(0x2E000000),
    inverse = Color(0xFF2F3036),
    onInverse = Color(0xFFF1F1F3),
    red = Color(0xFFC93030),
    amber = Color(0xFF9A6412),
    terminalBg = Color(0xFF0E0F11),
    terminalFg = Color(0xFFD9DBE0),
)

val DarkTokens = DeskLinkColors(
    isDark = true,
    bg = Color(0xFF111113),
    surface = Color(0xFF1A1A1D),
    surface2 = Color(0xFF1E1E21),
    hairline = Color(0x12FFFFFF),
    hairlineStrong = Color(0xFF38383D),
    text = Color(0xFFF2F2F3),
    textDim = Color(0xFFA1A1AA),
    textFaint = Color(0xFF71717A),
    accent = Color(0xFFA9B8FF),
    accentText = Color(0xFFA9B8FF),
    onAccent = Color(0xFF14204F),
    accentContainer = Color(0xFF262D52),
    onAccentContainer = Color(0xFFDFE5FF),
    mint = Color(0xFF3FD1A2),
    mintText = Color(0xFF3FD1A2),
    coral = Color(0xFFFF7C66),
    coralText = Color(0xFFFF9A88),
    coralContainer = Color(0xFF34201C),
    fill = Color(0x0FFFFFFF),
    switchOff = Color(0x33FFFFFF),
    inverse = Color(0xFFE6E6EA),
    onInverse = Color(0xFF18181B),
    red = Color(0xFFFF6B6B),
    amber = Color(0xFFF5B84A),
    terminalBg = Color(0xFF0E0F11),
    terminalFg = Color(0xFFD9DBE0),
)

val LocalDeskLinkColors = staticCompositionLocalOf { LightTokens }

/** The tokens of the current theme. */
val tokens: DeskLinkColors
    @Composable get() = LocalDeskLinkColors.current

private fun schemeOf(t: DeskLinkColors): ColorScheme {
    val base = if (t.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = t.accent, onPrimary = t.onAccent, primaryContainer = t.accentContainer, onPrimaryContainer = t.onAccentContainer,
        secondary = t.textDim, onSecondary = t.bg, secondaryContainer = t.accentContainer, onSecondaryContainer = t.onAccentContainer,
        tertiary = t.mint, onTertiary = t.bg, tertiaryContainer = t.mint.copy(alpha = 0.16f).compositeOver(t.surface), onTertiaryContainer = t.mintText,
        error = t.red, onError = t.surface, errorContainer = t.red.copy(alpha = 0.12f).compositeOver(t.surface), onErrorContainer = t.red,
        background = t.bg, onBackground = t.text, surface = t.bg, onSurface = t.text,
        surfaceVariant = t.surface2, onSurfaceVariant = t.textDim, outline = t.hairlineStrong, outlineVariant = t.hairline.compositeOver(t.bg),
        inverseSurface = t.inverse, inverseOnSurface = t.onInverse, inversePrimary = (if (t.isDark) LightTokens else DarkTokens).accent,
        surfaceTint = Color.Transparent, scrim = Color.Black,
        surfaceBright = t.surface, surfaceDim = t.bg, surfaceContainerLowest = t.surface,
        surfaceContainerLow = t.surface, surfaceContainer = t.surface, surfaceContainerHigh = t.surface2,
        surfaceContainerHighest = t.surface2,
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

/** The theme with the light or dark palette chosen by the caller (the screenshot harness, previews). */
@Composable
fun DeskLinkFixedTheme(context: Context, dark: Boolean, content: @Composable () -> Unit) =
    DeskLinkMaterialTheme(deskLinkColorScheme(context, dark), if (dark) DarkTokens else LightTokens, content)

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
