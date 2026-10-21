/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.screen.transfers

import android.net.Uri
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.MediaController
import android.widget.VideoView
import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.desklink.mobile.R
import org.desklink.mobile.plugins.share.PreviewKind
import org.desklink.mobile.plugins.share.TransferRecord
import org.desklink.mobile.plugins.share.previewKind
import org.desklink.mobile.ui.compose.MonoFamily
import org.desklink.mobile.ui.compose.components.IconCircle
import org.desklink.mobile.ui.compose.components.LargeTitle
import org.desklink.mobile.ui.compose.components.PillButton
import org.desklink.mobile.ui.compose.components.PillStyle
import org.desklink.mobile.ui.compose.components.ScreenHeader
import org.desklink.mobile.ui.compose.components.shimmer
import org.desklink.mobile.ui.compose.tokens
import java.util.Calendar
import java.util.Date

/** The transfer running now, as the top card shows it. */
@Immutable
data class LiveTransfer(val name: String, val upload: Boolean, val progress: Float?, val label: String)

class TransferActions(
    val onBack: () -> Unit,
    val onSendFiles: () -> Unit,
    val onCancelLive: () -> Unit,
    val onOpen: (TransferRecord) -> Unit,
    val onShare: (TransferRecord) -> Unit,
    val onOpenWith: (TransferRecord) -> Unit,
    val onRemove: (TransferRecord) -> Unit,
)

/** "2.4 MB/s" from bytes per second. */
@Composable
fun speedLabel(bytesPerSecond: Long): String = stringResource(R.string.transfers_speed, Formatter.formatShortFileSize(LocalContext.current, bytesPerSecond))

/**
 * Files with one computer: the running transfer on top, then everything sent and received in the
 * last 30 days, newest first, by day, two to a row.
 */
@Composable
fun TransfersScreen(deskName: String, live: LiveTransfer?, records: List<TransferRecord>, revision: Int, actions: TransferActions) {
    val t = tokens
    val context = LocalContext.current
    val groups = remember(records) { records.groupBy { dayStart(it.time) }.toList() }
    Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding()) {
        ScreenHeader(actions.onBack) {
            IconButton(onClick = actions.onSendFiles, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.sym_upload_file), contentDescription = stringResource(R.string.home_send_files), tint = t.text)
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(2) }) {
                LargeTitle(stringResource(R.string.transfers_title), Modifier.offset(x = (-16).dp), subtitle = stringResource(R.string.transfers_subtitle, deskName))
            }
            live?.let { item(span = { GridItemSpan(2) }) { LiveCard(it, actions.onCancelLive) } }
            if (records.isEmpty()) {
                item(span = { GridItemSpan(2) }) { Empty(actions.onSendFiles) }
            }
            groups.forEach { (day, items) ->
                item(span = { GridItemSpan(2) }, key = "day$day") {
                    Text(
                        dayLabel(context, day),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = t.textDim,
                        modifier = Modifier.padding(start = 6.dp, top = 8.dp).semantics { heading() },
                    )
                }
                items(items, key = { it.id }) { record -> FileCard(record, deskName, revision, actions) }
            }
        }
    }
}

@Composable
private fun LiveCard(live: LiveTransfer, onCancel: () -> Unit) {
    val t = tokens
    Row(
        Modifier
            .fillMaxWidth()
            .background(t.surface, RoundedCornerShape(24.dp))
            .border(1.dp, t.hairline, RoundedCornerShape(24.dp))
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconCircle(if (live.upload) R.drawable.sym_upload else R.drawable.sym_download, 36.dp, 20.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(live.name, fontFamily = MonoFamily, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            if (live.progress == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp), color = t.accent, trackColor = t.fill, gapSize = 0.dp)
            } else {
                LinearProgressIndicator({ live.progress }, Modifier.fillMaxWidth().height(4.dp), color = t.accent, trackColor = t.fill, drawStopIndicator = {}, gapSize = 0.dp)
            }
            Text(live.label, fontSize = 12.sp, color = t.textDim)
        }
        PillButton(stringResource(R.string.cancel), onCancel, style = PillStyle.Outlined, height = 40.dp)
    }
}

