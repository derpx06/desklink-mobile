/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.desklink.mobile.ui.compose.DeskLinkFixedTheme
import org.desklink.mobile.ui.compose.screen.onboarding.ForgetLeftRoutes
import org.desklink.mobile.ui.compose.screen.onboarding.OnboardingRoute
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xhdpi")
class OnboardingBehaviorTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun replayingTheTourStartsOnPageOneNotWhereItWasLeft() {
        var route by mutableStateOf("onboarding")
        compose.setContent {
            DeskLinkFixedTheme(compose.activity, false) {
                val holder = rememberSaveableStateHolder()
                ForgetLeftRoutes(route, holder, setOf("onboarding"))
                holder.SaveableStateProvider(route) {
                    if (route == "onboarding") OnboardingRoute(paired = true, onFinish = {}, onSkip = {})
                }
            }
        }
        repeat(4) { compose.onNodeWithText("Next").performClick() }
        compose.onNodeWithText("Done").assertExists() // page five
        route = "settings"
        compose.waitForIdle()
        route = "onboarding"
        compose.waitForIdle()
        compose.onNodeWithText("Welcome to DeskLink").assertExists()
        compose.onNodeWithText("Next").assertExists()
    }

    @Test
    fun everySegmentBarIsFortyEightDpToTouch() {
        compose.setContent { DeskLinkFixedTheme(compose.activity, false) { OnboardingRoute(paired = false, onFinish = {}, onSkip = {}) } }
        val bars = compose.onAllNodesWithContentDescription("Go to page", substring = true)
        repeat(5) { bars[it].assertHeightIsAtLeast(48.dp) }
    }
}
