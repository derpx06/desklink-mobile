/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.capture

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.DeskLinkTheme
import org.desklink.mobile.ui.compose.components.LargeTitle
import org.desklink.mobile.ui.compose.components.PillButton
import org.desklink.mobile.ui.compose.components.PillStyle
import org.desklink.mobile.ui.compose.components.ScreenHeader
import org.desklink.mobile.ui.compose.screen.pairing.QrCamera
import org.desklink.mobile.ui.compose.tokens
import java.io.File
import kotlin.math.hypot

/**
 * F3 "Insert from phone": what the desk asked for — a photo, a scanned document, the text in a
 * picture, or a QR code — captured here and sent back, or cancelled with a reason.
 */
class CaptureActivity : AppCompatActivity() {
    private var request by mutableStateOf<CaptureRequest?>(null)
    private var stage by mutableStateOf(Stage.STARTING)
    private var photo by mutableStateOf<Bitmap?>(null)
    private var quad by mutableStateOf<Quad?>(null)
    private var filter by mutableStateOf(ScanFilter.COLOUR)
    private var page by mutableStateOf<Bitmap?>(null)
    private val pages = mutableStateListOf<Bitmap>()
    private var text by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var shot: File? = null
    private var sent = false

    enum class Stage { STARTING, PHOTO, CORNERS, PAGE, TEXT, QR, QR_RESULT }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin() else cancel("permission")
    }

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = shot
        if (!ok || file == null || !file.exists() || file.length() == 0L) {
            // Back from the camera: nothing taken. With pages already scanned, stay on them.
            if (pages.isEmpty()) cancel("user") else stage = Stage.PAGE
            return@registerForActivityResult
        }
        onShot(file)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request = PhoneCapture.opened(this) ?: return finish()
        setContent {
            DeskLinkTheme(this) {
                // The desk withdrew it, or it timed out: nothing more to do here.
                LaunchedEffect(PhoneCapture.current) { if (PhoneCapture.current == null && !sent) finish() }
                BackHandler { cancel("user") }
                CaptureScreen()
            }
        }
        if (savedInstanceState == null) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) begin()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    /** A newer request while this one is open (it replaced it): start over with it. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        val next = PhoneCapture.opened(this) ?: return
        if (next.captureId == request?.captureId) return
        cleanUp()
        request = next
        stage = Stage.STARTING
        filter = ScanFilter.COLOUR
        text = ""
        begin()
    }

    private fun begin() {
        if (request?.kind == "qr") stage = Stage.QR else shoot()
    }

    private fun shoot() {
        val file = File(PhoneCapture.folder(this), "shot-${System.currentTimeMillis()}.jpg")
        shot = file
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        runCatching { takePicture.launch(uri) }.onFailure { cancel("unsupported") }
    }

    private fun onShot(file: File) {
        busy = true
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.Default) { ScanImage.load(this@CaptureActivity, Uri.fromFile(file)) }
            busy = false
            if (bitmap == null) return@launch cancel("unsupported")
            photo = bitmap
            when (request?.kind) {
                "scan" -> {
                    quad = withContext(Dispatchers.Default) { ScanImage.detect(bitmap) }
                    stage = Stage.CORNERS
                }
                "text" -> recognise(bitmap)
                else -> stage = Stage.PHOTO
            }
            file.delete()
        }
    }

    private fun recognise(bitmap: Bitmap) {
        busy = true
        stage = Stage.TEXT
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            .process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result -> text = result.text; busy = false }
            .addOnFailureListener { text = ""; busy = false }
    }

    private fun cancel(reason: String) {
        PhoneCapture.finish(this, reason)
        cleanUp()
        finish()
    }

    private fun cleanUp() {
        shot?.delete()
        photo = null
        page = null
        pages.clear()
    }

    private fun done(ok: Boolean) {
        sent = ok
        if (!ok) android.widget.Toast.makeText(this, R.string.capture_not_sent, android.widget.Toast.LENGTH_LONG).show()
        cleanUp()
        finish()
    }

    // ---- sending ----------------------------------------------------------------------------------

    private fun sendPhoto() {
        val request = request ?: return
        val bitmap = photo ?: return
        busy = true
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { ScanImage.writeJpeg(bitmap, File(PhoneCapture.folder(this@CaptureActivity), "photo.jpg")) }
            done(PhoneCapture.sendFile(request, file, "image/jpeg", pages = 1))
        }
    }

    private fun sendScan() {
        val request = request ?: return
        if (pages.isEmpty()) return
        busy = true
        lifecycleScope.launch {
            val folder = PhoneCapture.folder(this@CaptureActivity)
            // The clipboard holds one picture: the page on screen. A file is the whole PDF.
            val ok = if (request.target == "clipboard") {
                val file = withContext(Dispatchers.IO) { ScanImage.writeJpeg(pages.last(), File(folder, "scan.jpg")) }
                PhoneCapture.sendFile(request, file, "image/jpeg", pages = 1)
            } else {
                val file = withContext(Dispatchers.IO) { ScanImage.writePdf(pages.toList(), File(folder, "scan.pdf")) }
                PhoneCapture.sendFile(request, file, "application/pdf", pages = pages.size)
            }
            done(ok)
        }
    }

    private fun sendText() {
        val request = request ?: return
        done(PhoneCapture.sendText(request, text))
    }

    /** The corners are right: flatten the page and show it with its filter. */
    private fun flatten() {
        val bitmap = photo ?: return
        val corners = quad ?: return
        busy = true
        lifecycleScope.launch {
            page = withContext(Dispatchers.Default) { ScanImage.filter(ScanImage.flatten(bitmap, corners), filter) }
            busy = false
            stage = Stage.PAGE
        }
    }

    private fun refilter(next: ScanFilter) {
        filter = next
        flatten()
    }

    // ---- screens ----------------------------------------------------------------------------------

    @Composable
    private fun CaptureScreen() {
        val t = tokens
        val request = request ?: return
        val desk = remember { DeskLinkApplication.getInstance().getDevice(request.deviceId)?.name ?: PhoneCapture.deskName }
        // imePadding: editing the recognised text must not hide Send under the keyboard.
        Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
            ScreenHeader(onBack = { cancel("user") })
            LargeTitle(stringResource(PhoneCapture.titleFor(request.kind), desk), subtitle = subtitle(request))
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                when (stage) {
                    Stage.STARTING -> Busy()
                    Stage.PHOTO -> PhotoPreview()
                    Stage.CORNERS -> Corners()
                    Stage.PAGE -> PagePreview()
                    Stage.TEXT -> TextPreview()
                    Stage.QR -> Box(Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp))) {
                        QrCamera { code -> text = code; stage = Stage.QR_RESULT }
                    }
                    Stage.QR_RESULT -> QrResult()
                }
                if (busy) Busy()
            }
            Actions(desk)
        }
    }

    @Composable
    private fun subtitle(request: CaptureRequest): String = stringResource(
        when {
            request.kind == "text" || request.kind == "qr" -> R.string.capture_to_text
            request.kind == "scan" && request.target == "file" -> R.string.capture_to_pdf
            else -> R.string.capture_to_clipboard
        },
    )

    @Composable
    private fun Busy() {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = tokens.accent) }
    }

    @Composable
    private fun Picture(bitmap: Bitmap, description: String) {
        Image(
            bitmap.asImageBitmap(),
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(tokens.fill),
        )
    }

    @Composable
    private fun PhotoPreview() {
        photo?.let { Picture(it, stringResource(R.string.capture_photo_description)) }
    }

    /** The photo with the page's four corners: drag a handle to where the page's corner is. */
    @Composable
    private fun Corners() {
        val bitmap = photo ?: return
        val corners = quad ?: return
        val t = tokens
        val handle = with(LocalDensity.current) { 24.dp.toPx() }
        val description = stringResource(R.string.capture_corners_description)
        BoxWithConstraints(Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(Color.Black)) {
            val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
            // A handle's width of margin, so a corner at the photo's edge stays whole and grabbable.
            val scale = minOf((w - 2 * handle) / bitmap.width, (h - 2 * handle) / bitmap.height)
            val ox = (w - bitmap.width * scale) / 2; val oy = (h - bitmap.height * scale) / 2
            fun toView(p: Pair<Float, Float>) = Offset(ox + p.first * scale, oy + p.second * scale)
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            var dragging by remember { mutableStateOf(-1) }
            Canvas(
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = description }
                    .pointerInput(bitmap) {
                        detectDragGestures(
                            onDragStart = { at ->
                                val points = quad?.points.orEmpty().map(::toView)
                                dragging = points.indices.minByOrNull { hypot(points[it].x - at.x, points[it].y - at.y) }
                                    ?.takeIf { hypot(points[it].x - at.x, points[it].y - at.y) < handle * 3 } ?: -1
                            },
                            onDragEnd = { dragging = -1 },
                            onDragCancel = { dragging = -1 },
                        ) { change, delta ->
                            val index = dragging
                            val current = quad ?: return@detectDragGestures
                            if (index < 0) return@detectDragGestures
                            change.consume()
                            val (x, y) = current.points[index]
                            val moved = (x + delta.x / scale).coerceIn(0f, bitmap.width.toFloat()) to (y + delta.y / scale).coerceIn(0f, bitmap.height.toFloat())
                            quad = Quad(current.points.toMutableList().also { it[index] = moved })
                        }
                    },
            ) {
                drawImage(
                    image,
                    dstOffset = androidx.compose.ui.unit.IntOffset(ox.toInt(), oy.toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize((bitmap.width * scale).toInt(), (bitmap.height * scale).toInt()),
                )
                val points = corners.points.map(::toView)
                val outline = Path().apply {
                    moveTo(points[0].x, points[0].y)
                    points.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }
                drawPath(outline, t.accent.copy(alpha = 0.18f))
                drawPath(outline, t.accent, style = Stroke(width = 2.dp.toPx()))
                points.forEachIndexed { i, p ->
                    drawCircle(Color.White, radius = if (i == dragging) handle * 0.9f else handle * 0.6f, center = p)
                    drawCircle(t.accent, radius = if (i == dragging) handle * 0.9f else handle * 0.6f, center = p, style = Stroke(width = 3.dp.toPx()))
                }
            }
        }
    }

    @Composable
    private fun PagePreview() {
        val t = tokens
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                page?.let { Picture(it, stringResource(R.string.capture_page_description, pages.size + 1)) }
            }
            // Colour, Greyscale, Black & white.
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ScanFilter.COLOUR to R.string.capture_filter_colour, ScanFilter.GREY to R.string.capture_filter_grey, ScanFilter.BLACK_WHITE to R.string.capture_filter_bw)
                    .forEach { (value, label) ->
                        val selected = filter == value
                        Box(
                            Modifier
                                .heightIn(min = 48.dp)
                                .selectable(selected = selected, role = Role.RadioButton) { refilter(value) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(label),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (selected) t.onAccent else t.text,
                                modifier = Modifier
                                    .background(if (selected) t.accent else t.fill, RoundedCornerShape(18.dp))
                                    .padding(horizontal = 16.dp, vertical = 9.dp),
                            )
                        }
                    }
            }
            if (pages.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pages.forEachIndexed { i, p ->
                        Image(
                            p.asImageBitmap(),
                            contentDescription = stringResource(R.string.capture_page_description, i + 1),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(48.dp, 64.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, t.hairline, RoundedCornerShape(8.dp)),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun TextPreview() {
        val t = tokens
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.capture_text_hint), fontSize = 13.sp, color = t.textDim)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().weight(1f),
                placeholder = { if (!busy) Text(stringResource(R.string.capture_text_none)) },
            )
        }
    }

    @Composable
    private fun QrResult() {
        val t = tokens
        val wifi = text.startsWith("WIFI:", ignoreCase = true)
        Column(
            Modifier.fillMaxWidth().background(t.surface, RoundedCornerShape(20.dp)).border(1.dp, t.hairline, RoundedCornerShape(20.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(if (wifi) R.string.capture_qr_wifi else R.string.capture_qr_read), fontSize = 13.sp, color = t.textDim)
            Text(text, fontSize = 16.sp, fontFamily = org.desklink.mobile.ui.compose.MonoFamily, maxLines = 8)
        }
    }

    @Composable
    private fun Actions(desk: String) {
        val primary: Pair<String, () -> Unit>?
        val secondary: Pair<String, () -> Unit>?
        when (stage) {
            Stage.PHOTO -> {
                primary = stringResource(R.string.capture_send) to ::sendPhoto
                secondary = stringResource(R.string.capture_retake) to ::shoot
            }
            Stage.CORNERS -> {
                primary = stringResource(R.string.capture_crop) to ::flatten
                secondary = stringResource(R.string.capture_retake) to ::shoot
            }
            Stage.PAGE -> {
                primary = pluralSend(desk) to {
                    page?.let { pages += ScanImage.forPage(it) }
                    page = null
                    sendScan()
                }
                val full = pages.size + 1 >= ScanImage.MAX_PAGES
                val fullMessage = stringResource(R.string.capture_pages_full, ScanImage.MAX_PAGES)
                secondary = stringResource(R.string.capture_add_page) to {
                    if (full) {
                        android.widget.Toast.makeText(this@CaptureActivity, fullMessage, android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        page?.let { pages += ScanImage.forPage(it) }
                        page = null
                        filter = ScanFilter.COLOUR
                        shoot()
                    }
                }
            }
            Stage.TEXT -> {
                primary = stringResource(R.string.capture_send) to ::sendText
                secondary = stringResource(R.string.capture_retake) to ::shoot
            }
            Stage.QR_RESULT -> {
                primary = stringResource(R.string.capture_send) to ::sendText
                secondary = stringResource(R.string.capture_scan_again) to { text = ""; stage = Stage.QR }
            }
            else -> {
                primary = null
                secondary = null
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            secondary?.let { (label, action) -> PillButton(label, action, Modifier.weight(1f), style = PillStyle.Outlined, enabled = !busy) }
            primary?.let { (label, action) ->
                PillButton(label, action, Modifier.weight(1f), enabled = !busy && (stage != Stage.TEXT || text.isNotBlank()))
            }
        }
    }

    @Composable
    private fun pluralSend(desk: String): String {
        val count = pages.size + (if (page != null) 1 else 0)
        return if (request?.target == "file") {
            androidx.compose.ui.res.pluralStringResource(R.plurals.capture_send_pages, count, count)
        } else {
            stringResource(R.string.capture_send)
        }
    }

    override fun onDestroy() {
        // Leaving without sending is a cancel (closed from recents, the desk's own cancel already handled).
        if (isFinishing && !sent && PhoneCapture.current?.captureId == request?.captureId) PhoneCapture.finish(this, "user")
        super.onDestroy()
    }
}
