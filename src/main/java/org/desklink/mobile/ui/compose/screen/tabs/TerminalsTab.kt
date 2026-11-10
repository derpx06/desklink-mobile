/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose.screen.tabs

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.repeatOnLifecycle
import org.desklink.mobile.ui.compose.shell.LocalPageSettled
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R
import org.desklink.mobile.plugins.terminal.EndSessionDialog
import org.desklink.mobile.plugins.terminal.NewTerminalSheet
import org.desklink.mobile.plugins.terminal.TerminalRenameDialog
import org.desklink.mobile.ui.SessionGate
import org.desklink.mobile.ui.compose.DeskTextButton
import org.desklink.mobile.ui.compose.SecondaryButton
import org.desklink.mobile.ui.compose.SessionPasswordDialog
import org.desklink.mobile.ui.compose.components.DeskSearchField
import org.desklink.mobile.ui.compose.components.EmptyState
import org.desklink.mobile.ui.compose.components.LabelChip
import org.desklink.mobile.ui.compose.components.LargeTitle
import org.desklink.mobile.ui.compose.components.SectionHeader
import org.desklink.mobile.ui.compose.components.SessionPreview
import org.desklink.mobile.ui.compose.components.SessionPreviews
import org.desklink.mobile.ui.compose.components.StartedEffect
import org.desklink.mobile.ui.compose.components.StatusDot
import org.desklink.mobile.ui.compose.components.TerminalTile
import org.desklink.mobile.ui.compose.components.shimmer
import org.desklink.mobile.ui.compose.sessionPasswordLockedMessage
import org.desklink.mobile.ui.compose.tokens
import org.desklink.mobile.webrtc.SessionPasswordDenial
import org.desklink.mobile.webrtc.terminal.TerminalClientState
import org.desklink.mobile.webrtc.terminal.TerminalLinkState
import org.desklink.mobile.webrtc.terminal.TerminalSessionState

// ---- Terminals --------------------------------------------------------------------------------------------------

/** The phone-side check before terminals open (fingerprint or screen lock, if the person turned it on), per connection. */
enum class TerminalsGate { PENDING, OPEN, DENIED }

/** What the tab shows under its title. */
enum class TerminalsPanel {
    /** No terminal client for this computer (not paired, or gone). */
    UNAVAILABLE,

    /** The phone-only check was cancelled: "Locked", with Unlock. */
    GATE,

    /** The desk has terminal access off for this phone: Retry. */
    NOT_ALLOWED,

    /** The desk wants its session password: Enter password (or Retry while locked out). */
    LOCKED,

    /** Waiting for the check or the first list. */
    LOADING,

    /** The sessions, the launchers, search. */
    LIST,
}

fun terminalsPanel(hasClient: Boolean, gate: TerminalsGate, state: TerminalClientState): TerminalsPanel = when {
    !hasClient -> TerminalsPanel.UNAVAILABLE
    gate == TerminalsGate.DENIED -> TerminalsPanel.GATE
    gate == TerminalsGate.PENDING -> TerminalsPanel.LOADING
    state.notAllowed -> TerminalsPanel.NOT_ALLOWED
    state.passwordNeeded != null -> TerminalsPanel.LOCKED
    !state.listLoaded -> TerminalsPanel.LOADING
    else -> TerminalsPanel.LIST
}

/** What a card says beyond the screen's lines. */
sealed interface SessionStatus {
    data object Ended : SessionStatus
    data class Exited(val code: Long) : SessionStatus
    data object Here : SessionStatus
    data object Elsewhere : SessionStatus
}

fun sessionStatus(s: TerminalSessionState): SessionStatus? = when {
    s.gone -> SessionStatus.Ended
    s.exitStatus != null -> SessionStatus.Exited(s.exitStatus)
    s.attached -> SessionStatus.Here
    s.attachedElsewhere -> SessionStatus.Elsewhere
    else -> null
}

/** Sessions whose name matches [query] (the name as shown), all of them for a blank query. */
fun <T> matchingSessions(sessions: List<T>, query: String, name: (T) -> String): List<T> =
    if (query.isBlank()) sessions else sessions.filter { name(it).contains(query.trim(), ignoreCase = true) }

