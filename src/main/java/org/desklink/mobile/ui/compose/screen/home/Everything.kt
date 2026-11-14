/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose.screen.home

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.screen.home.PaletteModel.Action

/** The groups of Home's "Everything DeskLink does", in the order they are listed. */
enum class FeatureGroup(@StringRes val title: Int) {
    SHARE(R.string.everything_group_share),
    SCREEN(R.string.everything_group_screen),
    CALLS(R.string.everything_group_calls),
    NOTES(R.string.everything_group_notes),
    MORE(R.string.everything_group_more),
}

/** Glide with the selected computer, as Home reads it on every refresh. */
@Immutable
data class GlideFacts(
    /** The computer takes Glide (its DeskLink has it). */
    val deskCan: Boolean = true,
    /** This phone's switch "Let <computer> control this phone". */
    val on: Boolean = false,
    /** DeskLink's accessibility service, which moves the pointer here. */
    val service: Boolean = false,
    /** The computer asked this phone to turn Glide on and nobody answered yet. */
    val asking: Boolean = false,
    /** The last glide was handed back because this phone was locked. */
    val refusedLocked: Boolean = false,
)

/** What this phone has for the selected computer, beyond what [HomeOffers] says the computer has. */
@Immutable
data class PhoneFacts(
    /** Ids of the "Finish setting up" permission rows still missing ("calls", "notification_access"…). */
    val missing: Set<String> = emptySet(),
    val glide: GlideFacts = GlideFacts(),
)

/** The one thing between a feature and using it, which its row's button does. */
sealed interface FeatureStep {
    /** This phone's switch for the feature, for this computer. */
    data object TurnOn : FeatureStep

    /** A "Finish setting up" permission row ([row] is its id): its own Allow runs. */
    data class Permission(val row: String) : FeatureStep

    /** A permission only the feature's own explanation asks for. */
    data object PluginPermission : FeatureStep

    /** Glide's switch is off; [asking]: the computer asked for it. */
    data class Glide(val asking: Boolean) : FeatureStep

    /** The last glide was refused while this phone was locked: tell the computer it can try again. */
    data object GlideAgain : FeatureStep
}

sealed interface FeatureStatus {
    data object Ready : FeatureStatus
    data class Step(val step: FeatureStep) : FeatureStatus

    /** Not possible now. [why] replaces the [Blocker]'s own words when they would not fit. */
    data class Off(val blocker: Blocker, @StringRes val why: Int? = null) : FeatureStatus
}

/** What a row's one button does. */
enum class RowAction {
    /** Opens the feature: what its Home tile, palette entry or guide card does. */
    OPEN,
    /** Runs the [FeatureStep], then opens the feature when that is a page. */
    STEP,
    /** Looks for the computer again. */
    RETRY,
    /** Says why it is not possible and where it is turned on. */
    EXPLAIN,
}

/**
 * One feature. [open] is a palette id ("action:…", or "settings:<route>") and says where a ready row goes; [action] is the Home
 * action whose state the row shares ([HomeDerive.blocker]); [needs] is the permission row it waits for. [palette] and [guide]
 * are the palette actions and guide cards it stands for (a test keeps every one of them listed here).
 */
class Feature(
    val id: String,
    val group: FeatureGroup,
    @DrawableRes val icon: Int,
    @StringRes val name: Int,
    @StringRes val line: Int,
    val open: String,
    @StringRes val openLabel: Int,
    val action: String? = null,
    val needs: String? = null,
    val palette: List<String> = emptyList(),
    val guide: List<String> = emptyList(),
)

/** Home's "Everything DeskLink does": the feature table and each row's state. Pure, so a test can check every row. */
object Everything {
    const val NOTIFICATIONS = "feature:notifications"
    const val DESK_NOTIFICATIONS = "feature:desk-notifications"
    const val CALLS = "feature:calls"
    const val CONTACTS = "feature:contacts"
    const val MEDIA = "feature:media"
    const val GLIDE = "feature:glide"

