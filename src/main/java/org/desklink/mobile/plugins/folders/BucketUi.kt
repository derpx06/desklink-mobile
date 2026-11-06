/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.folders

import android.content.ClipData
import org.desklink.mobile.helpers.DeskNames
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.format.DateUtils
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.horizontalScroll
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.res.pluralStringResource
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
import org.desklink.mobile.plugins.share.hasPicture
import org.desklink.mobile.plugins.share.previewKind
import org.desklink.mobile.ui.compose.DeskLinkTheme
import org.desklink.mobile.ui.compose.MonoFamily
import org.desklink.mobile.ui.compose.DeskTextButton
import org.desklink.mobile.ui.compose.PrimaryButton
import org.desklink.mobile.ui.compose.SecondaryButton
import org.desklink.mobile.ui.compose.components.DeskDialog
import org.desklink.mobile.ui.compose.components.DeskProgressBar
import org.desklink.mobile.ui.compose.components.DeskTextField
import org.desklink.mobile.ui.compose.components.DialogAction
import org.desklink.mobile.ui.compose.components.HeroCard
import org.desklink.mobile.ui.compose.components.IconCircle
import org.desklink.mobile.ui.compose.components.IconTile
import org.desklink.mobile.ui.compose.components.StatusDot
import org.desklink.mobile.ui.compose.screen.transfers.BucketBreakdown
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.widthIn
import org.desklink.mobile.ui.compose.components.LargeTitle
import org.desklink.mobile.ui.compose.components.ScreenHeader
import org.desklink.mobile.ui.compose.components.shimmer
import org.desklink.mobile.ui.compose.screen.transfers.iconFor
import org.desklink.mobile.ui.compose.screen.transfers.rememberFilePreview
import org.desklink.mobile.ui.compose.tokens
import java.io.File
import androidx.activity.ComponentActivity
import org.desklink.mobile.plugins.folders.BucketActivity.Companion.documentsUri
import org.desklink.mobile.plugins.folders.BucketActivity.Companion.skipText
import org.desklink.mobile.plugins.folders.BucketActivity.Companion.summaryText
import org.desklink.mobile.plugins.folders.BucketActivity.Companion.MAX_SHARED

/**
 * The Bucket's screen as a reusable piece: the Bucket activity hosts it full screen, and the Files tab
 * hosts it ([embedded]) under its own header. Everything that needs an Activity goes through [host].
 *
 * [pendingUris] / [pendingText]: shared in from another app, added as soon as the Bucket is on.
 */
