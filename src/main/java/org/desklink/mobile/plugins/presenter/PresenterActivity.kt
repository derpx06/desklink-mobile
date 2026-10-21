/*
 * SPDX-FileCopyrightText: 2023 Dmitry Yudin <dgyudin@gmail.com>
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.plugins.presenter

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media.VolumeProviderCompat
import androidx.preference.PreferenceManager
import kotlinx.coroutines.delay
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.DeskLinkDarkTheme
import org.desklink.mobile.ui.compose.MonoFamily

private const val VOLUME_UP = 1
private const val VOLUME_DOWN = -1

private val Black = Color(0xFF0E0F11)
private val Text = Color(0xFFF2F2F3)
private val Dim = Color(0xFFA1A1AA)
private val NextBg = Color(0xFFA9B8FF)
private val OnNext = Color(0xFF14204F)
private val Button = Color(0x14FFFFFF)

/**
 * Slides on the computer: a big Next, Back and Blank, a slide counter kept here (the desk does not
 * report one) and the time since the talk started. Keys go as `mousepad.request`, so the desk
 * needs control allowed, like any other input.
 */
class PresenterActivity : AppCompatActivity() {

    private val offScreenControlsSupported = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA
    private val mediaSession by lazy {
        if (offScreenControlsSupported) MediaSessionCompat(this, "kdeconnect") else null
    }
    private lateinit var plugin: PresenterPlugin
    private var volumeKeys = false

    private var slide by mutableIntStateOf(1)
    private var total by mutableIntStateOf(0)
    private var startedAt by mutableLongStateOf(SystemClock.elapsedRealtime())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeKeys = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean(getString(R.string.pref_presenter_enable_volume_keys), true)
        savedInstanceState?.let {
            slide = it.getInt(STATE_SLIDE, 1)
            total = it.getInt(STATE_TOTAL, 0)
            startedAt = it.getLong(STATE_STARTED, startedAt)
        }

        val deviceId = intent.getStringExtra("deviceId")
        plugin = DeskLinkApplication.getInstance().getDevicePlugin(deviceId, PresenterPlugin::class.java)
            ?: run {
                finish()
                return
            }
        val desk = DeskLinkApplication.getInstance().getDevice(deviceId)?.name.orEmpty()
        setContent { DeskLinkDarkTheme(this) { PresenterScreen(desk) } }
        createMediaSession()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SLIDE, slide)
        outState.putInt(STATE_TOTAL, total)
        outState.putLong(STATE_STARTED, startedAt)
    }

    override fun onDestroy() {
        mediaSession?.release()
        super.onDestroy()
    }

    private fun next() {
        plugin.sendNext()
        slide = if (total > 0) minOf(slide + 1, total) else slide + 1
    }

    private fun previous() {
        plugin.sendPrevious()
        slide = maxOf(1, slide - 1)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Only when the media session cannot take the volume keys (Android 13–15).
        if (offScreenControlsSupported || !volumeKeys) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> previous()
                KeyEvent.KEYCODE_VOLUME_UP -> next()
                else -> return super.dispatchKeyEvent(event)
            }
        }
        return event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || super.dispatchKeyEvent(event)
    }

    private fun createMediaSession() {
        if (volumeKeys) {
            mediaSession?.setPlaybackState(
                PlaybackStateCompat.Builder().setState(PlaybackStateCompat.STATE_PLAYING, 0, 0f).build(),
            )
            mediaSession?.setPlaybackToRemote(volumeProvider)
            mediaSession?.setActive(true)
        }
    }

    private val volumeProvider = object : VolumeProviderCompat(VOLUME_CONTROL_RELATIVE, 0, 0) {
        override fun onAdjustVolume(direction: Int) {
            if (direction == VOLUME_UP) next() else if (direction == VOLUME_DOWN) previous()
        }
    }

    @Composable
    private fun PresenterScreen(desk: String) {
        var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
        LaunchedEffect(Unit) {
            while (true) {
                now = SystemClock.elapsedRealtime()
                delay(1_000)
            }
        }
        PresenterContent(
            desk = desk,
            slide = slide,
            total = total,
            elapsedSeconds = ((now - startedAt) / 1000).coerceAtLeast(0),
            onBack = ::finish,
            onNext = ::next,
            onPrevious = ::previous,
            onBlank = plugin::sendBlank,
            onFullscreen = plugin::sendFullscreen,
            onEsc = plugin::sendEsc,
            onTotal = { n -> total = n; if (n > 0) slide = minOf(slide, n) },
            onRestart = { slide = 1; startedAt = SystemClock.elapsedRealtime() },
        )
    }

    private companion object {
        const val STATE_SLIDE = "slide"
        const val STATE_TOTAL = "total"
        const val STATE_STARTED = "startedAt"
    }
}

