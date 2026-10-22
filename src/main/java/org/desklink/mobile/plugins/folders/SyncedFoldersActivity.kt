/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.folders

import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.DeskLinkTheme
import org.desklink.mobile.ui.compose.components.GroupRow
import org.desklink.mobile.ui.compose.components.LargeTitle
import org.desklink.mobile.ui.compose.components.PillButton
import org.desklink.mobile.ui.compose.components.PillStyle
import org.desklink.mobile.ui.compose.components.RowGroup
import org.desklink.mobile.ui.compose.components.ScreenHeader
import org.desklink.mobile.ui.compose.components.SectionLabel
import org.desklink.mobile.ui.compose.tokens

/** F8 "Synced folders" for one desk: its offers, the pairs with their state, and adding one. */
class SyncedFoldersActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val deviceId = intent.getStringExtra("deviceId") ?: return finish()
        FolderEngine.start(this)
        val desk = DeskLinkApplication.getInstance().getDevice(deviceId)?.name.orEmpty()
        setContent { DeskLinkTheme(this) { Screen(deviceId, desk) } }
    }

    @Composable
    private fun Screen(deviceId: String, desk: String) {
        val t = tokens
        val views by FolderEngine.views.collectAsState()
        val allOffers by FolderEngine.pendingOffers.collectAsState()
        val pairs = views.filter { it.pair.deviceId == deviceId }
        val offers = allOffers.filter { it.deviceId == deviceId }
        // The offer being answered, while the system folder picker is up.
        var answering by remember { mutableStateOf<Offer?>(null) }
        val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
            val offer = answering
            answering = null
            tree ?: return@rememberLauncherForActivityResult
            val ok = if (offer != null) FolderEngine.accept(this, offer, tree) else FolderEngine.add(this, deviceId, tree)
            if (!ok) toast(R.string.folders_add_failed)
        }
        Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding()) {
            ScreenHeader(::finish)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                LargeTitle(stringResource(R.string.folders_title), subtitle = stringResource(R.string.folders_subtitle, desk))
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (offers.isNotEmpty()) {
                        SectionLabel(stringResource(R.string.folders_offers, desk))
                        RowGroup {
                            offers.forEach { offer ->
                                GroupRow(offer.name, icon = R.drawable.sym_folder_zip, subtitle = stringResource(R.string.folders_offer_pick), onClick = {
                                    answering = offer
                                    pick.launch(null)
                                })
                                GroupRow(stringResource(R.string.folders_offer_decline, offer.name), titleColor = t.coralText, onClick = { FolderEngine.decline(offer) })
                            }
                        }
                    }
                    pairs.forEach { view ->
                        val pair = view.pair
                        SectionLabel(pair.name)
                        RowGroup {
                            GroupRow(
                                stringResource(R.string.folders_state),
                                icon = R.drawable.sym_folder_zip,
                                subtitle = phase(view, desk),
                                onClick = if (view.phase != Phase.OFFERED) ({ FolderEngine.syncNow(pair.id) }) else null,
                            )
                            if (view.phase != Phase.OFFERED) {
                                GroupRow(stringResource(R.string.folders_wifi_only), checked = pair.wifiOnly, onClick = { FolderEngine.update(pair.copy(wifiOnly = !pair.wifiOnly)) })
                                GroupRow(stringResource(R.string.folders_charging_only), checked = pair.chargingOnly, onClick = { FolderEngine.update(pair.copy(chargingOnly = !pair.chargingOnly)) })
                                GroupRow(stringResource(R.string.folders_hidden), checked = pair.includeHidden, onClick = { FolderEngine.update(pair.copy(includeHidden = !pair.includeHidden)) })
                            }
                            GroupRow(stringResource(R.string.folders_remove), titleColor = t.coralText, onClick = { FolderEngine.remove(pair.id) })
                        }
                    }
                    Text(stringResource(R.string.folders_blocked), fontSize = 13.sp, color = t.textDim)
                    PillButton(
                        stringResource(R.string.folders_add),
                        {
                            if (pairs.size >= FolderSync.MAX_PAIRS) toast(R.string.folders_full) else {
                                answering = null
                                pick.launch(null)
                            }
                        },
                        icon = R.drawable.sym_add,
                        style = PillStyle.Tonal,
                        height = 48.dp,
                    )
                    Spacer(Modifier.height(24.dp).navigationBarsPadding())
                }
            }
        }
    }

    @Composable
    private fun phase(view: PairView, desk: String): String = when (view.phase) {
        Phase.WAITING -> stringResource(R.string.folders_phase_waiting, desk)
        Phase.NOT_ON_DESK -> stringResource(R.string.folders_phase_not_on_desk, desk)
        Phase.OFFERED -> stringResource(R.string.folders_phase_offered, desk)
        Phase.PAUSED_WIFI -> stringResource(R.string.folders_phase_wifi)
        Phase.PAUSED_CHARGING -> stringResource(R.string.folders_phase_charging)
        Phase.SCANNING -> stringResource(R.string.folders_scanning)
        Phase.SYNCING -> pluralStringResource(R.plurals.folders_phase_syncing, view.pending, view.pending)
        Phase.TOO_MANY -> stringResource(R.string.folders_phase_too_many)
        Phase.NO_ACCESS -> stringResource(R.string.folders_grant_lost)
        Phase.UP_TO_DATE -> view.lastSync?.let {
            stringResource(R.string.folders_phase_up_to_date, view.files, DateUtils.getRelativeTimeSpanString(it).toString())
        } ?: stringResource(R.string.folders_phase_up_to_date_now, view.files)
    }

    private fun toast(text: Int) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