/** What the tab asks of its owner; everything else (search, the sheets and dialogs) it keeps itself. */
class TerminalsActions(
    val onOpen: (String) -> Unit = {},
    /** A launcher ("claude", "codex") or null for a shell, in a folder or null; the terminal opens once it exists. */
    val onNew: (launcher: String?, cwd: String?) -> Unit = { _, _ -> },
    val onRetry: () -> Unit = {},
    val onEnterPassword: () -> Unit = {},
    val onUnlock: () -> Unit = {},
    val onRename: (id: String, name: String) -> Unit = { _, _ -> },
    val onEnd: (id: String) -> Unit = {},
    val onRestart: (TerminalSessionState) -> Unit = {},
    /** A dimmed "new terminal" was tapped with the computer not connected: say so and look for it again (never starts anything). */
    val onUnavailable: () -> Unit = {},
)

/**
 * The one list of terminal sessions on the computer: launcher tiles (a shell, Claude Code, Codex), a dark card per session
 * (open, rename, end, restart), search, and an honest state when they are locked, not allowed or the link is down. The terminal
 * screen shows one session and lists nothing. Sessions are not attached here: only a tap opens one.
 */
@Composable
fun TerminalsTab(
    deviceId: String,
    deskName: String,
    onOpenSession: (String) -> Unit,
    onNewTerminal: (launcher: String?, cwd: String?) -> Unit,
    onMessage: (String) -> Unit = {},
) {
    org.desklink.mobile.ui.compose.SecureWindow()
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val client = remember(deviceId) { DeskLinkApplication.getInstance().terminalClient(deviceId) }
    val state by (client?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(TerminalClientState()) }).collectAsState()

    // The phone-only check, once per connection (it answers at once when already passed or not asked for).
    var gate by remember(deviceId) { mutableStateOf(if (activity == null) TerminalsGate.OPEN else TerminalsGate.PENDING) }
    var gateRuns by remember { mutableIntStateOf(0) }
    // Not while the page is only peeking in during a swipe: the prompt comes when the tab is the one selected.
    val settled = LocalPageSettled.current
    LaunchedEffect(deviceId, gateRuns, settled) {
        if (settled && activity != null && client != null) {
            gate = TerminalsGate.PENDING
            SessionGate.check(activity, deviceId, onOpen = { gate = TerminalsGate.OPEN }, onDenied = { gate = TerminalsGate.DENIED })
        }
    }

    val panel = terminalsPanel(client != null, gate, state)
    val connected = state.link == TerminalLinkState.CONNECTED
    // Sessions can also start at the desk, which is not announced: look again while this tab is on screen.
    StartedEffect(client, connected, panel) {
        while (client != null && connected && (panel == TerminalsPanel.LIST || panel == TerminalsPanel.LOADING) && gate == TerminalsGate.OPEN) {
            client.refresh()
            kotlinx.coroutines.delay(REFRESH_MS)
        }
    }
    var tick by remember { mutableIntStateOf(0) }
    var labelsRevision by remember { mutableIntStateOf(0) }
    StartedEffect(panel) {
        while (panel == TerminalsPanel.LIST) {
            kotlinx.coroutines.delay(PREVIEW_MS)
            tick++
        }
    }
    // Back from the terminal screen: names given there, and new tiles may be tapped again.
    var launching by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { labelsRevision++; launching = false }
    // A session this phone asked for from here is the terminal screen's to open; one that arrives with nobody asking (the person
    // went back first) is nobody's. Only while this tab is the foreground screen, so the screen that asked still sees its own.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(client, state.createdId, lifecycle) {
        if (client != null && state.createdId != null) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { client.consumeCreated() }
    }
    val start: (String?, String?) -> Unit = { launcher, cwd -> if (!launching) { launching = true; onNewTerminal(launcher, cwd) } }
    val labels = remember(labelsRevision, deviceId) { SessionPreviews.labels(context, deviceId) }
    val previews = remember(state, tick, labelsRevision, panel) {
        if (panel == TerminalsPanel.LIST) SessionPreviews.of(context, deviceId, lineCount = 2).associateBy { it.id } else emptyMap()
    }

    // The desk's session password: asked here, as in the terminal screen. Cancelled, the tab says "Locked" until asked again.
    var passwordDismissed by remember(state.passwordNeeded == null) { mutableStateOf(false) }
    val needed = state.passwordNeeded
    LaunchedEffect(needed) { if (needed == SessionPasswordDenial.Wrong) SessionGate.forget(context, deviceId) }
    if (client != null && gate == TerminalsGate.OPEN && needed != null && needed !is SessionPasswordDenial.Locked && !passwordDismissed) {
        SessionPasswordDialog(
            wrong = needed == SessionPasswordDenial.Wrong,
            onSubmit = { password ->
                client.unlock(password)
                activity?.let { SessionGate.offerToRemember(it, deviceId, password) }
            },
            onDismiss = {
                passwordDismissed = true
                client.cancelUnlock()
            },
        )
    }

    val ended = stringResource(R.string.terminal_snack_ended)
    val needsLink = stringResource(R.string.terminal_needs_link, deskName)
    TerminalsContent(
        deskName = deskName,
        panel = panel,
        state = state,
        previews = previews,
        labels = labels,
        busy = launching,
        actions = TerminalsActions(
            onOpen = onOpenSession,
            onNew = start,
            onRetry = { client?.refresh() },
            onEnterPassword = { passwordDismissed = false },
            onUnlock = { gateRuns++ },
            onRename = { id, name -> SessionPreviews.setLabel(context, deviceId, id, name); labelsRevision++ },
            onEnd = { id -> client?.close(id); onMessage(ended) },
            onUnavailable = { client?.refresh(); onMessage(needsLink) },
            onRestart = { session ->
                client?.close(session.id)
                // A shell in the folder it was in, not the computer's default (agent CLIs are not started from the phone).
                start(null, session.cwd)
            },
        ),
    )
}

