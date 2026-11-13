/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose.screen.guide

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.DeskLinkShape
import org.desklink.mobile.ui.compose.SecondaryButton
import org.desklink.mobile.ui.compose.components.ChecklistRow
import org.desklink.mobile.ui.compose.components.ChecklistState
import org.desklink.mobile.ui.compose.components.IconTile
import org.desklink.mobile.ui.compose.screen.home.Blocker
import org.desklink.mobile.ui.compose.screen.home.HomeDerive
import org.desklink.mobile.ui.compose.screen.home.HomeState
import org.desklink.mobile.ui.compose.screen.home.PaletteModel
import org.desklink.mobile.ui.compose.screen.settings.PermissionRow
import org.desklink.mobile.ui.compose.components.ScreenHeader
import org.desklink.mobile.ui.compose.screen.settings.actionLabel
import org.desklink.mobile.ui.compose.tokens
import org.desklink.mobile.ui.compose.screen.guide.Guide.TROUBLE

/** What a guide card's button does. Every value has a destination in the activity (an exhaustive `when`). */
enum class GuideAction { PAIR, SEND_FILE, SEND_CLIPBOARD, DESK_SCREEN, SHARE_SCREEN, NOTIFICATIONS, TERMINALS, NOTES, RING, LOCK, GLIDE, TILES, RETRY, WIFI }

/**
 * One task: [how] says how in one line (formatted with the computer's name), [button] runs [action]. [needsComputer]: it says
 * "Pair a computer first" until one is paired. [blockedBy] is the Home action whose state it shares, so a card says why it
 * cannot run now exactly as Home does. [atComputer]: done at the computer, nothing to press here.
 */
class GuideCard(
    val id: String,
    @DrawableRes val icon: Int,
    @StringRes val title: Int,
    @StringRes val how: Int,
    @StringRes val button: Int? = null,
    val action: GuideAction? = null,
    val needsComputer: Boolean = true,
    val blockedBy: String? = null,
    val atComputer: Boolean = false,
)

/** Why a card cannot run now: nothing, no computer, or the Home blocker (with its fix). */
sealed interface GuideStatus {
    data object Ready : GuideStatus
    data object NeedsPair : GuideStatus
    data class Blocked(val blocker: Blocker) : GuideStatus
}

/** The guide's cards and their state: pure, so a test can check every card without drawing it. */
object Guide {
    const val TROUBLE = "trouble"

    val cards = listOf(
        GuideCard("pair", R.drawable.sym_qr_code_scanner, R.string.guide_pair_title, R.string.guide_pair_how, R.string.guide_pair_button, GuideAction.PAIR, needsComputer = false),
        GuideCard("send", R.drawable.sym_upload_file, R.string.guide_send_title, R.string.guide_send_how, R.string.guide_send_button, GuideAction.SEND_FILE, blockedBy = PaletteModel.Action.SEND_FILES),
        GuideCard("clipboard", R.drawable.sym_content_paste, R.string.guide_clip_title, R.string.guide_clip_how, R.string.guide_clip_button, GuideAction.SEND_CLIPBOARD, blockedBy = PaletteModel.Action.SEND_CLIPBOARD),
        GuideCard("desk_screen", R.drawable.sym_desktop_windows, R.string.guide_desk_title, R.string.guide_desk_how, R.string.guide_desk_button, GuideAction.DESK_SCREEN, blockedBy = PaletteModel.Action.DESK_SCREEN),
        GuideCard("extend", R.drawable.sym_aspect_ratio, R.string.guide_extend_title, R.string.guide_extend_how, R.string.guide_desk_button, GuideAction.DESK_SCREEN, blockedBy = PaletteModel.Action.DESK_SCREEN),
        GuideCard("share_screen", R.drawable.sym_mobile_screen_share, R.string.guide_share_title, R.string.guide_share_how, R.string.guide_share_button, GuideAction.SHARE_SCREEN, blockedBy = PaletteModel.Action.SHARE_SCREEN),
        GuideCard("notifications", R.drawable.sym_notifications, R.string.guide_notif_title, R.string.guide_notif_how, R.string.guide_notif_button, GuideAction.NOTIFICATIONS),
        GuideCard("terminals", R.drawable.sym_terminal, R.string.guide_term_title, R.string.guide_term_how, R.string.guide_term_button, GuideAction.TERMINALS),
        GuideCard("notes", R.drawable.sym_edit_note, R.string.guide_notes_title, R.string.guide_notes_how, R.string.guide_notes_button, GuideAction.NOTES),
        GuideCard("find", R.drawable.sym_vibration, R.string.guide_find_title, R.string.guide_find_how, R.string.guide_find_button, GuideAction.RING, blockedBy = PaletteModel.Action.RING),
        GuideCard("lock", R.drawable.sym_lock, R.string.guide_lock_title, R.string.guide_lock_how, R.string.guide_lock_button, GuideAction.LOCK, blockedBy = PaletteModel.Action.LOCK),
        GuideCard("glide", R.drawable.sym_mouse, R.string.guide_glide_title, R.string.guide_glide_how, R.string.guide_glide_button, GuideAction.GLIDE),
        GuideCard("tiles", R.drawable.sym_tune, R.string.guide_tiles_title, R.string.guide_tiles_how, R.string.guide_tiles_button, GuideAction.TILES, needsComputer = false),
        GuideCard("top_bar", R.drawable.sym_more_vert, R.string.guide_topbar_title, R.string.guide_topbar_how, needsComputer = false, atComputer = true),
        GuideCard(TROUBLE, R.drawable.sym_link_off, R.string.guide_trouble_title, R.string.guide_trouble_how, R.string.guide_trouble_retry, GuideAction.RETRY, needsComputer = false),
    )

