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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.PreferenceManager
import org.desklink.mobile.ui.ThemeUtil

// Brand palette, from the DeskLink mark: navy #14213D, link blue #2E6BFF, mint #74D9C4.
// Dark surfaces are near-black with a cool cast, so a terminal or a remote screen sits in them
// without a visible seam.
private val LightBrand = lightColorScheme(
    primary = Color(0xFF2557E0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4FF),
    onPrimaryContainer = Color(0xFF00174B),
    secondary = Color(0xFF4F5B78),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE3F6),
    onSecondaryContainer = Color(0xFF14213D),
    tertiary = Color(0xFF006B59),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFB4F1E2),
    onTertiaryContainer = Color(0xFF00201A),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF151A24),
    surface = Color(0xFFF7F8FC),
    onSurface = Color(0xFF151A24),
    surfaceVariant = Color(0xFFE1E5EF),
    onSurfaceVariant = Color(0xFF545C6E),
    outline = Color(0xFF8790A3),
    outlineVariant = Color(0xFFD3D8E3),
    inverseSurface = Color(0xFF2A2F3A),
    inverseOnSurface = Color(0xFFEFF1F7),
    inversePrimary = Color(0xFFB3C5FF),
    surfaceTint = Color(0xFF2557E0),
    surfaceBright = Color(0xFFF7F8FC),
    surfaceDim = Color(0xFFD7DAE2),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F3F9),
    surfaceContainer = Color(0xFFEBEEF5),
    surfaceContainerHigh = Color(0xFFE5E8F0),
    surfaceContainerHighest = Color(0xFFDFE3EC),
)

private val DarkBrand = darkColorScheme(
    primary = Color(0xFF9DB6FF),
    onPrimary = Color(0xFF002A78),
    primaryContainer = Color(0xFF1E43B0),
    onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Color(0xFFBCC6E3),
    onSecondary = Color(0xFF263048),
    secondaryContainer = Color(0xFF2D3753),
    onSecondaryContainer = Color(0xFFDDE3F6),
    tertiary = Color(0xFF74D9C4),
    onTertiary = Color(0xFF00382E),
    tertiaryContainer = Color(0xFF005143),
    onTertiaryContainer = Color(0xFFB4F1E2),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0B0D12),
    onBackground = Color(0xFFE9ECF2),
    surface = Color(0xFF0B0D12),
    onSurface = Color(0xFFE9ECF2),
    surfaceVariant = Color(0xFF2A303C),
    onSurfaceVariant = Color(0xFFA6ADBC),
    outline = Color(0xFF6E7687),
    outlineVariant = Color(0xFF2C323E),
    inverseSurface = Color(0xFFE9ECF2),
    inverseOnSurface = Color(0xFF1A1E27),
    inversePrimary = Color(0xFF2557E0),
    surfaceTint = Color(0xFF9DB6FF),
    surfaceBright = Color(0xFF2A2F3A),
    surfaceDim = Color(0xFF0B0D12),
    surfaceContainerLowest = Color(0xFF060709),
    surfaceContainerLow = Color(0xFF12151C),
    surfaceContainer = Color(0xFF161A22),
    surfaceContainerHigh = Color(0xFF1C212B),
    surfaceContainerHighest = Color(0xFF242A35),
)

/** Colors for connection states, which must read the same whatever the wallpaper is. */
@Immutable
data class StatusColors(
    val online: Color,
    val onOnlineContainer: Color,
    val onlineContainer: Color,
    val away: Color,
    val awayContainer: Color,
    val onAwayContainer: Color,
    val pending: Color,
    val pendingContainer: Color,
    val onPendingContainer: Color,
)

private val LightStatus = StatusColors(
    online = Color(0xFF1E8E5A),
    onlineContainer = Color(0xFFD5F5E3),
    onOnlineContainer = Color(0xFF0B4A2C),
    away = Color(0xFF5B5BD6),
    awayContainer = Color(0xFFE4E4FF),
    onAwayContainer = Color(0xFF26267A),
    pending = Color(0xFFB7791F),
    pendingContainer = Color(0xFFFBEBCB),
    onPendingContainer = Color(0xFF5A3A06),
)

private val DarkStatus = StatusColors(
    online = Color(0xFF4ADE80),
    onlineContainer = Color(0xFF0F2E1E),
    onOnlineContainer = Color(0xFFB7F5CF),
    away = Color(0xFFA5A6FF),
    awayContainer = Color(0xFF23244D),
    onAwayContainer = Color(0xFFDADBFF),
    pending = Color(0xFFF5B94A),
    pendingContainer = Color(0xFF3A2A0C),
    onPendingContainer = Color(0xFFFBE2B0),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

private val DeskLinkTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val DeskLinkShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** The scheme DeskLink uses here: the wallpaper's on Android 12+ unless turned off, else the brand's. */
fun deskLinkColorScheme(context: Context, dark: Boolean): ColorScheme {
    val dynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(ThemeUtil.KEY_DYNAMIC_COLORS, true)
    return when {
        dynamic && dark -> dynamicDarkColorScheme(context)
        dynamic -> dynamicLightColorScheme(context)
        dark -> DarkBrand
        else -> LightBrand
    }
}

@Composable
fun DeskLinkTheme(context: Context, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    DeskLinkMaterialTheme(deskLinkColorScheme(context, dark), if (dark) DarkStatus else LightStatus, content)
}

/**
 * Also sets the content color: text placed straight on a screen, outside any Surface, would
 * otherwise default to black, unreadable on a dark background.
 */
@Composable
private fun DeskLinkMaterialTheme(scheme: ColorScheme, status: StatusColors, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = DeskLinkTypography, shapes = DeskLinkShapes) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalStatusColors provides status,
            androidx.compose.material3.LocalContentColor provides scheme.onSurface,
            content = content,
        )
    }
}

/** The always-dark scheme of the terminal and the remote screen, whatever the phone's theme. */
@Composable
fun DeskLinkDarkTheme(context: Context, content: @Composable () -> Unit) =
    DeskLinkMaterialTheme(deskLinkColorScheme(context, dark = true), DarkStatus, content)
