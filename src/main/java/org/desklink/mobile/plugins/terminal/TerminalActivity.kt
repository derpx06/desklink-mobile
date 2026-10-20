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
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.view.HapticFeedbackConstants
import androidx.annotation.DrawableRes
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ListItem
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextDecoration
import org.desklink.mobile.ui.compose.components.TabularNumbers
import org.desklink.mobile.ui.compose.components.connectionStatsLabel
import org.desklink.mobile.ui.compose.components.rememberConnectionStats
import org.desklink.mobile.ui.compose.DeskLinkType
import org.desklink.mobile.ui.compose.tokens
import org.desklink.mobile.ui.compose.components.EmptyState
import org.desklink.mobile.ui.compose.components.EmptyTerminalTile
import org.desklink.mobile.ui.compose.components.Hairline
import org.desklink.mobile.ui.compose.components.LabelChip
import org.desklink.mobile.ui.compose.components.SessionPreview
import org.desklink.mobile.ui.compose.components.SessionPreviews
import org.desklink.mobile.ui.compose.components.TerminalTile
import org.desklink.mobile.ui.compose.components.shimmer
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.DeskLinkTheme
import org.desklink.mobile.ui.compose.DeskLinkTopAppBar
import org.desklink.mobile.ui.compose.SessionPasswordDialog
import org.desklink.mobile.ui.compose.sessionPasswordLockedMessage
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
    private var client: TerminalClient? = null
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
        client = terminalClient
        TerminalPalette.apply(
            (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES,
        )
        openId = savedInstanceState?.getString(STATE_OPEN_ID) ?: intent.getStringExtra(EXTRA_TERMINAL_ID)
        loadLabels()
        fullscreen = getSharedPreferences(LABEL_PREFS, MODE_PRIVATE).getBoolean(KEY_FULLSCREEN, true)
        setContent {
            DeskLinkTheme(this) {
                TerminalRoot(terminalClient)
            }
        }
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
    }

    override fun onPause() {
        resumed = false
        TerminalForeground.terminalId = null
        super.onPause()
    }

    override fun onDestroy() {
        // Leaving for good stops output; a rotation or a background stay keeps it flowing so
        // notifications still fire and the screen is current when the person returns.
        if (isFinishing && !isChangingConfigurations) client?.detachAll()
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
        LaunchedEffect(state.createdId) {
            state.createdId?.let {
                openId = it
                client.consumeCreated()
            }
        }
        LaunchedEffect(state.error) {
            state.error?.let {
                snackbar.showSnackbar(it)
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
        if (needed != null && needed !is SessionPasswordDenial.Locked && !passwordDismissed) {
            SessionPasswordDialog(
                wrong = needed == SessionPasswordDenial.Wrong,
                onSubmit = client::unlock,
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

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SessionListScreen(
        client: TerminalClient,
        state: TerminalClientState,
        snackbar: SnackbarHostState,
        onEnterPassword: () -> Unit,
    ) {
        var renaming by remember { mutableStateOf<TerminalSessionState?>(null) }
        var closing by remember { mutableStateOf<TerminalSessionState?>(null) }
        val connected = state.link == TerminalLinkState.CONNECTED
        val locked = state.passwordNeeded != null
        // Sessions can also start at the desk (any tmux session there is
        // listed), which the desktop does not announce: look again while the
        // list is on screen. Not while it is locked: only the person retries that.
        androidx.compose.runtime.LaunchedEffect(connected, locked) {
            while (connected && !locked) {
                client.refresh()
                kotlinx.coroutines.delay(LIST_REFRESH_MS)
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(
                                painterResource(R.drawable.sym_arrow_back),
                                contentDescription = getString(androidx.appcompat.R.string.abc_action_bar_up_description),
                            )
                        }
                    },
                    title = {
                        Column {
                            Text(stringResource(R.string.terminal_title))
                            Text(
                                stringResource(if (connected) R.string.terminal_status_connected else R.string.terminal_status_reconnecting),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { client.refresh() }, enabled = connected) {
                            Icon(painterResource(R.drawable.sym_refresh), contentDescription = stringResource(R.string.terminal_refresh))
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            floatingActionButton = {
                if (!state.notAllowed && !locked) {
                    NewSession(client, state.launchers) { open ->
                        ExtendedFloatingActionButton(
                            onClick = { if (connected) open() },
                            icon = { Icon(painterResource(R.drawable.sym_add), contentDescription = null) },
                            text = { Text(stringResource(R.string.terminal_new_session)) },
                            containerColor = tokens.accent,
                            contentColor = tokens.onAccent,
                        )
                    }
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (!connected) ReconnectingBanner()
                when {
                    state.notAllowed -> StatePanel(
                        title = stringResource(R.string.terminal_not_allowed_title),
                        message = stringResource(R.string.terminal_not_allowed_message),
                        action = stringResource(R.string.terminal_retry),
                        onAction = { client.refresh() },
                    )
                    state.passwordNeeded != null -> Locked(state.passwordNeeded, onEnterPassword, onRetry = { client.refresh() })
                    connected && !state.listLoaded -> CenteredProgress()
                    state.sessions.isEmpty() -> Column(Modifier.padding(16.dp)) {
                        EmptyTerminalTile(
                            launchers = listOf(stringResource(R.string.terminal_launch_shell) to { client.create(DEFAULT_ROWS, DEFAULT_COLS, null) }) +
                                state.launchers.mapNotNull { launcher -> agentName(launcher)?.let { it to { client.create(DEFAULT_ROWS, DEFAULT_COLS, listOf(launcher)) } } },
                            enabled = connected,
                        )
                    }
                    else -> {
                        // Previews re-read the screens now and then: output arrives without a state change.
                        var tick by remember { mutableStateOf(0) }
                        LaunchedEffect(Unit) {
                            while (true) {
                                kotlinx.coroutines.delay(PREVIEW_REFRESH_MS)
                                tick++
                            }
                        }
                        val previews = remember(state, tick, labels.toMap()) { SessionPreviews.of(this@TerminalActivity, deviceId) }.associateBy { it.id }
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            // Clear of the floating button.
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(state.sessions, key = { it.id }) { session ->
                                val preview = previews[session.id] ?: SessionPreview(
                                    id = session.id,
                                    name = labels[session.id] ?: agentName(session.title) ?: session.title.ifBlank { session.id.take(8) },
                                    badge = SessionPreviews.badgeOf(session.title),
                                    live = false,
                                    lines = emptyList(),
                                    size = stringResource(R.string.terminal_session_size, session.cols, session.rows),
                                )
                                SessionTile(
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
        }

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

    /**
     * [button] starts a shell, or opens a plain menu of the agent CLIs the desktop says it has
     * installed. Those sessions are titled by their command, which [agentName] turns back into a name.
     */
    @Composable
    private fun NewSession(client: TerminalClient, launchers: List<String>, button: @Composable (open: () -> Unit) -> Unit) {
        var menu by remember { mutableStateOf(false) }
        val create = { command: List<String>? ->
            menu = false
            client.create(DEFAULT_ROWS, DEFAULT_COLS, command)
        }
        Box {
            button { if (launchers.isEmpty()) create(null) else menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.terminal_launch_shell)) }, onClick = { create(null) })
                launchers.forEach { launcher ->
                    agentName(launcher)?.let { name ->
                        DropdownMenuItem(text = { Text(name) }, onClick = { create(listOf(launcher)) })
                    }
                }
            }
        }
    }

    @Composable
    private fun agentName(title: String): String? = when (title) {
        TerminalProtocol.LAUNCHER_CLAUDE -> stringResource(R.string.terminal_launcher_claude)
        TerminalProtocol.LAUNCHER_CODEX -> stringResource(R.string.terminal_launcher_codex)
        else -> null
    }

    /** A session as a dark terminal tile with its last lines; finished ones offer Restart. */
    @Composable
    private fun SessionTile(
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
        Column {
            TerminalTile(
                preview,
                Modifier.fillMaxWidth(),
                menu = {
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                painterResource(R.drawable.sym_more_vert),
                                contentDescription = stringResource(R.string.terminal_session_options, preview.name),
                                tint = Color(0xFFB8BBC4),
                            )
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
                },
                onClick = onOpen,
            )
            if (finished) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (session.gone) stringResource(R.string.terminal_chip_ended) else stringResource(R.string.terminal_chip_exited, session.exitStatus ?: 0L),
                        style = DeskLinkType.mono,
                        color = tokens.textDim,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                    if (connected) TextButton(onClick = onRestart) { Text(stringResource(R.string.terminal_restart)) }
                }
            }
        }
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

    /** Loading the list: tiles shaped like the real ones, with a sheen. */
    @Composable
    private fun CenteredProgress() {
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp)
                .semantics { contentDescription = getString(R.string.terminal_loading) },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repeat(3) {
                Box(Modifier.fillMaxWidth().height(132.dp).clip(RoundedCornerShape(14.dp)).shimmer())
            }
        }
    }

    @Composable
    private fun ReconnectingBanner() {
        Surface(color = tokens.surface2, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(12.dp))
                Text(stringResource(R.string.terminal_reconnecting), style = MaterialTheme.typography.bodyMedium, color = tokens.textDim)
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

    @OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
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
        val imeVisible = WindowInsets.isImeVisible
        val canDictate = remember { SpeechRecognizer.isRecognitionAvailable(context) }

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
                    viewRef?.showKeyboard()
                }

                override fun onFontSizeChanged(sizePx: Int) = Unit
            }
        }

        val name = labels[id] ?: session?.title?.let { agentName(it) ?: it.ifBlank { null } } ?: id.take(8)
        val subtitle = when {
            !connected -> stringResource(R.string.terminal_status_reconnecting)
            session == null || session.gone -> stringResource(R.string.terminal_chip_ended)
            session.exitStatus != null -> stringResource(R.string.terminal_chip_exited, session.exitStatus)
            else -> stringResource(R.string.terminal_chip_running)
        }
        val running = connected && session != null && !session.gone && session.exitStatus == null
        val stats = rememberConnectionStats(deviceId)
        var switcher by remember { mutableStateOf(false) }

        // Typed like the key bar's symbols, armed modifiers included, and never followed by Enter:
        // the person reads what was recognised before running it.
        val type = { text: String ->
            val m = host.takeModifiers()
            host.send(TerminalKeys.text(text, m.ctrl, m.alt))
        }
        LaunchedEffect(dictated) {
            dictated?.let {
                dictated = null
                type(it)
            }
        }

        DisposableEffect(fullscreen) {
            setSystemBarsHidden(fullscreen)
            onDispose { setSystemBarsHidden(false) }
        }

        Scaffold(
            // The chrome follows the theme; the terminal itself stays dark (see below).
            modifier = Modifier.background(MaterialTheme.colorScheme.background).safeDrawingPadding(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                if (!fullscreen) TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    navigationIcon = {
                        IconButton(onClick = { leaveTerminal(client, id) }) {
                            Icon(painterResource(R.drawable.sym_arrow_back), contentDescription = stringResource(R.string.terminal_back_to_sessions))
                        }
                    },
                    title = {
                        // Tapping the title switches to another session.
                        Box {
                            Column(
                                Modifier
                                    .clickable(enabled = state.sessions.size > 1, onClickLabel = stringResource(R.string.terminal_switch_session)) { switcher = true }
                                    .padding(vertical = 4.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    LabelChip(SessionPreviews.badgeOf(session?.title.orEmpty()), onDark = false)
                                    Spacer(Modifier.width(8.dp))
                                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (running) {
                                        Box(Modifier.size(7.dp).background(tokens.mint, CircleShape))
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(
                                        listOfNotNull(subtitle, stats?.takeIf { connected }?.let { connectionStatsLabel(it) }).joinToString(" · "),
                                        style = DeskLinkType.mono,
                                        color = tokens.textDim,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            DropdownMenu(expanded = switcher, onDismissRequest = { switcher = false }) {
                                state.sessions.filter { it.id != id }.forEach { other ->
                                    val otherName = labels[other.id] ?: agentName(other.title) ?: other.title.ifBlank { other.id.take(8) }
                                    DropdownMenuItem(
                                        text = { Text(otherName) },
                                        onClick = {
                                            switcher = false
                                            leaveTerminal(client, id)
                                            openId = other.id
                                        },
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        // Shown when the session is not receiving output and nothing is about to change that.
                        if (connected && !state.notAllowed && session != null && !session.gone && !session.wanted && !session.attached && session.exitStatus == null) {
                            IconButton(onClick = { client.reattach(id) }) {
                                Icon(painterResource(R.drawable.sym_refresh), contentDescription = stringResource(R.string.terminal_reattach))
                            }
                        }
                        IconButton(onClick = { rememberFullscreen(true) }) {
                            Icon(painterResource(R.drawable.sym_open_in_full), contentDescription = stringResource(R.string.remote_fullscreen))
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (state.notAllowed) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.terminal_not_allowed_message),
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else if (!connected) {
                    ReconnectingBanner()
                }
                AndroidView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(tokens.terminalBg)
                        .padding(horizontal = 4.dp),
                    factory = { ctx -> TerminalView(ctx, host).also { it.attach(screen); viewRef = it } },
                )
                KeyBar(
                    fullscreen = fullscreen,
                    onExitFullscreen = { rememberFullscreen(false) },
                    ctrl = ctrl,
                    alt = alt,
                    imeVisible = imeVisible,
                    onCtrl = { ctrl = ctrl.tap() },
                    onCtrlLong = { ctrl = ctrl.longPress() },
                    onAlt = { alt = alt.tap() },
                    onAltLong = { alt = alt.longPress() },
                    onKey = { keyCode ->
                        val cursorApp = screen.withEmulator { it.isCursorKeysApplicationMode }
                        val m = host.takeModifiers()
                        TerminalKeys.key(keyCode, ctrl = m.ctrl, alt = m.alt, cursorApp = cursorApp)?.let(host::send)
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

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun KeyBar(
        fullscreen: Boolean,
        onExitFullscreen: () -> Unit,
        ctrl: Sticky,
        alt: Sticky,
        imeVisible: Boolean,
        onCtrl: () -> Unit,
        onCtrlLong: () -> Unit,
        onAlt: () -> Unit,
        onAltLong: () -> Unit,
        onKey: (Int) -> Unit,
        onText: (String) -> Unit,
        onMic: (() -> Unit)?,
        onPaste: () -> Unit,
        onKeyboard: () -> Unit,
    ) {
        var functionRow by remember { mutableStateOf(false) }
        Surface(color = tokens.surface) {
            Column(Modifier.padding(vertical = 6.dp)) {
                Hairline(Modifier.padding(bottom = 6.dp))
                if (functionRow) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        TerminalKeys.FUNCTION_KEYS.forEachIndexed { index, keyCode ->
                            Key("F${index + 1}", stringResource(R.string.terminal_key_function, index + 1)) { onKey(keyCode) }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState())
                            .padding(start = 8.dp, end = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // The app bar is hidden in full screen; this is the way back to it.
                        if (fullscreen) IconKey(R.drawable.sym_close_fullscreen, stringResource(R.string.remote_exit_fullscreen), onExitFullscreen)
                        Key("Esc", stringResource(R.string.terminal_key_escape)) { onKey(KeyEvent.KEYCODE_ESCAPE) }
                        Key("Tab", stringResource(R.string.terminal_key_tab)) { onKey(KeyEvent.KEYCODE_TAB) }
                        Key(
                            "Ctrl",
                            stringResource(R.string.terminal_key_ctrl) + stickySuffix(ctrl),
                            state = ctrl,
                            onLongClick = onCtrlLong,
                            onClick = onCtrl,
                        )
                        Key(
                            "Alt",
                            stringResource(R.string.terminal_key_alt) + stickySuffix(alt),
                            state = alt,
                            onLongClick = onAltLong,
                            onClick = onAlt,
                        )
                        // Fixed bytes, so these arrows never mirror in right-to-left layouts.
                        IconKey(R.drawable.sym_arrow_back, stringResource(R.string.terminal_key_left)) { onKey(KeyEvent.KEYCODE_DPAD_LEFT) }
                        IconKey(R.drawable.sym_arrow_downward, stringResource(R.string.terminal_key_down)) { onKey(KeyEvent.KEYCODE_DPAD_DOWN) }
                        IconKey(R.drawable.sym_arrow_upward, stringResource(R.string.terminal_key_up)) { onKey(KeyEvent.KEYCODE_DPAD_UP) }
                        IconKey(R.drawable.sym_arrow_forward, stringResource(R.string.terminal_key_right)) { onKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
                        for (symbol in listOf("|", "~", "/", "-", "_", "\\", "`")) Key(symbol, null) { onText(symbol) }
                        Key("Home", stringResource(R.string.terminal_key_home)) { onKey(KeyEvent.KEYCODE_MOVE_HOME) }
                        Key("End", stringResource(R.string.terminal_key_end)) { onKey(KeyEvent.KEYCODE_MOVE_END) }
                        Key("PgUp", stringResource(R.string.terminal_key_page_up)) { onKey(KeyEvent.KEYCODE_PAGE_UP) }
                        Key("PgDn", stringResource(R.string.terminal_key_page_down)) { onKey(KeyEvent.KEYCODE_PAGE_DOWN) }
                        Key(
                            "F1–F12",
                            stringResource(R.string.terminal_key_function_row),
                            state = if (functionRow) Sticky.LOCKED else Sticky.OFF,
                        ) { functionRow = !functionRow }
                        IconKey(R.drawable.sym_content_paste, stringResource(R.string.terminal_paste), onPaste)
                    }
                    // Always in reach, never scrolled away: dictation and the keyboard.
                    if (onMic != null) {
                        IconKey(R.drawable.sym_mic, stringResource(R.string.terminal_dictate), onMic)
                        Spacer(Modifier.width(6.dp))
                    }
                    IconKey(
                        if (imeVisible) R.drawable.sym_keyboard_hide else R.drawable.sym_keyboard,
                        stringResource(if (imeVisible) R.string.terminal_hide_keyboard else R.string.terminal_show_keyboard),
                        onKeyboard,
                    )
                    Spacer(Modifier.width(8.dp))
                }
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
     * A key chip. A latched modifier is the only key that changes color: armed (next key only) or
     * locked, which also underlines its label.
     */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun Key(
        label: String,
        description: String?,
        state: Sticky = Sticky.OFF,
        onLongClick: (() -> Unit)? = null,
        onClick: () -> Unit,
    ) {
        val colors = MaterialTheme.colorScheme
        val latched = state != Sticky.OFF
        val view = LocalView.current
        Surface(
            shape = MaterialTheme.shapes.small,
            color = if (latched) tokens.accent.copy(alpha = 0.18f) else tokens.surface2,
            contentColor = if (latched) tokens.accent else colors.onSurface,
            modifier = Modifier
                .widthIn(min = 44.dp)
                .height(44.dp)
                // Keys must never take focus: that would pull the keyboard away from the terminal.
                .focusProperties { canFocus = false }
                .clip(MaterialTheme.shapes.small)
                .combinedClickable(
                    onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onClick() },
                    onLongClick = onLongClick,
                )
                .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    textDecoration = if (state == Sticky.LOCKED) TextDecoration.Underline else null,
                    maxLines = 1,
                )
            }
        }
    }

    @Composable
    private fun IconKey(@DrawableRes icon: Int, description: String, onClick: () -> Unit) {
        val view = LocalView.current
        Surface(
            shape = MaterialTheme.shapes.small,
            color = tokens.surface2,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .size(44.dp)
                .focusProperties { canFocus = false }
                .clip(MaterialTheme.shapes.small)
                .clickable { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onClick() },
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size(20.dp)) }
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
        val background = if (dark) "#050506" else "#0b0c0e"
        com.termux.terminal.TerminalColors.COLOR_SCHEME.updateWith(java.util.Properties().apply { putAll(colors + ("background" to background)) })
    }
}

/** How often the session list re-reads the preview tiles' screens. */
private const val PREVIEW_REFRESH_MS = 1_500L

/** How often the session list asks for sessions started elsewhere. */
private const val LIST_REFRESH_MS = 5_000L
