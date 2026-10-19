/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.terminal

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.DeskLinkTheme
import org.desklink.mobile.ui.compose.DeskLinkTopAppBar
import org.desklink.mobile.webrtc.terminal.TerminalClient
import org.desklink.mobile.webrtc.terminal.TerminalClientState
import org.desklink.mobile.webrtc.terminal.TerminalLinkState
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

        if (current == null) {
            SessionListScreen(client, state, snackbar)
        } else {
            BackHandler { leaveTerminal(client, current) }
            TerminalScreenUi(client, state, current, snackbar)
        }
    }

    private fun leaveTerminal(client: TerminalClient, id: String) {
        client.leave(id)
        openId = null
    }

    // ---- session list ---------------------------------------------------------------------------------

    @Composable
    private fun SessionListScreen(client: TerminalClient, state: TerminalClientState, snackbar: SnackbarHostState) {
        var renaming by remember { mutableStateOf<TerminalSessionState?>(null) }
        var closing by remember { mutableStateOf<TerminalSessionState?>(null) }
        val connected = state.link == TerminalLinkState.CONNECTED
        // Sessions can also start at the desk (any tmux session there is
        // listed), which the desktop does not announce: look again while the
        // list is on screen.
        androidx.compose.runtime.LaunchedEffect(connected) {
            while (connected) {
                client.refresh()
                kotlinx.coroutines.delay(LIST_REFRESH_MS)
            }
        }

        Scaffold(
            modifier = Modifier.safeDrawingPadding(),
            topBar = {
                DeskLinkTopAppBar(
                    title = stringResource(R.string.terminal_title),
                    subTitle = stringResource(if (connected) R.string.terminal_status_connected else R.string.terminal_status_reconnecting),
                    navIconOnClick = { finish() },
                    navIconDescription = getString(androidx.appcompat.R.string.abc_action_bar_up_description),
                    actions = {
                        IconButton(onClick = { client.refresh() }, enabled = connected) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.terminal_refresh))
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            floatingActionButton = {
                if (!state.notAllowed) {
                    ExtendedFloatingActionButton(
                        onClick = { if (connected) client.create(DEFAULT_ROWS, DEFAULT_COLS) },
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        text = { Text(stringResource(R.string.terminal_new_session)) },
                    )
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (!connected) ReconnectingBanner()
                when {
                    state.notAllowed -> NotAllowed(onRetry = { client.refresh() })
                    connected && !state.listLoaded -> CenteredProgress()
                    state.sessions.isEmpty() -> EmptyState(connected) { client.create(DEFAULT_ROWS, DEFAULT_COLS) }
                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 88.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
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
                    TextButton(onClick = { client.close(session.id); closing = null }) {
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
    private fun SessionCard(
        session: TerminalSessionState,
        label: String?,
        connected: Boolean,
        onOpen: () -> Unit,
        onRename: () -> Unit,
        onClose: () -> Unit,
    ) {
        val name = label ?: session.title.ifBlank { session.id.take(8) }
        Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusChip(session, connected)
                        Text(
                            "${session.cols}×${session.rows}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onRename) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.terminal_rename))
                }
                IconButton(onClick = onClose, enabled = connected) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.terminal_close_session))
                }
            }
        }
    }

    @Composable
    private fun StatusChip(session: TerminalSessionState, connected: Boolean) {
        val (text, container) = when {
            session.gone -> stringResource(R.string.terminal_chip_ended) to MaterialTheme.colorScheme.errorContainer
            session.exitStatus != null -> stringResource(R.string.terminal_chip_exited, session.exitStatus) to MaterialTheme.colorScheme.surfaceVariant
            !connected -> stringResource(R.string.terminal_chip_disconnected) to MaterialTheme.colorScheme.tertiaryContainer
            else -> stringResource(R.string.terminal_chip_running) to MaterialTheme.colorScheme.primaryContainer
        }
        Surface(shape = RoundedCornerShape(8.dp), color = container) {
            Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
        }
    }

    @Composable
    private fun NotAllowed(onRetry: () -> Unit) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.terminal_not_allowed_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.terminal_not_allowed_message), textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRetry) { Text(stringResource(R.string.terminal_retry)) }
        }
    }

    @Composable
    private fun EmptyState(connected: Boolean, onCreate: () -> Unit) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.terminal_empty_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.terminal_empty_message), textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onCreate, enabled = connected) { Text(stringResource(R.string.terminal_new_session)) }
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
        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
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

    @OptIn(ExperimentalLayoutApi::class)
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

        val name = labels[id] ?: session?.title?.ifBlank { null } ?: id.take(8)
        val subtitle = when {
            !connected -> stringResource(R.string.terminal_status_reconnecting)
            session == null || session.gone -> stringResource(R.string.terminal_chip_ended)
            session.exitStatus != null -> stringResource(R.string.terminal_chip_exited, session.exitStatus)
            else -> stringResource(R.string.terminal_chip_running)
        }

        DisposableEffect(fullscreen) {
            setSystemBarsHidden(fullscreen)
            onDispose { setSystemBarsHidden(false) }
        }

        Scaffold(
            // The camera cutout strip stays clear of text; in full screen it is black like the terminal.
            modifier = (if (fullscreen) Modifier.background(Color.Black) else Modifier).safeDrawingPadding(),
            topBar = {
                if (!fullscreen) DeskLinkTopAppBar(
                    title = name,
                    subTitle = subtitle,
                    navIconOnClick = { leaveTerminal(client, id) },
                    navIconDescription = stringResource(R.string.terminal_back_to_sessions),
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
                    modifier = Modifier.weight(1f).fillMaxWidth(),
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
                    onText = { text ->
                        val m = host.takeModifiers()
                        host.send(TerminalKeys.text(text, m.ctrl, m.alt))
                    },
                    onPaste = { paste(context, screen) },
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

    private fun paste(context: Context, screen: TerminalScreen) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        when {
            text.isNullOrEmpty() -> Toast.makeText(context, R.string.terminal_paste_empty, Toast.LENGTH_SHORT).show()
            text.length > MAX_PASTE_CHARS -> Toast.makeText(context, R.string.terminal_paste_too_large, Toast.LENGTH_LONG).show()
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
        onPaste: () -> Unit,
        onKeyboard: () -> Unit,
    ) {
        var functionRow by remember { mutableStateOf(false) }
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 2.dp) {
            Column {
                if (functionRow) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp)) {
                        TerminalKeys.FUNCTION_KEYS.forEachIndexed { index, keyCode ->
                            Key("F${index + 1}", stringResource(R.string.terminal_key_function, index + 1)) { onKey(keyCode) }
                        }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp)) {
                    // The app bar is hidden in full screen; this is the way back to it.
                    if (fullscreen) IconKey(painterResource(R.drawable.ic_open_in_full_24dp), stringResource(R.string.remote_exit_fullscreen), onExitFullscreen)
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
                    Key("Home", stringResource(R.string.terminal_key_home)) { onKey(KeyEvent.KEYCODE_MOVE_HOME) }
                    Key("End", stringResource(R.string.terminal_key_end)) { onKey(KeyEvent.KEYCODE_MOVE_END) }
                    Key("PgUp", stringResource(R.string.terminal_key_page_up)) { onKey(KeyEvent.KEYCODE_PAGE_UP) }
                    Key("PgDn", stringResource(R.string.terminal_key_page_down)) { onKey(KeyEvent.KEYCODE_PAGE_DOWN) }
                    for (symbol in listOf("|", "~", "/", "-", "_", "\\", "`")) Key(symbol, null) { onText(symbol) }
                    Key(
                        "F1–F12",
                        stringResource(R.string.terminal_key_function_row),
                        state = if (functionRow) Sticky.LOCKED else Sticky.OFF,
                    ) { functionRow = !functionRow }
                    IconKey(painterResource(R.drawable.ic_baseline_content_paste_24), stringResource(R.string.terminal_paste), onPaste)
                    IconKey(
                        painterResource(if (imeVisible) R.drawable.ic_keyboard_hide_36dp else R.drawable.ic_action_keyboard_24dp),
                        stringResource(if (imeVisible) R.string.terminal_hide_keyboard else R.string.terminal_show_keyboard),
                        onKeyboard,
                    )
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

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun Key(
        label: String,
        description: String?,
        state: Sticky = Sticky.OFF,
        onLongClick: (() -> Unit)? = null,
        onClick: () -> Unit,
    ) {
        val container = when (state) {
            Sticky.OFF -> MaterialTheme.colorScheme.surface
            Sticky.ARMED -> MaterialTheme.colorScheme.primary
            Sticky.LOCKED -> MaterialTheme.colorScheme.tertiary
        }
        val content = when (state) {
            Sticky.OFF -> MaterialTheme.colorScheme.onSurface
            Sticky.ARMED -> MaterialTheme.colorScheme.onPrimary
            Sticky.LOCKED -> MaterialTheme.colorScheme.onTertiary
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = container,
            contentColor = content,
            modifier = Modifier
                .padding(2.dp)
                .widthIn(min = 44.dp)
                .height(44.dp)
                // Keys must never take focus: that would pull the keyboard away from the terminal.
                .focusProperties { canFocus = false }
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 10.dp)) {
                Text(label, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }

    @Composable
    private fun IconKey(icon: ImageVector, description: String, onClick: () -> Unit) =
        IconKey(rememberVectorPainter(icon), description, onClick)

    @Composable
    private fun IconKey(icon: Painter, description: String, onClick: () -> Unit) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .padding(2.dp)
                .size(44.dp)
                .focusProperties { canFocus = false }
                .clickable(onClick = onClick),
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = description) }
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

/** How often the session list asks for sessions started elsewhere. */
private const val LIST_REFRESH_MS = 5_000L