    fun status(card: GuideCard, state: HomeState?): GuideStatus = when {
        card.needsComputer && state == null -> GuideStatus.NeedsPair
        state != null && card.blockedBy != null -> HomeDerive.blocker(state, card.blockedBy)?.let(GuideStatus::Blocked) ?: GuideStatus.Ready
        else -> GuideStatus.Ready
    }
}

/**
 * "Get started": every task as a short card with one button that does it. A card that cannot run now says why and what fixes it
 * ([onAction] gets [GuideAction.PAIR] for "Pair a computer first"; [onBlocked] explains a Home blocker and offers its fix).
 * [state] is null before a computer is paired. [trouble] are the background-running rows (battery, the maker's own switch),
 * each with its own button. [tiles]: this Android can add a tile from here (13+).
 */
@Composable
internal fun GuideScreen(
    state: HomeState?,
    onBack: () -> Unit,
    onAction: (GuideAction) -> Unit,
    onBlocked: (card: GuideCard, title: String, blocker: Blocker) -> Unit = { _, _, _ -> },
    trouble: List<PermissionRow> = emptyList(),
    tiles: Boolean = true,
    scroll: ScrollState = rememberScrollState(),
) {
    val t = tokens
    val name = state?.name ?: stringResource(R.string.guide_your_computer)
    Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding()) {
        ScreenHeader(onBack, title = stringResource(R.string.guide_title))
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.guide_subtitle), fontSize = 15.sp, lineHeight = 21.sp, color = t.textDim, modifier = Modifier.padding(horizontal = 4.dp))
            Guide.cards.forEach { card -> CardView(card, state, name, onAction, onBlocked, trouble, tiles) }
        }
    }
}

/** One task: icon and title, the one-line how, then what the person can press (or why it cannot run now and the fix). */
@Composable
private fun CardView(
    card: GuideCard,
    state: HomeState?,
    name: String,
    onAction: (GuideAction) -> Unit,
    onBlocked: (card: GuideCard, title: String, blocker: Blocker) -> Unit,
    trouble: List<PermissionRow>,
    tiles: Boolean,
) {
    val t = tokens
    val title = stringResource(card.title)
    val status = Guide.status(card, state)
    // The icon beside the words; at the largest fonts it goes, so the words keep the width.
    val icon = androidx.compose.ui.platform.LocalDensity.current.fontScale <= 1.4f
    Column(Modifier.fillMaxWidth().background(t.surface, DeskLinkShape.group).border(1.dp, t.hairline, DeskLinkShape.group).padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (icon) IconTile(card.icon, size = 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = t.text, modifier = Modifier.semantics { heading() })
                val how = if (card.action == GuideAction.PAIR && state != null) stringResource(R.string.guide_pair_how_paired, name) else stringResource(card.how, name)
                Text(how, fontSize = 14.sp, lineHeight = 20.sp, color = t.textDim)
                if (card.atComputer) Note(R.drawable.sym_desktop_windows, stringResource(R.string.guide_on_computer))
                when (status) {
                    GuideStatus.NeedsPair -> {
                        Note(R.drawable.sym_warning, stringResource(R.string.guide_needs_pair), warn = true)
                        SecondaryButton(stringResource(R.string.guide_pair_button), { onAction(GuideAction.PAIR) }, height = 48.dp, tonal = true)
                    }
                    is GuideStatus.Blocked -> {
                        Note(R.drawable.sym_warning, stringResource(HomeDerive.message(status.blocker), title, name), warn = true)
                        val fix = HomeDerive.fixLabel(HomeDerive.fix(status.blocker))
                        SecondaryButton(stringResource(fix ?: card.button ?: R.string.guide_open), { onBlocked(card, title, status.blocker) }, height = 48.dp, tonal = true)
                    }
                    GuideStatus.Ready -> {
                        val label = if (card.action == GuideAction.PAIR && state != null) R.string.guide_pair_another else card.button
                        val action = card.action?.takeIf { it != GuideAction.TILES || tiles }
                        if (label != null && action != null) SecondaryButton(stringResource(label), { onAction(action) }, height = 48.dp, tonal = true)
                    }
                }
                if (card.id == TROUBLE) SecondaryButton(stringResource(R.string.guide_trouble_wifi), { onAction(GuideAction.WIFI) }, height = 48.dp)
            }
        }
        // The card's full width: each row has a title, a line and its own button. Never hidden once allowed: it says "On".
        if (card.id == TROUBLE) trouble.forEach { row ->
            ChecklistRow(
                icon = row.icon,
                title = row.title,
                description = row.summary,
                state = if (row.granted) ChecklistState.Done else ChecklistState.Todo,
                actionLabel = stringResource(row.actionLabel()),
                doneLabel = stringResource(R.string.perm_on),
                onClick = if (row.granted) null else row.allow,
            )
        }
    }
}

/** A short line with an icon under the "how": where it is done, or why it cannot be done now. */
@Composable
private fun Note(@DrawableRes icon: Int, text: String, warn: Boolean = false) {
    val t = tokens
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(icon), contentDescription = null, tint = if (warn) t.amber else t.textDim, modifier = Modifier.size(18.dp))
        Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = t.text, modifier = Modifier.weight(1f))
    }
}