    /** Opens what is playing on the computer (Home's player). */
    const val OPEN_PLAYER = "action:player"

    /** Quick Settings tiles and widgets. */
    const val ADD_TILES = "action:tiles"

    private fun settings(route: String) = "settings:$route"

    val features: List<Feature> = listOf(
        Feature("send-files", FeatureGroup.SHARE, R.drawable.sym_upload_file, R.string.home_send_files, R.string.everything_send_files_line,
            Action.SEND_FILES, R.string.everything_send, action = Action.SEND_FILES, palette = listOf(Action.SEND_FILES), guide = listOf("send")),
        Feature("send-link", FeatureGroup.SHARE, R.drawable.sym_link, R.string.home_send_link, R.string.everything_send_link_line,
            Action.SEND_LINK, R.string.everything_send, action = Action.SEND_LINK, palette = listOf(Action.SEND_LINK)),
        Feature("bucket", FeatureGroup.SHARE, R.drawable.sym_inventory, R.string.bucket_title, R.string.everything_bucket_line,
            Action.OPEN_BUCKET, R.string.settings_open, palette = listOf(Action.OPEN_BUCKET)),

        Feature("desk-screen", FeatureGroup.SCREEN, R.drawable.sym_desktop_windows, R.string.home_desk_screen, R.string.everything_desk_screen_line,
            Action.DESK_SCREEN, R.string.settings_open, action = Action.DESK_SCREEN, palette = listOf(Action.DESK_SCREEN), guide = listOf("desk_screen")),
        Feature("extend", FeatureGroup.SCREEN, R.drawable.sym_aspect_ratio, R.string.everything_extend, R.string.everything_extend_line,
            Action.DESK_SCREEN, R.string.settings_open, action = Action.DESK_SCREEN, guide = listOf("extend")),
        Feature("share-screen", FeatureGroup.SCREEN, R.drawable.sym_mobile_screen_share, R.string.home_share_screen, R.string.everything_share_screen_line,
            Action.SHARE_SCREEN, R.string.everything_share, action = Action.SHARE_SCREEN, palette = listOf(Action.SHARE_SCREEN), guide = listOf("share_screen")),
        Feature(GLIDE, FeatureGroup.SCREEN, R.drawable.sym_mouse, R.string.settings_glide_title, R.string.everything_glide_line,
            settings("control"), R.string.settings_open, guide = listOf("glide")),
        Feature("terminals", FeatureGroup.SCREEN, R.drawable.sym_terminal, R.string.tab_terminals, R.string.everything_terminals_line,
            Action.OPEN_TERMINALS, R.string.settings_open, palette = listOf(Action.OPEN_TERMINALS, Action.NEW_TERMINAL), guide = listOf("terminals")),
        Feature("lock", FeatureGroup.SCREEN, R.drawable.sym_lock, R.string.home_lock_desk, R.string.everything_lock_line,
            Action.LOCK, R.string.everything_lock, action = Action.LOCK, palette = listOf(Action.LOCK), guide = listOf("lock")),
        Feature("unlock", FeatureGroup.SCREEN, R.drawable.sym_lock_open, R.string.home_tile_unlock, R.string.everything_unlock_line,
            Action.UNLOCK, R.string.everything_unlock, action = Action.UNLOCK, palette = listOf(Action.UNLOCK), guide = listOf("lock")),

        Feature(NOTIFICATIONS, FeatureGroup.CALLS, R.drawable.sym_notifications, R.string.everything_notifications, R.string.everything_notifications_line,
            settings("notifications"), R.string.settings_open, needs = "notification_access", guide = listOf("notifications")),
        Feature(DESK_NOTIFICATIONS, FeatureGroup.CALLS, R.drawable.sym_notifications_active, R.string.everything_desk_notifications, R.string.everything_desk_notifications_line,
            settings("notifications"), R.string.settings_open, needs = "notifications"),
        Feature(CALLS, FeatureGroup.CALLS, R.drawable.sym_call, R.string.settings_perm_calls, R.string.everything_calls_line,
            settings("calls"), R.string.settings_open, needs = "calls"),
        Feature(CONTACTS, FeatureGroup.CALLS, R.drawable.sym_person, R.string.settings_perm_contacts, R.string.everything_contacts_line,
            settings("files"), R.string.settings_open, needs = "contacts"),

        Feature("clipboard", FeatureGroup.NOTES, R.drawable.sym_content_paste, R.string.home_tile_clipboard, R.string.everything_clipboard_line,
            Action.SEND_CLIPBOARD, R.string.everything_send, action = Action.SEND_CLIPBOARD, palette = listOf(Action.SEND_CLIPBOARD), guide = listOf("clipboard")),
        Feature("notes", FeatureGroup.NOTES, R.drawable.sym_edit_note, R.string.tab_notes, R.string.everything_notes_line,
            Action.OPEN_NOTES, R.string.settings_open, palette = listOf(Action.NEW_NOTE), guide = listOf("notes")),
        Feature(MEDIA, FeatureGroup.NOTES, R.drawable.sym_music_note, R.string.everything_media, R.string.everything_media_line,
            OPEN_PLAYER, R.string.settings_open),
        Feature("sound", FeatureGroup.NOTES, R.drawable.sym_mic, R.string.sg_sound, R.string.sg_sound_summary,
            settings("sound"), R.string.settings_open),

        Feature("ring", FeatureGroup.MORE, R.drawable.sym_vibration, R.string.home_ring_desk, R.string.everything_ring_line,
            Action.RING, R.string.everything_ring, action = Action.RING, palette = listOf(Action.RING), guide = listOf("find")),
        Feature("ping", FeatureGroup.MORE, R.drawable.sym_waving_hand, R.string.home_ping_desk, R.string.everything_ping_line,
            Action.PING, R.string.everything_ping, action = Action.PING, palette = listOf(Action.PING)),
        Feature("tiles", FeatureGroup.MORE, R.drawable.sym_tune, R.string.everything_tiles, R.string.everything_tiles_line,
            ADD_TILES, R.string.everything_add, guide = listOf("tiles")),
        Feature("pair", FeatureGroup.MORE, R.drawable.sym_qr_code_scanner, R.string.guide_pair_another, R.string.everything_pair_line,
            Action.PAIR_ANOTHER, R.string.everything_pair, palette = listOf(Action.PAIR_ANOTHER), guide = listOf("pair")),
        Feature("guide", FeatureGroup.MORE, R.drawable.sym_info, R.string.guide_open, R.string.guide_open_summary,
            Action.GUIDE, R.string.settings_open, palette = listOf(Action.GUIDE)),
    )