private fun Context.findFragmentActivity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    return null
}

/** The tab with everything it shows passed in, so it can be drawn in every state. */
@Composable
fun TerminalsContent(
    deskName: String,
    panel: TerminalsPanel,
    state: TerminalClientState,
    previews: Map<String, SessionPreview>,
    /** The names people gave sessions, by id. */
    labels: Map<String, String>,
    actions: TerminalsActions,
    /** A terminal is being started: the tiles wait for it, so a second tap is not a second session. */
    busy: Boolean = false,
) {
    val t = tokens
    val context = LocalContext.current
    val connected = state.link == TerminalLinkState.CONNECTED
    val live = state.sessions.filter { !it.gone }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var newSheet by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<TerminalSessionState?>(null) }
    var ending by remember { mutableStateOf<TerminalSessionState?>(null) }
    val listed = panel == TerminalsPanel.LIST

    Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding()) {
        // At a larger font the title and its three controls do not fit one line (the title broke mid-word): the controls get their own row.
        val big = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.15f
        val controls: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
            if (listed) {
                if (state.sessions.isNotEmpty()) {
                    IconButton(
                        onClick = { searching = !searching; if (!searching) query = "" },
                        modifier = Modifier.size(48.dp).semantics { selected = searching },
                    ) {
                        Icon(painterResource(R.drawable.sym_search), contentDescription = stringResource(R.string.terminal_search), tint = t.text)
                    }
                }
                // Dimmed while the computer is away, but still answers a tap with why.
                IconButton(onClick = { if (!connected) actions.onUnavailable() else if (!busy) newSheet = true }, modifier = Modifier.size(48.dp).alpha(if (connected && !busy) 1f else 0.5f)) {
                    Icon(painterResource(R.drawable.sym_add), contentDescription = stringResource(R.string.terminal_new_terminal), tint = t.text)
                }
                LinkChip(connected)
            }
        }
        // The last session ended with the search open: nothing to search, and its button is gone.
        LaunchedEffect(state.sessions.isEmpty()) { if (state.sessions.isEmpty()) { searching = false; query = "" } }
        LargeTitle(
            stringResource(R.string.terminal_section),
            Modifier.padding(top = 12.dp),
            subtitle = when {
                panel == TerminalsPanel.UNAVAILABLE || !connected -> stringResource(R.string.terminal_status_reconnecting)
                live.isEmpty() -> stringResource(R.string.terminals_subtitle_none, deskName)
                else -> pluralStringResource(R.plurals.terminal_count_on, live.size, live.size, deskName)
            },
        ) { if (!big) controls() }
        if (big && listed) Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { controls() }
        if (listed && searching) {
            DeskSearchField(
                query, { query = it.take(80) }, stringResource(R.string.terminal_search_hint), stringResource(R.string.palette_clear),
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            )
        }
        when (panel) {
            TerminalsPanel.UNAVAILABLE -> Notice(R.drawable.sym_terminal, stringResource(R.string.terminal_section), stringResource(R.string.terminals_unavailable))
            TerminalsPanel.GATE -> Notice(
                R.drawable.sym_lock, stringResource(R.string.terminal_locked_title), stringResource(R.string.terminals_gate_message),
                action = stringResource(R.string.terminals_unlock), onAction = actions.onUnlock,
            )
            TerminalsPanel.NOT_ALLOWED -> Notice(
                R.drawable.sym_lock, stringResource(R.string.terminal_not_allowed_title), stringResource(R.string.terminal_not_allowed_message),
                action = stringResource(R.string.terminal_retry), onAction = actions.onRetry,
            )
            TerminalsPanel.LOCKED -> {
                val denial = state.passwordNeeded
                val lockedOut = denial is SessionPasswordDenial.Locked
                Notice(
                    R.drawable.sym_lock, stringResource(R.string.terminal_locked_title),
                    if (denial is SessionPasswordDenial.Locked) sessionPasswordLockedMessage(context, denial.seconds) else stringResource(R.string.session_password_message),
                    action = stringResource(if (lockedOut) R.string.terminal_retry else R.string.terminal_enter_password),
                    onAction = if (lockedOut) actions.onRetry else actions.onEnterPassword,
                )
            }
            TerminalsPanel.LOADING -> Loading()
            TerminalsPanel.LIST -> {
                val names = state.sessions.associate { s ->
                    s.id to (labels[s.id] ?: previews[s.id]?.name ?: SessionPreviews.agentName(context, s.title) ?: s.cwd?.takeIf { it.isNotBlank() } ?: s.title.ifBlank { s.id.take(8) })
                }
                val shown = matchingSessions(state.sessions, query) { names[it.id].orEmpty() }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "launchers") { NewTerminalTile(connected && !busy, actions.onNew) { if (!connected) actions.onUnavailable() } }
                    if (state.sessions.isEmpty()) {
                        item(key = "none") {
                            Text(stringResource(R.string.terminal_empty_hint), fontSize = 14.sp, lineHeight = 20.sp, color = t.textDim, modifier = Modifier.padding(horizontal = 6.dp))
                        }
                    } else {
                        item(key = "open") { SectionHeader(stringResource(R.string.terminals_open_sessions)) }
                        if (shown.isEmpty()) {
                            item(key = "nomatch") {
                                Text(stringResource(R.string.terminals_no_match, query.trim()), fontSize = 14.sp, lineHeight = 20.sp, color = t.textDim, modifier = Modifier.padding(horizontal = 6.dp))
                            }
                        }
                        items(shown, key = { it.id }) { session ->
                            val base = previews[session.id] ?: SessionPreview(
                                id = session.id,
                                name = names[session.id].orEmpty(),
                                badge = SessionPreviews.badgeOf(session.title),
                                live = false,
                                lines = emptyList(),
                                size = stringResource(R.string.terminal_session_size, session.cols, session.rows),
                            )
                            val status = when (val s = sessionStatus(session)) {
                                null -> null
                                SessionStatus.Ended -> stringResource(R.string.terminal_chip_ended)
                                is SessionStatus.Exited -> stringResource(R.string.terminal_chip_exited, s.code)
                                SessionStatus.Here -> stringResource(R.string.terminal_you_are_here)
                                SessionStatus.Elsewhere -> stringResource(R.string.terminal_open_elsewhere)
                            }
                            val named = base.copy(name = names[session.id].orEmpty())
                            val finished = session.gone || session.exitStatus != null
                            Column {
                                TerminalTile(
                                    named.copy(status = status),
                                    Modifier.fillMaxWidth(),
                                    menu = { SessionMenu(named.name, connected, onRename = { renaming = session }, onEnd = { ending = session }) },
                                ) { actions.onOpen(session.id) }
                                if (finished && connected) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        DeskTextButton(stringResource(R.string.terminal_restart), { actions.onRestart(session) })
                                    }
                                }
                            }
                        }
                        item(key = "footnote") {
                            Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(painterResource(R.drawable.sym_terminal), contentDescription = null, tint = t.textDim, modifier = Modifier.size(18.dp))
                                Text(stringResource(R.string.terminals_keep_running, deskName), fontSize = 13.sp, lineHeight = 18.sp, color = t.textDim)
                            }
                        }
                    }
                }
            }
        }
    }

    if (newSheet) {
        NewTerminalSheet(deskName, state.folders, onDismiss = { newSheet = false }) { launcher, cwd ->
            newSheet = false
            actions.onNew(launcher, cwd)
        }
    }
    renaming?.let { session ->
        TerminalRenameDialog(
            initial = labels[session.id].orEmpty(),
            onDismiss = { renaming = null },
            onConfirm = { actions.onRename(session.id, it); renaming = null },
        )
    }
    ending?.let { session ->
        EndSessionDialog(onDismiss = { ending = null }) { actions.onEnd(session.id); ending = null }
    }
}

