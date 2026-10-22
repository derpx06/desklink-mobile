/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.terminal

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R
import org.desklink.mobile.ui.SessionGate
import org.desklink.mobile.plugins.screen.SessionLiveNotification
import org.desklink.mobile.ui.compose.DeskLinkTheme
import org.desklink.mobile.ui.compose.DeskLinkTopAppBar
import org.desklink.mobile.ui.compose.DeskLinkType
import org.desklink.mobile.ui.compose.MonoFamily
import org.desklink.mobile.ui.compose.SessionPasswordDialog
import org.desklink.mobile.ui.compose.components.EmptyState
import org.desklink.mobile.ui.compose.components.EmptyTerminalTile
import org.desklink.mobile.ui.compose.components.Hairline
import org.desklink.mobile.ui.compose.components.LabelChip
import org.desklink.mobile.ui.compose.components.LargeTitle
import org.desklink.mobile.ui.compose.components.PillButton
import org.desklink.mobile.ui.compose.components.PresenceDot
import org.desklink.mobile.ui.compose.components.ScreenHeader
import org.desklink.mobile.ui.compose.components.SessionPreview
import org.desklink.mobile.ui.compose.components.SessionPreviews
import org.desklink.mobile.ui.compose.components.TabularNumbers
import org.desklink.mobile.ui.compose.components.TerminalTile
import org.desklink.mobile.ui.compose.components.connectionStatsLabel
import org.desklink.mobile.ui.compose.components.rememberConnectionStats
import org.desklink.mobile.ui.compose.components.shimmer
import org.desklink.mobile.ui.compose.sessionPasswordLockedMessage
import org.desklink.mobile.ui.compose.tokens
import org.desklink.mobile.webrtc.SessionPasswordDenial
import org.desklink.mobile.webrtc.terminal.TerminalClient
import org.desklink.mobile.webrtc.terminal.TerminalClientState
import org.desklink.mobile.webrtc.terminal.TerminalLinkState
import org.desklink.mobile.webrtc.terminal.TerminalProtocol
import org.desklink.mobile.webrtc.terminal.TerminalSessionState

/**
 * Terminal sessions on the paired desktop: a session list, and a full-screen terminal with a key
 * bar. All state that must survive rotation lives in [TerminalClient], which the application owns.
 */
class TerminalActivity : AppCompatActivity() {
    private var deviceId: String = ""
    /** Looked up each time: the application owns the client, per device. */
    private val client: TerminalClient? get() = DeskLinkApplication.getInstance().terminalClient(deviceId)
    private var openId by mutableStateOf<String?>(null)
    private var resumed by mutableStateOf(false)

    /** The session screen without the app bar or the phone's status and navigation bars. */
    private var fullscreen by mutableStateOf(true)
    private val labels = mutableStateMapOf<String, String>()

