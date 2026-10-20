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
import org.desklink.mobile.ui.compose.DeskLinkDarkTheme
import org.desklink.mobile.ui.compose.LocalStatusColors
import org.desklink.mobile.ui.compose.components.SectionTitle
import org.desklink.mobile.ui.compose.components.StatusDot
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
                client.consumeEnded()
                snackbar.showSnackbar(getString(R.string.terminal_ended_by_computer))
            }
        }

        // Cancelled: the list shows "Locked" until the person asks for the dialog again.
        var passwordDismissed by remember(state.passwordNeeded == null) { mutableStateOf(false) }

        if (current == null) {
            SessionListScreen(client, state, snackbar) { passwordDismissed = false }
        } else {
            BackHandler { leaveTerminal(client, current) }
            val view = androidx.compose.ui.platform.LocalView.current
            val lightBars = !androidx.compose.foundation.isSystemInDarkTheme()
            DisposableEffect(Unit) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = false
                controller.isAppearanceLightNavigationBars = false
                onDispose {
                    controller.isAppearanceLightStatusBars = lightBars
                    controller.isAppearanceLightNavigationBars = lightBars
                }
            }
            DeskLinkDarkTheme(this) {
                TerminalScreenUi(client, state, current, snackbar)
            }
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
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = getString(androidx.appcompat.R.string.abc_action_bar_up_description),
                            )
                        }
                    },
                    title = {
                        Column {
                            Text(stringResource(R.string.terminal_title))
                            LinkStatus(connected)
                        }
                    },
                    actions = {
                        IconButton(onClick = { client.refresh() }, enabled = connected) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.terminal_refresh))
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
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
                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item { NewSessionPanel(client, state.launchers, connected) }
                        if (state.sessions.isEmpty()) {
                            item { EmptyState() }
                        } else {
                            item { SectionTitle(stringResource(R.string.terminal_sessions_title)) }
                            items(state.sessions, key = { it.id }) { session ->
                                SessionCard(
                                    session = session,
                                    label = labels[session.id],
                                    connected = connected,
                                    onOpen = { openId = session.id },
                                    onRename = { renaming = session },
                                    onClose = { closing = session },
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

    @Composable
    private fun LinkStatus(connected: Boolean) {
        val status = LocalStatusColors.current
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (connected) status.online else status.pending, pulsing = !connected, size = 7.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(if (connected) R.string.terminal_status_connected else R.string.terminal_status_reconnecting),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    /**
     * A shell, or one of the agent CLIs the desktop says it has installed. Agent sessions are
     * titled by their command, which [agentName] turns back into a name.
     */
    @Composable
    private fun NewSessionPanel(client: TerminalClient, launchers: List<String>, connected: Boolean) {
        val create = { command: List<String>? -> client.create(DEFAULT_ROWS, DEFAULT_COLS, command) }
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.terminal_new_session_choose), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LauncherTile(
                        kind = SessionKind.SHELL,
                        name = stringResource(R.string.terminal_launch_shell),
                        enabled = connected,
                        modifier = Modifier.weight(1f),
                    ) { create(null) }
                    launchers.forEach { launcher ->
                        agentName(launcher)?.let { name ->
                            LauncherTile(
                                kind = sessionKind(launcher),
                                name = name,
                                enabled = connected,
                                modifier = Modifier.weight(1f),
                            ) { create(listOf(launcher)) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun LauncherTile(kind: SessionKind, name: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
        val description = stringResource(R.string.terminal_new_session) + ": " + name
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceBright,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = modifier
                .heightIn(min = 96.dp)
                .semantics { contentDescription = description },
        ) {
            Column(
                Modifier
                    .padding(12.dp)
                    .alpha(if (enabled) 1f else 0.5f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                AgentBadge(kind, size = 40.dp)
                Spacer(Modifier.height(10.dp))
                Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    @Composable
    private fun agentName(title: String): String? = when (title) {
        TerminalProtocol.LAUNCHER_CLAUDE -> stringResource(R.string.terminal_launcher_claude)
        TerminalProtocol.LAUNCHER_CODEX -> stringResource(R.string.terminal_launcher_codex)
        else -> null
    }

    @Composable
    private fun SessionCard(
        session: TerminalSessionState,
        label: String?,
        connected: Boolean,
        onOpen: () -> Unit,
        onRename: () -> Unit,
        onClose: () -> Unit,
    ) {
        val name = label ?: agentName(session.title) ?: session.title.ifBlank { session.id.take(8) }
        var menu by remember { mutableStateOf(false) }
        Surface(
            onClick = onOpen,
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgentBadge(sessionKind(session.title), size = 44.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SessionStatus(session, connected)
                        Text(
                            "  ·  ",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Text(
                            stringResource(R.string.terminal_session_size, session.cols, session.rows),
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.terminal_session_options, name))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.terminal_rename)) },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = { menu = false; onRename() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.terminal_close_session), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            enabled = connected,
                            onClick = { menu = false; onClose() },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun SessionStatus(session: TerminalSessionState, connected: Boolean) {
        val status = LocalStatusColors.current
        val (text, color) = when {
            session.gone -> stringResource(R.string.terminal_chip_ended) to MaterialTheme.colorScheme.error
            session.exitStatus != null -> stringResource(R.string.terminal_chip_exited, session.exitStatus) to MaterialTheme.colorScheme.outline
            !connected -> stringResource(R.string.terminal_chip_disconnected) to status.pending
            else -> stringResource(R.string.terminal_chip_running) to status.online
        }
        StatusDot(color, size = 7.dp)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(80.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            Button(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
        }
    }

    @Composable
    private fun EmptyState() {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.terminal_empty_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.terminal_empty_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }

    @Composable
    private fun CenteredProgress() {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = getString(R.string.terminal_loading) })
        }
    }

    @Composable
    private fun ReconnectingBanner() {
        val status = LocalStatusColors.current
        Surface(color = status.pendingContainer, contentColor = status.onPendingContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = status.pending)
                Spacer(Modifier.size(12.dp))
                Text(stringResource(R.string.terminal_reconnecting), style = MaterialTheme.typography.bodyMedium)
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
        val screen = remember(id) { client.sink(id, DEFAULT_ROWS, DEFAULT_COLS) as TerminalScreen }
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
        val status = LocalStatusColors.current
        val subtitleDot = when {
            !connected -> status.pending
            session == null || session.gone -> MaterialTheme.colorScheme.error
            session.exitStatus != null -> MaterialTheme.colorScheme.outline
            else -> status.online
        }

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
            // The camera cutout strip stays clear of text; it is black like the terminal.
            modifier = Modifier.background(TerminalBlack).safeDrawingPadding(),
            containerColor = TerminalBlack,
            topBar = {
                if (!fullscreen) TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = TerminalBlack),
                    navigationIcon = {
                        IconButton(onClick = { leaveTerminal(client, id) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.terminal_back_to_sessions))
                        }
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AgentBadge(sessionKind(session?.title.orEmpty()), size = 32.dp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    StatusDot(subtitleDot, pulsing = !connected, size = 7.dp)
                                    Spacer(Modifier.width(6.dp))
                                    Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    },
                    actions = {
                        // Shown when the session is not receiving output and nothing is about to change that.
                        if (connected && !state.notAllowed && session != null && !session.gone && !session.wanted && !session.attached && session.exitStatus == null) {
                            IconButton(onClick = { client.reattach(id) }) {
                                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.terminal_reattach))
                            }
                        }
                        IconButton(onClick = { rememberFullscreen(true) }) {
                            Icon(painterResource(R.drawable.ic_open_in_full_24dp), contentDescription = stringResource(R.string.remote_fullscreen))
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
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp),
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

    // The Left/Right keys send fixed bytes, so their arrows must not mirror in RTL layouts: the
    // auto-mirrored variants the deprecation points to would.
    @Suppress("DEPRECATION")
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
        Surface(color = KeyBarBackground) {
            Column {
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                if (functionRow) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        TerminalKeys.FUNCTION_KEYS.forEachIndexed { index, keyCode ->
                            Key("F${index + 1}", stringResource(R.string.terminal_key_function, index + 1)) { onKey(keyCode) }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Row(
                        Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState())
                            .padding(start = 6.dp),
                    ) {
                        // The app bar is hidden in full screen; this is the way back to it.
                        if (fullscreen) IconKey(painterResource(R.drawable.ic_open_in_full_24dp), stringResource(R.string.remote_exit_fullscreen), onClick = onExitFullscreen)
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
                        IconKey(Icons.Default.KeyboardArrowLeft, stringResource(R.string.terminal_key_left)) { onKey(KeyEvent.KEYCODE_DPAD_LEFT) }
                        IconKey(Icons.Default.KeyboardArrowDown, stringResource(R.string.terminal_key_down)) { onKey(KeyEvent.KEYCODE_DPAD_DOWN) }
                        IconKey(Icons.Default.KeyboardArrowUp, stringResource(R.string.terminal_key_up)) { onKey(KeyEvent.KEYCODE_DPAD_UP) }
                        IconKey(Icons.Default.KeyboardArrowRight, stringResource(R.string.terminal_key_right)) { onKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
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
                        IconKey(painterResource(R.drawable.ic_baseline_content_paste_24), stringResource(R.string.terminal_paste), onClick = onPaste)
                    }
                    // Always in reach, never scrolled away: dictation and the keyboard.
                    Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(28.dp).background(Color.White.copy(alpha = 0.12f)))
                    if (onMic != null) IconKey(painterResource(R.drawable.ic_mic_black), stringResource(R.string.terminal_dictate), accent = true, onClick = onMic)
                    IconKey(
                        painterResource(if (imeVisible) R.drawable.ic_keyboard_hide_36dp else R.drawable.ic_action_keyboard_24dp),
                        stringResource(if (imeVisible) R.string.terminal_hide_keyboard else R.string.terminal_show_keyboard),
                        onClick = onKeyboard,
                    )
                    Spacer(Modifier.width(6.dp))
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
     * A key chip. A sticky modifier shows its state: armed (next key only) fills with the accent;
     * locked keeps the fill and adds a bar beneath the label.
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
        val container = if (state == Sticky.OFF) KeyChip else MaterialTheme.colorScheme.primary
        val content = if (state == Sticky.OFF) KeyLabel else MaterialTheme.colorScheme.onPrimary
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = container,
            contentColor = content,
            modifier = Modifier
                .padding(horizontal = 3.dp, vertical = 2.dp)
                .widthIn(min = 44.dp)
                .height(44.dp)
                // Keys must never take focus: that would pull the keyboard away from the terminal.
                .focusProperties { canFocus = false }
                .clip(RoundedCornerShape(10.dp))
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge, fontSize = 15.sp, maxLines = 1)
                if (state == Sticky.LOCKED) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 5.dp)
                            .width(16.dp)
                            .height(2.dp)
                            .background(content, CircleShape),
                    )
                }
            }
        }
    }

    @Composable
    private fun IconKey(icon: ImageVector, description: String, onClick: () -> Unit) =
        IconKey(rememberVectorPainter(icon), description, onClick = onClick)

    @Composable
    private fun IconKey(icon: Painter, description: String, accent: Boolean = false, onClick: () -> Unit) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (accent) MaterialTheme.colorScheme.primaryContainer else KeyChip,
            contentColor = if (accent) MaterialTheme.colorScheme.onPrimaryContainer else KeyLabel,
            modifier = Modifier
                .padding(horizontal = 3.dp, vertical = 2.dp)
                .size(44.dp)
                .focusProperties { canFocus = false }
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onClick),
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = description, modifier = Modifier.size(22.dp)) }
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

