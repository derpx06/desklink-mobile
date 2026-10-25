/*
 * SPDX-FileCopyrightText: 2014 Ahmed I. Khalil <ahmedibrahimkhali@gmail.com>
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.mousepad

import android.content.Intent
import org.desklink.mobile.helpers.DeskNames
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.preference.PreferenceManager
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.R
import org.desklink.mobile.plugins.screen.ScreenControlPlugin
import org.desklink.mobile.plugins.screen.ScreenCoordinateMapper
import org.desklink.mobile.plugins.screen.ScreenPoint
import org.desklink.mobile.ui.compose.DeskLinkDarkTheme
import androidx.compose.material3.SegmentedButton
import org.desklink.mobile.ui.compose.components.TabularNumbers
import org.desklink.mobile.ui.compose.components.connectionStatsLabel
import org.desklink.mobile.ui.compose.components.rememberConnectionStats
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.heightIn
import org.desklink.mobile.ui.compose.DeskLinkTopAppBar
import org.desklink.mobile.ui.compose.SessionPasswordDialog
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import org.desklink.mobile.ui.compose.MonoFamily
import org.desklink.mobile.plugins.screen.SessionLiveNotification

class MousePadActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private var deviceId: String? = null
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private var prefsApplied = false

    // The remote-control surface is a screen viewer first. Trackpad remains
    // available as an explicit mode instead of hiding the phone display.
    private var controlMode by mutableStateOf(ControlMode.Touch)
    private var statusText by mutableStateOf("")

    private var currentSensitivity = 1.0f
    private var displayDpiMultiplier = 1.0f
    private var scrollDirection = 1
    private var scrollCoefficient = 1.0
    private var autoKeyboard by mutableStateOf(true)
    private var autoHideControls by mutableStateOf(true)
    private var haptics = true
    private var accelerationProfile = PointerAccelerationProfileFactory.getProfileWithName("default")
    private val mouseDelta = PointerAccelerationProfile.MouseDelta()

    /**
     * Touches straight to the gesture code, not batched to the next frame: a pointer move leaves
     * one vsync earlier (it is still sent at most once per frame by [PointerMotionCoalescer]).
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) window.decorView.requestUnbufferedDispatch(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceId = intent.getStringExtra("deviceId")
        displayDpiMultiplier = STANDARD_DPI / resources.displayMetrics.xdpi
        statusText = getString(R.string.remote_screen_requesting)

        prefs.registerOnSharedPreferenceChangeListener(this)
        applyPrefs()
        // How the screen starts; the top bar changes both for this session.
        // Fixed defaults: touch mode, the whole desktop shown (no longer settings).
        fillScreen = false
        displayMode = org.desklink.mobile.plugins.screen.ScreenDisplay.requested(this, deviceId.orEmpty())
        if (displayMode == org.desklink.mobile.plugins.screen.ScreenDisplay.EXTEND) {
            controlMode = ControlMode.Touch
            fillScreen = false
        }
        // A rotation recreates this activity but must not stop/re-request the
        // same authenticated desktop view. The process-level WebRTC peer and
        // renderer keep their session; an explicit refresh remains available
        // for a real retry after permission denial or stream loss.
        // Recreated (the system restoring the task after the process died) with no live view: the session died with
        // the process, so it is asked for again; a plain recreation (dark mode, language) keeps the one that is live.
        if (savedInstanceState == null || screenControlPlugin()?.remoteLive != true) {
            // The phone-side gate first (fingerprint for a remembered password, or the optional
            // phone-only check); the request then carries the password when it is known.
            org.desklink.mobile.ui.SessionGate.check(this, deviceId.orEmpty(), onOpen = ::requestDesktopScreen, onDenied = ::finish)
        }
        // A desktop is landscape: the view is always full screen, like a video.
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        setContent {
            DeskLinkDarkTheme(this) {
                RemoteControlScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyPrefs()
    }

    /** The view stopped because the screen went dark: nobody is watching, so no video keeps coming. */
    private var stoppedForScreenOff = false

    private val backgroundHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val endInBackground = Runnable {
        if (screenControlPlugin()?.remoteLive == true) {
            stoppedForScreenOff = true
            sendStopScreen()
        }
    }

    override fun onStop() {
        super.onStop()
        val interactive = getSystemService(android.os.PowerManager::class.java)?.isInteractive != false
        val live = screenControlPlugin()?.remoteLive == true
        when (backgroundAction(interactive, isFinishing, isChangingConfigurations, live)) {
            BackgroundAction.END_NOW -> {
                stoppedForScreenOff = true
                sendStopScreen()
            }
            // Left for another app: the desk keeps encoding and the phone decoding at full rate to nobody, so after a grace
            // period (coming back within it changes nothing) the view ends; the live notification can end it sooner.
            BackgroundAction.END_AFTER_GRACE -> backgroundHandler.postDelayed(endInBackground, BACKGROUND_GRACE_MS)
            BackgroundAction.NONE -> Unit
        }
    }

    override fun onStart() {
        super.onStart()
        backgroundHandler.removeCallbacks(endInBackground)
        if (stoppedForScreenOff) {
            stoppedForScreenOff = false
            requestDesktopScreen()
        }
    }

    override fun onDestroy() {
        backgroundHandler.removeCallbacks(endInBackground)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        if (isFinishing && !isChangingConfigurations) {
            sendStopScreen()
            SessionLiveNotification.refresh(this, deviceId)
        }
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        prefsApplied = false
    }

    @Composable
    private fun RemoteControlScreen() {
        val plugin = screenControlPlugin()
        val controlEnabled = plugin?.remoteControlEnabled == true
        val live = plugin?.remoteLive == true
        val prompt = plugin?.passwordPrompt
        // Control follows the picture: once the desktop is visible, ask for
        // control and keep asking until it is granted — no extra button. The
        // desktop grants at once when its portal approved input, which it does
        // in the same dialog as the screen. Not while the password is being
        // asked for, nor once the person declined to give it.
        val viewOnly = controlMode == ControlMode.View
        LaunchedEffect(live, controlEnabled, prompt, controlPasswordDeclined, viewOnly) {
            var first = true
            while (live && !controlEnabled && prompt == null && !controlPasswordDeclined && !viewOnly) {
                // Retries keep quiet: a refusal's reason (e.g. "allow remote
                // interaction on the computer") must stay shown.
                if (DeskLinkApplication.getInstance().requestRemoteControl(deviceId) && first) {
                    screenControlPlugin()?.onRemoteControlRequesting()
                }
                first = false
                kotlinx.coroutines.delay(CONTROL_RETRY_MS)
            }
        }
        LaunchedEffect(Unit) { setSystemBarsHidden(true) }
        HiddenKeyboard()
        // A text input on the desktop took focus: up comes the keyboard, the
        // way it does for the phone's own fields — and down when it leaves.
        val textFocused = plugin?.desktopTextFocused == true
        val input = controlEnabled && controlMode != ControlMode.View
        LaunchedEffect(textFocused, input) {
            if (input && autoKeyboard) setKeyboardShown(textFocused)
        }
        // View sends nothing: the keyboard goes away and what is still typed is dropped.
        LaunchedEffect(input) {
            keyboardView?.setInputEnabled(input)
            if (!input) {
                setKeyboardShown(false)
                keyboardView?.clearFocus()
            }
        }
        LaunchedEffect(live) { SessionLiveNotification.refresh(this@MousePadActivity, deviceId) }
        // F2: the desk could not extend — say so once; the screen now mirrors.
        val notice = plugin?.extendNotice
        LaunchedEffect(notice) {
            if (notice != null) {
                displayMode = org.desklink.mobile.plugins.screen.ScreenDisplay.MIRROR
                android.widget.Toast.makeText(this@MousePadActivity, notice, android.widget.Toast.LENGTH_LONG).show()
                plugin.extendNotice = null
            }
        }
        // F2: an extended display follows this view's size (a fold, multi-window): ask again.
        val area = org.desklink.mobile.plugins.screen.ScreenDisplay.viewArea
        var askedArea by remember { mutableStateOf(area) }
        LaunchedEffect(area, displayMode, live) {
            if (displayMode == org.desklink.mobile.plugins.screen.ScreenDisplay.EXTEND && live && area != null && askedArea != null && area != askedArea) {
                kotlinx.coroutines.delay(500)
                requestDesktopScreen()
            }
            if (live) askedArea = area
        }
        ImmersiveDesktop(controlEnabled, live)
        if (plugin != null && prompt != null) {
            SessionPasswordDialog(
                wrong = prompt.wrong,
                onSubmit = { password ->
                    DeskLinkApplication.getInstance().setSessionPassword(deviceId, password)
                    org.desklink.mobile.ui.SessionGate.offerToRemember(this@MousePadActivity, deviceId.orEmpty(), password)
                    plugin.passwordPrompt = null
                    // Control is asked for again by the loop above as the prompt clears;
                    // asking here as well would spend two guesses on one password.
                    if (!prompt.control) requestDesktopScreen()
                },
                onDismiss = {
                    plugin.passwordPrompt = null
                    // Without the password there is no view to stay on; a refused control
                    // still leaves the picture.
                    if (prompt.control) controlPasswordDeclined = true else finish()
                },
            )
        }
    }

    /** The person cancelled the password prompt for control: stop asking for it. */
    private var controlPasswordDeclined by mutableStateOf(false)

    private val stickyModifiers = StickyModifiers()
    private var zoomTransform by mutableStateOf(DesktopViewTransform())
    private var keyboardView: KeyListenerView? = null

    /**
     * The desktop, full width, zoomable. Touches act on the desktop where they
     * land (see [detectDesktopGestures]); pinch zooms the picture on the phone.
     */
    @Composable
    private fun DesktopScreen(
        modifier: Modifier,
        controlEnabled: Boolean,
        rounded: Boolean = true,
        fill: Boolean = false,
        onBarsTapped: () -> Unit = {},
    ) {
        val plugin = screenControlPlugin()
        val desktopWidth = plugin?.remoteScreenWidth ?: 0
        val desktopHeight = plugin?.remoteScreenHeight ?: 0
        var size by remember { mutableStateOf(IntSize.Zero) }
        // Fill (no bars, edges cropped) or fit; recomputed when the view or the
        // desktop changes size, e.g. on rotation.
        LaunchedEffect(fill, size, desktopWidth, desktopHeight) {
            zoomTransform = if (fill) {
                DesktopViewTransform.filling(size.width, size.height, desktopWidth, desktopHeight)
            } else {
                DesktopViewTransform()
            }
        }
        val density = LocalDensity.current
        // Interim predicted cursor: where this phone just put the desk's pointer, drawn at once
        // instead of waiting for the next frame to show the real one. Read in draw only.
        val localCursor = remember { mutableStateOf<Offset?>(null) }

        val sink = object : DesktopGestureSink {
            private fun point(at: Offset) = desktopPointFor(
                at.x, at.y, zoomTransform, size.width, size.height, desktopWidth, desktopHeight,
            ).also { if (it != null && controlEnabled && !trackpad) localCursor.value = at }
            private fun pad() = if (controlEnabled) mousePadPlugin() else null

            private val trackpad get() = controlMode == ControlMode.Trackpad

            override fun tap(at: Offset) {
                // The dark bars beside the picture are not the desktop: a tap
                // there shows or hides the controls, like a video player.
                if (point(at) == null || controlMode == ControlMode.View) {
                    onBarsTapped()
                    return
                }
                if (trackpad) {
                    pad()?.sendLeftClick()
                    return
                }
                feedback(HapticFeedbackConstants.KEYBOARD_TAP)
                val p = point(at)
                android.util.Log.i(
                    "DeskLink/RemoteDesktop",
                    "tap $at in ${size.width}x${size.height} -> $p on ${desktopWidth}x$desktopHeight control=$controlEnabled",
                )
                p ?: return
                pad()?.sendScreenTap(p.x.toInt(), p.y.toInt())
            }
            override fun rightClick(at: Offset) {
                if (trackpad) {
                    pad()?.sendRightClick()
                    return
                }
                val p = point(at) ?: return
                pad()?.apply {
                    sendMousePosition(p.x.toInt(), p.y.toInt())
                    sendRightClick()
                }
            }
            private var lastDrag = Offset.Zero

            override fun dragStart(at: Offset) {
                lastDrag = at
                if (trackpad) return
                val p = point(at) ?: return
                pad()?.sendScreenHold(p.x.toInt(), p.y.toInt())
            }
            override fun dragMove(to: Offset) {
                if (trackpad) {
                    // A trackpad moves the pointer by the finger's travel.
                    if (controlEnabled) sendAcceleratedDelta(to - lastDrag)
                    lastDrag = to
                    return
                }
                val p = point(to) ?: return
                pad()?.sendMousePosition(p.x.toInt(), p.y.toInt())
            }
            override fun dragEnd() {
                if (trackpad) return
                pad()?.sendSingleRelease()
            }
            override fun scrollStart(at: Offset) {
                // The desktop scrolls whatever is under its pointer.
                if (trackpad) return
                val p = point(at) ?: return
                pad()?.sendMousePosition(p.x.toInt(), p.y.toInt())
            }
            override fun scroll(by: Offset) {
                // Natural scrolling: content follows the fingers, at the same
                // speed whatever the zoom.
                val k = desktopPixelsPerViewPixel(zoomTransform, size.width, size.height, desktopWidth, desktopHeight) *
                    scrollCoefficient * scrollDirection
                pad()?.sendScroll(-by.x.toDouble() * k, -by.y.toDouble() * k)
            }
            override fun zoom(factor: Float, focus: Offset) {
                zoomTransform = zoomTransform.zoomedBy(
                    factor, focus.x, focus.y, size.width.toFloat(), size.height.toFloat(),
                )
            }
            override fun pan(by: Offset) {
                zoomTransform = zoomTransform.pannedBy(by.x, by.y, size.width.toFloat(), size.height.toFloat())
            }
            override fun longPressFeedback() {
                feedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }

        Box(
            modifier = modifier
                .clip(RoundedCornerShape(if (rounded) 12.dp else 0.dp))
                .background(Color.Black)
                .clipToBounds()
                .onSizeChanged {
                    size = it
                    // F2: Extend sizes the desk's virtual monitor to this view.
                    org.desklink.mobile.plugins.screen.ScreenDisplay.viewArea = it.width to it.height
                }
                .pointerInput(controlEnabled, desktopWidth, desktopHeight, size) {
                    detectDesktopGestures(sink)
                },
        ) {
            // The picture, letterboxed to the desktop's aspect ratio (16:9
            // until the desktop reports its size), then zoomed.
            val aspectW = if (desktopWidth > 0) desktopWidth else 16
            val aspectH = if (desktopHeight > 0) desktopHeight else 9
            val fit = if (size.width > 0 && size.height > 0) {
                minOf(size.width.toFloat() / aspectW, size.height.toFloat() / aspectH)
            } else {
                0f
            }
            val fittedW = aspectW * fit
            val fittedH = aspectH * fit
            val left = (size.width - fittedW) / 2f
            val top = (size.height - fittedH) / 2f
            if (fit > 0f) {
                RemoteDesktopView(
                    modifier = Modifier
                        .offset { IntOffset(left.toInt(), top.toInt()) }
                        .size(with(density) { fittedW.toDp() }, with(density) { fittedH.toDp() })
                        .graphicsLayer {
                            // Box point = unzoomed point * scale + offset; this
                            // view starts at (left, top) of the unzoomed box.
                            transformOrigin = TransformOrigin(0f, 0f)
                            scaleX = zoomTransform.scale
                            scaleY = zoomTransform.scale
                            translationX = left * (zoomTransform.scale - 1f) + zoomTransform.offsetX
                            translationY = top * (zoomTransform.scale - 1f) + zoomTransform.offsetY
                        },
                )
            }
            if (controlEnabled && controlMode != ControlMode.Trackpad) {
                Spacer(
                    Modifier.matchParentSize().drawWithContent {
                        val at = localCursor.value ?: return@drawWithContent
                        drawCircle(Color.Black.copy(alpha = 0.55f), 8.dp.toPx(), at)
                        drawCircle(Color.White, 5.5.dp.toPx(), at)
                    },
                )
            }
            // Live but without control, the amber notice says so (see ImmersiveDesktop).
            if (plugin?.remoteLive != true) {
                StatusOverlay(live = plugin?.remoteLive == true, modifier = Modifier.align(if (plugin?.remoteLive == true) Alignment.TopCenter else Alignment.Center))
            }
        }
    }

    /**
     * Before the picture: a spinner and "Connecting to manas-82xf…", with the stage below. A stage
     * that is not progress (a refusal, a timeout) shows without the spinner, with Try again. Once
     * the picture is up, a control refusal sits in a bar at the top.
     */
    @Composable
    private fun StatusOverlay(live: Boolean, modifier: Modifier) {
        val stage = ScreenControlPlugin.latestStatus
        val waiting = stage.isBlank() || stage in setOf(
            getString(R.string.remote_screen_requesting),
            getString(R.string.remote_screen_waiting_frame),
            getString(R.string.remote_control_requesting),
            getString(R.string.remote_screen_connected),
        )
        if (live) {
            if (stage.isBlank()) return
            Pill(modifier.padding(top = 80.dp, start = 24.dp, end = 24.dp).widthIn(max = 520.dp)) {
                Text(
                    stage,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            return
        }
        Column(
            modifier.padding(32.dp).widthIn(max = 520.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (waiting) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(32.dp))
                Spacer(Modifier.height(16.dp))
            }
            Text(
                stringResource(R.string.remote_connecting_to, deviceName()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            if (stage.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (!waiting) {
                Spacer(Modifier.height(16.dp))
                androidx.compose.material3.FilledTonalButton(onClick = { requestDesktopScreen() }) {
                    Text(stringResource(R.string.device_try_again))
                }
            }
        }
    }

    /** Stages that are progress, not a reason: the notice does not repeat them. */
    private fun progressStages() = setOf(
        getString(R.string.remote_screen_requesting),
        getString(R.string.remote_screen_waiting_frame),
        getString(R.string.remote_control_requesting),
        getString(R.string.remote_screen_connected),
    )

    private fun deviceName(): String = DeskNames.of(deviceId)

    /**
     * Only while the activity is started: a stopped one (screen off, another app) releases its
     * EglRenderer, which otherwise kept running and logging 0-frame statistics, and stops feeding
     * frames to it. Back in front, a fresh renderer attaches.
     */
    @Composable
    private fun RemoteDesktopView(modifier: Modifier) {
        val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        val state by lifecycle.currentStateAsState()
        if (state.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            StartedRemoteDesktopView(modifier)
        } else {
            Box(modifier)
        }
    }

    @Composable
    private fun StartedRemoteDesktopView(modifier: Modifier) {
        var renderer by remember { mutableStateOf<RemoteTextureRenderer?>(null) }
        val plugin = screenControlPlugin()
        DisposableEffect(renderer, plugin) {
            val current = renderer
            if (current == null || plugin == null) {
                return@DisposableEffect onDispose { }
            }
            plugin.attachRemoteVideoSink(current)
            onDispose { plugin.detachRemoteVideoSink(current) }
        }
        AndroidView(
            modifier = modifier,
            factory = { context -> RemoteTextureRenderer(context).also { renderer = it } },
            onRelease = { it.release() },
        )
    }

    /** Keys a phone keyboard lacks; over the picture while the keyboard is up. */
    @Composable
    private fun KeyBar(controlEnabled: Boolean, modifier: Modifier = Modifier, onUsed: () -> Unit = {}) {
        val pad = { mousePadPlugin() }
        fun special(code: Int) {
            onUsed()
            val np = NetworkPacket(MousePadPlugin.PACKET_TYPE_MOUSEPAD_REQUEST)
            np["specialKey"] = code
            stickyModifiers.applyTo(np)
            pad()?.sendPacket(np)
        }
        Pill(modifier) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PillText("ctrl", controlEnabled, selected = stickyModifiers.ctrl, mono = true) { onUsed(); stickyModifiers.ctrl = !stickyModifiers.ctrl }
                PillText("alt", controlEnabled, selected = stickyModifiers.alt, mono = true) { onUsed(); stickyModifiers.alt = !stickyModifiers.alt }
                PillText("esc", controlEnabled, mono = true) { special(SPECIAL_ESCAPE) }
                PillText("tab", controlEnabled, mono = true) { special(SPECIAL_TAB) }
                PillIcon(R.drawable.sym_arrow_back, stringResource(R.string.terminal_key_left), controlEnabled) { special(SPECIAL_LEFT) }
                PillIcon(R.drawable.sym_arrow_upward, stringResource(R.string.terminal_key_up), controlEnabled) { special(SPECIAL_UP) }
                PillIcon(R.drawable.sym_arrow_downward, stringResource(R.string.terminal_key_down), controlEnabled) { special(SPECIAL_DOWN) }
                PillIcon(R.drawable.sym_arrow_forward, stringResource(R.string.terminal_key_right), controlEnabled) { special(SPECIAL_RIGHT) }
            }
        }
    }

    /** The view the soft keyboard types into; composed in every layout so a
     * rotation or full-screen switch does not drop the keyboard. */
    @Composable
    private fun HiddenKeyboard() {
        AndroidView(
            modifier = Modifier.size(1.dp),
            factory = { context ->
                KeyListenerView(context, null).also {
                    it.setDeviceId(deviceId)
                    it.setInputEnabled(screenControlPlugin()?.remoteControlEnabled == true && controlMode != ControlMode.View)
                    it.setStickyModifiers(stickyModifiers)
                    keyboardView = it
                }
            },
        )
    }

    /**
     * The desktop filling the screen, like a video in landscape, with the design's floating pills:
     * back, name and latency top left; End top right; the amber notice while control is not
     * granted; and the mode, clicks, keyboard and Super at the bottom. They fade after a few
     * seconds; the handle in the corner (or a tap on the bars beside the picture) brings them back.
     */
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    @Composable
    private fun ImmersiveDesktop(controlEnabled: Boolean, live: Boolean) {
        var controlsVisible by remember { mutableStateOf(true) }
        var lastUse by remember { mutableStateOf(0L) }
        var more by remember { mutableStateOf(false) }
        val touch = { lastUse = SystemClock.uptimeMillis() }
        LaunchedEffect(controlsVisible, lastUse, autoHideControls, more) {
            if (controlsVisible && autoHideControls && !more) {
                kotlinx.coroutines.delay(CONTROLS_HIDE_MS)
                controlsVisible = false
            }
        }
        val deviceName = remember { deviceName() }
        val input = controlEnabled && controlMode != ControlMode.View
        val keyboardUp = WindowInsets.isImeVisible
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            DesktopScreen(
                modifier = Modifier.fillMaxSize(),
                controlEnabled = input,
                rounded = false,
                fill = fillScreen,
                onBarsTapped = {
                    controlsVisible = !controlsVisible
                    touch()
                },
            )
            DeskControls(
                visible = controlsVisible,
                deviceName = deviceName,
                rttMs = rememberConnectionStats(deviceId)?.roundTripTimeMs,
                live = live,
                controlEnabled = controlEnabled,
                mode = controlMode,
                onMode = { touch(); controlMode = it },
                onBack = { finish() },
                onEnd = { finish() },
                onLeft = { touch(); mousePadPlugin()?.sendLeftClick() },
                onRight = { touch(); mousePadPlugin()?.sendRightClick() },
                onKeyboard = { touch(); toggleKeyboard() },
                onSuper = {
                    touch()
                    val np = NetworkPacket(MousePadPlugin.PACKET_TYPE_MOUSEPAD_REQUEST)
                    np["specialKey"] = SPECIAL_SUPER
                    mousePadPlugin()?.sendPacket(np)
                },
                keys = { if (keyboardUp) KeyBar(input, onUsed = touch) },
                onMore = { touch(); more = true },
                reason = ScreenControlPlugin.latestStatus.takeUnless { it in progressStages() },
                display = displayMode,
                onDisplay = { mode ->
                    touch()
                    if (mode != displayMode) {
                        displayMode = mode
                        org.desklink.mobile.plugins.screen.ScreenDisplay.choose(this@MousePadActivity, deviceId.orEmpty(), mode)
                        if (mode == org.desklink.mobile.plugins.screen.ScreenDisplay.EXTEND) {
                            // A monitor of its own: touch where you mean, 1:1, no crop.
                            controlMode = ControlMode.Touch
                            fillScreen = false
                        }
                        requestDesktopScreen()
                    }
                },
                moreMenu = {
                    androidx.compose.material3.DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(if (fillScreen) R.string.remote_zoom_fit else R.string.remote_zoom_fill)) },
                            onClick = { more = false; fillScreen = !fillScreen },
                        )
                        if (zoomTransform.scale > 1f) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(stringResource(R.string.remote_zoom_reset)) },
                                onClick = { more = false; fillScreen = false; zoomTransform = DesktopViewTransform() },
                            )
                        }
                        if ((screenControlPlugin()?.remoteScreenCount ?: 1) > 1) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(stringResource(R.string.remote_switch_screen)) },
                                onClick = { more = false; DeskLinkApplication.getInstance().switchRemoteScreen(deviceId) },
                            )
                        }
                        // F1: the desk's sound here, the same switch as Settings → "Play <desk>'s sound here".
                        val sound = org.desklink.mobile.plugins.audio.PhoneAudio.playDeskSound(this@MousePadActivity, deviceId.orEmpty())
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(if (sound) R.string.remote_sound_off else R.string.remote_sound_on)) },
                            onClick = {
                                more = false
                                org.desklink.mobile.plugins.audio.PhoneAudio.setPlayDeskSound(this@MousePadActivity, deviceId.orEmpty(), !sound)
                                DeskLinkApplication.getInstance().webRtcCoordinator(deviceId)?.let { if (sound) it.stopDeskSound() else it.requestDeskSound() }
                            },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.remote_screen_retry)) },
                            onClick = { more = false; requestDesktopScreen() },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings)) },
                            onClick = { more = false; openSettings() },
                        )
                    }
                },
            )
            if (!controlsVisible) {
                Pill(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                ) {
                    PillIcon(R.drawable.sym_expand_less, stringResource(R.string.remote_show_controls)) {
                        controlsVisible = true; touch()
                    }
                }
            }
        }
    }

    /** F2: what this screen asks the desk for (the switch shows it; a refusal falls back to mirror). */
    private var displayMode by mutableStateOf(org.desklink.mobile.plugins.screen.ScreenDisplay.MIRROR)

    /** "Edge to edge": fill the phone, cropping a little — never stretching.
     * Off by default: the whole desktop is visible at its true aspect. */
    private var fillScreen by mutableStateOf(false)

    private fun setSystemBarsHidden(hidden: Boolean) {
        // Edge to edge means the camera cutout's strip too: by default Android
        // keeps landscape apps out of it, leaving a black band on that side.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (hidden) {
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                } else {
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
        }
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        if (hidden) {
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Whether the keyboard is on screen now — not whether our view has
     * focus: it keeps focus after Back dismisses the keyboard, and toggling on
     * focus then "hid" an already hidden keyboard. */
    private fun keyboardVisible(): Boolean =
        androidx.core.view.ViewCompat.getRootWindowInsets(window.decorView)
            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true

    private fun toggleKeyboard() {
        setKeyboardShown(!keyboardVisible())
    }

    private fun setKeyboardShown(shown: Boolean) {
        val view = keyboardView ?: return
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, view)
        if (shown) {
            view.requestFocus()
            controller.show(androidx.core.view.WindowInsetsCompat.Type.ime())
        } else {
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
    }

    private fun feedback(kind: Int) {
        if (haptics) window.decorView.performHapticFeedback(kind)
    }

    private fun sendAcceleratedDelta(dragAmount: Offset) {
        val plugin = mousePadPlugin() ?: run {
            finish()
            return
        }
        accelerationProfile.touchMoved(
            dragAmount.x * displayDpiMultiplier * currentSensitivity,
            dragAmount.y * displayDpiMultiplier * currentSensitivity,
            SystemClock.uptimeMillis()
        )
        val delta = accelerationProfile.commitAcceleratedMouseDelta(mouseDelta)
        if (delta.x != 0f || delta.y != 0f) {
            plugin.sendMouseDelta(delta.x, delta.y)
        }
    }

    private fun requestDesktopScreen() {
        controlPasswordDeclined = false
        if (screenControlPlugin() == null) {
            statusText = getString(R.string.remote_screen_plugin_missing)
            return
        }
        val requested = DeskLinkApplication.getInstance().requestRemoteView(
            deviceId,
            org.desklink.mobile.webrtc.WebRtcScreenDirection.DESKTOP_TO_PHONE,
        )
        screenControlPlugin()?.onRemoteScreenRequesting()
        statusText = if (requested) {
            getString(R.string.remote_screen_requesting)
        } else {
            getString(R.string.remote_screen_error)
        }
    }

    private fun sendStopScreen() {
        DeskLinkApplication.getInstance().stopRemoteSession(deviceId)
    }

    private fun openSettings() {
        // Pointer speed and scroll direction live in Settings, Control.
        startActivity(
            Intent(this, org.desklink.mobile.ui.MainActivity::class.java)
                .putExtra(org.desklink.mobile.ui.MainActivity.EXTRA_SETTINGS_PAGE, org.desklink.mobile.ui.compose.screen.settings.SettingsPage.CONTROL.route)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }

    private fun mousePadPlugin(): MousePadPlugin? =
        DeskLinkApplication.getInstance().getDevicePlugin(deviceId, MousePadPlugin::class.java)

    private fun screenControlPlugin(): ScreenControlPlugin? =
        DeskLinkApplication.getInstance().getDevicePlugin(deviceId, ScreenControlPlugin::class.java)

    private fun applyPrefs() {
        if (prefsApplied) {
            return
        }

        scrollDirection = if (prefs.getBoolean(getString(R.string.mousepad_scroll_direction), false)) {
            -1
        } else {
            1
        }

        // A percentage: 100 moves the content exactly with the fingers.
        scrollCoefficient = 1.0
        // Fixed defaults (no longer settings): the keyboard opens for a text field, the controls hide, the screen stays on.
        autoKeyboard = true
        autoHideControls = true
        haptics = true
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val sensitivitySetting = prefs.getString(
            getString(R.string.mousepad_sensitivity_key),
            getString(R.string.mousepad_default_sensitivity)
        )
        currentSensitivity = when (sensitivitySetting) {
            "slowest" -> 0.2f
            "aboveSlowest" -> 0.5f
            "default" -> 1.0f
            "aboveDefault" -> 1.5f
            "fastest" -> 2.0f
            else -> 1.0f
        }

        accelerationProfile =
            PointerAccelerationProfileFactory.getProfileWithName(getString(R.string.mousepad_default_acceleration_profile))
        prefsApplied = true
    }

    /** What leaving the screen does to a live view of the desk. */
    enum class BackgroundAction { NONE, END_NOW, END_AFTER_GRACE }

    companion object {
        /** A view of the desk left running behind another app ends after this long. */
        const val BACKGROUND_GRACE_MS = 60_000L

        fun backgroundAction(interactive: Boolean, finishing: Boolean, changingConfiguration: Boolean, live: Boolean): BackgroundAction = when {
            !live || finishing || changingConfiguration -> BackgroundAction.NONE
            !interactive -> BackgroundAction.END_NOW
            else -> BackgroundAction.END_AFTER_GRACE
        }

        private const val STANDARD_DPI = 240.0f
        /** Well inside the desktop's per-packet limit even at four bytes a character. */
        private const val TEXT_CHUNK_CHARS = 200
        private const val CONTROL_RETRY_MS = 3_000L
        private const val CONTROLS_HIDE_MS = 4_000L
        // Desktop `specialKey` codes (KeyListenerView.SpecialKeysMap, and the
        // desktop's `special_key_to_keysym`).
        private const val SPECIAL_TAB = 2
        private const val SPECIAL_LEFT = 4
        private const val SPECIAL_UP = 5
        private const val SPECIAL_RIGHT = 6
        private const val SPECIAL_DOWN = 7
        private const val SPECIAL_ESCAPE = 14
        private const val SPECIAL_SUPER = 40
    }
}