/** The three dots on a card: rename, and end (which the computer must be reachable for). */
@Composable
private fun SessionMenu(name: String, connected: Boolean, onRename: () -> Unit, onEnd: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.sym_more_vert), contentDescription = stringResource(R.string.terminal_session_options, name), tint = Color(0xFFA1A5AE))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.terminal_rename)) }, onClick = { open = false; onRename() })
            DropdownMenuItem(text = { Text(stringResource(R.string.terminal_close_session)) }, enabled = connected, onClick = { open = false; onEnd() })
        }
    }
}

/** An explanation with at most one way forward. */
@Composable
private fun Notice(icon: Int, title: String, message: String, action: String? = null, onAction: () -> Unit = {}) {
    // Scrolls: at the largest text the explanation and its button are taller than the screen, and a button squeezed under 48 dp is no target.
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        EmptyState(icon, title, message) { if (action != null) SecondaryButton(action, onAction, tonal = true, height = 48.dp) }
    }
}

/** Loading the list: cards shaped like the real ones, with a sheen. */
@Composable
private fun Loading() {
    val label = stringResource(R.string.terminal_loading)
    Column(Modifier.fillMaxSize().padding(16.dp).semantics { contentDescription = label }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(3) { Box(Modifier.fillMaxWidth().height(132.dp).clip(RoundedCornerShape(20.dp)).shimmer()) }
    }
}

