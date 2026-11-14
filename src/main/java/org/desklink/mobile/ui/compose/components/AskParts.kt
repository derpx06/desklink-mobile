/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.desklink.mobile.R
import org.desklink.mobile.plugins.desksettings.AskBook
import org.desklink.mobile.plugins.desksettings.Asks
import org.desklink.mobile.ui.compose.PrimaryButton
import org.desklink.mobile.ui.compose.SecondaryButton
import org.desklink.mobile.ui.compose.tokens

/** The words for where an ask stands (asks.json words), [device] being the one asked. */
@Composable
fun askWords(state: AskBook.State, device: String): String = stringResource(
    when (state) {
        AskBook.State.WAITING -> R.string.ask_waiting
        AskBook.State.DECLINED -> R.string.ask_declined
        AskBook.State.TIMED_OUT -> R.string.ask_timed_out
        AskBook.State.UNDELIVERED -> R.string.ask_undelivered
        AskBook.State.BLOCKED -> R.string.ask_blocked
    },
    device,
)

/**
 * "Ask on computer": shown where a feature is off at the computer. Its line says the state (off there, waiting, said not now, didn't
 * answer, couldn't reach, notifications off there) and its one button sends the ask, or asks again; none while it waits.
 */
@Composable
fun AskOnComputerRow(state: AskBook.State?, deskName: String, onAsk: () -> Unit) {
    val waiting = state == AskBook.State.WAITING || state == AskBook.State.BLOCKED
    GroupRow(
        if (state == null) stringResource(R.string.ask_off_on, deskName) else askWords(state, deskName),
        icon = R.drawable.sym_info,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        if (!waiting) {
            SecondaryButton(
                stringResource(if (state == null) R.string.ask_on_computer else R.string.ask_again),
                onAsk, tonal = true, height = 48.dp, modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/** [AskOnComputerRow] for a desk's [plugin], shown only when that desk can be asked and reports [key] off. */
@Composable
fun AskOnComputer(plugin: org.desklink.mobile.plugins.desksettings.DeskSettingsPlugin?, deskName: String, key: String) {
    plugin ?: return
    @Suppress("UNUSED_VARIABLE") val changed = plugin.asksChanged
    if (!plugin.askable(key)) return
    AskOnComputerRow(plugin.askState(key), deskName) { plugin.askDesk(key) }
}

/**
 * The asks waiting on this phone, at the top of every screen: the same Allow / Not now as the notification, so a muted channel or
 * notifications turned off still reach the person once the app is open.
 */
@Composable
fun IncomingAsksBanner(asks: List<Asks.Incoming>, onAllow: (Asks.Incoming) -> Unit, onNotNow: (Asks.Incoming) -> Unit, modifier: Modifier = Modifier) {
    if (asks.isEmpty()) return
    val t = tokens
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        asks.forEach { ask ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(t.accentContainer).padding(16.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.ask_prompt, ask.deskName, stringResource(ask.feature.phrase)), fontSize = 16.sp, lineHeight = 22.sp, color = t.text)
                Text(stringResource(R.string.ask_prompt_text), fontSize = 13.sp, color = t.textDim)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(stringResource(R.string.ask_not_now), { onNotNow(ask) }, Modifier.weight(1f), height = 48.dp)
                    PrimaryButton(stringResource(R.string.ask_allow), { onAllow(ask) }, Modifier.weight(1f), height = 48.dp)
                }
            }
        }
    }
}
