/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.mousereceiver

import android.app.Application
import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import org.desklink.mobile.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** Nobody is sent to Accessibility settings before they have read what the service can see and agreed. */
@RunWith(RobolectricTestRunner::class)
class AccessibilityDisclosureTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun fresh() {
        app.getSharedPreferences("accessibility_disclosure", 0).edit().clear().commit()
    }

    private fun latest(): AlertDialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as AlertDialog

    @Test
    fun theFirstThingShownIsTheDisclosureWithAgreeAndNoThanks() {
        Robolectric.buildActivity(AccessibilitySetupActivity::class.java).setup()
        val dialog = latest()
        assertTrue(dialog.isShowing)
        val text = app.getString(R.string.accessibility_disclosure_body)
        assertTrue("says what it can see", "see what is on this phone" in text)
        assertEquals(app.getString(R.string.accessibility_disclosure_agree), dialog.getButton(DialogInterface.BUTTON_POSITIVE).text.toString())
        assertFalse("the settings are not offered yet", AccessibilitySetupActivity.disclosureAccepted(app))
    }

    @Test
    fun agreeingRecordsConsentAndOnlyThenOffersTheSettings() {
        val controller = Robolectric.buildActivity(AccessibilitySetupActivity::class.java).setup()
        latest().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(AccessibilitySetupActivity.disclosureAccepted(app))
        assertEquals(app.getString(R.string.accessibility_setup_open), latest().getButton(DialogInterface.BUTTON_POSITIVE).text.toString())
        controller.destroy()
    }

    @Test
    fun sayingNoThanksFinishesWithoutConsent() {
        val controller = Robolectric.buildActivity(AccessibilitySetupActivity::class.java).setup()
        latest().getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(controller.get().isFinishing)
        assertFalse(AccessibilitySetupActivity.disclosureAccepted(app))
    }
}