/** "Connected" with its dot, or "Reconnecting…": the link the sessions ride on. */
@Composable
private fun LinkChip(connected: Boolean) {
    val t = tokens
    val color = if (connected) t.mintText else t.amber
    Row(
        Modifier.padding(end = 8.dp, bottom = 6.dp).heightIn(min = 28.dp).background(t.surface, RoundedCornerShape(14.dp)).padding(start = 8.dp, end = 10.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        StatusDot(color)
        Text(
            stringResource(if (connected) R.string.connection_connected else R.string.terminal_status_reconnecting),
            fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (connected) t.mintText else t.text,
        )
    }
}

/** One "New terminal" tile: a shell on the computer (agent CLIs are started from it, not offered here). */
@Composable
private fun NewTerminalTile(enabled: Boolean, onStart: (String?, String?) -> Unit, onDisabled: () -> Unit) {
    LauncherTile(SessionPreviews.badgeOf(""), stringResource(R.string.terminal_new_terminal), enabled, Modifier.fillMaxWidth()) { if (enabled) onStart(null, null) else onDisabled() }
}

@Composable
private fun LauncherTile(badge: String, name: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val t = tokens
    val label = name
    Column(
        modifier
            .heightIn(min = 96.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(t.terminalBg)
            .border(1.dp, Color(0xFF262931), RoundedCornerShape(20.dp))
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            LabelChip(badge)
            Icon(painterResource(R.drawable.sym_add), contentDescription = null, tint = Color(0xFFA1A5AE), modifier = Modifier.size(18.dp))
        }
        Text(name, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE6E6E6))
    }
}

private const val REFRESH_MS = 5_000L
private const val PREVIEW_MS = 2_000L
