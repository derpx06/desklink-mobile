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
import org.desklink.mobile.webrtc.WebRtcRuntime
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

class MousePadActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private var deviceId: String? = null
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private var prefsApplied = false

    // The remote-control surface is a screen viewer first. Trackpad remains
    // available as an explicit mode instead of hiding the phone display.
    private var controlMode by mutableStateOf(ControlMode.Screen)
    private var statusText by mutableStateOf("")
    private var textInput by mutableStateOf("")

    private var currentSensitivity = 1.0f
    private var displayDpiMultiplier = 1.0f
    private var scrollDirection = 1
    private var scrollCoefficient = 1.0
    private var accelerationProfile = PointerAccelerationProfileFactory.getProfileWithName("default")
    private val mouseDelta = PointerAccelerationProfile.MouseDelta()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceId = intent.getStringExtra("deviceId")
        displayDpiMultiplier = STANDARD_DPI / resources.displayMetrics.xdpi
        statusText = getString(R.string.remote_screen_requesting)

        prefs.registerOnSharedPreferenceChangeListener(this)
        applyPrefs()
        // A rotation recreates this activity but must not stop/re-request the
        // same authenticated desktop view. The process-level WebRTC peer and
        // renderer keep their session; an explicit refresh remains available
        // for a real retry after permission denial or stream loss.
        if (savedInstanceState == null) {
            requestDesktopScreen()
        }

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
        Scaffold(
            modifier = Modifier.safeDrawingPadding(),
            topBar = {
                DeskLinkTopAppBar(
                    title = stringResource(R.string.pref_plugin_mousepad),
                    subTitle = screenStatus,
                    navIconOnClick = { onBackPressedDispatcher.onBackPressed() },
                    navIconDescription = getString(androidx.appcompat.R.string.abc_action_bar_up_description),
                    actions = {
                        IconButton(onClick = { requestDesktopScreen() }) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.remote_screen_retry)
                            )
                        }
                        IconButton(onClick = { openSettings() }) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = stringResource(R.string.settings)
                            )
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextComposer()
                ModeSelector()
                FilledTonalButton(
                    onClick = {
                        val granted = DeskLinkApplication.getInstance().requestRemoteControl(deviceId)
                        statusText = if (granted) {
                            getString(R.string.remote_control_requesting)
                        } else {
                            getString(R.string.remote_control_unavailable)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.remote_enable_control))
                }
                RemoteInputSurface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
                if (controlMode == ControlMode.Trackpad) {
                    ClickControls()
                }
            }
        }
    }

    @Composable
    private fun ModeSelector() {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ModeButton(
                selected = controlMode == ControlMode.Screen,
                label = stringResource(R.string.remote_mode_screen),
                modifier = Modifier.weight(1f),
                onClick = { controlMode = ControlMode.Screen }
            )
            ModeButton(
                selected = controlMode == ControlMode.Trackpad,
                label = stringResource(R.string.remote_mode_trackpad),
                modifier = Modifier.weight(1f),
                onClick = { controlMode = ControlMode.Trackpad }
            )
        }
    }

    @Composable
    private fun ModeButton(
        selected: Boolean,
        label: String,
        modifier: Modifier,
        onClick: () -> Unit
    ) {
        if (selected) {
            Button(onClick = onClick, modifier = modifier.height(40.dp)) {
                Text(label)
            }
        } else {
            OutlinedButton(onClick = onClick, modifier = modifier.height(40.dp)) {
                Text(label)
            }
        }
    }

    @Composable
    private fun RemoteInputSurface(modifier: Modifier = Modifier) {
        var size by mutableStateOf(IntSize.Zero)
        val shape = RoundedCornerShape(18.dp)
        val colors = MaterialTheme.colorScheme
        Surface(
            modifier = modifier
                .clip(shape)
                .background(colors.surfaceVariant)
                .border(1.dp, colors.outlineVariant, shape)
                .onSizeChanged { size = it }
                .pointerInput(controlMode, size) {
                    detectTapGestures(
                        onTap = { offset -> handleSurfaceTap(offset, size) },
                        onDoubleTap = { sendDoubleClick() },
                        onLongPress = { offset ->
                            window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            if (controlMode == ControlMode.Screen) {
                                mapScreenPoint(offset, size)?.let { point ->
                                    mousePadPlugin()?.sendScreenHold(point.x, point.y) ?: finish()
                                }
                            } else {
                                mousePadPlugin()?.sendSingleHold() ?: finish()
                            }
                        }
                    )
                }
                .pointerInput(controlMode, size) {
                    detectDragGestures { change, dragAmount ->
                        if (controlMode == ControlMode.Trackpad) {
                            sendAcceleratedDelta(dragAmount)
                        } else {
                            mapScreenPoint(change.position, size)?.let { point ->
                                mousePadPlugin()?.sendMousePosition(point.x, point.y) ?: finish()
                            }
                        }
                        change.consume()
                    }
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val scroll = event.changes.fold(Offset.Zero) { acc, change ->
                                acc + change.scrollDelta
                            }
                            if (scroll.x != 0f || scroll.y != 0f) {
                                mousePadPlugin()?.sendScroll(
                                    scroll.x.toDouble(),
                                    scrollDirection * scroll.y.toDouble() * scrollCoefficient
                                ) ?: finish()
                            }
                        }
                    }
                },
            color = colors.surfaceVariant,
            tonalElevation = 2.dp
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (controlMode == ControlMode.Screen) 4.dp else 20.dp),
                contentAlignment = Alignment.Center
            ) {
                if (controlMode == ControlMode.Screen) {
                    RemoteVideoPreview(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 220.dp, height = 124.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(colors.surface)
                                .border(1.dp, colors.outlineVariant, RoundedCornerShape(14.dp))
                        )
                        Text(
                            text = if (controlMode == ControlMode.Screen) {
                                stringResource(R.string.remote_live_screen_pending)
                            } else {
                                stringResource(R.string.remote_trackpad_ready)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = if (controlMode == ControlMode.Screen) {
                                stringResource(R.string.remote_live_screen_hint)
                            } else {
                                stringResource(R.string.remote_trackpad_hint)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun RemoteVideoPreview(modifier: Modifier = Modifier) {
        var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
        val plugin = screenControlPlugin()

        DisposableEffect(renderer, plugin) {
            val current = renderer
            if (current == null || plugin == null) {
                return@DisposableEffect onDispose { }
            }
            plugin.attachRemoteVideoSink(current)
            onDispose {
                plugin.detachRemoteVideoSink(current)
                current.release()
            }
        }

        AndroidView(
            modifier = modifier,
            factory = { viewContext ->
                SurfaceViewRenderer(viewContext).apply {
                    init(WebRtcRuntime.eglContext(viewContext), null)
                    setEnableHardwareScaler(true)
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    renderer = this
                }
            },
            update = { it.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT) },
        )
    }

    @Composable
    private fun ClickControls() {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = { sendLeftClick() },
                modifier = Modifier.weight(1f).height(40.dp)
            ) {
                Text(stringResource(R.string.remote_click_left))
            }
            FilledTonalButton(
                onClick = { sendMiddleClick() },
                modifier = Modifier.weight(1f).height(40.dp)
            ) {
                Text(stringResource(R.string.remote_click_middle))
            }
            FilledTonalButton(
                onClick = { sendRightClick() },
                modifier = Modifier.weight(1f).height(40.dp)
            ) {
                Text(stringResource(R.string.remote_click_right))
            }
        }
    }

    @Composable
    private fun TextComposer() {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = textInput,
                onValueChange = { textInput = it },
                label = { Text(stringResource(R.string.click_here_to_type)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = { sendTextInput() },
                enabled = textInput.isNotEmpty(),
                modifier = Modifier.height(48.dp)
            ) {
                Icon(Icons.Default.Send, contentDescription = null)
            }
        }
    }

    private fun handleSurfaceTap(offset: Offset, size: IntSize) {
        val plugin = mousePadPlugin() ?: run {
            finish()
            return
        }

        if (controlMode == ControlMode.Screen) {
            mapScreenPoint(offset, size)?.let { point ->
                plugin.sendScreenTap(point.x, point.y)
            }
            return
        }
        plugin.sendLeftClick()
    }

    private fun mapScreenPoint(offset: Offset, size: IntSize) =
        mapFittedScreenPoint(offset, size)

    private fun mapFittedScreenPoint(offset: Offset, size: IntSize): ScreenPoint? {
        val remoteWidth = ScreenControlPlugin.latestFrameWidth
        val remoteHeight = ScreenControlPlugin.latestFrameHeight
        if (remoteWidth <= 0 || remoteHeight <= 0 || size.width <= 0 || size.height <= 0) {
            return null
        }

        val scale = minOf(size.width.toFloat() / remoteWidth, size.height.toFloat() / remoteHeight)
        val fittedWidth = remoteWidth * scale
        val fittedHeight = remoteHeight * scale
        val left = (size.width - fittedWidth) / 2f
        val top = (size.height - fittedHeight) / 2f
        if (offset.x < left || offset.x > left + fittedWidth || offset.y < top || offset.y > top + fittedHeight) {
            return null
        }

        return ScreenCoordinateMapper(
            remoteWidth = remoteWidth,
            remoteHeight = remoteHeight,
            viewWidth = fittedWidth.toInt().coerceAtLeast(1),
            viewHeight = fittedHeight.toInt().coerceAtLeast(1)
        ).mapTouch(offset.x - left, offset.y - top)
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

    private fun sendLeftClick() {
        mousePadPlugin()?.sendLeftClick() ?: finish()
    }

    private fun sendMiddleClick() {
        mousePadPlugin()?.sendMiddleClick() ?: finish()
    }

    private fun sendRightClick() {
        mousePadPlugin()?.sendRightClick() ?: finish()
    }

    private fun sendDoubleClick() {
        mousePadPlugin()?.sendDoubleClick() ?: finish()
    }

    private fun sendTextInput() {
        if (textInput.isBlank()) {
            return
        }
        val plugin = mousePadPlugin() ?: run {
            finish()
            return
        }
        plugin.sendText(textInput)
        textInput = ""
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

        val scrollSensitivity = prefs.getInt(getString(R.string.mousepad_scroll_sensitivity), 100)
            .coerceAtLeast(1)
        scrollCoefficient = Math.pow((scrollSensitivity / 100f).toDouble(), 1.5)

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

    private enum class ControlMode {
        Screen,
        Trackpad
    }

    companion object {
        private const val STANDARD_DPI = 240.0f
    }
}