    /** Recognised speech waiting to be typed into the open terminal. */
    private var dictated by mutableStateOf<String?>(null)
    private val dictation = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            dictated = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.ifEmpty { null }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID).orEmpty()
        val terminalClient = DeskLinkApplication.getInstance().terminalClient(deviceId)
        if (terminalClient == null) {
            Toast.makeText(this, R.string.terminal_device_unavailable, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        TerminalPalette.apply(
            (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES,
        )
        openId = savedInstanceState?.getString(STATE_OPEN_ID) ?: intent.getStringExtra(EXTRA_TERMINAL_ID)
        loadLabels()
        fullscreen = getSharedPreferences(LABEL_PREFS, MODE_PRIVATE).getBoolean(KEY_FULLSCREEN, true)
        SessionGate.check(this, deviceId, onOpen = {
            setContent {
                DeskLinkTheme(this) {
                    TerminalRoot(terminalClient)
                }
            }
        }, onDenied = ::finish)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra(EXTRA_DEVICE_ID).orEmpty() != deviceId) {
            recreate() // a notification for another device
        } else {
            intent.getStringExtra(EXTRA_TERMINAL_ID)?.let { openId = it }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        openId?.let { outState.putString(STATE_OPEN_ID, it) }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // Seen: the widget's "finished" badge goes.
        org.desklink.mobile.ui.surfaces.DeskSurfaces.clearFinished(this, deviceId)
    }

    override fun onPause() {
        resumed = false
        TerminalForeground.terminalId = null
        super.onPause()
    }

    override fun onDestroy() {
        // Leaving for good stops output; a rotation or a background stay keeps it flowing so
        // notifications still fire and the screen is current when the person returns.
        if (isFinishing && !isChangingConfigurations) {
            client?.detachAll()
            SessionLiveNotification.refresh(this, deviceId)
        }
        super.onDestroy()
    }

    private fun rememberFullscreen(on: Boolean) {
        fullscreen = on
        getSharedPreferences(LABEL_PREFS, MODE_PRIVATE).edit { putBoolean(KEY_FULLSCREEN, on) }
    }

    /** Swipe from an edge brings the bars back for a moment, as in the remote control. */
    private fun setSystemBarsHidden(hidden: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (hidden) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** The recognizer activity owns the microphone, so this app needs no audio permission. */
    private fun dictate() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.terminal_dictate_prompt))
        try {
            dictation.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.terminal_dictate_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadLabels() {
        val prefs = getSharedPreferences(LABEL_PREFS, MODE_PRIVATE)
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("$deviceId/") && value is String) labels[key.removePrefix("$deviceId/")] = value
        }
    }

    private fun setLabel(id: String, label: String) {
        val trimmed = label.trim().take(60)
        if (trimmed.isEmpty()) labels.remove(id) else labels[id] = trimmed
        getSharedPreferences(LABEL_PREFS, MODE_PRIVATE).edit {
            if (trimmed.isEmpty()) remove("$deviceId/$id") else putString("$deviceId/$id", trimmed)
        }
    }

    // ---- root ---------------------------------------------------------------------------------------

    @Composable
    private fun TerminalRoot(client: TerminalClient) {
        val state by client.state.collectAsState()
        val snackbar = remember { SnackbarHostState() }
        val current = openId

        LaunchedEffect(Unit) { client.refresh() }
        LaunchedEffect(current, resumed) { TerminalForeground.terminalId = if (resumed) current else null }
        // The coral notification with End while this phone has terminals open.
        val attached = state.sessions.count { it.attached && !it.gone }
        LaunchedEffect(attached) { SessionLiveNotification.refresh(this@TerminalActivity, deviceId) }
        LaunchedEffect(state.createdId) {
            state.createdId?.let {
                openId = it
                client.consumeCreated()
            }
        }
        LaunchedEffect(state.error) {
            state.error?.let {
                snackbar.showSnackbar(if (it == TerminalProtocol.ERROR_BAD_CWD) getString(R.string.terminal_bad_cwd, deskName()) else it)
                client.clearError()
            }
        }
        // Ended from the desk: back to the list, which the client is already refreshing. The
        // session is not attached again until the person opens it.
        LaunchedEffect(state.endedByComputer) {
            if (state.endedByComputer) {
                openId?.let { leaveTerminal(client, it) }
                // Shown before the event is consumed: consuming changes this effect's key, which
                // would cancel it mid-way and the message would never appear.
                snackbar.showSnackbar(getString(R.string.terminal_ended_by_computer))
                client.consumeEnded()
            }
        }

        // Cancelled: the list shows "Locked" until the person asks for the dialog again.
        var passwordDismissed by remember(state.passwordNeeded == null) { mutableStateOf(false) }

        if (current == null) {
            SessionListScreen(client, state, snackbar) { passwordDismissed = false }
        } else {
            BackHandler { leaveTerminal(client, current) }
            TerminalScreenUi(client, state, current, snackbar)
        }

        val needed = state.passwordNeeded
        // A remembered password that the desk refuses is never offered again.
        LaunchedEffect(needed) { if (needed == SessionPasswordDenial.Wrong) SessionGate.forget(this@TerminalActivity, deviceId) }
        if (needed != null && needed !is SessionPasswordDenial.Locked && !passwordDismissed) {
            SessionPasswordDialog(
                wrong = needed == SessionPasswordDenial.Wrong,
                onSubmit = { password ->
                    client.unlock(password)
                    SessionGate.offerToRemember(this@TerminalActivity, deviceId, password)
                },
                onDismiss = {
                    passwordDismissed = true
                    client.cancelUnlock()
                    openId?.let { leaveTerminal(client, it) }
                },
            )
        }
    }

    private fun leaveTerminal(client: TerminalClient, id: String) {
        client.leave(id)
        openId = null
    }

    // ---- session list ---------------------------------------------------------------------------------

    @Composable
    private fun SessionListScreen(
        client: TerminalClient,
        state: TerminalClientState,
        snackbar: SnackbarHostState,
        onEnterPassword: () -> Unit,
    ) {
        var renaming by remember { mutableStateOf<TerminalSessionState?>(null) }
        var closing by remember { mutableStateOf<TerminalSessionState?>(null) }
        var newSheet by rememberSaveable { mutableStateOf(false) }
        var searching by rememberSaveable { mutableStateOf(false) }
        var query by rememberSaveable { mutableStateOf("") }
        val connected = state.link == TerminalLinkState.CONNECTED
        val locked = state.passwordNeeded != null
        val t = tokens
        // Sessions can also start at the desk, which the desktop does not announce: look again
        // while the list is on screen. Not while it is locked: only the person retries that.
        LaunchedEffect(connected, locked) {
            while (connected && !locked) {
                client.refresh()
                kotlinx.coroutines.delay(LIST_REFRESH_MS)
            }
        }
        val live = state.sessions.filter { !it.gone }

        Box(Modifier.fillMaxSize().background(t.bg).safeDrawingPadding()) {
            Column(Modifier.fillMaxSize()) {
                ScreenHeader(onBack = { finish() })
                LargeTitle(
                    stringResource(R.string.terminal_section),
                    subtitle = if (connected) {
                        pluralStringResource(R.plurals.terminal_count_on, live.size, live.size, deskName())
                    } else {
                        stringResource(R.string.terminal_status_reconnecting)
                    },
                ) {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.sym_search), contentDescription = stringResource(R.string.terminal_search), tint = t.text)
                    }
                }
                if (searching) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it.take(80) },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.terminal_search_hint)) },
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                    )
                }
                if (!connected && state.listLoaded) ReconnectingBanner()
                when {
                    state.notAllowed -> StatePanel(
                        title = stringResource(R.string.terminal_not_allowed_title),
                        message = stringResource(R.string.terminal_not_allowed_message),
                        action = stringResource(R.string.terminal_retry),
                        onAction = { client.refresh() },
                    )
                    state.passwordNeeded != null -> Locked(state.passwordNeeded, onEnterPassword, onRetry = { client.refresh() })
                    !state.listLoaded -> CenteredProgress()
                    state.sessions.isEmpty() -> Column(Modifier.padding(16.dp)) {
                        EmptyTerminalTile(
                            launchers = listOf(stringResource(R.string.terminal_launch_shell) to { client.create(DEFAULT_ROWS, DEFAULT_COLS, null) }) +
                                state.launchers.mapNotNull { launcher -> agentName(launcher)?.let { it to { client.create(DEFAULT_ROWS, DEFAULT_COLS, listOf(launcher)) } } },
                            enabled = connected,
                        )
                    }
                    else -> {
                        // Previews re-read the screens now and then: output arrives without a state change.
                        var tick by remember { mutableIntStateOf(0) }
                        LaunchedEffect(Unit) {
                            while (true) {
                                kotlinx.coroutines.delay(PREVIEW_REFRESH_MS)
                                tick++
                            }
                        }
                        val previews = remember(state, tick, labels.toMap()) { SessionPreviews.of(this@TerminalActivity, deviceId, lineCount = 2) }.associateBy { it.id }
                        val shown = state.sessions.filter { s ->
                            query.isBlank() || (labels[s.id] ?: s.title).contains(query.trim(), ignoreCase = true)
                        }
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            // Clear of the floating button.
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(shown, key = { it.id }) { session ->
                                val preview = previews[session.id] ?: SessionPreview(
                                    id = session.id,
                                    name = labels[session.id] ?: agentName(session.title) ?: session.title.ifBlank { session.id.take(8) },
                                    badge = SessionPreviews.badgeOf(session.title),
                                    live = false,
                                    lines = emptyList(),
                                    size = stringResource(R.string.terminal_session_size, session.cols, session.rows),
                                )
                                SessionCard(
                                    session = session,
                                    preview = preview,
                                    connected = connected,
                                    onOpen = { openId = session.id },
                                    onRename = { renaming = session },
                                    onClose = { closing = session },
                                    onRestart = {
                                        client.close(session.id)
                                        client.create(DEFAULT_ROWS, DEFAULT_COLS, session.title.takeIf { it in TerminalProtocol.KNOWN_LAUNCHERS }?.let(::listOf))
                                    },
                                )
                            }
                        }
                    }
                }
            }
            if (!state.notAllowed && !locked) {
                NewTerminalButton(
                    Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    onClick = { if (connected) newSheet = true },
                )
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }

        if (newSheet) NewTerminalSheet(state.launchers, state.folders, onDismiss = { newSheet = false }) { command, cwd -> newSheet = false; client.create(DEFAULT_ROWS, DEFAULT_COLS, command, cwd) }
        renaming?.let { session ->
            RenameDialog(
                initial = labels[session.id].orEmpty(),
                onDismiss = { renaming = null },
                onConfirm = { setLabel(session.id, it); renaming = null },
            )
        }
        closing?.let { session ->
            AlertDialog(
                onDismissRequest = { closing = null },
                title = { Text(stringResource(R.string.terminal_close_title)) },
                text = { Text(stringResource(R.string.terminal_close_message)) },
                confirmButton = {
                    TextButton(
                        onClick = { client.close(session.id); closing = null },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text(stringResource(R.string.terminal_close_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { closing = null }) { Text(stringResource(android.R.string.cancel)) }
                },
            )
        }
    }

    private fun deskName(): String = DeskLinkApplication.getInstance().getDevice(deviceId)?.name.orEmpty()

    @Composable
    private fun NewTerminalButton(modifier: Modifier, onClick: () -> Unit) {
        val t = tokens
        Row(
            modifier
                .shadow(8.dp, RoundedCornerShape(18.dp))
                .height(56.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(t.accentContainer)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(start = 16.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(painterResource(R.drawable.sym_add), contentDescription = null, tint = t.onAccentContainer)
            Text(stringResource(R.string.terminal_new_terminal), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = t.onAccentContainer)
        }
    }

    /**
     * What to start: a shell or one of the agent CLIs the desktop has. The "Start in" folder chips
     * go between the choice and the button once the desktop reports folders.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun NewTerminalSheet(launchers: List<String>, folders: List<String>, onDismiss: () -> Unit, onStart: (List<String>?, String?) -> Unit) {
        val t = tokens
        val options = listOf<Pair<String, List<String>?>>(stringResource(R.string.terminal_launch_shell) to null) +
            launchers.mapNotNull { launcher -> agentName(launcher)?.let { it to listOf(launcher) } }
        var choice by rememberSaveable { mutableIntStateOf(0) }
        // "Start in": the desk's folders ("~" first), the first one picked. An older desk sends none.
        var folder by rememberSaveable { mutableIntStateOf(0) }
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = t.surface2,
            // The stock handle: TalkBack names it and it expands or collapses the sheet.
            dragHandle = { androidx.compose.material3.BottomSheetDefaults.DragHandle(width = 32.dp, height = 4.dp, color = t.hairlineStrong) },
        ) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.terminal_new_terminal), fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.terminal_new_runs_on, deskName()), fontSize = 14.sp, color = t.textDim)
                }
                Text(stringResource(R.string.terminal_new_start), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = t.textDim)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.selectableGroup()) {
                    options.forEachIndexed { index, (label, _) ->
                        val selected = index == choice
                        Box(
                            Modifier
                                .heightIn(min = 48.dp)
                                .selectable(selected = selected, role = Role.RadioButton) { choice = index },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                fontSize = 14.sp,
                                color = if (selected) t.onAccentContainer else t.text,
                                modifier = Modifier
                                    .background(if (selected) t.accentContainer else Color.Transparent, RoundedCornerShape(18.dp))
                                    .border(1.dp, if (selected) Color.Transparent else t.hairlineStrong, RoundedCornerShape(18.dp))
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
                if (folders.isNotEmpty()) {
                    Text(stringResource(R.string.terminal_new_start_in), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = t.textDim)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.selectableGroup()) {
                        folders.forEachIndexed { index, path ->
                            val selected = index == folder
                            Box(
                                Modifier
                                    .heightIn(min = 48.dp)
                                    .selectable(selected = selected, role = Role.RadioButton) { folder = index },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    path,
                                    fontFamily = MonoFamily,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.MiddleEllipsis,
                                    color = if (selected) t.onAccentContainer else t.text,
                                    modifier = Modifier
                                        .widthIn(max = 220.dp)
                                        .background(if (selected) t.accentContainer else Color.Transparent, RoundedCornerShape(18.dp))
                                        .border(1.dp, if (selected) Color.Transparent else t.hairlineStrong, RoundedCornerShape(18.dp))
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                )
                            }
                        }
                    }
                }
                PillButton(
                    stringResource(R.string.terminal_new_start_button),
                    { onStart(options.getOrNull(choice)?.second, folders.getOrNull(folder)) },
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    icon = R.drawable.sym_terminal,
                )
            }
        }
    }

    @Composable
    private fun agentName(title: String): String? = when (title) {
        TerminalProtocol.LAUNCHER_CLAUDE -> stringResource(R.string.terminal_launcher_claude)
        TerminalProtocol.LAUNCHER_CODEX -> stringResource(R.string.terminal_launcher_codex)
        else -> null
    }

    /** A session as a dark card: name, its last lines, and whether this phone has it open. */
    @Composable
    private fun SessionCard(
        session: TerminalSessionState,
        preview: SessionPreview,
        connected: Boolean,
        onOpen: () -> Unit,
        onRename: () -> Unit,
        onClose: () -> Unit,
        onRestart: () -> Unit,
    ) {
        val finished = session.gone || session.exitStatus != null
        var menu by remember { mutableStateOf(false) }
        val here = session.attached && !finished
        Column {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(TermBg)
                    .then(if (here) Modifier.border(1.5.dp, TermCoral.copy(alpha = 0.6f), RoundedCornerShape(24.dp)) else Modifier)
                    .clickable(onClickLabel = stringResource(R.string.terminal_open_session), onClick = onOpen)
                    .padding(start = 18.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Its folder names a plain shell best; a name given here or an agent's name wins.
                val cardTitle = labels[session.id] ?: agentName(session.title) ?: session.cwd ?: preview.name
                val meta = listOfNotNull(session.shell, session.startedAt?.let { startedLabel(it) }).joinToString(" · ")
                    .ifEmpty { "${preview.badge} · ${preview.size}" }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(cardTitle, fontFamily = MonoFamily, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TermText, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
                    Text(meta, fontFamily = MonoFamily, fontSize = 11.sp, color = TermDim, maxLines = 1)
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                            Icon(painterResource(R.drawable.sym_more_vert), contentDescription = stringResource(R.string.terminal_session_options, preview.name), tint = TermIcon)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.terminal_rename)) }, onClick = { menu = false; onRename() })
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.terminal_close_session)) },
                                enabled = connected,
                                onClick = { menu = false; onClose() },
                            )
                        }
                    }
                }
                Column(Modifier.padding(end = 14.dp)) {
                    if (preview.lines.isEmpty()) {
                        Text(stringResource(R.string.terminal_preview_open), fontFamily = MonoFamily, fontSize = 11.sp, lineHeight = 16.sp, color = TermDim, maxLines = 1)
                    } else {
                        preview.lines.takeLast(2).forEach {
                            Text(it, fontFamily = MonoFamily, fontSize = 11.sp, lineHeight = 16.sp, color = TermDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Row(Modifier.heightIn(min = 40.dp).padding(end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        finished -> Text(
                            if (session.gone) stringResource(R.string.terminal_chip_ended) else stringResource(R.string.terminal_chip_exited, session.exitStatus ?: 0L),
                            fontSize = 12.sp,
                            color = TermDim,
                        )
                        here -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PresenceDot(color = TermCoral)
                            Text(stringResource(R.string.terminal_you_are_here), fontSize = 12.sp, color = TermCoralText)
                        }
                        session.attachedElsewhere -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PresenceDot(color = TermCoral)
                            Text(stringResource(R.string.terminal_open_elsewhere), fontSize = 12.sp, color = TermCoralText)
                        }
                        else -> Text(stringResource(R.string.terminal_running_on, deskName()), fontSize = 12.sp, color = TermDim)
                    }
                    Spacer(Modifier.weight(1f))
                    Icon(painterResource(R.drawable.sym_arrow_forward), contentDescription = null, tint = TermIcon, modifier = Modifier.size(20.dp))
                }
            }
            if (finished && connected) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onRestart) { Text(stringResource(R.string.terminal_restart)) }
                }
            }
        }
    }

    /** "10:42" today, "Mon 10:42" otherwise. */
    private fun startedLabel(unixSecs: Long): String {
        val millis = unixSecs * 1000
        val time = android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(millis))
        return if (android.text.format.DateUtils.isToday(millis)) time else
            android.text.format.DateUtils.formatDateTime(this, millis, android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY or android.text.format.DateUtils.FORMAT_ABBREV_WEEKDAY) + " " + time
    }

    /** The desktop wants its session password. During a lockout only a retry is offered. */
    @Composable
    private fun Locked(denial: SessionPasswordDenial, onEnterPassword: () -> Unit, onRetry: () -> Unit) {
        val lockedOut = denial is SessionPasswordDenial.Locked
        StatePanel(
            title = stringResource(R.string.terminal_locked_title),
            message = if (denial is SessionPasswordDenial.Locked) {
                sessionPasswordLockedMessage(this@TerminalActivity, denial.seconds)
            } else {
                stringResource(R.string.session_password_message)
            },
            action = stringResource(if (lockedOut) R.string.terminal_retry else R.string.terminal_enter_password),
            onAction = if (lockedOut) onRetry else onEnterPassword,
        )
    }

    /** A centred explanation with one way forward: access turned off, or a password wanted. */
    @Composable
    private fun StatePanel(title: String, message: String, action: String, onAction: () -> Unit) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(icon = R.drawable.sym_lock, title = title, body = message) {
                Button(onClick = onAction) { Text(action) }
            }
        }
    }

    /** Loading the list: cards shaped like the real ones, with a sheen. */
    @Composable
    private fun CenteredProgress() {
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp)
                .semantics { contentDescription = getString(R.string.terminal_loading) },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            repeat(3) {
                Box(Modifier.fillMaxWidth().height(132.dp).clip(RoundedCornerShape(24.dp)).shimmer())
            }
        }
    }

    @Composable
    private fun ReconnectingBanner(dark: Boolean = false) {
        Surface(color = if (dark) TermBar else tokens.surface2, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = if (dark) TermAccent else tokens.accent)
                Spacer(Modifier.size(12.dp))
                Text(stringResource(R.string.terminal_reconnecting), style = MaterialTheme.typography.bodyMedium, color = if (dark) TermIcon else tokens.textDim)
            }
        }
    }

    @Composable
    private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
        var text by remember { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.terminal_rename)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(60) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.terminal_rename_hint)) },
                )
            },
            confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(stringResource(android.R.string.ok)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    // ---- terminal ------------------------------------------------------------------------------------------

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun TerminalScreenUi(client: TerminalClient, state: TerminalClientState, id: String, snackbar: SnackbarHostState) {
        val context = LocalContext.current
        val session = state.sessions.firstOrNull { it.id == id }
        val connected = state.link == TerminalLinkState.CONNECTED
        val screen = remember(id) {
            (client.sink(id, DEFAULT_ROWS, DEFAULT_COLS) as TerminalScreen).also { s ->
                // A screen made before the palette was set keeps the stock one until reset.
                s.withEmulator { it.mColors.reset() }
            }
        }
        var ctrl by remember { mutableStateOf(Sticky.OFF) }
        var alt by remember { mutableStateOf(Sticky.OFF) }
        var viewRef by remember { mutableStateOf<TerminalView?>(null) }
        var menu by remember { mutableStateOf(false) }
        var newSheet by rememberSaveable { mutableStateOf(false) }
        var composing by rememberSaveable { mutableStateOf(false) }
        var draft by rememberSaveable { mutableStateOf("") }
        val imeVisible = WindowInsets.isImeVisible
        val canDictate = remember { SpeechRecognizer.isRecognitionAvailable(context) }
        val resumes = rememberResumes()

        val host = remember(id) {
            object : TerminalView.Host {
                override fun send(bytes: ByteArray) = client.sendInput(id, bytes)

                override fun takeModifiers(): TerminalView.Modifiers {
                    val taken = TerminalView.Modifiers(ctrl.active, alt.active)
                    ctrl = ctrl.afterUse()
                    alt = alt.afterUse()
                    return taken
                }

                override fun onGridChanged(rows: Int, cols: Int) = client.attachOrResize(id, rows, cols)

                override fun onTap() {
                    if (!composing) viewRef?.showKeyboard()
                }

                override fun onFontSizeChanged(sizePx: Int) = Unit
            }
        }

        val name = labels[id] ?: session?.title?.let { agentName(it) ?: it.ifBlank { null } } ?: id.take(8)
        val rttMs = rememberConnectionStats(deviceId)?.roundTripTimeMs
        val subtitle = when {
            !connected -> stringResource(R.string.terminal_status_reconnecting)
            session == null || session.gone -> stringResource(R.string.terminal_chip_ended)
            session.exitStatus != null -> stringResource(R.string.terminal_chip_exited, session.exitStatus)
            else -> null
        }

        // Typed like the key bar's symbols, armed modifiers included, and never followed by Enter:
        // the person reads what was recognised before running it.
        val type = { text: String ->
            val m = host.takeModifiers()
            host.send(TerminalKeys.text(text, m.ctrl, m.alt))
        }
        /** A quick command or the compose bar: the text, then Enter. Several lines go as one paste. */
        val run = { text: String ->
            if ('\n' in text) screen.withEmulator { it.paste(text) } else host.send(TerminalKeys.text(text))
            host.send(byteArrayOf('\r'.code.toByte()))
        }
        LaunchedEffect(dictated) {
            dictated?.let {
                dictated = null
                if (composing) draft += it else type(it)
            }
        }

        org.desklink.mobile.ui.compose.components.DarkSystemBars()
        DisposableEffect(fullscreen) {
            setSystemBarsHidden(fullscreen)
            onDispose { setSystemBarsHidden(false) }
        }

        Box(Modifier.fillMaxSize().background(TermBg).safeDrawingPadding()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { leaveTerminal(client, id) }, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.sym_arrow_back), contentDescription = stringResource(R.string.terminal_back_to_sessions), tint = TermText)
                    }
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(name, fontFamily = MonoFamily, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TermText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(deskName(), fontSize = 12.sp, color = TermIcon, maxLines = 1)
                            val meta = subtitle ?: rttMs?.let { stringResource(R.string.home_meta_rtt, it.toInt()) }
                            meta?.let {
                                Text(" · ", fontSize = 12.sp, color = TermIcon)
                                Text(it, fontFamily = if (subtitle == null) MonoFamily else null, fontSize = 12.sp, color = TermIcon, maxLines = 1)
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                            Icon(painterResource(R.drawable.sym_more_vert), contentDescription = stringResource(R.string.terminal_session_options, name), tint = TermText)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            // Shown when the session is not receiving output and nothing is about to change that.
                            if (connected && !state.notAllowed && session != null && !session.gone && !session.wanted && !session.attached && session.exitStatus == null) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.terminal_reattach)) }, onClick = { menu = false; client.reattach(id) })
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(if (fullscreen) R.string.terminal_show_system_bars else R.string.remote_fullscreen)) },
                                onClick = { menu = false; rememberFullscreen(!fullscreen) },
                            )
                            DropdownMenuItem(text = { Text(stringResource(R.string.terminal_paste)) }, onClick = { menu = false; paste(context, screen, snackbar) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.terminal_rename)) }, onClick = { menu = false; renamingOpen = id })
                        }
                    }
                }
                SessionChips(state, id, onPick = { other -> leaveTerminal(client, id); openId = other }, onNew = { newSheet = true })
                if (state.notAllowed) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.terminal_not_allowed_message), modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                } else if (!connected) {
                    ReconnectingBanner(dark = true)
                }
                AndroidView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // The view scrolls its own content: without a clip it paints over the header.
                        .clipToBounds()
                        .background(TermBg)
                        .padding(horizontal = 8.dp),
                    factory = { ctx -> TerminalView(ctx, host).also { it.attach(screen); viewRef = it } },
                )
                if (composing) {
                    // F6: the synced snippets, one tap into the draft.
                    val notesRevision by org.desklink.mobile.plugins.notes.NotesStore.revision.collectAsState()
                    val snippets = remember(notesRevision) { org.desklink.mobile.plugins.notes.NotesStore.snippets(context).take(30) }
                    if (snippets.isNotEmpty()) SnippetChips(snippets) { draft = (draft + it).take(MAX_PASTE_CHARS) }
                }
                if (composing) ComposeBar(draft, { draft = it }, enabled = connected) {
                    if (draft.isNotEmpty()) {
                        run(draft)
                        draft = ""
                    }
                }
                KeyBar(
                    ctrl = ctrl,
                    alt = alt,
                    imeVisible = imeVisible,
                    composing = composing,
                    onCompose = { composing = !composing; if (!composing) viewRef?.showKeyboard() },
                    onCtrl = { ctrl = ctrl.tap() },
                    onCtrlLong = { ctrl = ctrl.longPress() },
                    onAlt = { alt = alt.tap() },
                    onAltLong = { alt = alt.longPress() },
                    onKey = { keyCode ->
                        val cursorApp = screen.withEmulator { it.isCursorKeysApplicationMode }
                        val m = host.takeModifiers()
                        TerminalKeys.key(keyCode, ctrl = m.ctrl, alt = m.alt, cursorApp = cursorApp)?.let(host::send)
                    },
                    onArrow = { keyCode ->
                        // Modifiers are taken once per press: a held Ctrl+← repeats as Ctrl+←.
                        val m = host.takeModifiers()
                        val send = {
                            val cursorApp = screen.withEmulator { it.isCursorKeysApplicationMode }
                            TerminalKeys.key(keyCode, ctrl = m.ctrl, alt = m.alt, cursorApp = cursorApp)?.let(host::send)
                            Unit
                        }
                        send
                    },
                    onText = type,
                    onMic = if (canDictate) ::dictate else null,
                    onPaste = { paste(context, screen, snackbar) },
                    onKeyboard = {
                        viewRef?.let { view ->
                            // Asked of the window at click time: the composed value lags on older Android.
                            val shown = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) ?: imeVisible
                            if (shown) view.hideKeyboard() else view.showKeyboard()
                        }
                    },
                )
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp))
        }
        if (newSheet) NewTerminalSheet(state.launchers, state.folders, onDismiss = { newSheet = false }) { command, cwd -> newSheet = false; client.create(DEFAULT_ROWS, DEFAULT_COLS, command, cwd) }
        renamingOpen?.let { renameId ->
            RenameDialog(
                initial = labels[renameId].orEmpty(),
                onDismiss = { renamingOpen = null },
                onConfirm = { setLabel(renameId, it); renamingOpen = null },
            )
        }
    }

    private var renamingOpen by mutableStateOf<String?>(null)

    @Composable
    private fun rememberResumes(): Int {
        var resumes by remember { mutableIntStateOf(0) }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
        return resumes
    }

    /** Every session, the open one lit; "+" starts another. Replaces the old title switcher. */
    @Composable
    private fun SessionChips(state: TerminalClientState, current: String, onPick: (String) -> Unit, onNew: () -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.sessions.filter { !it.gone }.forEach { s ->
                val selected = s.id == current
                val label = labels[s.id] ?: agentName(s.title) ?: s.title.ifBlank { s.id.take(8) }
                Box(
                    Modifier
                        .heightIn(min = 48.dp)
                        .selectable(selected = selected, role = Role.Tab) { if (!selected) onPick(s.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        Modifier
                            .height(32.dp)
                            .background(if (selected) TermChipOn else TermChip, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (s.attached && !selected) PresenceDot(color = TermCoral)
                        Text(label, fontFamily = MonoFamily, fontSize = 12.sp, color = if (selected) TermChipOnText else TermText, maxLines = 1)
                    }
                }
            }
            Box(
                Modifier
                    .size(48.dp)
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.terminal_new_terminal), onClick = onNew)
                    .semantics { contentDescription = getString(R.string.terminal_new_terminal) },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(32.dp).background(TermChip, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.sym_add), contentDescription = null, tint = TermText, modifier = Modifier.size(18.dp))
                }
            }
        }
    }

    /**
     * Type a whole command before sending it: a plain multi-line field (never a password type, which
     * blacks out screen capture on ColorOS). Send writes it, then Enter.
     */
    @Composable
    private fun ComposeBar(text: String, onText: (String) -> Unit, enabled: Boolean, onSend: () -> Unit) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                .heightIn(min = 48.dp)
                .background(TermField, RoundedCornerShape(24.dp))
                .padding(start = 14.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("❯", fontFamily = MonoFamily, fontSize = 13.sp, color = TermPrompt)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) Text(stringResource(R.string.terminal_compose_hint), fontFamily = MonoFamily, fontSize = 13.sp, color = TermDim)
                BasicTextField(
                    value = text,
                    onValueChange = { onText(it.take(MAX_PASTE_CHARS)) },
                    textStyle = TextStyle(fontFamily = MonoFamily, fontSize = 13.sp, color = TermText),
                    cursorBrush = SolidColor(TermAccent),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = getString(R.string.terminal_compose_hint) },
                )
            }
            Box(
                Modifier
                    .size(48.dp)
                    .clickable(enabled = enabled && text.isNotEmpty(), role = Role.Button, onClick = onSend)
                    .semantics { contentDescription = getString(R.string.terminal_compose_send) },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(38.dp).background(TermAccent, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.sym_keyboard_return), contentDescription = null, tint = TermOnAccent, modifier = Modifier.size(20.dp))
                }
            }
        }
    }

    private fun paste(context: Context, screen: TerminalScreen, snackbar: SnackbarHostState) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        when {
            text.isNullOrEmpty() -> lifecycleScope.launch { snackbar.showSnackbar(getString(R.string.terminal_paste_empty)) }
            text.length > MAX_PASTE_CHARS -> lifecycleScope.launch { snackbar.showSnackbar(getString(R.string.terminal_paste_too_large)) }
            // The emulator applies bracketed-paste mode and normalises newlines, exactly as a desktop terminal does.
            else -> screen.withEmulator { it.paste(text) }
        }
    }

    // ---- key bar ------------------------------------------------------------------------------------------

    /**
     * Row one is fixed: Ctrl, Alt and the four arrows always in reach (Esc and Tab too where eight
     * 48dp keys fit). Row two scrolls through the rest, with compose, dictation and the keyboard
     * pinned at its end.
     */
    @Composable
    private fun KeyBar(
        ctrl: Sticky,
        alt: Sticky,
        imeVisible: Boolean,
        composing: Boolean,
        onCompose: () -> Unit,
        onCtrl: () -> Unit,
        onCtrlLong: () -> Unit,
        onAlt: () -> Unit,
        onAltLong: () -> Unit,
        onKey: (Int) -> Unit,
        /** Starts a press of an arrow; the returned sender repeats it while held. */
        onArrow: (Int) -> (() -> Unit),
        onText: (String) -> Unit,
        onMic: (() -> Unit)?,
        onPaste: () -> Unit,
        onKeyboard: () -> Unit,
    ) {
        var functionRow by remember { mutableStateOf(false) }
        BoxWithConstraints(Modifier.fillMaxWidth().background(TermBar).padding(vertical = 2.dp)) {
            val roomy = maxWidth >= 48.dp * 8
            Column(Modifier.fillMaxWidth()) {
                if (functionRow) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                        TerminalKeys.FUNCTION_KEYS.forEachIndexed { index, keyCode ->
                            Key("F${index + 1}", stringResource(R.string.terminal_key_function, index + 1)) { onKey(keyCode) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    val each = Modifier.weight(1f)
                    if (roomy) {
                        Key("esc", stringResource(R.string.terminal_key_escape), modifier = each) { onKey(KeyEvent.KEYCODE_ESCAPE) }
                        Key("tab", stringResource(R.string.terminal_key_tab), modifier = each) { onKey(KeyEvent.KEYCODE_TAB) }
                    }
                    Key("ctrl", stringResource(R.string.terminal_key_ctrl) + stickySuffix(ctrl), state = ctrl, onLongClick = onCtrlLong, modifier = each, onClick = onCtrl)
                    Key("alt", stringResource(R.string.terminal_key_alt) + stickySuffix(alt), state = alt, onLongClick = onAltLong, modifier = each, onClick = onAlt)
                    // Fixed bytes, so these arrows never mirror in right-to-left layouts.
                    ArrowKey(R.drawable.sym_arrow_back, stringResource(R.string.terminal_key_left), each) { onArrow(KeyEvent.KEYCODE_DPAD_LEFT) }
                    ArrowKey(R.drawable.sym_arrow_upward, stringResource(R.string.terminal_key_up), each) { onArrow(KeyEvent.KEYCODE_DPAD_UP) }
                    ArrowKey(R.drawable.sym_arrow_downward, stringResource(R.string.terminal_key_down), each) { onArrow(KeyEvent.KEYCODE_DPAD_DOWN) }
                    ArrowKey(R.drawable.sym_arrow_forward, stringResource(R.string.terminal_key_right), each) { onArrow(KeyEvent.KEYCODE_DPAD_RIGHT) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = 4.dp)) {
                        if (!roomy) {
                            Key("esc", stringResource(R.string.terminal_key_escape)) { onKey(KeyEvent.KEYCODE_ESCAPE) }
                            Key("tab", stringResource(R.string.terminal_key_tab)) { onKey(KeyEvent.KEYCODE_TAB) }
                        }
                        for (symbol in listOf("|", "~", "/", "-", "_", "\\", "`")) Key(symbol, null) { onText(symbol) }
                        Key("Home", stringResource(R.string.terminal_key_home)) { onKey(KeyEvent.KEYCODE_MOVE_HOME) }
                        Key("End", stringResource(R.string.terminal_key_end)) { onKey(KeyEvent.KEYCODE_MOVE_END) }
                        Key("PgUp", stringResource(R.string.terminal_key_page_up)) { onKey(KeyEvent.KEYCODE_PAGE_UP) }
                        Key("PgDn", stringResource(R.string.terminal_key_page_down)) { onKey(KeyEvent.KEYCODE_PAGE_DOWN) }
                        Key("F1–F12", stringResource(R.string.terminal_key_function_row), state = if (functionRow) Sticky.LOCKED else Sticky.OFF) { functionRow = !functionRow }
                        IconKey(R.drawable.sym_content_paste, stringResource(R.string.terminal_paste), onClick = onPaste)
                    }
                    // Always in reach, never scrolled away: the compose bar, dictation and the keyboard.
                    IconKey(R.drawable.sym_edit_note, stringResource(if (composing) R.string.terminal_compose_hide else R.string.terminal_compose_show), latched = composing, onClick = onCompose)
                    if (onMic != null) IconKey(R.drawable.sym_mic, stringResource(R.string.terminal_dictate), onClick = onMic)
                    IconKey(
                        if (imeVisible) R.drawable.sym_keyboard_hide else R.drawable.sym_keyboard,
                        stringResource(if (imeVisible) R.string.terminal_hide_keyboard else R.string.terminal_show_keyboard),
                        onClick = onKeyboard,
                    )
                    Spacer(Modifier.width(4.dp))
                }
            }
        }
    }

    /**
     * An arrow that repeats while held, like a hardware key: one press at once, then after 400 ms
     * every 50 ms until released. TalkBack's double tap sends one.
     */
    @Composable
    private fun ArrowKey(@DrawableRes icon: Int, description: String, modifier: Modifier, onPress: () -> (() -> Unit)) {
        val view = LocalView.current
        val scope = rememberCoroutineScope()
        val press by rememberUpdatedState(onPress)
        Box(
            modifier
                .height(48.dp)
                .focusProperties { canFocus = false }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        val send = press()
                        send()
                        val repeat = scope.launch {
                            kotlinx.coroutines.delay(ARROW_REPEAT_DELAY_MS)
                            while (true) {
                                send()
                                kotlinx.coroutines.delay(ARROW_REPEAT_MS)
                            }
                        }
                        waitForUpOrCancellation()
                        repeat.cancel()
                    }
                }
                .semantics {
                    contentDescription = description
                    role = Role.Button
                    onClick { press()(); true }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.padding(horizontal = 2.dp).fillMaxWidth().height(40.dp).background(TermKey, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), contentDescription = null, tint = TermText, modifier = Modifier.size(18.dp))
            }
        }
    }

    @Composable
    private fun stickySuffix(state: Sticky) = when (state) {
        Sticky.OFF -> ""
        Sticky.ARMED -> ", " + stringResource(R.string.terminal_key_armed)
        Sticky.LOCKED -> ", " + stringResource(R.string.terminal_key_locked)
    }

    /**
     * A key cap (40dp tall in a 48dp touch target). A latched modifier is the only key that
     * changes colour: armed (next key only) or locked, which also underlines its label.
     */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun Key(
        label: String,
        description: String?,
        state: Sticky = Sticky.OFF,
        onLongClick: (() -> Unit)? = null,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
    ) {
        val latched = state != Sticky.OFF
        val view = LocalView.current
        Box(
            modifier
                .widthIn(min = 48.dp)
                .height(48.dp)
                // Keys must never take focus: that would pull the keyboard away from the terminal.
                .focusProperties { canFocus = false }
                .combinedClickable(
                    onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onClick() },
                    onLongClick = onLongClick,
                )
                .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                // A weighted key (the fixed first row) fills its share; the rest size to the label.
                (if (modifier == Modifier) Modifier.widthIn(min = 44.dp) else Modifier.padding(horizontal = 2.dp).fillMaxWidth())
                    .height(40.dp)
                    .background(if (latched) TermAccent else TermKey, RoundedCornerShape(8.dp))
                    .padding(horizontal = if (modifier == Modifier) 10.dp else 0.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontFamily = MonoFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (latched) TermOnAccent else TermText,
                    textDecoration = if (state == Sticky.LOCKED) TextDecoration.Underline else null,
                    maxLines = 1,
                )
            }
        }
    }

    @Composable
    private fun IconKey(@DrawableRes icon: Int, description: String, latched: Boolean = false, onClick: () -> Unit) {
        val view = LocalView.current
        Box(
            Modifier
                .size(48.dp)
                .focusProperties { canFocus = false }
                .clickable(role = Role.Button) { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onClick() }
                .semantics { contentDescription = description; if (latched) selected = true },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(44.dp, 40.dp).background(if (latched) TermAccent else TermKey, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), contentDescription = null, tint = if (latched) TermOnAccent else TermText, modifier = Modifier.size(18.dp))
            }
        }
    }

    companion object {
        const val EXTRA_DEVICE_ID = "deviceId"
        const val EXTRA_TERMINAL_ID = "terminalId"

        private const val STATE_OPEN_ID = "openTerminalId"
        private const val LABEL_PREFS = "desklink_terminal_labels"
        private const val KEY_FULLSCREEN = "/fullscreen"
        private const val DEFAULT_ROWS = 24
        private const val DEFAULT_COLS = 80

        /** Bigger than any reasonable paste; keeps one tap from queueing megabytes onto the channel. */
        private const val MAX_PASTE_CHARS = 100_000

        fun intent(context: Context, deviceId: String): Intent =
            Intent(context, TerminalActivity::class.java).putExtra(EXTRA_DEVICE_ID, deviceId)
    }
}