    /** The features by group, in [FeatureGroup] order. */
    val grouped: List<Pair<FeatureGroup, List<Feature>>> = FeatureGroup.entries.map { g -> g to features.filter { it.group == g } }

    /**
     * Where the feature stands: the computer's own link and offer first (exactly what Home's tile says), then this phone's switch,
     * then a missing permission. A feature that only opens a page here is never held back by the link.
     */
    fun status(f: Feature, s: HomeState): FeatureStatus {
        if (f.id == GLIDE) return glide(s)
        // A page of this phone's settings opens whatever the link: "off at the computer" counts only while it can be asked.
        val blocker = f.action?.let { HomeDerive.blocker(s, it) }
            ?: s.offers.why[f.id]?.takeIf { it != Blocker.DESK_OFF || HomeDerive.usable(s) }
        when (blocker) {
            null -> Unit
            Blocker.PHONE_OFF -> return FeatureStatus.Step(FeatureStep.TurnOn)
            Blocker.PERMISSION -> return FeatureStatus.Step(f.needs?.let(FeatureStep::Permission) ?: FeatureStep.PluginPermission)
            else -> return FeatureStatus.Off(blocker)
        }
        if (f.needs != null && f.needs in s.phone.missing) return FeatureStatus.Step(FeatureStep.Permission(f.needs))
        return FeatureStatus.Ready
    }

