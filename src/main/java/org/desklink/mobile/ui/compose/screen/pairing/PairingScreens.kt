/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.screen.pairing

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.MonoFamily
import org.desklink.mobile.ui.compose.components.DarkSystemBars
import org.desklink.mobile.ui.compose.components.LaptopGlyph
import androidx.compose.foundation.layout.offset
import org.desklink.mobile.ui.compose.components.PillButton
import org.desklink.mobile.ui.compose.components.PillStyle
import org.desklink.mobile.ui.compose.tokens

// ---- welcome ----------------------------------------------------------------------------------------

@Composable
fun WelcomeScreen(onPair: () -> Unit, onSettings: () -> Unit) {
    val t = tokens
    // Scrolls when the text is large; otherwise the button sits at the bottom.
    BoxWithConstraints(Modifier.fillMaxSize().background(t.bg).statusBarsPadding().navigationBarsPadding()) {
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .heightIn(min = maxHeight)
            .padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(60.dp)
                .background(Color(0xFF14213D), RoundedCornerShape(18.dp))
                .border(1.dp, t.hairline, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.desklink_mark), contentDescription = null, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.weight(1f))
        // Name, appearance and network options before any computer is paired.
        IconButton(onClick = onSettings, modifier = Modifier.offset(x = 12.dp, y = (-12).dp).size(48.dp)) {
            Icon(painterResource(R.drawable.sym_settings), contentDescription = stringResource(R.string.settings), tint = t.textDim)
        }
        }
        Spacer(Modifier.height(26.dp))
        Text(
            stringResource(R.string.welcome_title),
            fontSize = 34.sp,
            lineHeight = 40.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.6).sp,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.welcome_body), fontSize = 16.sp, lineHeight = 24.sp, color = t.textDim)
        Spacer(Modifier.height(26.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WelcomeRow(R.drawable.sym_terminal, stringResource(R.string.welcome_terminals))
            WelcomeRow(R.drawable.sym_devices, stringResource(R.string.welcome_screens))
            WelcomeRow(R.drawable.sym_encrypted, stringResource(R.string.welcome_encrypted))
        }
        Spacer(Modifier.weight(1f).heightIn(min = 26.dp))
        PillButton(stringResource(R.string.welcome_pair), onPair, Modifier.fillMaxWidth(), icon = R.drawable.sym_qr_code_scanner)
        Spacer(Modifier.height(18.dp))
    }
    }
}

@Composable
private fun WelcomeRow(@DrawableRes icon: Int, text: String) {
    val t = tokens
    Row(
        Modifier
            .fillMaxWidth()
            .background(t.surface, RoundedCornerShape(20.dp))
            .border(1.dp, t.hairline, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = t.accent, modifier = Modifier.size(22.dp))
        Text(text, fontSize = 15.sp)
    }
}

// ---- scanner ------------------------------------------------------------------------------------------

/** A computer on this network that has not been paired. */
@Immutable
data class NearbyComputer(val id: String, val name: String)

private val ScanText = Color(0xFFFFFFFF)
private val ScanDim = Color(0xFFA1A1AA)

/**
 * The camera behind brackets, and the computers found on this network to pair by code instead.
 * [camera] false shows why there is no picture, and a way to allow it.
 */
@Composable
fun ScanScreen(
    nearby: List<NearbyComputer>,
    camera: Boolean,
    onAllowCamera: () -> Unit,
    /** A new value restarts the camera (it pauses on every code it reads). */
    attempt: Int = 0,
    onCode: (String) -> Unit,
    onPairNearby: (String) -> Unit,
    onClose: () -> Unit,
) {
    DarkSystemBars()
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF2A2C31), Color(0xFF0B0B0C))))) {
        if (camera) androidx.compose.runtime.key(attempt) { QrCamera(onCode = onCode) }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.sym_close), contentDescription = stringResource(R.string.qr_close), tint = ScanText)
                }
                Text(stringResource(R.string.scan_title), fontSize = 18.sp, fontWeight = FontWeight.Medium, color = ScanText, modifier = Modifier.semantics { heading() })
            }
            Brackets(Modifier.align(Alignment.CenterHorizontally).padding(top = 48.dp).size(232.dp), camera, onAllowCamera)
            Text(
                stringResource(R.string.scan_hint),
                fontSize = 15.sp,
                lineHeight = 22.sp,
                color = ScanText.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 24.dp),
            )
            Spacer(Modifier.weight(1f))
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                nearby.forEach { computer -> NearbyRow(computer) { onPairNearby(computer.id) } }
            }
        }
    }
}

@Composable
private fun Brackets(modifier: Modifier, camera: Boolean, onAllowCamera: () -> Unit) {
    val density = LocalDensity.current
    Box(
        modifier.drawBehind {
            val w = with(density) { 3.dp.toPx() }
            val arm = with(density) { 44.dp.toPx() }
            val r = with(density) { 24.dp.toPx() }
            // Four corners of one rounded outline, each clipped to its own arm.
            for ((x, y) in listOf(0f to 0f, size.width - arm to 0f, 0f to size.height - arm, size.width - arm to size.height - arm)) {
                clipRect(x, y, x + arm, y + arm) {
                    drawRoundRect(Color.White, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(r), style = Stroke(w))
                }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        if (!camera) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(24.dp)) {
                Text(stringResource(R.string.scan_camera_needed), fontSize = 13.sp, color = ScanText.copy(alpha = 0.8f), textAlign = TextAlign.Center)
                PillButton(stringResource(R.string.scan_allow_camera), onAllowCamera, style = PillStyle.Tonal, height = 40.dp)
            }
        }
    }
}

