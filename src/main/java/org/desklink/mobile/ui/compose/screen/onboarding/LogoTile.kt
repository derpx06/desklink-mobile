/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.screen.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.tokens

/** The app mark on its navy tile (the same tile in both themes: the mark is drawn for it). */
@Composable
fun LogoTile(size: Dp, modifier: Modifier = Modifier, corner: Dp = size * 0.29f, mark: Dp = size * 0.6f) {
    val shape = RoundedCornerShape(corner)
    Box(modifier.size(size).background(Color(0xFF14213D), shape).border(1.dp, tokens.hairline, shape), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.desklink_mark), contentDescription = null, modifier = Modifier.size(mark))
    }
}
