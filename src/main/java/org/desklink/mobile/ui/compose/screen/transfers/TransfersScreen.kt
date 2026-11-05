/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.screen.transfers

import android.net.Uri
import android.text.format.DateFormat
import android.text.format.DateUtils
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.style.TextAlign
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
import org.desklink.mobile.plugins.share.hasPicture
import org.desklink.mobile.plugins.share.previewKind
import org.desklink.mobile.ui.compose.MonoFamily
import org.desklink.mobile.ui.compose.DeskTextButton
import org.desklink.mobile.ui.compose.PrimaryButton
import org.desklink.mobile.ui.compose.components.DeskCard
import org.desklink.mobile.ui.compose.components.DeskFilterChip
import org.desklink.mobile.ui.compose.components.DeskProgressBar
import org.desklink.mobile.ui.compose.components.EmptyState
import org.desklink.mobile.ui.compose.components.IconTile
import org.desklink.mobile.ui.compose.components.SectionHeader
import org.desklink.mobile.ui.compose.components.ScreenHeader
import org.desklink.mobile.ui.compose.components.shimmer
import org.desklink.mobile.ui.compose.tokens
import java.util.Calendar
import java.util.Date

/** The transfer running now, as the top card shows it. */
@Immutable
data class LiveTransfer(val name: String, val upload: Boolean, val progress: Float?, val label: String)

@androidx.compose.runtime.Stable
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
fun speedLabel(bytesPerSecond: Long): String = stringResource(R.string.transfers_speed, org.desklink.mobile.helpers.Sizes.iec(bytesPerSecond))

/**
 * The file list, as items of the Files tab's column: the running transfer first, the filter chips, then everything sent
 * and received in the last 30 days, newest first, by day (each day's rows one rounded group). At most [limit] are shown
 * until [onShowAll].
 */
fun androidx.compose.foundation.lazy.LazyListScope.recentTransfers(
    deskName: String,
    live: LiveTransfer?,
    records: List<TransferRecord>,
    revision: Int,
    actions: TransferActions,
    filter: FileFilter = FileFilter.ALL,
    onFilter: (FileFilter) -> Unit = {},
    limit: Int = RECENT_LIMIT,
    onShowAll: (() -> Unit)? = null,
) {
    live?.let { item(key = "recent-live") { LiveCard(it, actions.onCancelLive) } }
    if (records.isEmpty()) {
        if (live == null) item(key = "recent-empty") { Empty(actions.onSendFiles) }
        return
    }
    item(key = "recent-chips") { FilterChips(filter, onFilter) }
    val matching = records.filter(filter::matches)
    if (matching.isEmpty()) {
        item(key = "recent-none") {
            Text(stringResource(R.string.files_filter_none), fontSize = 14.sp, color = tokens.textDim, modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp))
        }
        return
    }
    val groups = groupByDay(matching.take(limit))
    groups.forEach { (day, items) ->
        item(key = "day$day") {
            val context = LocalContext.current
            SectionHeader(dayLabel(context, day), Modifier.padding(top = 8.dp))
        }
        itemsIndexed(items, key = { _, r -> r.id }) { i, record ->
            FileRow(record, deskName, revision, actions, first = i == 0, last = i == items.lastIndex)
        }
    }
    if (matching.size > limit && onShowAll != null) {
        item(key = "recent-more") {
            DeskTextButton(stringResource(R.string.files_show_all, matching.size), onShowAll)
        }
    }
}

/** How many recent transfers the Files tab lists before "Show all". */
const val RECENT_LIMIT = 12

/** All / Images / Documents / Archives: one row that scrolls sideways at a large font, each chip 48 dp to touch. */
@Composable
private fun FilterChips(selected: FileFilter, onSelect: (FileFilter) -> Unit) {
    val label = stringResource(R.string.files_filters)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup().semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FileFilter.entries.forEach { f -> DeskFilterChip(stringResource(f.label), f == selected, { onSelect(f) }) }
    }
}

