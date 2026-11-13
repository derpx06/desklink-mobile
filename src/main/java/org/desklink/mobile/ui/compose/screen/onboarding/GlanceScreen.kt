/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose.screen.onboarding

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.DeskLinkShape
import org.desklink.mobile.ui.compose.DeskLinkType
import org.desklink.mobile.ui.compose.DeskTextButton
import org.desklink.mobile.ui.compose.PrimaryButton
import org.desklink.mobile.ui.compose.components.IconTile
import org.desklink.mobile.ui.compose.tokens

/** The first screen's words: what DeskLink does and the steps to start. The desk's glance says the same (both apps' tests pin it). */
object Glance {
    class Line(@DrawableRes val icon: Int, @StringRes val text: Int)

    val lines = listOf(
        Line(R.drawable.sym_desktop_windows, R.string.ob_glance_line_screens),
        Line(R.drawable.sym_upload_file, R.string.ob_glance_line_share),
        Line(R.drawable.sym_notifications_active, R.string.ob_glance_line_more),
    )

    val steps = listOf(R.string.ob_glance_step_install, R.string.ob_glance_step_pair, R.string.ob_glance_step_done)
}

/**
 * The very first screen: one headline, three lines with icons saying what DeskLink does, the three steps to start, and one
 * button ([onStart], the tour). [onSkip] is always on screen.
 */
@Composable
fun GlanceScreen(onStart: () -> Unit, onSkip: () -> Unit) {
    val t = tokens
    Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding().navigationBarsPadding()) {
        // Outside the scroll: Skip never scrolls away.
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            LogoTile(30.dp, corner = 9.dp, mark = 22.dp)
            Text("DeskLink", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = t.text, modifier = Modifier.padding(start = 8.dp).weight(1f))
            DeskTextButton(stringResource(R.string.ob_skip), onSkip)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Text(stringResource(R.string.ob_glance_kicker), fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = t.accentText, modifier = Modifier.padding(top = 16.dp))
            Text(
                stringResource(R.string.ob_glance_title),
                style = DeskLinkType.screenTitle,
                color = t.text,
                modifier = Modifier.padding(top = 6.dp).semantics { heading() },
            )
            Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Glance.lines.forEach { line ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        IconTile(line.icon, size = 44.dp)
                        Text(stringResource(line.text), fontSize = 16.sp, lineHeight = 22.sp, color = t.text, modifier = Modifier.weight(1f))
                    }
                }
            }
            Column(
                Modifier.padding(top = 26.dp).fillMaxWidth().background(t.surface, DeskLinkShape.card).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.ob_glance_steps), fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = t.text, modifier = Modifier.semantics { heading() })
                Glance.steps.forEachIndexed { i, step ->
                    val number = stringResource(R.string.ob_glance_step_number, i + 1)
                    Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(28.dp).background(t.accentContainer, CircleShape).clearAndSetSemantics { contentDescription = number }, contentAlignment = Alignment.Center) {
                            Text("${i + 1}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.onAccentContainer)
                        }
                        Text(stringResource(step), fontSize = 15.sp, lineHeight = 21.sp, color = t.text, modifier = Modifier.weight(1f))
                    }
                }
            }
            Row(Modifier.padding(top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.sym_encrypted), contentDescription = null, tint = t.textDim, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.ob_glance_private), fontSize = 13.sp, lineHeight = 18.sp, color = t.textDim, modifier = Modifier.weight(1f))
            }
        }
        PrimaryButton(stringResource(R.string.ob_glance_start), onStart, Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 18.dp, top = 8.dp))
    }
}