/**
 * The 16 ANSI colors, tuned for DeskLink's terminal black: the prompt green is the brand mint, the
 * cursor coral. Applied to Termux's shared scheme, which every new emulator copies.
 */
internal object TerminalPalette {
    private val colors = mapOf(
        "foreground" to "#e6e6e6",
        "cursor" to "#ff7a62",
        "color0" to "#2a2e37", "color8" to "#5c6370",
        "color1" to "#ff6b6b", "color9" to "#ff8787",
        "color2" to "#34d1a0", "color10" to "#5fe0b8",
        "color3" to "#ffc24d", "color11" to "#ffd47a",
        "color4" to "#6e8cff", "color12" to "#93a9ff",
        "color5" to "#c394e8", "color13" to "#d2acf0",
        "color6" to "#5cc8d6", "color14" to "#86d9e3",
        "color7" to "#c7cbd3", "color15" to "#f2f3f5",
    )

    /** [dark]: the app's theme; the terminal black is a shade deeper there. */
    fun apply(dark: Boolean) {
        @Suppress("UNUSED_VARIABLE") val unused = dark
        val background = "#0e0f11"
        com.termux.terminal.TerminalColors.COLOR_SCHEME.updateWith(java.util.Properties().apply { putAll(colors + ("background" to background)) })
    }
}