class BucketUi(
    private val host: ComponentActivity,
    private val pendingUris: androidx.compose.runtime.MutableState<List<Uri>> = mutableStateOf(emptyList()),
    private val pendingText: androidx.compose.runtime.MutableState<String?> = mutableStateOf(null),
) {
    private val resources get() = host.resources
    private val cacheDir get() = host.cacheDir
    private val packageName get() = host.packageName
    private val contentResolver get() = host.contentResolver
    private fun getString(id: Int, vararg args: Any) = host.getString(id, *args)
    private fun runOnUiThread(block: () -> Unit) = host.runOnUiThread(block)
    private fun startActivity(intent: Intent) = host.startActivity(intent)
    private fun <T : Any> getSystemService(cls: Class<T>): T? = host.getSystemService(cls)

    @Composable
    fun Screen(
        deviceId: String,
        onBack: () -> Unit,
        embedded: Boolean = false,
        /** More list items after the Bucket's card (the Files tab's filter chips and file rows). Used with [embedded] only. */
        below: (androidx.compose.foundation.lazy.LazyListScope.() -> Unit)? = null,
        /** The Files tab's "Send files" (to the computer). */
        onSendFiles: () -> Unit = {},
    ) {
        val t = tokens
        val context = LocalContext.current
        val desk = remember(deviceId) { DeskNames.of(deviceId) }
        val views by BucketEngine.views.collectAsState()
        val view = views[deviceId]
        val on = view?.treeUri != null && view.phase != Phase.ASKING
        var plusMenu by remember { mutableStateOf(false) }
        var overflow by remember { mutableStateOf(false) }
        var photo by rememberSaveable { mutableStateOf<String?>(null) }
        // Where we are in the Bucket ("" = the top), and what is selected (long-press starts selecting).
        var path by rememberSaveable { mutableStateOf("") }
        var selected by remember { mutableStateOf(setOf<String>()) }
        var newFolder by remember { mutableStateOf(false) }
        var moving by remember { mutableStateOf(false) }
        var confirmRemove by remember { mutableStateOf(false) }
        val selecting = selected.isNotEmpty()
        BackHandler(selecting || path.isNotEmpty()) { if (selecting) selected = emptySet() else path = BucketLevel.parent(path) }

        fun report(result: BucketEngine.AddResult) = runOnUiThread { toast(addMessage(result)) }

        val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
            tree ?: return@rememberLauncherForActivityResult
            if (!BucketEngine.turnOn(host, deviceId, tree)) toast(getString(R.string.bucket_folder_failed))
        }
        // Decided when the banner is tapped (a missing folder starts fresh, a revoked grant keeps its records), not when the picker returns.
        var fixFresh by rememberSaveable { mutableStateOf(false) }
        val pickFix = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
            tree ?: return@rememberLauncherForActivityResult
            when (BucketEngine.fix(host, deviceId, tree, fixFresh)) {
                BucketEngine.FixResult.OK -> Unit
                BucketEngine.FixResult.FAILED -> toast(getString(R.string.bucket_folder_failed))
                BucketEngine.FixResult.OTHER_FOLDER -> toast(getString(R.string.bucket_fix_other_folder))
            }
        }
        val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) BucketEngine.add(deviceId, uris.take(MAX_SHARED), path, ::report)
        }
        val pickSource = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
            tree ?: return@rememberLauncherForActivityResult
            BucketEngine.addTree(deviceId, tree, path, ::report)
        }
        val pickDestination = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
            tree ?: return@rememberLauncherForActivityResult
            BucketEngine.saveCopy(deviceId, selected, tree) { n -> runOnUiThread { toast(resources.getQuantityString(R.plurals.bucket_saved, n, n)) } }
            selected = emptySet()
        }
        val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val path = photo ?: return@rememberLauncherForActivityResult
            photo = null
            val file = File(path)
            if (ok && file.length() > 0) {
                BucketEngine.add(deviceId, listOf(Uri.fromFile(file)), path) { r -> file.delete(); report(r) }
            } else {
                file.delete()
            }
        }

        // Shared in from another app: never added on its own (any app can send this intent, and what is added reaches the
        // computer). Asked first, naming what it is; only then added.
        val shared = pendingUris.value
        val sharedText = pendingText.value
        if (on && (shared.isNotEmpty() || sharedText != null)) {
            DeskDialog(
                onDismissRequest = { pendingUris.value = emptyList(); pendingText.value = null },
                title = if (shared.isNotEmpty()) pluralStringResource(R.plurals.bucket_confirm_add_title, shared.size, shared.size) else stringResource(R.string.bucket_confirm_add_text_title),
                text = { Text(if (shared.isNotEmpty()) stringResource(R.string.bucket_confirm_add_body) else sharedText.orEmpty().take(200)) },
                confirm = DialogAction(stringResource(R.string.bucket_confirm_add)) {
                    val uris = pendingUris.value
                    val text = pendingText.value
                    pendingUris.value = emptyList(); pendingText.value = null
                    if (uris.isNotEmpty()) BucketEngine.add(deviceId, uris, path, ::report)
                    if (text != null) BucketEngine.addText(deviceId, text, path) { ok -> runOnUiThread { toast(getString(if (ok) R.string.bucket_text_added else R.string.bucket_text_failed)) } }
                },
                dismiss = DialogAction(stringResource(R.string.cancel)) { pendingUris.value = emptyList(); pendingText.value = null },
            )
        }

        if (newFolder) {
            var name by remember { mutableStateOf("") }
            DeskDialog(
                onDismissRequest = { newFolder = false },
                title = stringResource(R.string.bucket_new_folder),
                text = { DeskTextField(name, { name = it.replace("/", "").take(100) }, Modifier.fillMaxWidth(), label = stringResource(R.string.bucket_new_folder), singleLine = true) },
                confirm = DialogAction(stringResource(R.string.bucket_create), enabled = name.isNotBlank()) {
                    newFolder = false
                    BucketEngine.newFolder(deviceId, path, name) { ok -> if (!ok) runOnUiThread { toast(getString(R.string.bucket_new_folder_failed)) } }
                },
                dismiss = DialogAction(stringResource(R.string.cancel)) { newFolder = false },
            )
        }
        if (confirmRemove) {
            DeskDialog(
                onDismissRequest = { confirmRemove = false },
                title = resources.getQuantityString(R.plurals.bucket_remove_many_title, selected.size, selected.size),
                text = { Text(stringResource(R.string.bucket_remove_body, desk)) },
                confirm = DialogAction(stringResource(R.string.bucket_remove), destructive = true) { confirmRemove = false; BucketEngine.remove(deviceId, selected); selected = emptySet() },
                dismiss = DialogAction(stringResource(R.string.cancel)) { confirmRemove = false },
            )
        }
        if (moving) {
            val targets = BucketLevel.targets(view?.items.orEmpty(), selected)
            DeskDialog(
                onDismissRequest = { moving = false },
                title = stringResource(R.string.bucket_move_to),
                text = {
                    Column {
                        if (targets.isEmpty()) Text(stringResource(R.string.bucket_move_nowhere), fontSize = 14.sp)
                        targets.forEach { target ->
                            Text(
                                if (target.isEmpty()) stringResource(R.string.bucket_title) else target.replace("/", " › "),
                                fontSize = 16.sp,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) {
                                    moving = false
                                    BucketEngine.move(deviceId, selected, target) { moved, refused ->
                                        runOnUiThread { toast(if (refused > 0) resources.getQuantityString(R.plurals.bucket_move_refused, refused, refused) else resources.getQuantityString(R.plurals.bucket_moved, moved, moved)) }
                                    }
                                    selected = emptySet()
                                }.padding(vertical = 12.dp),
                            )
                        }
                    }
                },
                dismiss = DialogAction(stringResource(R.string.cancel)) { moving = false },
            )
        }
        val headerActions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
            if (on) {
                Box {
                    IconButton(onClick = { overflow = true }, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.sym_more_vert), contentDescription = stringResource(R.string.bucket_more), tint = t.text)
                    }
                    DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.bucket_sync_now)) }, onClick = { overflow = false; BucketEngine.syncNow(deviceId) })
                        DropdownMenuItem(
                            text = { Text(stringResource(if (view?.userPaused == true) R.string.bucket_resume else R.string.bucket_pause)) },
                            onClick = { overflow = false; BucketEngine.setPaused(deviceId, view?.userPaused != true) },
                        )
                        DropdownMenuItem(text = { Text(stringResource(R.string.bucket_turn_off)) }, onClick = { overflow = false; BucketEngine.turnOff(deviceId) })
                    }
                }
            }
        }
        // In the Files tab the tab supplies the window's bars and the title; here only the Bucket's own controls.
        Column(Modifier.fillMaxSize().background(t.bg).then(if (embedded) Modifier else Modifier.statusBarsPadding())) {
            if (embedded) {
                EmbeddedBody(
                    view, desk, deviceId, on, headerActions, below, onSendFiles,
                    onTurnOn = { pickFolder.launch(documentsUri()) },
                    onFix = { fresh -> fixFresh = fresh; pickFix.launch(BucketActivity.fixUri()) },
                )
                return@Column
            }
            if (selecting) SelectionBar(
                count = selected.size,
                onClose = { selected = emptySet() },
                onAll = { selected = view?.items?.filter { BucketLevel.parent(it.path) == path }?.map { it.path }?.toSet().orEmpty() },
                onShare = { shareMany(view, selected); selected = emptySet() },
                onSave = { pickDestination.launch(null) },
                onMove = { moving = true },
                onRemove = { confirmRemove = true },
            ) else if (embedded) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { headerActions() }
            } else ScreenHeader(onBack, title = path.substringAfterLast('/').takeIf { on && it.isNotEmpty() }, actions = headerActions)
            // Only inside a folder: at the top it would be an empty 48 dp band under the header.
            if (on && !selecting && path.isNotEmpty()) Breadcrumb(path) { path = it }
            if (!embedded && (!on || path.isEmpty())) LargeTitle(stringResource(R.string.bucket_title), subtitle = if (on && view!!.summary == null) status(view, desk) else if (!on) stringResource(R.string.bucket_subtitle, desk) else null)
            view?.summary?.takeIf { on }?.let { Box(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp)) { SyncHeader(it, view.userPaused, desk) } }
            Box(Modifier.weight(1f)) {
                when {
                    view?.phase == Phase.ASKING -> HeroBody(desk, waiting = true, offered = false, onTurnOn = {}, onDecline = {})
                    !on -> HeroBody(
                        desk,
                        waiting = false,
                        offered = view?.offered == true,
                        onTurnOn = { pickFolder.launch(documentsUri()) },
                        onDecline = { BucketEngine.decline(deviceId) },
                    )
                    else -> Content(view!!, desk, deviceId, path, selected, onFix = { fresh -> fixFresh = fresh; pickFix.launch(BucketActivity.fixUri()) }, onOpen = { path = it; selected = emptySet() }, onSelect = { p -> selected = if (p in selected) selected - p else selected + p })
                }
                if (on && !selecting) {
                    Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp)) {
                        PrimaryButton(stringResource(R.string.bucket_add), { plusMenu = true }, icon = R.drawable.sym_add)
                        DropdownMenu(expanded = plusMenu, onDismissRequest = { plusMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.bucket_add_files)) },
                                leadingIcon = { Icon(painterResource(R.drawable.sym_upload_file), contentDescription = null) },
                                onClick = { plusMenu = false; pickFiles.launch(arrayOf("*/*")) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.bucket_add_folder)) },
                                leadingIcon = { Icon(painterResource(R.drawable.sym_folder_zip), contentDescription = null) },
                                onClick = { plusMenu = false; pickSource.launch(null) },
                            )
                            if (BucketLevel.canMakeFolder(path)) DropdownMenuItem(
                                text = { Text(stringResource(R.string.bucket_new_folder)) },
                                leadingIcon = { Icon(painterResource(R.drawable.sym_add), contentDescription = null) },
                                onClick = { plusMenu = false; newFolder = true },
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

    /** Selection mode: the count, Select all, and the bulk actions. */
    @Composable
    private fun SelectionBar(count: Int, onClose: () -> Unit, onAll: () -> Unit, onShare: () -> Unit, onSave: () -> Unit, onMove: () -> Unit, onRemove: () -> Unit) {
        val t = tokens
        var more by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.sym_close), contentDescription = stringResource(R.string.bucket_selection_done), tint = t.text)
            }
            Text(
                resources.getQuantityString(R.plurals.bucket_selected, count, count),
                fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            TextButton(onClick = onAll, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.bucket_select_all)) }
            IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.sym_delete), contentDescription = stringResource(R.string.bucket_remove), tint = t.text)
            }
            Box {
                IconButton(onClick = { more = true }, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.sym_more_vert), contentDescription = stringResource(R.string.bucket_more), tint = t.text)
                }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.bucket_move_to)) }, onClick = { more = false; onMove() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.bucket_share)) }, onClick = { more = false; onShare() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.bucket_save_copy)) }, onClick = { more = false; onSave() })
                }
            }
        }
    }

    /** "Bucket › Holidays › 2026": each part opens that level (48 dp tall). */
    @Composable
    private fun Breadcrumb(path: String, onOpen: (String) -> Unit) {
        val t = tokens
        val root = stringResource(R.string.bucket_title)
        val crumbs = BucketLevel.crumbs(path, root)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            crumbs.forEachIndexed { i, (label, target) ->
                val last = i == crumbs.lastIndex
                if (i > 0) Text("›", color = t.textFaint, fontSize = 16.sp)
                Text(
                    label,
                    fontSize = if (path.isEmpty()) 0.sp else 16.sp,
                    fontWeight = if (last) FontWeight.Medium else FontWeight.Normal,
                    color = if (last) t.text else t.accentText,
                    maxLines = 1,
                    modifier = Modifier.heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically)
                        .clickable(enabled = !last, role = Role.Button, onClickLabel = label) { onOpen(target) }.padding(horizontal = 8.dp),
                )
            }
        }
    }

    /** One clear sentence for what an add did, and what was left out. */
    private fun addMessage(r: BucketEngine.AddResult): String {
        val parts = mutableListOf<String>()
        if (r.added > 0) parts += resources.getQuantityString(R.plurals.bucket_added, r.added, r.added)
        if (r.tooLarge > 0) parts += getString(R.string.bucket_too_large)
        if (r.bucketFull > 0) parts += getString(R.string.bucket_full)
        if (r.overAddLimit > 0) parts += getString(R.string.bucket_over_add_limit, r.overAddLimit)
        if (r.tooDeep > 0) parts += getString(R.string.bucket_too_deep_left_out, r.tooDeep)
        if (r.failed > 0) parts += getString(R.string.bucket_skipped, r.failed)
        if (r.cancelled) parts += getString(R.string.bucket_add_cancelled)
        return parts.joinToString(". ").ifEmpty { getString(R.string.bucket_nothing_added) }
    }

    private fun shareMany(view: BucketView?, paths: Set<String>) {
        val uris = ArrayList(view?.items.orEmpty().filter { !it.dir && it.path in paths }.mapNotNull { it.uri })
        if (uris.isEmpty()) return toast(getString(R.string.bucket_share_files_only))
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(send, null)) }
    }

    /** "realme-desk · Last synced 2 min ago · 1.2 GB". */
    @Composable
    private fun status(view: BucketView, desk: String): String {
        val connected = DeskLinkApplication.getInstance().getDevice(view.deviceId)?.hasActiveWebRtcFeatureTransport == true
        val sync = when {
            !connected -> stringResource(R.string.bucket_waiting_for, desk)
            view.phase == Phase.SYNCING || view.phase == Phase.SCANNING -> stringResource(R.string.bucket_syncing)
            view.userPaused -> stringResource(R.string.bucket_paused_by_you)
            view.lastSync != null -> stringResource(R.string.bucket_up_to_date, DateUtils.getRelativeTimeSpanString(view.lastSync, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString())
            else -> stringResource(R.string.bucket_with, desk)
        }
        return "$sync · ${org.desklink.mobile.helpers.Sizes.iec(view.bytes)}"
    }

    /** While files move: "Syncing 3 files · 1.1 GB of 2.4 GB · about 4 min left", a thin bar and the speed. */
    @Composable
    private fun SyncHeader(summary: ProgressSummary, userPaused: Boolean, desk: String) {
        val t = tokens
        val line = summaryLine(summary, userPaused, desk)
        Column(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = line },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(line, fontSize = 13.sp, lineHeight = 18.sp, color = t.textDim)
            DeskProgressBar(summary.fraction)
            if (summary.bytesPerSecond > 0 && !summary.paused) {
                Text(
                    BucketSyncNotification.words(host).perSecond.format(org.desklink.mobile.helpers.Sizes.iec(summary.bytesPerSecond)),
                    fontSize = 12.sp, color = t.textDim, fontFamily = MonoFamily,
                )
            }
        }
    }

    @Composable
    private fun HeroBody(desk: String, waiting: Boolean, offered: Boolean, onTurnOn: () -> Unit, onDecline: () -> Unit) {
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
                PrimaryButton(stringResource(R.string.bucket_turn_on), onTurnOn, icon = R.drawable.sym_inventory, modifier = Modifier.fillMaxWidth())
                if (offered) SecondaryButton(stringResource(R.string.bucket_not_now), onDecline, height = 48.dp, modifier = Modifier.fillMaxWidth())
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
    private fun Content(view: BucketView, desk: String, deviceId: String, path: String, selected: Set<String>, onFix: (Boolean) -> Unit, onOpen: (String) -> Unit, onSelect: (String) -> Unit) {
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
            item(span = { GridItemSpan(maxLineSpan) }) { Banners(view, desk, deviceId, onFix) }
            val tiles = BucketLevel.tiles(view.items, path)
            if (tiles.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    DropZone(Modifier.fillMaxWidth().heightIn(min = 300.dp)) {
                        IconCircle(R.drawable.sym_inventory, 64.dp, 32.dp, background = t.accentContainer, tint = t.onAccentContainer)
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.bucket_empty_title), fontSize = 18.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.bucket_empty_body, desk), fontSize = 14.sp, color = t.textDim, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.bucket_ways_in), fontSize = 12.sp, lineHeight = 17.sp, color = t.textDim, textAlign = TextAlign.Center)
                    }
                }
            }
            items(tiles, key = { it.item.path }) { tile ->
                Tile(
                    tile, desk, deviceId, selected = tile.item.path in selected, selecting = selected.isNotEmpty(),
                    onMenu = { menuFor = tile.item }, onResolve = { resolving = tile.item },
                    onOpenFolder = { onOpen(tile.item.path) }, onToggle = { onSelect(tile.item.path) },
                )
            }
            if (view.notSynced.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { NotSynced(view, deviceId) }
        }
        menuFor?.let { item ->
            ItemMenu(item, onDismiss = { menuFor = null }, onRename = { renaming = item }, onRemove = { removing = item })
        }
        renaming?.let { item ->
            var name by remember(item.name) { mutableStateOf(item.name) }
            DeskDialog(
                onDismissRequest = { renaming = null },
                title = stringResource(R.string.bucket_rename),
                text = { DeskTextField(name, { name = it.replace("/", "").take(200) }, Modifier.fillMaxWidth(), label = stringResource(R.string.bucket_rename), singleLine = true) },
                confirm = DialogAction(stringResource(R.string.bucket_rename)) {
                    renaming = null
                    BucketEngine.rename(deviceId, item.path, name) { ok -> if (!ok) runOnUiThread { toast(getString(R.string.bucket_rename_failed)) } }
                },
                dismiss = DialogAction(stringResource(R.string.cancel)) { renaming = null },
            )
        }
        removing?.let { item ->
            DeskDialog(
                onDismissRequest = { removing = null },
                title = stringResource(R.string.bucket_remove_title, item.name),
                text = { Text(stringResource(R.string.bucket_remove_body, desk)) },
                confirm = DialogAction(stringResource(R.string.bucket_remove), destructive = true) { removing = null; BucketEngine.remove(deviceId, listOf(item.path)) },
                dismiss = DialogAction(stringResource(R.string.cancel)) { removing = null },
            )
        }
        resolving?.let { item ->
            DeskDialog(
                onDismissRequest = { resolving = null },
                title = stringResource(R.string.bucket_conflict_title),
                text = {
                    Column {
                        Text(stringResource(R.string.bucket_conflict_body, item.name, desk), fontSize = 14.sp)
                        listOf(
                            BucketEngine.Resolve.BOTH to R.string.bucket_keep_both,
                            BucketEngine.Resolve.PHONE to R.string.bucket_keep_phone,
                            BucketEngine.Resolve.DESK to R.string.bucket_keep_desk,
                        ).forEach { (how, label) ->
                            DeskTextButton(stringResource(label, desk), { resolving = null; BucketEngine.resolve(deviceId, item.path, how) }, Modifier.fillMaxWidth())
                        }
                    }
                },
                dismiss = DialogAction(stringResource(R.string.cancel)) { resolving = null },
            )
        }
    }

    /** Everything that needs a word above the files: adding, the phase's warnings, a full phone or Bucket. Nothing when all is well. */
    @Composable
    private fun Banners(view: BucketView, desk: String, deviceId: String, onFix: (Boolean) -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            view.adding?.let { (done, total) ->
                Banner(stringResource(R.string.bucket_adding, done + 1, total), action = stringResource(R.string.cancel), progress = (done.toFloat() / total), onAction = BucketEngine::cancelAdd)
            }
            if (view.phase == Phase.HELD_EMPTY) {
                Banner(stringResource(R.string.bucket_held_empty, desk), action = stringResource(R.string.bucket_held_confirm), warn = true, action2 = stringResource(R.string.bucket_fix), onAction2 = { onFix(BucketFix.startsFresh(Phase.HELD_EMPTY)) }) { BucketEngine.confirmEmpty(deviceId) }
            }
            if (view.phase == Phase.NO_ACCESS) Banner(stringResource(R.string.bucket_no_access, desk), action = stringResource(R.string.bucket_fix), warn = true, onAction = { onFix(BucketFix.startsFresh(Phase.NO_ACCESS)) })
            if (view.phase == Phase.LISTING_FAILED) Banner(stringResource(R.string.bucket_listing_failed), warn = true)
            if (view.phase == Phase.NOT_ON_DESK) Banner(stringResource(R.string.bucket_not_on_desk, desk), warn = true)
            if (view.storageFull) Banner(stringResource(R.string.bucket_storage_full), warn = true)
            if (view.phase == Phase.TOO_MANY) Banner(stringResource(R.string.bucket_too_many), warn = true)
            if (view.bytes > FolderSync.WARN_BUCKET_BYTES) {
                Banner(stringResource(if (view.bytes >= FolderSync.MAX_BUCKET_BYTES) R.string.bucket_full else R.string.bucket_large, org.desklink.mobile.helpers.Sizes.iec(view.bytes)), warn = view.bytes >= FolderSync.MAX_BUCKET_BYTES)
            }
        }
    }

    /** "Not synced (N)" with a reason each, and the retry for files that could not be fetched. */
    @Composable
    private fun NotSynced(view: BucketView, deviceId: String) {
        val t = tokens
        val reasons = view.notSynced.groupBy { it.second }.map { (why, list) ->
            stringResource(skipText(why), list.size) + ": " + list.take(3).joinToString(", ") { it.first.substringAfterLast('/') } + if (list.size > 3) "…" else ""
        }
        Column {
            Text(
                stringResource(R.string.bucket_not_synced, view.notSynced.size) + "\n" + reasons.joinToString("\n"),
                fontSize = 12.sp, lineHeight = 17.sp, color = t.textDim, modifier = Modifier.padding(top = 8.dp),
            )
            if (view.notSynced.any { it.second == Skip.COULDNT_FETCH }) {
                DeskTextButton(stringResource(R.string.bucket_retry_fetch), { BucketEngine.retryGaveUp(deviceId) })
            }
        }
    }

    /**
     * The Files tab's whole Bucket part: warnings, the Bucket card, then [below] (the transfers). The folder browser is one tap
     * away ("Open"): it is its own screen, where adding, selecting and moving live.
     */
    @Composable
    private fun EmbeddedBody(
        view: BucketView?,
        desk: String,
        deviceId: String,
        on: Boolean,
        menu: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
        below: (androidx.compose.foundation.lazy.LazyListScope.() -> Unit)?,
        onSendFiles: () -> Unit,
        onTurnOn: () -> Unit,
        onFix: (Boolean) -> Unit,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.fillMaxSize().widthIn(max = 840.dp),
                contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "bucket") {
                    if (view != null && on) BucketCard(view, desk, deviceId, menu, onSendFiles, onFix)
                    else BucketInvite(desk, waiting = view?.phase == Phase.ASKING, offered = view?.offered == true, onTurnOn, onDecline = { BucketEngine.decline(deviceId) })
                }
                if (view != null && on && view.notSynced.isNotEmpty()) item(key = "not-synced") { NotSynced(view, deviceId) }
                below?.invoke(this)
            }
        }
    }

    /** Off: what the Bucket is, and one button (a single folder pick is all Android needs). */
    @Composable
    private fun BucketInvite(desk: String, waiting: Boolean, offered: Boolean, onTurnOn: () -> Unit, onDecline: () -> Unit) {
        val t = tokens
        HeroCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile(R.drawable.sym_inventory, size = 48.dp, radius = 16.dp)
                Text(
                    stringResource(if (offered) R.string.bucket_offered_title else R.string.bucket_hero_title, desk),
                    fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).semantics { heading() },
                )
            }
            Text(stringResource(R.string.bucket_hero_body, desk), fontSize = 14.sp, lineHeight = 20.sp, color = t.textDim)
            if (waiting) {
                Text(stringResource(R.string.bucket_asking, desk), fontSize = 14.sp, color = t.textDim)
            } else {
                // The button first, the one-time folder note after it as a caption: what to do before how it works.
                PrimaryButton(stringResource(R.string.bucket_turn_on), onTurnOn, icon = R.drawable.sym_inventory, height = 48.dp, modifier = Modifier.fillMaxWidth())
                if (offered) SecondaryButton(stringResource(R.string.bucket_not_now), onDecline, height = 48.dp, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.bucket_pick_hint), fontSize = 12.sp, lineHeight = 17.sp, color = t.textDim)
            }
        }
    }

    /** On: the state in a word, how much is in it, a bar of what kind, "Send files" and "Open". */
    @Composable
    private fun BucketCard(view: BucketView, desk: String, deviceId: String, menu: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit, onSendFiles: () -> Unit, onFix: (Boolean) -> Unit) {
        val t = tokens
        val context = LocalContext.current
        val connected = DeskLinkApplication.getInstance().getDevice(view.deviceId)?.hasActiveWebRtcFeatureTransport == true
        val (state, stateColor) = when {
            !connected -> stringResource(R.string.bucket_waiting_for, desk) to t.amber
            view.phase == Phase.SYNCING || view.phase == Phase.SCANNING -> stringResource(R.string.bucket_syncing) to t.accentText
            view.userPaused -> stringResource(R.string.bucket_paused_by_you) to t.textDim
            else -> stringResource(R.string.files_bucket_in_step) to t.mintText
        }
        val files = view.items.filter { !it.dir }
        val breakdown = remember(view.items) { BucketBreakdown.of(files.map { it.name to it.size }) }
        val count = stringResource(R.string.files_bucket_count, files.size, org.desklink.mobile.helpers.Sizes.iec(view.bytes))
        val synced = view.lastSync?.let { stringResource(R.string.bucket_up_to_date, DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()) }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Banners(view, desk, deviceId, onFix)
            HeroCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile(R.drawable.sym_folder, size = 48.dp, radius = 16.dp, container = t.surface, content = t.accent)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.bucket_title), fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, modifier = Modifier.semantics { heading() })
                        Text("↔ $desk", fontFamily = MonoFamily, fontSize = 12.sp, lineHeight = 16.sp, color = t.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    menu()
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    StatusDot(stateColor)
                    Text(state, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (stateColor == t.amber) t.text else stateColor)
                }
                val summary = view.summary
                if (summary != null) {
                    SyncHeader(summary, view.userPaused, desk)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(count, fontSize = 13.sp, lineHeight = 18.sp, color = t.text)
                        if (synced != null) Text(synced, fontSize = 12.sp, lineHeight = 16.sp, color = t.textDim)
                        if (breakdown.total > 0) {
                            Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(t.surface), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                listOf(breakdown.images to 1f, breakdown.documents to 0.55f, breakdown.other to 0.3f).filter { it.first > 0 }.forEach { (bytes, alpha) ->
                                    Box(Modifier.weight(bytes.toFloat()).height(6.dp).background(t.accent.copy(alpha = alpha)))
                                }
                            }
                            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                listOf(R.string.files_bucket_images to breakdown.images, R.string.files_bucket_documents to breakdown.documents, R.string.files_bucket_other to breakdown.other)
                                    .filter { it.second > 0 }
                                    .forEach { (label, bytes) -> Text(stringResource(label, org.desklink.mobile.helpers.Sizes.iec(bytes)), fontSize = 11.sp, lineHeight = 15.sp, color = t.textDim) }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(stringResource(R.string.home_send_files), onSendFiles, Modifier.weight(1.2f), icon = R.drawable.sym_upload, height = 48.dp)
                    SecondaryButton(stringResource(R.string.files_bucket_open), { startActivity(BucketActivity.intent(context, deviceId)) }, Modifier.weight(1f), icon = R.drawable.sym_folder, height = 48.dp)
                }
            }
        }
    }

    @Composable
    private fun Banner(text: String, action: String? = null, warn: Boolean = false, progress: Float? = null, action2: String? = null, onAction2: () -> Unit = {}, onAction: () -> Unit = {}) {
        val t = tokens
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (warn) t.coralContainer else t.surface)
                .border(1.dp, t.hairline, RoundedCornerShape(18.dp)).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
                action?.let { DeskTextButton(it, onAction) }
                action2?.let { DeskTextButton(it, onAction2) }
            }
            progress?.let { LinearProgressIndicator({ it }, Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 4.dp).height(4.dp), color = t.accent, trackColor = t.fill, drawStopIndicator = {}, gapSize = 0.dp) }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun Tile(tile: BucketTile, desk: String, deviceId: String, selected: Boolean, selecting: Boolean, onMenu: () -> Unit, onResolve: () -> Unit, onOpenFolder: () -> Unit, onToggle: () -> Unit) {
        val item = tile.item
        val t = tokens
        val context = LocalContext.current
        val sizePx = with(LocalDensity.current) { 180.dp.roundToPx() }
        val size = if (item.dir) resources.getQuantityString(R.plurals.bucket_folder_count, tile.count, tile.count) + " · " + org.desklink.mobile.helpers.Sizes.iec(tile.bytes) else org.desklink.mobile.helpers.Sizes.iec(item.size)
        val origin = if (item.dir) null else when (item.from) {
            Side.DESK -> stringResource(R.string.bucket_from, desk)
            Side.PHONE -> stringResource(R.string.bucket_from_this_phone)
            null -> null
        }
        val state = when (tile.state) {
            BucketItem.State.SYNCED -> stringResource(R.string.bucket_state_synced)
            BucketItem.State.SENDING -> stringResource(R.string.bucket_state_sending, desk)
            BucketItem.State.RECEIVING -> stringResource(R.string.bucket_state_receiving, desk)
            BucketItem.State.WAITING -> stringResource(R.string.bucket_waiting_for, desk)
            BucketItem.State.CONFLICT -> stringResource(R.string.bucket_state_conflict)
        }
        val progressLine = (item.progress ?: tile.progress?.let { Progress(it.done, it.total, it.etaSeconds, it.bytesPerSecond, it.paused) })
            ?.let { BucketProgress.line(it, { b -> org.desklink.mobile.helpers.Sizes.iec(b) }, BucketSyncNotification.words(context)) }
        val description = listOfNotNull(item.name, size, origin, state, progressLine).joinToString(", ")
        val moreLabel = stringResource(R.string.bucket_item_menu, item.name)
        Column(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(t.surface)
                .border(if (selected) 2.dp else 1.dp, if (selected) t.accent else if (tile.state == BucketItem.State.CONFLICT) t.coral else t.hairline, RoundedCornerShape(20.dp))
                .combinedClickable(role = Role.Button, onLongClick = onToggle, onLongClickLabel = stringResource(R.string.bucket_select)) {
                    when {
                        selecting -> onToggle()
                        item.dir -> onOpenFolder()
                        tile.state == BucketItem.State.CONFLICT -> onResolve()
                        item.uri != null -> open(item)
                        else -> onMenu()
                    }
                }
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    this.selected = selected
                },
        ) {
            Box(Modifier.padding(6.dp).fillMaxWidth().height(120.dp).clip(RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                val kind = previewKind(null, item.name)
                val uri = item.uri
                if (item.dir) {
                    TypeIcon(R.drawable.sym_folder)
                } else if (uri == null) {
                    if (kind.hasPicture) Box(Modifier.fillMaxSize().shimmer()) else TypeIcon(iconFor(kind))
                } else {
                    val record = TransferRecord(uri.toString() + item.mtime, deviceId, desk, item.name, item.size, false, item.mtime, uri.toString(), null)
                    val preview by rememberFilePreview(record, sizePx)
                    val bitmap = preview.bitmap
                    when {
                        preview.loading && kind.hasPicture -> Box(Modifier.fillMaxSize().shimmer())
                        bitmap != null -> Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else -> TypeIcon(iconFor(kind))
                    }
                }
                Badge(tile.state, item.progress ?: tile.progress?.let { Progress(it.done, it.total, it.etaSeconds, it.bytesPerSecond, it.paused) }, Modifier.align(Alignment.TopEnd).padding(6.dp))
                if (selected) {
                    Box(Modifier.align(Alignment.TopStart).padding(6.dp).size(26.dp).background(t.accent, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.sym_check_circle), contentDescription = null, tint = t.onAccent, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Row(Modifier.padding(start = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(item.name, fontFamily = MonoFamily, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    Text(
                        listOfNotNull(size, origin).joinToString(" · "),
                        fontSize = 11.sp, color = t.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (progressLine != null) {
                        Text(progressLine, fontSize = 11.sp, color = t.accentText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else if (item.restored) {
                        Text(stringResource(R.string.bucket_restored_note, desk), fontSize = 11.sp, color = t.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else if (tile.state == BucketItem.State.WAITING || tile.state == BucketItem.State.CONFLICT) {
                        Text(state, fontSize = 11.sp, color = if (tile.state == BucketItem.State.CONFLICT) t.coralText else t.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    private fun Badge(state: BucketItem.State, progress: Progress?, modifier: Modifier) {
        val t = tokens
        val background = Modifier.size(26.dp).background(t.surface.copy(alpha = 0.92f), CircleShape)
        when (state) {
            BucketItem.State.SYNCED -> Box(modifier.then(background), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.sym_check_circle), contentDescription = null, tint = t.mintText, modifier = Modifier.size(18.dp))
            }
            BucketItem.State.SENDING, BucketItem.State.RECEIVING -> Box(modifier.then(background), contentAlignment = Alignment.Center) {
                // Determinate: how much of this file has moved.
                CircularProgressIndicator({ progress?.fraction ?: 0f }, Modifier.size(18.dp), color = if (progress?.paused == true) t.textFaint else t.accent, strokeWidth = 2.5.dp, trackColor = t.fill, gapSize = 0.dp)
            }
            BucketItem.State.CONFLICT -> Box(modifier.then(background), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.sym_warning), contentDescription = null, tint = t.coralText, modifier = Modifier.size(18.dp))
            }
            BucketItem.State.WAITING -> Unit
        }
    }

    @Composable
    private fun ItemMenu(item: BucketItem, onDismiss: () -> Unit, onRename: () -> Unit, onRemove: () -> Unit) {
        DeskDialog(
            onDismissRequest = onDismiss,
            title = item.name,
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
            dismiss = DialogAction(stringResource(R.string.cancel)) { onDismiss() },
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
        val text = clip?.coerceToText(host)?.toString()
        when {
            uri != null -> BucketEngine.add(deviceId, listOf(uri), "") { r -> runOnUiThread { toast(addMessage(r)) } }
            !text.isNullOrBlank() -> BucketEngine.addText(deviceId, text, "") { ok -> runOnUiThread { toast(getString(if (ok) R.string.bucket_text_added else R.string.bucket_text_failed)) } }
            else -> toast(getString(R.string.snippets_clipboard_empty))
        }
    }

    private fun toast(text: String) = Toast.makeText(host, text, Toast.LENGTH_SHORT).show()

    private fun summaryLine(summary: ProgressSummary, userPaused: Boolean, desk: String) = summaryText(host, summary, userPaused, desk)
}