@Composable
private fun NearbyRow(computer: NearbyComputer, onPair: () -> Unit) {
    val onThisNetwork = stringResource(R.string.scan_on_this_network)
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xF0202023), RoundedCornerShape(24.dp))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(24.dp))
            .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(40.dp).background(Color(0xFF28305A), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            LaptopGlyph(Color(0xFFDFE5FF))
        }
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(computer.name, fontFamily = MonoFamily, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ScanText, maxLines = 1)
            Text(onThisNetwork, fontSize = 12.sp, color = ScanDim)
        }
        val pair = stringResource(R.string.scan_pair_named, computer.name)
        Box(
            Modifier
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onPair)
                .semantics { contentDescription = pair },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.home_pair),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF14204F),
                modifier = Modifier.background(Color(0xFFA9B8FF), RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

/** zxing's camera view, decoding QR codes until one is read; paused with the screen. */
@Composable
internal fun QrCamera(onError: (() -> Unit)? = null, onCode: (String) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val callback by rememberUpdatedState(onCode)
    val failed by rememberUpdatedState(onError)
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The whole picture is read, not just the bracketed square: a code anywhere in view pairs.
        val width = with(density) { maxWidth.roundToPx() }
        val height = with(density) { maxHeight.roundToPx() }
        val view = remember { mutableListOf<BarcodeView>() }
        AndroidView(
            modifier = Modifier.fillMaxSize().clearAndSetSemantics { },
            factory = { context ->
                BarcodeView(context).apply {
                    decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                    framingRectSize = com.journeyapps.barcodescanner.Size(width, height)
                    decodeContinuous { result -> result.text?.let { text -> pause(); callback(text) } }
                    // No camera, or one another app holds: said at once instead of a black square.
                    addStateListener(object : com.journeyapps.barcodescanner.CameraPreview.StateListener {
                        override fun previewSized() = Unit
                        override fun previewStarted() = Unit
                        override fun previewStopped() = Unit
                        override fun cameraError(error: Exception?) { failed?.invoke() }
                        override fun cameraClosed() = Unit
                    })
                    view += this
                }
            },
        )
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> view.forEach(BarcodeView::resume)
                    Lifecycle.Event.ON_PAUSE -> view.forEach(BarcodeView::pause)
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.forEach(BarcodeView::resume)
            onDispose {
                lifecycle.removeObserver(observer)
                view.forEach(BarcodeView::pause)
            }
        }
    }
}

// ---- comparing codes ----------------------------------------------------------------------------------------

enum class PairStage {
    /** The computer asked: compare the codes, then Codes match or Cancel. */
    ASKED,

    /** This phone asked: the computer has to accept. */
    WAITING,

    /** Not paired (refused, failed or cancelled): ask again. */
    IDLE,
}

@Immutable
data class PairCodeState(val name: String, val code: String?, val stage: PairStage, val message: String? = null)

@Composable
fun PairCodeScreen(state: PairCodeState, onCancel: () -> Unit, onMatch: () -> Unit, onRequest: () -> Unit) {
    val t = tokens
    Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding().navigationBarsPadding()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(
                    when (state.stage) {
                        PairStage.ASKED -> R.string.pair_code_title
                        PairStage.WAITING -> R.string.pair_code_waiting_title
                        PairStage.IDLE -> R.string.pair_code_idle_title
                    },
                ),
                fontSize = 26.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.3).sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
            )
            Row {
                Text(stringResource(R.string.pair_code_with), fontSize = 15.sp, color = t.textDim)
                Text(state.name, fontSize = 15.sp, fontFamily = MonoFamily, color = t.text)
            }
            state.code?.takeIf { state.stage != PairStage.IDLE }?.let { code ->
                val description = stringResource(R.string.pair_code_description, code.toList().joinToString(" "))
                Box(
                    Modifier
                        .padding(vertical = 24.dp)
                        .fillMaxWidth()
                        .background(t.surface, RoundedCornerShape(28.dp))
                        .border(1.dp, t.hairline, RoundedCornerShape(28.dp))
                        .padding(vertical = 30.dp)
                        .clearAndSetSemantics { contentDescription = description },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(code.chunked(3).joinToString(" "), fontFamily = MonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 42.sp, letterSpacing = 4.sp)
                }
            }
            when (state.stage) {
                PairStage.ASKED -> Text(stringResource(R.string.pair_code_mismatch), fontSize = 13.sp, color = t.textDim, textAlign = TextAlign.Center)
                PairStage.WAITING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = t.accent)
                    Text(stringResource(R.string.pair_code_waiting, state.name), fontSize = 13.sp, color = t.textDim, textAlign = TextAlign.Center)
                }
                PairStage.IDLE -> Text(stringResource(R.string.pair_request_hint), fontSize = 13.sp, color = t.textDim, textAlign = TextAlign.Center)
            }
            state.message?.let { Text(it, fontSize = 14.sp, color = t.red, textAlign = TextAlign.Center) }
            Spacer(Modifier.height(24.dp))
        }
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(stringResource(R.string.cancel), onCancel, Modifier.weight(1f), style = PillStyle.Outlined)
            when (state.stage) {
                PairStage.ASKED -> PillButton(stringResource(R.string.pair_codes_match), onMatch, Modifier.weight(1.3f))
                PairStage.IDLE -> PillButton(stringResource(R.string.request_pairing), onRequest, Modifier.weight(1.3f))
                PairStage.WAITING -> Unit
            }
        }
    }
}