// The terminal screen and cards are dark in both themes (the design's terminal black).
private val TermBg = Color(0xFF0E0F11)
private val TermBar = Color(0xFF16171A)
private val TermKey = Color(0xFF2A2B31)
private val TermField = Color(0xFF1B1C1F)
private val TermChip = Color(0x14FFFFFF)
private val TermChipOn = Color(0xFFF2F2F3)
private val TermChipOnText = Color(0xFF18181B)
private val TermText = Color(0xFFD9DBE0)
private val TermIcon = Color(0xFFA1A1AA)
/** 6:1 on the terminal black; the design's #6e737d is 4.0:1. */
private val TermDim = Color(0xFF8B9099)
private val TermPrompt = Color(0xFF86D3A8)
private val TermAccent = Color(0xFFA9B8FF)
private val TermOnAccent = Color(0xFF14204F)
private val TermCoral = Color(0xFFFF7C66)
private val TermCoralText = Color(0xFFFF9A88)

/** A held arrow repeats like a hardware key: after this long, then this often. */
private const val ARROW_REPEAT_DELAY_MS = 400L
private const val ARROW_REPEAT_MS = 50L

/** How often the session list re-reads the preview tiles' screens. */
private const val PREVIEW_REFRESH_MS = 1_500L

/** How often the session list asks for sessions started elsewhere. */
private const val LIST_REFRESH_MS = 5_000L
