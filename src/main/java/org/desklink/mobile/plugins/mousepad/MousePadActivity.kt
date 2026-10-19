/*
 * SPDX-FileCopyrightText: 2014 Ahmed I. Khalil <ahmedibrahimkhali@gmail.com>
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.mousepad

import android.content.Intent
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
import org.desklink.mobile.ui.PluginSettingsActivity
import org.desklink.mobile.ui.compose.DeskLinkTheme
import org.desklink.mobile.ui.compose.DeskLinkTopAppBar

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceId = intent.getStringExtra("deviceId")
        displayDpiMultiplier = STANDARD_DPI / resources.displayMetrics.xdpi
        statusText = getString(R.string.remote_screen_requesting)

        prefs.registerOnSharedPreferenceChangeListener(this)
        applyPrefs()
        // How the screen starts; the top bar changes both for this session.
        if (prefs.getString(getString(R.string.remote_pref_default_mode), "touch") == "trackpad") {
            controlMode = ControlMode.Trackpad
        }
        fillScreen = prefs.getBoolean(getString(R.string.remote_pref_edge_to_edge), false)
        // A rotation recreates this activity but must not stop/re-request the
        // same authenticated desktop view. The process-level WebRTC peer and
        // renderer keep their session; an explicit refresh remains available
        // for a real retry after permission denial or stream loss.
        if (savedInstanceState == null) {
            requestDesktopScreen()
        }
        // A desktop is landscape: the view is always full screen, like a video.
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        setContent {
            DeskLinkTheme(this) {
                RemoteControlScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyPrefs()
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        if (isFinishing && !isChangingConfigurations) {
            sendStopScreen()
        }
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        prefsApplied = false
    }

    @Composable
    private fun RemoteControlScreen() {
        val screenStatus = ScreenControlPlugin.latestStatus.ifBlank { statusText }
        val plugin = screenControlPlugin()
        val controlEnabled = plugin?.remoteControlEnabled == true
        val live = plugin?.remoteLive == true
        // Control follows the picture: once the desktop is visible, ask for
        // control and keep asking until it is granted — no extra button. The
        // desktop grants at once when its portal approved input, which it does
        // in the same dialog as the screen.
        LaunchedEffect(live, controlEnabled) {
            var first = true
            while (live && !controlEnabled) {
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
        LaunchedEffect(textFocused, controlEnabled) {
            if (controlEnabled && autoKeyboard) setKeyboardShown(textFocused)
        }
        ImmersiveDesktop(controlEnabled, screenStatus)
    }

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

        val sink = object : DesktopGestureSink {
            private fun point(at: Offset) = desktopPointFor(
                at.x, at.y, zoomTransform, size.width, size.height, desktopWidth, desktopHeight,
            )
            private fun pad() = if (controlEnabled) mousePadPlugin() else null

            private val trackpad get() = controlMode == ControlMode.Trackpad

            override fun tap(at: Offset) {
                // The dark bars beside the picture are not the desktop: a tap
                // there shows or hides the controls, like a video player.
                if (point(at) == null) {
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
                .onSizeChanged { size = it }
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
            if (plugin?.remoteLive != true || !controlEnabled) {
                Text(
                    text = ScreenControlPlugin.latestStatus.ifBlank {
                        stringResource(R.string.remote_live_screen_pending)
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(if (plugin?.remoteLive == true) Alignment.TopCenter else Alignment.Center)
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }

    @Composable
    private fun RemoteDesktopView(modifier: Modifier) {
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

    /** Keys a phone keyboard lacks, and the keyboard itself. */
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
        Row(
            modifier = modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(onClick = { onUsed(); toggleKeyboard() }, enabled = controlEnabled) {
                Text(stringResource(R.string.remote_key_keyboard))
            }
            FilterChip(
                selected = stickyModifiers.ctrl,
                onClick = { onUsed(); stickyModifiers.ctrl = !stickyModifiers.ctrl },
                label = { Text("Ctrl") },
                enabled = controlEnabled,
            )
            FilterChip(
                selected = stickyModifiers.alt,
                onClick = { onUsed(); stickyModifiers.alt = !stickyModifiers.alt },
                label = { Text("Alt") },
                enabled = controlEnabled,
            )
            OutlinedButton(onClick = { special(SPECIAL_SUPER) }, enabled = controlEnabled) { Text("Super") }
            OutlinedButton(onClick = { special(SPECIAL_ESCAPE) }, enabled = controlEnabled) { Text("Esc") }
            OutlinedButton(onClick = { special(SPECIAL_TAB) }, enabled = controlEnabled) { Text("Tab") }
            OutlinedButton(onClick = { special(SPECIAL_LEFT) }, enabled = controlEnabled) { Text("←") }
            OutlinedButton(onClick = { special(SPECIAL_UP) }, enabled = controlEnabled) { Text("↑") }
            OutlinedButton(onClick = { special(SPECIAL_DOWN) }, enabled = controlEnabled) { Text("↓") }
            OutlinedButton(onClick = { special(SPECIAL_RIGHT) }, enabled = controlEnabled) { Text("→") }
            if (zoomTransform.scale > 1f) {
                OutlinedButton(onClick = { onUsed(); fillScreen = false; zoomTransform = DesktopViewTransform() }) {
                    Text(stringResource(R.string.remote_zoom_fit))
                }
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
                    it.setStickyModifiers(stickyModifiers)
                    keyboardView = it
                }
            },
        )
    }

    /**
     * The desktop filling the screen, like a video in landscape. Controls float
     * over it and fade after a few seconds; the handle in the corner brings
     * them back. Taps on the picture stay clicks on the desktop, which is why
     * the controls have their own handle instead of YouTube's tap-to-show.
     */
    @Composable
    private fun ImmersiveDesktop(controlEnabled: Boolean, status: String) {
        var controlsVisible by remember { mutableStateOf(true) }
        var lastUse by remember { mutableStateOf(0L) }
        val touch = { lastUse = SystemClock.uptimeMillis() }
        LaunchedEffect(controlsVisible, lastUse, autoHideControls) {
            if (controlsVisible && autoHideControls) {
                kotlinx.coroutines.delay(CONTROLS_HIDE_MS)
                controlsVisible = false
            }
        }
        val scrim = Color.Black.copy(alpha = 0.55f)
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            DesktopScreen(
                modifier = Modifier.fillMaxSize(),
                controlEnabled = controlEnabled,
                rounded = false,
                fill = fillScreen,
                onBarsTapped = {
                    controlsVisible = !controlsVisible
                    touch()
                },
            )
            androidx.compose.animation.AnimatedVisibility(
                visible = controlsVisible,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(scrim)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { finish() }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.remote_close),
                            tint = Color.White,
                        )
                    }
                    Text(
                        text = status,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    if ((screenControlPlugin()?.remoteScreenCount ?: 1) > 1) {
                        androidx.compose.material3.TextButton(onClick = {
                            touch()
                            DeskLinkApplication.getInstance().switchRemoteScreen(deviceId)
                        }) {
                            Text(stringResource(R.string.remote_switch_screen), color = Color.White)
                        }
                    }
                    androidx.compose.material3.TextButton(onClick = {
                        touch()
                        controlMode = if (controlMode == ControlMode.Touch) ControlMode.Trackpad else ControlMode.Touch
                    }) {
                        Text(
                            stringResource(
                                if (controlMode == ControlMode.Touch) R.string.remote_mode_trackpad else R.string.remote_mode_touch,
                            ),
                            color = Color.White,
                        )
                    }
                    androidx.compose.material3.TextButton(onClick = { touch(); fillScreen = !fillScreen }) {
                        Text(
                            stringResource(if (fillScreen) R.string.remote_zoom_fit else R.string.remote_zoom_fill),
                            color = Color.White,
                        )
                    }
                    IconButton(onClick = { touch(); requestDesktopScreen() }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.remote_screen_retry),
                            tint = Color.White,
                        )
                    }
                    IconButton(onClick = { touch(); openSettings() }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings),
                            tint = Color.White,
                        )
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = controlsVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
            ) {
                KeyBar(
                    controlEnabled = controlEnabled,
                    modifier = Modifier.background(scrim).padding(horizontal = 8.dp, vertical = 6.dp),
                    onUsed = touch,
                )
            }
            if (!controlsVisible) {
                androidx.compose.material3.SmallFloatingActionButton(
                    onClick = { controlsVisible = true; touch() },
                    containerColor = scrim,
                    contentColor = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp),
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.remote_show_controls),
                    )
                }
            }
        }
    }

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
        val intent = Intent(this, PluginSettingsActivity::class.java)
            .putExtra(PluginSettingsActivity.EXTRA_DEVICE_ID, deviceId)
            .putExtra(PluginSettingsActivity.EXTRA_PLUGIN_KEY, MousePadPlugin::class.java.simpleName)
        startActivity(intent)
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
        scrollCoefficient = prefs.getInt(getString(R.string.mousepad_scroll_sensitivity), 100)
            .coerceIn(25, 300) / 100.0
        autoKeyboard = prefs.getBoolean(getString(R.string.remote_pref_auto_keyboard), true)
        autoHideControls = prefs.getBoolean(getString(R.string.remote_pref_autohide_controls), true)
        haptics = prefs.getBoolean(getString(R.string.remote_pref_haptics), true)
        if (prefs.getBoolean(getString(R.string.remote_pref_keep_screen_on), true)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

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

        val accelerationProfileName = prefs.getString(
            getString(R.string.mousepad_acceleration_profile_key),
            getString(R.string.mousepad_default_acceleration_profile)
        )
        accelerationProfile =
            PointerAccelerationProfileFactory.getProfileWithName(accelerationProfileName ?: "default")
        prefsApplied = true
    }

    /** How a touch on the picture acts: directly where it lands (touch), or
     * moving the desktop pointer like a laptop's trackpad. */
    private enum class ControlMode {
        Touch,
        Trackpad,
    }

    companion object {
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
