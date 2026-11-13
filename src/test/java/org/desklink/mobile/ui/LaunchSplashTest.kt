/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui

import org.desklink.mobile.ui.compose.screen.onboarding.FirstRun
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

/** One launch: the system splash (Theme.DeskLink.Starting) straight into the app, never a second splash drawn by Compose. */
@RunWith(RobolectricTestRunner::class)
class LaunchSplashTest {
    private fun text(path: String) = File(path).readText()

    @Test
    fun theLauncherActivityStartsOnTheSplashThemeAndHandsOverToTheAppTheme() {
        val manifest = text("src/main/AndroidManifest.xml")
        val main = manifest.substringAfter("android:name=\"org.desklink.mobile.ui.MainActivity\"").substringBefore(">")
        assertTrue(main.contains("android:theme=\"@style/Theme.DeskLink.Starting\""))
        val starting = text("src/main/res/values/styles.xml").substringAfter("name=\"Theme.DeskLink.Starting\"").substringBefore("</style>")
        assertTrue(starting.contains("<item name=\"postSplashScreenTheme\">@style/DeskLinkTheme.NoActionBar</item>"))
        assertTrue("light and dark from the app's own background", starting.contains("@color/brand_background"))
    }

    @Test
    fun theSplashIsInstalledBeforeAnythingIsDrawnAndHeldBriefly() {
        val onCreate = text("src/main/java/org/desklink/mobile/ui/MainActivity.kt").substringAfter("override fun onCreate(").substringBefore("setContent {")
        assertTrue(onCreate.indexOf("installSplashScreen()") in 0 until onCreate.indexOf("super.onCreate("))
        assertTrue("held while the computer is looked for, half a second at most", FirstRun.SPLASH_MAX_MS <= 500)
        assertFalse("no Compose splash on top of it", File("src/main/java/org/desklink/mobile/ui/compose/screen/onboarding/SplashScreen.kt").exists())
        // A cold start goes through installSplashScreen and the theme switch without throwing.
        Robolectric.buildActivity(MainActivity::class.java).create().destroy()
    }
}