    /** Glide's every state, each with its one action: the link, the computer, a pending ask, the switch, the service, a refusal. */
    fun glide(s: HomeState): FeatureStatus {
        val g = s.phone.glide
        HomeDerive.whyNot(s)?.let { return FeatureStatus.Off(HomeDerive.blocker(it)) }
        return when {
            !g.deskCan -> FeatureStatus.Off(Blocker.DESK_OFF, R.string.everything_glide_cant)
            !g.on -> FeatureStatus.Step(FeatureStep.Glide(asking = g.asking))
            !g.service -> FeatureStatus.Step(FeatureStep.Permission("accessibility"))
            g.refusedLocked -> FeatureStatus.Step(FeatureStep.GlideAgain)
            else -> FeatureStatus.Ready
        }
    }

    fun action(status: FeatureStatus): RowAction = when (status) {
        FeatureStatus.Ready -> RowAction.OPEN
        is FeatureStatus.Step -> RowAction.STEP
        is FeatureStatus.Off -> if (status.why == null && HomeDerive.fix(status.blocker) == Fix.RETRY) RowAction.RETRY else RowAction.EXPLAIN
    }

    /** The button's word. */
    @StringRes
    fun label(f: Feature, status: FeatureStatus): Int = when (status) {
        FeatureStatus.Ready -> f.openLabel
        is FeatureStatus.Step -> when (val step = status.step) {
            FeatureStep.TurnOn -> R.string.home_turn_on
            is FeatureStep.Glide -> if (step.asking) R.string.settings_allow else R.string.home_turn_on
            FeatureStep.GlideAgain -> R.string.terminal_retry
            is FeatureStep.Permission, FeatureStep.PluginPermission -> R.string.settings_allow
        }
        is FeatureStatus.Off -> if (action(status) == RowAction.RETRY) R.string.terminal_retry else R.string.everything_what_to_do
    }

    /** The dim line under the name, formatted with the computer's name: what it does, the step in words, or why not. */
    @StringRes
    fun line(f: Feature, status: FeatureStatus): Int = when (status) {
        FeatureStatus.Ready -> f.line
        is FeatureStatus.Step -> when (val step = status.step) {
            FeatureStep.TurnOn -> R.string.everything_step_turn_on
            is FeatureStep.Glide -> if (step.asking) R.string.everything_glide_asking else R.string.everything_glide_off
            FeatureStep.GlideAgain -> R.string.everything_glide_locked
            FeatureStep.PluginPermission -> R.string.everything_step_permission
            is FeatureStep.Permission -> when (step.row) {
                "notification_access" -> R.string.everything_step_notification_access
                "notifications" -> R.string.everything_step_notifications
                "calls" -> R.string.everything_step_calls
                "contacts" -> R.string.everything_step_contacts
                "accessibility" -> R.string.settings_glide_accessibility
                else -> R.string.everything_step_permission
            }
        }
        is FeatureStatus.Off -> status.why ?: HomeDerive.needs(status.blocker)
    }

    /** What TalkBack says the state is. */
    @StringRes
    fun statusWord(status: FeatureStatus): Int = when (status) {
        FeatureStatus.Ready -> R.string.everything_status_ready
        is FeatureStatus.Step -> R.string.everything_status_step
        is FeatureStatus.Off -> R.string.everything_status_off
    }

    /** Whether [open] is a page (a tab or a Settings page), so it opens after a step too; an action that sends something does not. */
    /** Whether the feature's page opens after [step]: a page does, except after "try again", which only tells the computer. */
    fun opensAfter(f: Feature, step: FeatureStep): Boolean = navigates(f.open) && step != FeatureStep.GlideAgain

    fun navigates(open: String): Boolean =
        open.startsWith("settings:") || open in setOf(Action.OPEN_TERMINALS, Action.OPEN_NOTES, Action.OPEN_BUCKET, Action.GUIDE, Action.PAIR_ANOTHER)
}
