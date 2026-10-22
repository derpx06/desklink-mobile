/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.folders

import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R
import org.desklink.mobile.helpers.ThreadHelper
import org.desklink.mobile.ui.compose.DeskLinkTheme
import org.desklink.mobile.ui.compose.components.GroupRow
import org.desklink.mobile.ui.compose.components.LargeTitle
import org.desklink.mobile.ui.compose.components.PillButton
import org.desklink.mobile.ui.compose.components.PillStyle
import org.desklink.mobile.ui.compose.components.RowGroup
import org.desklink.mobile.ui.compose.components.ScreenHeader
import org.desklink.mobile.ui.compose.components.SectionLabel
import org.desklink.mobile.ui.compose.tokens

/** F8 "Synced folders" for one desk: pick folders (with why some can't be picked) and their options. */
class SyncedFoldersActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val deviceId = intent.getStringExtra("deviceId") ?: return finish()
        val desk = DeskLinkApplication.getInstance().getDevice(deviceId)?.name.orEmpty()
        setContent { DeskLinkTheme(this) { Screen(deviceId, desk) } }
    }

    @Composable
    private fun Screen(deviceId: String, desk: String) {
        val t = tokens
        val revision by FolderPairs.revision.collectAsState()
        val pairs = remember(revision) { FolderPairs.all(this).filter { it.deviceId == deviceId } }
        var scanning by remember { mutableStateOf<String?>(null) }
        val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
            tree ?: return@rememberLauncherForActivityResult
            if (!FolderPairs.add(this, deviceId, tree)) toast(R.string.folders_add_failed)
        }
        Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding()) {
            ScreenHeader(::finish)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                LargeTitle(stringResource(R.string.folders_title), subtitle = stringResource(R.string.folders_subtitle, desk))
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(stringResource(R.string.folders_pending, desk), fontSize = 13.sp, color = t.textDim)
                    pairs.forEach { pair ->
                        val granted = remember(revision) { FolderPairs.granted(this@SyncedFoldersActivity, pair) }
                        SectionLabel(pair.name)
                        RowGroup {
                            GroupRow(
                                stringResource(R.string.folders_mode),
                                subtitle = stringResource(
                                    when (pair.mode) {
                                        Mode.TWO_WAY -> R.string.folders_mode_two_way
                                        Mode.DESK_TO_PHONE -> R.string.folders_mode_desk_to_phone
                                        Mode.PHONE_TO_DESK -> R.string.folders_mode_phone_to_desk
                                    },
                                    desk,
                                ),
                                onClick = { FolderPairs.update(this@SyncedFoldersActivity, pair.copy(mode = Mode.entries[(pair.mode.ordinal + 1) % Mode.entries.size])) },
                            )
                            GroupRow(stringResource(R.string.folders_wifi_only), checked = pair.wifiOnly, onClick = { FolderPairs.update(this@SyncedFoldersActivity, pair.copy(wifiOnly = !pair.wifiOnly)) })
                            GroupRow(stringResource(R.string.folders_charging_only), checked = pair.chargingOnly, onClick = { FolderPairs.update(this@SyncedFoldersActivity, pair.copy(chargingOnly = !pair.chargingOnly)) })
                            GroupRow(stringResource(R.string.folders_hidden), checked = pair.includeHidden, onClick = { FolderPairs.update(this@SyncedFoldersActivity, pair.copy(includeHidden = !pair.includeHidden)) })
                            GroupRow(
                                stringResource(if (scanning == pair.id) R.string.folders_scanning else R.string.folders_scan),
                                subtitle = when {
                                    !granted -> stringResource(R.string.folders_grant_lost)
                                    pair.scannedAt == 0L -> stringResource(R.string.folders_never_scanned)
                                    else -> stringResource(
                                        R.string.folders_scanned,
                                        pair.files,
                                        Formatter.formatShortFileSize(this@SyncedFoldersActivity, pair.bytes),
                                        DateUtils.getRelativeTimeSpanString(pair.scannedAt).toString(),
                                    )
                                },
                                enabled = granted && scanning == null,
                                onClick = {
                                    scanning = pair.id
                                    ThreadHelper.execute {
                                        val manifest = runCatching { FolderPairs.scan(this@SyncedFoldersActivity, pair, emptyMap()) }.getOrNull()
                                        runOnUiThread {
                                            scanning = null
                                            if (manifest == null) toast(R.string.folders_scan_failed) else {
                                                val live = manifest.values.filter { !it.deleted }
                                                FolderPairs.update(this@SyncedFoldersActivity, pair.copy(files = live.size, bytes = live.sumOf { it.size }, scannedAt = System.currentTimeMillis()))
                                            }
                                        }
                                    }
                                },
                            )
                            GroupRow(stringResource(R.string.folders_remove), titleColor = t.coralText, onClick = { FolderPairs.remove(this@SyncedFoldersActivity, pair.id) })
                        }
                    }
                    Text(stringResource(R.string.folders_blocked), fontSize = 13.sp, color = t.textDim)
                    PillButton(
                        stringResource(R.string.folders_add),
                        { if (pairs.size >= FolderSync.MAX_PAIRS) toast(R.string.folders_full) else pick.launch(null) },
                        icon = R.drawable.sym_add,
                        style = PillStyle.Tonal,
                        height = 48.dp,
                    )
                    Spacer(Modifier.height(24.dp).navigationBarsPadding())
                }
            }
        }
    }

    private fun toast(text: Int) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