@Composable
private fun LiveCard(live: LiveTransfer, onCancel: () -> Unit) {
    val t = tokens
    DeskCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconTile(if (live.upload) R.drawable.sym_upload else R.drawable.sym_download, size = 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(live.name, fontFamily = MonoFamily, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                if (live.progress == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = t.accent, trackColor = t.surface2, gapSize = 0.dp)
                } else {
                    DeskProgressBar(live.progress)
                }
                // The words are the transfer's own: percent, speed and the lane ("fast, over Wi-Fi", "over the secure link", ...), never cut.
                Text(live.label, fontSize = 12.sp, lineHeight = 16.sp, color = t.textDim)
            }
            DeskTextButton(stringResource(R.string.cancel), onCancel)
        }
    }
}

@Composable
private fun Empty(onSendFiles: () -> Unit) {
    EmptyState(R.drawable.sym_swap_vert, stringResource(R.string.transfers_empty_title), stringResource(R.string.transfers_empty_body)) {
        PrimaryButton(stringResource(R.string.home_send_files), onSendFiles, icon = R.drawable.sym_upload_file)
    }
}

/** One file: a 44 dp thumbnail (a picture once decoded, a typed glyph before), its name, "Sent . 2 min ago . 3.2 MB", and the menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(record: TransferRecord, deskName: String, revision: Int, actions: TransferActions, first: Boolean, last: Boolean) {
    val t = tokens
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { 88.dp.roundToPx() }
    val preview by rememberFilePreview(record, sizePx, revision)
    var menu by remember { mutableStateOf(false) }
    val size = org.desklink.mobile.helpers.Sizes.iec(record.size)
    val time = DateFormat.getTimeFormat(context).format(Date(record.time))
    val desk = record.deviceName.ifBlank { deskName }
    val description = stringResource(if (record.sent) R.string.transfers_item_sent else R.string.transfers_item_received, record.name, size, desk, time) +
        if (preview.missing) ". " + stringResource(R.string.transfers_missing) else ""
    val moreLabel = stringResource(R.string.transfers_more, record.name)
    val round = 24.dp
    val shape = RoundedCornerShape(topStart = if (first) round else 0.dp, topEnd = if (first) round else 0.dp, bottomStart = if (last) round else 0.dp, bottomEnd = if (last) round else 0.dp)
    val kind = previewKind(record.mime, record.name)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(t.surface)
            .border(1.dp, t.hairline, shape)
            .combinedClickable(role = Role.Button, onLongClick = { menu = true }, onLongClickLabel = moreLabel) { if (preview.missing) menu = true else actions.onOpen(record) }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .heightIn(min = 64.dp)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp)) {
            val bitmap = preview.bitmap
            when {
                preview.loading && kind.hasPicture -> Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).shimmer())
                preview.missing -> IconTile(R.drawable.sym_broken_image, container = t.fill, content = t.textDim)
                bitmap != null -> Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)))
                else -> IconTile(iconFor(kind))
            }
            // Which way it went, at a glance.
            Box(
                Modifier.align(Alignment.BottomEnd).offset(3.dp, 3.dp).size(20.dp).background(t.surface, CircleShape).border(1.dp, t.hairline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(if (record.sent) R.drawable.sym_upload else R.drawable.sym_download), contentDescription = null, tint = t.textDim, modifier = Modifier.size(12.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            // The middle goes: a name's start and its extension are what tell files apart.
            Text(record.name, fontFamily = MonoFamily, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
            Text(
                if (preview.missing) stringResource(R.string.transfers_missing)
                else stringResource(if (record.sent) R.string.files_sent_meta else R.string.files_received_meta, DateUtils.getRelativeTimeSpanString(record.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(), size),
                fontSize = 12.sp, lineHeight = 16.sp,
                color = if (preview.missing) t.red else t.textDim,
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
    Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
