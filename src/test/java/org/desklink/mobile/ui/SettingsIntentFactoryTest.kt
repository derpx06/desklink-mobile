package org.desklink.mobile.ui

import android.content.Intent
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsIntentFactoryTest {
    @Test
    fun buildsRequestedIntentWithUriWhenProvided() {
        val intent = SettingsIntentFactory.buildRequestedIntent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:org.desklink.mobile"
        )

        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:org.desklink.mobile", intent.data.toString())
    }

    @Test
    fun keepsRequestedIntentWhenItCanResolve() {
        val requested = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

        val selected = SettingsIntentFactory.bestAvailableIntent(
            requested = requested,
            packageName = "org.desklink.mobile",
            canResolve = { true }
        )

        assertSame(requested, selected)
    }

    @Test
    fun fallsBackToAppDetailsWhenRequestedIntentCannotResolve() {
        val requested = Intent("org.desklink.mobile.MISSING_SETTINGS")

        val selected = SettingsIntentFactory.bestAvailableIntent(
            requested = requested,
            packageName = "org.desklink.mobile",
            canResolve = { false }
        )

        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, selected.action)
        assertEquals("package:org.desklink.mobile", selected.data.toString())
    }
}