/** The presenter, stateless: everything it shows comes in, every tap goes out. */
@Composable
fun PresenterContent(
    desk: String,
    slide: Int,
    total: Int,
    elapsedSeconds: Long,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onBlank: () -> Unit,
    onFullscreen: () -> Unit,
    onEsc: () -> Unit,
    onTotal: (Int) -> Unit,
    onRestart: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var settingTotal by rememberSaveable { mutableStateOf(false) }
    org.desklink.mobile.ui.compose.components.DarkSystemBars()
    val elapsed = elapsedSeconds
    val clock = "%02d:%02d".format(elapsed / 60, elapsed % 60)
    Column(Modifier.fillMaxSize().background(Black).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.sym_arrow_back), contentDescription = stringResource(R.string.navigate_up), tint = Text)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.home_presenter), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Text)
                Text(stringResource(R.string.presenter_controlling, desk), fontSize = 12.sp, color = Dim, maxLines = 1)
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.sym_more_vert), contentDescription = stringResource(R.string.extra_options), tint = Text)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.presenter_fullscreen)) }, onClick = { menu = false; onFullscreen() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.presenter_exit)) }, onClick = { menu = false; onEsc() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.presenter_set_total)) }, onClick = { menu = false; settingTotal = true })
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.presenter_restart)) },
                        onClick = { menu = false; onRestart() },
                    )
                }
            }
        }
        val counter = if (total > 0) "$slide / $total" else "$slide"
        val counterDescription = if (total > 0) stringResource(R.string.presenter_slide_of, slide, total) else stringResource(R.string.presenter_slide, slide)
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 8.dp)
                .clickable(onClickLabel = stringResource(R.string.presenter_set_total)) { settingTotal = true }
                .semantics(mergeDescendants = true) { contentDescription = counterDescription; liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(counter, fontFamily = MonoFamily, fontSize = 56.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp, color = Text)
            Row {
                Text(stringResource(R.string.presenter_elapsed) + " ", fontSize = 13.sp, color = Dim)
                Text(clock, fontFamily = MonoFamily, fontSize = 13.sp, color = Dim)
            }
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 10.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(NextBg)
                .clickable(role = Role.Button, onClick = onNext),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            Icon(painterResource(R.drawable.sym_arrow_forward), contentDescription = null, tint = OnNext, modifier = Modifier.size(56.dp))
            Text(stringResource(R.string.mpris_next), fontSize = 18.sp, fontWeight = FontWeight.Medium, color = OnNext)
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SideButton(R.drawable.sym_arrow_back, stringResource(R.string.presenter_back), Modifier.weight(1f), onPrevious)
            SideButton(R.drawable.sym_tv_off, stringResource(R.string.presenter_blank), Modifier.weight(1f), onBlank)
        }
    }
    if (settingTotal) TotalDialog(total, onDismiss = { settingTotal = false }) { n -> settingTotal = false; onTotal(n) }
}

@Composable
private fun SideButton(@DrawableRes icon: Int, label: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .height(72.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Button)
            .clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = Text, modifier = Modifier.size(24.dp))
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Text)
    }
}

@Composable
private fun TotalDialog(total: Int, onDismiss: () -> Unit, onSet: (Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf(total.takeIf { it > 0 }?.toString().orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.presenter_set_total)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(4) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                placeholder = { Text(stringResource(R.string.presenter_total_hint)) },
            )
        },
        confirmButton = { TextButton(onClick = { onSet(text.toIntOrNull() ?: 0) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

