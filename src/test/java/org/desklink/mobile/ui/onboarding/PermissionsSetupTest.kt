/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.onboarding

import android.Manifest
import android.app.Application
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.screen.onboarding.FirstRun
import org.desklink.mobile.ui.compose.screen.onboarding.allowAllRequest
import org.desklink.mobile.ui.compose.screen.onboarding.checklistRows
import org.desklink.mobile.ui.compose.screen.onboarding.currentStep
import org.desklink.mobile.ui.compose.screen.onboarding.setupGroups
import org.desklink.mobile.ui.compose.screen.onboarding.specialSteps
import org.desklink.mobile.ui.compose.screen.onboarding.stepExplanation
import org.desklink.mobile.ui.compose.screen.onboarding.stillMissing
import org.desklink.mobile.ui.compose.screen.settings.PermissionNeeds
import org.desklink.mobile.ui.compose.screen.settings.PermissionRow
import org.desklink.mobile.ui.compose.screen.settings.actionLabel
import org.desklink.mobile.ui.compose.screen.settings.permissionRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The first-run permissions step: what it lists on each Android release, what "Allow all" asks, the order of the special accesses, and that it shows once. */
@RunWith(RobolectricTestRunner::class)
class PermissionsSetupTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun setup(sdk: Int) = checklistRows(permissionRows(app, "your computer", {}, PermissionNeeds.setup, sdk))

    @Test
    fun androidThirteenAndLaterListNotificationsAndNoFilesRow() {
        // Robolectric's manufacturer is not an OEM with its own background setting; accessibility is asked when it is used.
        assertEquals(
            listOf("notifications", "camera", "notification_access", "calls", "contacts", "microphone", "battery"),
            setup(34).map { it.id },
        )
    }

    @Test
    fun beforeAndroidThirteenThereIsNoNotificationsPermissionAndBeforeElevenTheFilesRowShows() {
        assertEquals(listOf("camera", "notification_access", "calls", "contacts", "microphone", "battery"), setup(32).map { it.id })
        assertEquals(listOf("camera", "notification_access", "calls", "contacts", "microphone", "files", "battery"), setup(29).map { it.id })
    }

    @Test
    fun answeringCallsIsAskedOnlyWhereAndroidHasIt() {
        assertEquals(listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.CALL_PHONE), setup(34).first { it.id == "calls" }.permissions)
        assertEquals(listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE), setup(25).first { it.id == "calls" }.permissions)
    }

    @Test
    fun allowAllAsksEveryMissingRuntimePermissionInOneRequest() {
        assertEquals(
            listOf(
                Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.CAMERA, Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS,
                Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS, Manifest.permission.RECORD_AUDIO,
            ),
            allowAllRequest(setup(34)),
        )
        assertFalse("location is asked when trusted networks are used, not here", allowAllRequest(setup(34)).any { "LOCATION" in it })
    }

    @Test
    fun grantedRowsReadAsOnAndAreNotAskedAgain() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE)
        val rows = setup(34)
        assertTrue(rows.first { it.id == "camera" }.granted)
        assertFalse("a row is on only when all its permissions are", rows.first { it.id == "calls" }.granted)
        assertEquals(
            listOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS),
            allowAllRequest(rows),
        )
        assertEquals(listOf("notifications", "notification_access", "calls", "contacts", "battery"), stillMissing(rows))
    }

    private fun row(id: String, granted: Boolean = false, permissions: List<String> = emptyList(), blocked: Boolean = false, unknown: Boolean = false, relevant: Boolean = true) =
        PermissionRow(R.drawable.sym_info, id, "s", granted, unknown = unknown, relevant = relevant, id = id, permissions = permissions, blocked = blocked) { }

    @Test
    fun aPermissionAndroidWillNotAskForIsLeftToItsRowsOpenSettings() {
        val rows = listOf(row("camera", permissions = listOf("c")), row("contacts", permissions = listOf("k"), blocked = true))
        assertEquals(listOf("c"), allowAllRequest(rows))
        assertEquals(R.string.perm_open_settings, rows[1].actionLabel())
        assertEquals(R.string.settings_allow, rows[0].actionLabel())
        assertEquals(R.string.settings_open, row("oem", unknown = true).actionLabel())
    }

    @Test
    fun theSpecialAccessesFollowInListOrderSkippingWhatIsGranted() {
        val rows = listOf(
            row("notifications", permissions = listOf("n")),
            row("notification_access"),
            row("battery"),
            row("oem", unknown = true),
            row("accessibility", relevant = false),
        )
        assertEquals(listOf("notification_access", "battery", "oem"), specialSteps(rows))
        val steps = specialSteps(rows)
        // Notification access granted while the walk was on: the battery step is next.
        val after = rows.map { if (it.id == "notification_access") row("notification_access", granted = true) else it }
        assertEquals("battery", currentStep(steps, after)?.id)
        assertEquals(null, currentStep(emptyList(), rows))
        steps.forEach { assertNotNull("$it says what happens next", stepExplanation(it)) }
    }

    @Test
    fun theFirstGroupIsWhatPairingAndStayingConnectedNeed() {
        val (connect, features) = setupGroups(setup(34))
        assertEquals(listOf("notifications", "camera", "battery"), connect.map { it.id })
        assertEquals(listOf("notification_access", "calls", "contacts", "microphone"), features.map { it.id })
    }

    @Test
    fun theStepShowsOnceAndNeverToAPairedPerson() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        assertEquals("permissions_setup_done", FirstRun.KEY_SETUP_DONE)
        assertTrue(FirstRun.showSetup(FirstRun.setupDone(prefs), pairedCount = 0))
        assertFalse("a paired person goes straight to the scan screen", FirstRun.showSetup(done = false, pairedCount = 1))
        FirstRun.markSetupDone(prefs)
        assertFalse(FirstRun.showSetup(FirstRun.setupDone(prefs), pairedCount = 0))
        FirstRun.markSetupDone(prefs) // idempotent
        assertTrue(FirstRun.setupDone(prefs))
    }

    @Test
    fun settingsAndThePostPairingChecklistDoNotListCameraOrMicrophone() {
        val needs = PermissionNeeds.of(app)
        assertFalse(needs.camera || needs.microphone)
        val ids = permissionRows(app, "Desk", {}, needs).map { it.id }
        assertFalse(ids.toString(), "camera" in ids || "microphone" in ids)
    }
}
