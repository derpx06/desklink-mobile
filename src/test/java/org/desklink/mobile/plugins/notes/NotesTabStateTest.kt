/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notes

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.desklink.mobile.ui.compose.DeskLinkFixedTheme
import org.desklink.mobile.ui.compose.screen.tabs.NotesTab
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NotesTabStateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(editing: androidx.compose.runtime.MutableState<String?>) {
        compose.setContent {
            DeskLinkFixedTheme(compose.activity, false) {
                NotesTab(androidx.compose.runtime.remember { NotesUi(compose.activity, editing) }, compose.activity, "desk", clipboardOn = false, onClipboard = {}, onSendClipboard = {}, onMessage = {})
            }
        }
    }

    @Test fun theSearchSurvivesOpeningANoteAndComingBack() {
        val editing = mutableStateOf<String?>(null)
        show(editing)
        compose.onNodeWithContentDescription("Search notes and snippets").performTextInput("milk")
        compose.onNodeWithText("milk").assertExists()
        editing.value = NotesActivity.NEW
        compose.waitForIdle()
        editing.value = null
        compose.waitForIdle()
        compose.onNodeWithText("milk").assertExists()
    }

    @Test fun anotherNoteOpensItsOwnEditorNotTheOldOne() {
        val a = NotesStore.newNote(compose.activity, "First note title", "body one")!!
        val b = NotesStore.newNote(compose.activity, "Second note title", "body two")!!
        val editing = mutableStateOf<String?>(a)
        show(editing)
        compose.onNodeWithText("First note title").assertExists()
        editing.value = b
        compose.waitForIdle()
        compose.onNodeWithText("Second note title").assertExists()
        compose.onNodeWithText("First note title").assertDoesNotExist()
    }

    /**
     * F41: the caller reads the setting from preferences, which Compose does not observe, so it kept passing "on" after a tap
     * turned sharing off. The switch must follow the tap, and a second tap must turn it back on, not send "off" again.
     */
    @Test fun theClipboardSwitchFollowsATapEvenWhileTheCallerStillSaysOn() {
        val sent = mutableListOf<Boolean>()
        compose.setContent {
            DeskLinkFixedTheme(compose.activity, false) {
                NotesTab(androidx.compose.runtime.remember { NotesUi(compose.activity, mutableStateOf(null)) }, compose.activity, "desk", clipboardOn = true, onClipboard = { sent += it }, onSendClipboard = {}, onMessage = {})
            }
        }
        val header = compose.onNodeWithContentDescription("Clipboard sync")
        header.assertIsOn()
        header.performClick()
        header.assertIsOff()
        header.performClick()
        header.assertIsOn()
        assertEquals(listOf(false, true), sent)
    }
}
