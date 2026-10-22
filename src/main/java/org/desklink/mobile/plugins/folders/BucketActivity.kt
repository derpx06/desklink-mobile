/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.folders

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.format.DateUtils
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R
import org.desklink.mobile.plugins.share.TransferRecord
import org.desklink.mobile.plugins.share.previewKind
import org.desklink.mobile.ui.compose.DeskLinkTheme
import org.desklink.mobile.ui.compose.MonoFamily
import org.desklink.mobile.ui.compose.components.IconCircle
import org.desklink.mobile.ui.compose.components.LargeTitle
import org.desklink.mobile.ui.compose.components.PillButton
import org.desklink.mobile.ui.compose.components.PillStyle
import org.desklink.mobile.ui.compose.components.ScreenHeader
import org.desklink.mobile.ui.compose.components.shimmer
import org.desklink.mobile.ui.compose.screen.transfers.iconFor
import org.desklink.mobile.ui.compose.screen.transfers.rememberFilePreview
import org.desklink.mobile.ui.compose.tokens
import java.io.File

/**
 * F8b, the Bucket: everything put in it shows up on the desk too. A thumbnail grid, newest first,
 * each item with where it came from and its state; `+` adds files, a photo or the clipboard; the
 * share sheet's "DeskLink Bucket" adds anything from any app.
 */
