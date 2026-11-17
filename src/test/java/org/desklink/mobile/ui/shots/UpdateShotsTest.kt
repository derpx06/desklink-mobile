/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.desklink.mobile.ui.compose.components.SectionLabel
import org.desklink.mobile.ui.compose.tokens
import org.desklink.mobile.ui.compose.update.CheckStatus
import org.desklink.mobile.ui.compose.update.UpdateSheetActions
import org.desklink.mobile.ui.compose.update.UpdateSheetContent
import org.desklink.mobile.ui.compose.update.UpdateSheetModel
import org.desklink.mobile.ui.compose.update.UpdatesSettings
import org.desklink.mobile.ui.compose.update.UpdatesSettingsActions
import org.desklink.mobile.ui.compose.update.UpdatesSettingsModel
import org.desklink.mobile.update.Channel
import org.desklink.mobile.update.InstallSource
import org.desklink.mobile.update.Note
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val NOTES = listOf(
    Note("Faster reconnects", "Back online in seconds after a Wi-Fi change.", "refresh"),
    Note("Pairing from the phone fixed", "No more waiting after you pair.", "swap"),
    Note("Clearer Terminals", "A plain message when the computer is offline.", "terminal"),
)

private fun model(source: InstallSource, required: Boolean = false) = UpdateSheetModel(
    version = "0.4.0", installed = "0.3.0", beta = true, notes = NOTES, source = source,
    sizeText = if (source == InstallSource.GITHUB) "24 MB" else null,
    sha256 = if (source == InstallSource.GITHUB) "3f9a7c41d8e2b6055a1c9d4e7f20b3a86c5d1e9f04a7b2c8d3e6f1a09b5c1d2e" else null,
    required = required, requiredMajorMinor = "0.4",
)

private fun actions(log: MutableList<String> = mutableListOf()) = UpdateSheetActions({ log += "primary" }, { log += "notnow" }, { log += "skip" }, { log += "copy" })

/** The sheet's own shape, as the bottom-sheet window draws it (a window is not in the decor view the harness captures). */
@Composable
private fun SheetFrame(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xCC1B1B1F)), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)).background(tokens.surface2)
                .verticalScroll(rememberScrollState()),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 10.dp).height(4.dp).widthIn(min = 38.dp).background(tokens.hairlineStrong, RoundedCornerShape(2.dp)))
            content()
        }
    }
}

/** The update sheet and the Settings controls, drawn in every state. Each shot also runs the accessibility and mid-word walkers. */
abstract class UpdateShots : ShotTest() {
    @Test fun sheetGitHub() = shoot("update-sheet-github") { SheetFrame { UpdateSheetContent(model(InstallSource.GITHUB), actions()) } }
    @Test fun sheetGitHubVerifyOpen() = shoot("update-sheet-verify-open") { SheetFrame { UpdateSheetContent(model(InstallSource.GITHUB), actions(), verifyOpen = true) } }
    @Test fun sheetPlay() = shoot("update-sheet-play") { SheetFrame { UpdateSheetContent(model(InstallSource.GOOGLE_PLAY), actions()) } }
    @Test fun sheetFdroid() = shoot("update-sheet-fdroid") { SheetFrame { UpdateSheetContent(model(InstallSource.FDROID), actions()) } }
    @Test fun sheetRequired() = shoot("update-sheet-required") { SheetFrame { UpdateSheetContent(model(InstallSource.GOOGLE_PLAY, required = true), actions()) } }

    @Test fun settingsStates() = shoot("update-settings") {
        Column(Modifier.fillMaxSize().background(tokens.bg).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)) {
            val none = UpdatesSettingsActions({}, {}, {}, {}, {})
            val base = UpdatesSettingsModel(true, true, true, Channel.BETA, false, CheckStatus.Idle, null)
            SectionLabel("Updates")
            UpdatesSettings(base.copy(status = CheckStatus.UpToDate("0.3.0", "Nov 17, 20:04"), skippedVersion = "0.4.0"), none)
            UpdatesSettings(base.copy(status = CheckStatus.Checking, enabled = false), none)
            UpdatesSettings(base.copy(status = CheckStatus.Failed, stableSelectable = true), none)
            UpdatesSettings(base.copy(configured = false), none)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xhdpi")
class PhoneUpdateShots : UpdateShots() {
    override val form = "phone"

    /** The real host: back (and so a swipe or a tap outside) is "Not now". */
    @Test fun theHostMapsDismissToNotNow() {
        val log = mutableListOf<String>()
        compose.setContent { org.desklink.mobile.ui.compose.DeskLinkFixedTheme(compose.activity, false) { org.desklink.mobile.ui.compose.update.UpdatePromptHost(model(InstallSource.GOOGLE_PLAY), actions(log)) } }
        compose.waitForIdle()
        compose.onNodeWithText("Update on Google Play").assertIsDisplayed()
        (org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.activity.ComponentDialog).onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertEquals(listOf("notnow"), log)
    }

    @Test fun everyButtonCallsItsAction() {
        val log = mutableListOf<String>()
        compose.setContent { org.desklink.mobile.ui.compose.DeskLinkFixedTheme(compose.activity, false) { SheetFrame { UpdateSheetContent(model(InstallSource.GITHUB), actions(log)) } } }
        compose.onNodeWithText("Download update").performClick()
        compose.onNodeWithText("Not now").performClick()
        compose.onNodeWithText("Skip this version").performClick()
        compose.onNodeWithText("Verify the download").performClick()
        compose.onNodeWithText("Copy").performClick()
        assertEquals(listOf("primary", "notnow", "skip", "copy"), log)
    }

    @Test fun aRequiredSheetOffersOnlyItsPrimaryAction() {
        compose.setContent { org.desklink.mobile.ui.compose.DeskLinkFixedTheme(compose.activity, false) { SheetFrame { UpdateSheetContent(model(InstallSource.GOOGLE_PLAY, required = true), actions()) } } }
        compose.onNodeWithText("Update needed").assertIsDisplayed()
        compose.onNodeWithText("Update on Google Play").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("Not now")).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasText("Skip this version")).fetchSemanticsNodes().size)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
class NarrowUpdateShots : UpdateShots() { override val form = "narrow" }

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class LargeUpdateShots : UpdateShots() {
    override val form = "large"
    @org.junit.Before fun fontScale() = org.robolectric.RuntimeEnvironment.setFontScale(1.3f)
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class HugeUpdateShots : UpdateShots() {
    override val form = "huge"
    @org.junit.Before fun fontScale() = org.robolectric.RuntimeEnvironment.setFontScale(2.0f)
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w840dp-h1100dp-xhdpi")
class TabletUpdateShots : UpdateShots() { override val form = "tablet" }

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ldrtl-w360dp-h780dp-xhdpi")
class RtlUpdateShots : UpdateShots() {
    override val form = "rtl"
    override val rtl = true
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w780dp-h360dp-land-xhdpi")
class LandscapeUpdateShots : UpdateShots() { override val form = "landscape" }