/** The terminal surface and its key bar: black like the emulator's default background, keys lifted off it. */
private val TerminalBlack = Color(0xFF000000)
private val KeyBarBackground = Color(0xFF0D0F14)
private val KeyChip = Color(0xFF1F232C)
private val KeyLabel = Color(0xFFE6E8EE)

/** What a session runs, from its title: the desktop titles agent sessions by their launcher. */
private enum class SessionKind { SHELL, CLAUDE, CODEX }

private fun sessionKind(title: String) = when (title) {
    TerminalProtocol.LAUNCHER_CLAUDE -> SessionKind.CLAUDE
    TerminalProtocol.LAUNCHER_CODEX -> SessionKind.CODEX
    else -> SessionKind.SHELL
}

/** A monogram tile: ">_" for a shell, the agent's initials otherwise. No third-party artwork. */
@Composable
private fun AgentBadge(kind: SessionKind, size: androidx.compose.ui.unit.Dp) {
    val colors = MaterialTheme.colorScheme
    val (text, container, content) = when (kind) {
        SessionKind.SHELL -> Triple(">_", Color(0xFF14213D), Color(0xFF74D9C4))
        SessionKind.CLAUDE -> Triple("CC", colors.tertiaryContainer, colors.onTertiaryContainer)
        SessionKind.CODEX -> Triple("CX", colors.secondaryContainer, colors.onSecondaryContainer)
    }
    Surface(shape = RoundedCornerShape(size * 0.3f), color = container, contentColor = content, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.36f).sp,
                maxLines = 1,
            )
        }
    }
}

/** How often the session list asks for sessions started elsewhere. */
private const val LIST_REFRESH_MS = 5_000L