class BucketActivity : AppCompatActivity() {
    /** Shared in from another app, waiting for the Bucket (or added at once). */
    private val pendingUris = mutableStateOf<List<Uri>>(emptyList())
    private val pendingText = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        BucketEngine.start(this)
        val deviceId = deskFor(intent.getStringExtra(EXTRA_DEVICE)) ?: run {
            Toast.makeText(this, R.string.bucket_no_desk, Toast.LENGTH_LONG).show()
            return finish()
        }
        if (savedInstanceState == null) takeShare(intent)
        setContent { DeskLinkTheme(this) { Screen(deviceId) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeShare(intent)
    }

    /** The share sheet (one or several files, or text and links). */
    private fun takeShare(intent: Intent) {
        @Suppress("DEPRECATION")
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (stream != null) pendingUris.value = listOf(stream)
                else intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let { pendingText.value = it }
            }
            Intent.ACTION_SEND_MULTIPLE -> pendingUris.value = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty().take(MAX_SHARED)
        }
    }

    /** The desk to use: the one asked for, else the one with a Bucket, else the first paired. */
    private fun deskFor(wanted: String?): String? {
        val app = DeskLinkApplication.getInstance()
        val paired = app.devices.values.filter { it.isPaired }
        wanted?.takeIf { id -> paired.any { it.deviceId == id } }?.let { return it }
        val withBucket = BucketEngine.views.value.values.firstOrNull { it.treeUri != null && paired.any { d -> d.deviceId == it.deviceId } }
        return withBucket?.deviceId ?: paired.firstOrNull { it.hasActiveWebRtcFeatureTransport }?.deviceId ?: paired.firstOrNull()?.deviceId
    }

    @Composable
    private fun Screen(deviceId: String) {
        val t = tokens
        val context = LocalContext.current
        val desk = remember(deviceId) { DeskLinkApplication.getInstance().getDevice(deviceId)?.name.orEmpty() }
        val views by BucketEngine.views.collectAsState()
        val view = views[deviceId]
        val on = view?.treeUri != null && view.phase != Phase.ASKING
        var plusMenu by remember { mutableStateOf(false) }
        var overflow by remember { mutableStateOf(false) }
        var photo by rememberSaveable { mutableStateOf<String?>(null) }

        fun added(added: Int, skipped: Int) = runOnUiThread {
            if (skipped > 0) toast(getString(R.string.bucket_skipped, skipped))
            else if (added > 0) toast(resources.getQuantityString(R.plurals.bucket_added, added, added))
        }

        val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
            tree ?: return@rememberLauncherForActivityResult
            if (!BucketEngine.turnOn(this, deviceId, tree)) toast(getString(R.string.bucket_folder_failed))
        }
        val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) BucketEngine.add(deviceId, uris.take(MAX_SHARED), ::added)
        }
        val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val path = photo ?: return@rememberLauncherForActivityResult
            photo = null
            val file = File(path)
            if (ok && file.length() > 0) {
                BucketEngine.add(deviceId, listOf(Uri.fromFile(file))) { a, s -> file.delete(); added(a, s) }
            } else {
                file.delete()
            }
        }

        // Shared in: added as soon as the Bucket is on.
        LaunchedEffect(on, pendingUris.value, pendingText.value) {
            if (!on) return@LaunchedEffect
            pendingUris.value.takeIf { it.isNotEmpty() }?.let { BucketEngine.add(deviceId, it, ::added); pendingUris.value = emptyList() }
            pendingText.value?.let { text ->
                BucketEngine.addText(deviceId, text) { ok -> runOnUiThread { toast(getString(if (ok) R.string.bucket_text_added else R.string.bucket_text_failed)) } }
                pendingText.value = null
            }
        }

        Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding()) {
            ScreenHeader(::finish) {
                if (on) {
                    Box {
                        IconButton(onClick = { overflow = true }, modifier = Modifier.size(48.dp)) {
                            Icon(painterResource(R.drawable.sym_more_vert), contentDescription = stringResource(R.string.bucket_more), tint = t.text)
                        }
                        DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.bucket_sync_now)) }, onClick = { overflow = false; BucketEngine.syncNow(deviceId) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.bucket_turn_off)) }, onClick = { overflow = false; BucketEngine.turnOff(deviceId) })
                        }
                    }
                }
            }
            LargeTitle(stringResource(R.string.bucket_title), subtitle = if (on) status(view!!, desk) else stringResource(R.string.bucket_subtitle, desk))
            Box(Modifier.weight(1f)) {
                when {
                    view?.phase == Phase.ASKING -> Hero(desk, waiting = true, offered = false, onTurnOn = {}, onDecline = {})
                    !on -> Hero(
                        desk,
                        waiting = false,
                        offered = view?.offered == true,
                        onTurnOn = { pickFolder.launch(documentsUri()) },
                        onDecline = { BucketEngine.decline(deviceId) },
                    )
                    else -> Content(view!!, desk, deviceId)
                }
                if (on) {
                    Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp)) {
                        PillButton(stringResource(R.string.bucket_add), { plusMenu = true }, icon = R.drawable.sym_add)
                        DropdownMenu(expanded = plusMenu, onDismissRequest = { plusMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.bucket_add_files)) },
                                leadingIcon = { Icon(painterResource(R.drawable.sym_upload_file), contentDescription = null) },
                                onClick = { plusMenu = false; pickFiles.launch(arrayOf("*/*")) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.bucket_add_photo)) },
                                leadingIcon = { Icon(painterResource(R.drawable.sym_image), contentDescription = null) },
                                onClick = {
                                    plusMenu = false
                                    val file = File(File(cacheDir, "bucket-photo").apply { mkdirs() }, "Photo ${System.currentTimeMillis()}.jpg")
                                    photo = file.path
                                    runCatching { takePhoto.launch(FileProvider.getUriForFile(context, "$packageName.fileprovider", file)) }
                                        .onFailure { toast(getString(R.string.bucket_no_camera)) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.bucket_add_paste)) },
                                leadingIcon = { Icon(painterResource(R.drawable.sym_content_paste), contentDescription = null) },
                                onClick = { plusMenu = false; paste(deviceId) },
                            )
                        }
                    }
                }
            }
        }
    }

    /** "realme-desk · Last synced 2 min ago · 1.2 GB". */
    @Composable
    private fun status(view: BucketView, desk: String): String {
        val connected = DeskLinkApplication.getInstance().getDevice(view.deviceId)?.hasActiveWebRtcFeatureTransport == true
        val sync = when {
            !connected -> stringResource(R.string.bucket_waiting_for, desk)
            view.phase == Phase.SYNCING || view.phase == Phase.SCANNING -> stringResource(R.string.bucket_syncing)
            view.lastSync != null -> stringResource(R.string.bucket_last_synced, DateUtils.getRelativeTimeSpanString(view.lastSync).toString())
            else -> stringResource(R.string.bucket_with, desk)
        }
        return "$sync · ${Formatter.formatShortFileSize(LocalContext.current, view.bytes)}"
    }

    /** Off: what the Bucket is, and one button (a single folder pick is all Android needs). */
    @Composable
    private fun Hero(desk: String, waiting: Boolean, offered: Boolean, onTurnOn: () -> Unit, onDecline: () -> Unit) {
        val t = tokens
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            DropZone(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 260.dp)) {
                IconCircle(R.drawable.sym_inventory, 72.dp, 36.dp, background = t.accentContainer, tint = t.onAccentContainer)
                Spacer(Modifier.height(18.dp))
                Text(
                    stringResource(if (offered) R.string.bucket_offered_title else R.string.bucket_hero_title, desk),
                    fontSize = 20.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.bucket_hero_body, desk), fontSize = 14.sp, lineHeight = 20.sp, color = t.textDim, textAlign = TextAlign.Center)
            }
            if (waiting) {
                Text(stringResource(R.string.bucket_asking, desk), fontSize = 14.sp, color = t.textDim)
            } else {
                Text(stringResource(R.string.bucket_pick_hint), fontSize = 13.sp, lineHeight = 18.sp, color = t.textDim)
                PillButton(stringResource(R.string.bucket_turn_on), onTurnOn, icon = R.drawable.sym_inventory, modifier = Modifier.fillMaxWidth())
                if (offered) PillButton(stringResource(R.string.bucket_not_now), onDecline, style = PillStyle.Outlined, height = 48.dp, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    /** A rounded dashed outline: the Bucket's drop-zone look. */
    @Composable
    private fun DropZone(modifier: Modifier, content: @Composable () -> Unit) {
        val t = tokens
        val dash = with(LocalDensity.current) { 8.dp.toPx() }
        val stroke = with(LocalDensity.current) { 2.dp.toPx() }
        val radius = with(LocalDensity.current) { 28.dp.toPx() }
        Column(
            modifier
                .clip(RoundedCornerShape(28.dp))
                .background(t.surface)
                .drawBehind {
                    drawRoundRect(
                        color = t.hairlineStrong,
                        cornerRadius = CornerRadius(radius),
                        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
                    )
                }
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) { content() }
    }

    @Composable
    private fun Content(view: BucketView, desk: String, deviceId: String) {
        val t = tokens
        var menuFor by remember { mutableStateOf<BucketItem?>(null) }
        var renaming by remember { mutableStateOf<BucketItem?>(null) }
        var removing by remember { mutableStateOf<BucketItem?>(null) }
        var resolving by remember { mutableStateOf<BucketItem?>(null) }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(156.dp),
            contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 96.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            view.adding?.let { (done, total) ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Banner(stringResource(R.string.bucket_adding, done + 1, total), action = stringResource(R.string.cancel), progress = (done.toFloat() / total), onAction = BucketEngine::cancelAdd)
                }
            }
            if (view.phase == Phase.HELD_EMPTY) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Banner(stringResource(R.string.bucket_held_empty, desk), action = stringResource(R.string.bucket_held_confirm), warn = true) { BucketEngine.confirmEmpty(deviceId) }
                }
            }
            if (view.phase == Phase.NO_ACCESS) {
                item(span = { GridItemSpan(maxLineSpan) }) { Banner(stringResource(R.string.bucket_no_access), warn = true) }
            }
            if (view.phase == Phase.NOT_ON_DESK) {
                item(span = { GridItemSpan(maxLineSpan) }) { Banner(stringResource(R.string.bucket_not_on_desk, desk), warn = true) }
            }
            if (view.storageFull) {
                item(span = { GridItemSpan(maxLineSpan) }) { Banner(stringResource(R.string.bucket_storage_full), warn = true) }
            }
            if (view.phase == Phase.TOO_MANY) {
                item(span = { GridItemSpan(maxLineSpan) }) { Banner(stringResource(R.string.bucket_too_many), warn = true) }
            }
            if (view.bytes > FolderSync.WARN_BUCKET_BYTES) {
                item(span = { GridItemSpan(maxLineSpan) }) { Banner(stringResource(R.string.bucket_large, Formatter.formatShortFileSize(this@BucketActivity, view.bytes))) }
            }
            if (view.items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    DropZone(Modifier.fillMaxWidth().heightIn(min = 300.dp)) {
                        IconCircle(R.drawable.sym_inventory, 64.dp, 32.dp, background = t.accentContainer, tint = t.onAccentContainer)
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.bucket_empty_title), fontSize = 18.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.bucket_empty_body, desk), fontSize = 14.sp, color = t.textDim, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.bucket_ways_in), fontSize = 12.sp, lineHeight = 17.sp, color = t.textFaint, textAlign = TextAlign.Center)
                    }
                }
            }
            items(view.items, key = { it.name }) { item ->
                Tile(item, desk, deviceId, onMenu = { menuFor = item }, onResolve = { resolving = item })
            }
            if (view.notSynced.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResource(R.string.bucket_not_synced_folders, view.notSynced.joinToString(", ")),
                        fontSize = 12.sp, lineHeight = 17.sp, color = t.textFaint, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        menuFor?.let { item ->
            ItemMenu(item, onDismiss = { menuFor = null }, onRename = { renaming = item }, onRemove = { removing = item })
        }
        renaming?.let { item ->
            var name by remember(item.name) { mutableStateOf(item.name) }
            AlertDialog(
                onDismissRequest = { renaming = null },
                title = { Text(stringResource(R.string.bucket_rename)) },
                text = { OutlinedTextField(name, { name = it.replace("/", "").take(200) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
                confirmButton = {
                    TextButton(onClick = {
                        renaming = null
                        BucketEngine.rename(deviceId, item.name, name) { ok -> if (!ok) runOnUiThread { toast(getString(R.string.bucket_rename_failed)) } }
                    }) { Text(stringResource(R.string.bucket_rename)) }
                },
                dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }
        removing?.let { item ->
            AlertDialog(
                onDismissRequest = { removing = null },
                title = { Text(stringResource(R.string.bucket_remove_title, item.name)) },
                text = { Text(stringResource(R.string.bucket_remove_body, desk)) },
                confirmButton = { TextButton(onClick = { removing = null; BucketEngine.remove(deviceId, item.name) }) { Text(stringResource(R.string.bucket_remove)) } },
                dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }
        resolving?.let { item ->
            AlertDialog(
                onDismissRequest = { resolving = null },
                title = { Text(stringResource(R.string.bucket_conflict_title)) },
                text = {
                    Column {
                        Text(stringResource(R.string.bucket_conflict_body, item.name, desk), fontSize = 14.sp)
                        listOf(
                            BucketEngine.Resolve.BOTH to R.string.bucket_keep_both,
                            BucketEngine.Resolve.PHONE to R.string.bucket_keep_phone,
                            BucketEngine.Resolve.DESK to R.string.bucket_keep_desk,
                        ).forEach { (how, label) ->
                            TextButton(onClick = { resolving = null; BucketEngine.resolve(deviceId, item.name, how) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(stringResource(label, desk))
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { resolving = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }

    @Composable
    private fun Banner(text: String, action: String? = null, warn: Boolean = false, progress: Float? = null, onAction: () -> Unit = {}) {
        val t = tokens
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (warn) t.coralContainer else t.surface)
                .border(1.dp, t.hairline, RoundedCornerShape(18.dp)).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
                action?.let { TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(it) } }
            }
            progress?.let { LinearProgressIndicator({ it }, Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 4.dp).height(4.dp), color = t.accent, trackColor = t.fill, drawStopIndicator = {}, gapSize = 0.dp) }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun Tile(item: BucketItem, desk: String, deviceId: String, onMenu: () -> Unit, onResolve: () -> Unit) {
        val t = tokens
        val context = LocalContext.current
        val sizePx = with(LocalDensity.current) { 180.dp.roundToPx() }
        val size = Formatter.formatShortFileSize(context, item.size)
        val origin = when (item.from) {
            Side.DESK -> stringResource(R.string.bucket_from, desk)
            Side.PHONE -> stringResource(R.string.bucket_from_this_phone)
            null -> null
        }
        val state = when (item.state) {
            BucketItem.State.SYNCED -> stringResource(R.string.bucket_state_synced)
            BucketItem.State.SENDING -> stringResource(R.string.bucket_state_sending, desk)
            BucketItem.State.RECEIVING -> stringResource(R.string.bucket_state_receiving, desk)
            BucketItem.State.WAITING -> stringResource(R.string.bucket_waiting_for, desk)
            BucketItem.State.CONFLICT -> stringResource(R.string.bucket_state_conflict)
        }
        val description = listOfNotNull(item.name, size, origin, state).joinToString(", ")
        val moreLabel = stringResource(R.string.bucket_item_menu, item.name)
        Column(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(t.surface)
                .border(1.dp, if (item.state == BucketItem.State.CONFLICT) t.coral else t.hairline, RoundedCornerShape(20.dp))
                .combinedClickable(role = Role.Button, onLongClick = onMenu, onLongClickLabel = moreLabel) {
                    when {
                        item.state == BucketItem.State.CONFLICT -> onResolve()
                        item.uri != null -> open(item)
                        else -> onMenu()
                    }
                }
                .semantics(mergeDescendants = true) { contentDescription = description },
        ) {
            Box(Modifier.padding(6.dp).fillMaxWidth().height(120.dp).clip(RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                val kind = previewKind(null, item.name)
                val uri = item.uri
                if (uri == null) {
                    Box(Modifier.fillMaxSize().shimmer())
                } else {
                    val record = TransferRecord(uri.toString() + item.mtime, deviceId, desk, item.name, item.size, false, item.mtime, uri.toString(), null)
                    val preview by rememberFilePreview(record, sizePx)
                    val bitmap = preview.bitmap
                    when {
                        preview.loading -> Box(Modifier.fillMaxSize().shimmer())
                        bitmap != null -> Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else -> TypeIcon(iconFor(kind))
                    }
                }
                Badge(item.state, Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
            Row(Modifier.padding(start = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(item.name, fontFamily = MonoFamily, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    Text(
                        listOfNotNull(size, origin).joinToString(" · "),
                        fontSize = 11.sp, color = t.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (item.state == BucketItem.State.WAITING || item.state == BucketItem.State.CONFLICT) {
                        Text(state, fontSize = 11.sp, color = if (item.state == BucketItem.State.CONFLICT) t.coralText else t.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = onMenu, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.sym_more_vert), contentDescription = moreLabel, tint = t.textDim)
                }
            }
        }
    }

    @Composable
    private fun TypeIcon(@DrawableRes icon: Int) {
        val t = tokens
        Box(Modifier.fillMaxSize().background(t.accentContainer), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = t.onAccentContainer, modifier = Modifier.size(36.dp))
        }
    }

    /** Synced tick, a ring while it moves, a conflict mark; nothing while it waits (the text says so). */
    @Composable
    private fun Badge(state: BucketItem.State, modifier: Modifier) {
        val t = tokens
        val background = Modifier.size(26.dp).background(t.surface.copy(alpha = 0.92f), CircleShape)
        when (state) {
            BucketItem.State.SYNCED -> Box(modifier.then(background), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.sym_check_circle), contentDescription = null, tint = t.mintText, modifier = Modifier.size(18.dp))
            }
            BucketItem.State.SENDING, BucketItem.State.RECEIVING -> Box(modifier.then(background), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(16.dp), color = t.accent, strokeWidth = 2.dp, trackColor = t.fill)
            }
            BucketItem.State.CONFLICT -> Box(modifier.then(background), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.sym_warning), contentDescription = null, tint = t.coralText, modifier = Modifier.size(18.dp))
            }
            BucketItem.State.WAITING -> Unit
        }
    }

    @Composable
    private fun ItemMenu(item: BucketItem, onDismiss: () -> Unit, onRename: () -> Unit, onRemove: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    val uri = item.uri
                    listOfNotNull(
                        Triple(R.string.bucket_open, R.drawable.sym_open_in_new) { open(item) }.takeIf { uri != null },
                        Triple(R.string.bucket_share, R.drawable.sym_share) { share(item) }.takeIf { uri != null },
                        Triple(R.string.bucket_copy, R.drawable.sym_content_paste) { copy(item) }.takeIf { uri != null },
                        Triple(R.string.bucket_rename, R.drawable.sym_edit) { onRename() },
                        Triple(R.string.bucket_remove, R.drawable.sym_delete) { onRemove() },
                    ).forEach { (label, icon, action) ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                                .combinedClickable(role = Role.Button) { onDismiss(); action() }.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Icon(painterResource(icon), contentDescription = null, tint = tokens.textDim, modifier = Modifier.size(22.dp))
                            Text(stringResource(label), fontSize = 16.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
    }

    private fun mime(name: String) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    private fun open(item: BucketItem) {
        val uri = item.uri ?: return
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime(item.name)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (runCatching { startActivity(view) }.isFailure) toast(getString(R.string.bucket_no_app))
    }

    private fun share(item: BucketItem) {
        val uri = item.uri ?: return
        val send = Intent(Intent.ACTION_SEND).setType(mime(item.name)).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(send, item.name)) }
    }

    private fun copy(item: BucketItem) {
        val uri = item.uri ?: return
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newUri(contentResolver, item.name, uri))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast(getString(R.string.snippets_copied))
    }

    /** "Paste": a copied file, or text and links saved as files (F8b). */
    private fun paste(deviceId: String) {
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val uri = clip?.uri
        val text = clip?.coerceToText(this)?.toString()
        when {
            uri != null -> BucketEngine.add(deviceId, listOf(uri)) { a, s -> runOnUiThread { toast(if (a > 0) resources.getQuantityString(R.plurals.bucket_added, a, a) else getString(R.string.bucket_skipped, s)) } }
            !text.isNullOrBlank() -> BucketEngine.addText(deviceId, text) { ok -> runOnUiThread { toast(getString(if (ok) R.string.bucket_text_added else R.string.bucket_text_failed)) } }
            else -> toast(getString(R.string.snippets_clipboard_empty))
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_DEVICE = "deviceId"
        /** Files taken from one share. */
        const val MAX_SHARED = 500

        fun intent(context: Context, deviceId: String?): Intent = Intent(context, BucketActivity::class.java).apply { deviceId?.let { putExtra(EXTRA_DEVICE, it) } }

        /** Documents on the phone's storage: where the folder picker starts ("Choose or create DeskLink Bucket"). */
        fun documentsUri(): Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents")
    }
}