@Composable
private fun Empty(onSendFiles: () -> Unit) {
    val t = tokens
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        IconCircle(R.drawable.sym_swap_vert, 64.dp, 30.dp, background = t.accentContainer, tint = t.onAccentContainer)
        Text(stringResource(R.string.transfers_empty_title), fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Text(stringResource(R.string.transfers_empty_body), fontSize = 14.sp, color = t.textDim)
        PillButton(stringResource(R.string.home_send_files), onSendFiles, height = 48.dp, icon = R.drawable.sym_upload_file)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileCard(record: TransferRecord, deskName: String, revision: Int, actions: TransferActions) {
    val t = tokens
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { 180.dp.roundToPx() }
    val preview by rememberFilePreview(record, sizePx, revision)
    var menu by remember { mutableStateOf(false) }
    val size = Formatter.formatShortFileSize(context, record.size)
    val time = DateFormat.getTimeFormat(context).format(Date(record.time))
    val desk = record.deviceName.ifBlank { deskName }
    val description = stringResource(if (record.sent) R.string.transfers_item_sent else R.string.transfers_item_received, record.name, size, desk, time) +
        if (preview.missing) ". " + stringResource(R.string.transfers_missing) else ""
    val moreLabel = stringResource(R.string.transfers_more, record.name)
    Column(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(t.surface)
            .border(1.dp, t.hairline, RoundedCornerShape(20.dp))
            .combinedClickable(
                role = Role.Button,
                onLongClick = { menu = true },
                onLongClickLabel = moreLabel,
            ) { if (preview.missing) menu = true else actions.onOpen(record) }
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Box(
            Modifier
                .padding(6.dp)
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val kind = previewKind(record.mime, record.name)
            val bitmap = preview.bitmap
            when {
                preview.loading -> Box(Modifier.fillMaxSize().shimmer())
                preview.missing -> TypedIcon(R.drawable.sym_broken_image, t.fill, t.textDim)
                bitmap != null -> Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else -> TypedIcon(iconFor(kind), t.accentContainer, t.onAccentContainer)
            }
            if (kind == PreviewKind.VIDEO && !preview.missing && !preview.loading) {
                Box(Modifier.size(40.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.sym_play_arrow), contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
                preview.durationMs?.let {
                    Text(
                        DateUtils.formatElapsedTime(it / 1000),
                        fontFamily = MonoFamily,
                        fontSize = 11.sp,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            // Which way it went, at a glance.
            Box(
                Modifier.align(Alignment.TopStart).padding(6.dp).size(24.dp).background(t.surface.copy(alpha = 0.9f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(if (record.sent) R.drawable.sym_upload else R.drawable.sym_download), contentDescription = null, tint = t.text, modifier = Modifier.size(15.dp))
            }
        }
        Row(Modifier.padding(start = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                // The middle goes: a name's start and its extension are what tell files apart.
                Text(record.name, fontFamily = MonoFamily, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text(
                    if (preview.missing) stringResource(R.string.transfers_missing) else "$size · $time",
                    fontSize = 11.sp,
                    color = if (preview.missing) t.red else t.textDim,
                    maxLines = if (preview.missing) 2 else 1,
                )
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.sym_more_vert), contentDescription = moreLabel, tint = t.textDim)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!preview.missing) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.transfers_share)) }, onClick = { menu = false; actions.onShare(record) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.transfers_open_with)) }, onClick = { menu = false; actions.onOpenWith(record) })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.transfers_remove)) }, onClick = { menu = false; actions.onRemove(record) })
                }
            }
        }
    }
}

@Composable
private fun TypedIcon(@DrawableRes icon: Int, background: Color, tint: Color) {
    Box(Modifier.fillMaxSize().background(background), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(36.dp))
    }
}

@DrawableRes
fun iconFor(kind: PreviewKind): Int = when (kind) {
    PreviewKind.IMAGE -> R.drawable.sym_image
    PreviewKind.VIDEO -> R.drawable.sym_movie
    PreviewKind.AUDIO -> R.drawable.sym_music_note
    PreviewKind.PDF -> R.drawable.sym_picture_as_pdf
    PreviewKind.TEXT -> R.drawable.sym_description
    PreviewKind.ARCHIVE -> R.drawable.sym_folder_zip
    PreviewKind.OTHER -> R.drawable.sym_draft
}

private fun dayStart(time: Long): Long = Calendar.getInstance().apply {
    timeInMillis = time
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun dayLabel(context: android.content.Context, day: Long): String {
    val today = dayStart(System.currentTimeMillis())
    return when (day) {
        today -> context.getString(R.string.transfers_today)
        today - DateUtils.DAY_IN_MILLIS -> context.getString(R.string.transfers_yesterday)
        else -> DateUtils.formatDateTime(context, day, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL)
    }
}

// ---- viewers ----------------------------------------------------------------------------------------------

private val ViewerBg = Color(0xFF0B0B0C)

@Composable
private fun ViewerHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.sym_arrow_back), contentDescription = stringResource(R.string.navigate_up), tint = Color.White)
        }
        Text(title, fontFamily = MonoFamily, fontSize = 14.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/** Full-screen images: swipe between them, pinch or double-tap to zoom. */
@Composable
fun ImageViewer(images: List<TransferRecord>, startId: String, onBack: () -> Unit) {
    org.desklink.mobile.ui.compose.components.DarkSystemBars()
    val pager = rememberPagerState(initialPage = images.indexOfFirst { it.id == startId }.coerceAtLeast(0)) { images.size }
    var zoomed by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(ViewerBg)) {
        HorizontalPager(pager, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize()) { page ->
            ZoomableImage(images[page], onZoom = { if (page == pager.currentPage) zoomed = it })
        }
        ViewerHeader(images.getOrNull(pager.currentPage)?.name.orEmpty(), onBack)
    }
}

@Composable
private fun ZoomableImage(record: TransferRecord, onZoom: (Boolean) -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, record.id) {
        value = withContext(Dispatchers.IO) { runCatching { Thumbnails.decodeSampled(context, Uri.parse(record.uri), 1600) }.getOrNull() }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale > 1f) offset + pan * scale else Offset.Zero
        onZoom(scale > 1f)
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    scale = if (scale > 1f) 1f else 2.5f
                    offset = Offset.Zero
                    onZoom(scale > 1f)
                })
            }
            .transformable(state, canPan = { scale > 1f }),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                it.asImageBitmap(),
                contentDescription = record.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                },
            )
        } ?: Box(Modifier.size(64.dp).clearAndSetSemantics { })
    }
}

/** A video in the platform's own player view, with its standard controls. */
@Composable
fun VideoViewer(record: TransferRecord, onBack: () -> Unit) {
    org.desklink.mobile.ui.compose.components.DarkSystemBars()
    Box(Modifier.fillMaxSize().background(ViewerBg)) {
        AndroidView(
            modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
            factory = { context ->
                VideoView(context).apply {
                    contentDescription = record.name
                    val controller = MediaController(context)
                    controller.setAnchorView(this)
                    setMediaController(controller)
                    setVideoURI(Uri.parse(record.uri))
                    setOnPreparedListener { start(); controller.show() }
                }
            },
            onRelease = { it.stopPlayback() },
        )
        Column {
            ViewerHeader(record.name, onBack)
            Spacer(Modifier.width(0.dp))
        }
    }
}
