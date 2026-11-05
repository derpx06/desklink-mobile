/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.DeskLinkSpacing
import org.desklink.mobile.ui.compose.DeskLinkType
import org.desklink.mobile.ui.compose.DeskTextButton
import org.desklink.mobile.ui.compose.PrimaryButton
import org.desklink.mobile.ui.compose.SecondaryButton
import org.desklink.mobile.ui.compose.components.ActionTile
import org.desklink.mobile.ui.compose.components.AssistChip
import org.desklink.mobile.ui.compose.components.CheckItem
import org.desklink.mobile.ui.compose.components.ChecklistRow
import org.desklink.mobile.ui.compose.components.ChecklistState
import org.desklink.mobile.ui.compose.components.DeskFilterChip
import org.desklink.mobile.ui.compose.components.DeskProgressBar
import org.desklink.mobile.ui.compose.components.DeskSnackbar
import org.desklink.mobile.ui.compose.components.GroupRow
import org.desklink.mobile.ui.compose.components.IconTile
import org.desklink.mobile.ui.compose.components.QuickActionTile
import org.desklink.mobile.ui.compose.components.RowGroup
import org.desklink.mobile.ui.compose.components.SectionHeader
import org.desklink.mobile.ui.compose.components.SegmentedControl
import org.desklink.mobile.ui.compose.components.StatusDot
import org.desklink.mobile.ui.compose.DeskIconButton
import org.desklink.mobile.ui.compose.tokens
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every shared component of the design system on one sheet, light and dark, at a 360 dp phone, the 320 dp narrow
 * case and 320 dp at 200 % text. Images land in `~/.cache/android-ui/shots/` as `<form>-components-*-<theme>.png`.
 */
abstract class ComponentShots : ShotTest() {
    @Composable
    private fun Sheet(content: @Composable () -> Unit) {
        Column(
            Modifier.fillMaxSize().background(tokens.bg).padding(horizontal = DeskLinkSpacing.page).padding(top = 8.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DeskLinkSpacing.md),
        ) { content() }
    }

    @Test fun buttons() = shoot("components-buttons") {
        Sheet {
            PrimaryButton("Send files", {}, Modifier.fillMaxWidth(), icon = R.drawable.sym_upload)
            SecondaryButton("Open", {}, Modifier.fillMaxWidth(), icon = R.drawable.sym_folder)
            SecondaryButton("Not now", {}, Modifier.fillMaxWidth(), tonal = true)
            PrimaryButton("Send files", {}, Modifier.fillMaxWidth(), height = 48.dp)
            PrimaryButton("Waiting for arch-desk to answer", {}, Modifier.fillMaxWidth(), enabled = false)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeskTextButton("Skip", {})
                DeskTextButton("All actions", {}, icon = R.drawable.sym_arrow_forward)
                DeskIconButton(R.drawable.sym_search, "Search", {})
                DeskIconButton(R.drawable.sym_more_vert, "More", {}, enabled = false)
            }
        }
    }

    @Test fun choices() = shoot("components-choices") {
        Sheet {
            SegmentedControl(listOf("Notes", "Snippets", "Clipboard"), 1, {})
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState()).fillMaxWidth()) {
                DeskFilterChip("All", true, {})
                DeskFilterChip("Images", false, {})
                DeskFilterChip("Documents", false, {})
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip("Send a link", {}, icon = R.drawable.sym_link)
                AssistChip("Ping", {})
            }
        }
    }

    @Test fun tiles() = shoot("components-tiles") {
        Sheet {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionTile(R.drawable.sym_desktop_windows, "Desk screen", {}, Modifier.weight(1f), primary = true)
                ActionTile(R.drawable.sym_upload_file, "Send files", {}, Modifier.weight(1f))
                ActionTile(R.drawable.sym_terminal, "Terminal", {}, Modifier.weight(1f), enabled = false, hint = "Needs Wi-Fi")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                QuickActionTile(R.drawable.sym_link, "Send a link", {}, Modifier.weight(1f))
                QuickActionTile(R.drawable.sym_vibration, "Ring desk", {}, Modifier.weight(1f))
                QuickActionTile(R.drawable.sym_lock, "Lock desk", {}, Modifier.weight(1f))
                QuickActionTile(R.drawable.sym_lock_open, "Unlock arch-desk", {}, Modifier.weight(1f), enabled = false)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile(R.drawable.sym_image)
                IconTile(R.drawable.sym_description)
                IconTile(R.drawable.sym_folder_zip, size = 36.dp)
            }
        }
    }

    @Test fun rowsAndStatus() = shoot("components-rows") {
        Sheet {
            SectionHeader("Quick actions") { DeskTextButton("All actions", {}) }
            RowGroup {
                GroupRow("Files", icon = R.drawable.sym_upload_file, subtitle = "Photos, documents, anything", chevron = true, onClick = {})
                GroupRow("Clipboard", icon = R.drawable.sym_content_paste, subtitle = "Share copies with arch-desk", checked = true, onClick = {})
            }
            SectionHeader("Turn on what you will use")
            RowGroup {
                ChecklistRow(R.drawable.sym_notifications, "Notifications", "Show this phone's notifications on arch-desk", ChecklistState.Todo, "Allow", "On", {})
                ChecklistRow(R.drawable.sym_mic, "Microphone", "Talk through arch-desk", ChecklistState.Done, "Allow", "On", {})
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                StatusDot(tokens.mint); Text("Connected", style = DeskLinkType.mono)
                StatusDot(tokens.amber); Text("Waiting", style = DeskLinkType.mono)
                StatusDot(tokens.red); Text("Failed", style = DeskLinkType.mono)
            }
        }
    }

    @Test fun progressChecklistSnackbar() = shoot("components-feedback") {
        Sheet {
            DeskProgressBar(0.64f)
            DeskProgressBar(1f, color = tokens.mint)
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(
                    Modifier.fillMaxWidth().background(tokens.accentContainer, org.desklink.mobile.ui.compose.DeskLinkShape.group).padding(16.dp),
                ) {
                    CheckItem("Bump protocol to v9", true, {})
                    CheckItem("Sign APK, upload to Play", false, {})
                }
            }
            DeskSnackbar("File sent to arch-desk")
            DeskSnackbar("Note deleted", actionLabel = "Undo")
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xhdpi")
class PhoneComponentShots : ComponentShots() { override val form = "phone" }

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
class NarrowComponentShots : ComponentShots() { override val form = "narrow" }

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
class HugeTextComponentShots : ComponentShots() {
    override val form = "huge"
    @Before fun fontScale() = RuntimeEnvironment.setFontScale(2.0f)
}
