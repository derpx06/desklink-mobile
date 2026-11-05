/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.shots

import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// The system's "Larger" font size (1.3) on a 360 dp phone; 1.0 is the phone/narrow classes and 2.0 the Huge ones. Every shot also runs the
// accessibility walker (`A11y.violations`), so a target under 48 dp or without a name fails here too.

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class LargeTextScreenShots : ScreenShots() {
    override val form = "large"
    @Before fun fontScale() = RuntimeEnvironment.setFontScale(1.3f)
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class LargeTextFirstRunShots : FirstRunShots() {
    override val form = "large"
    @Before fun fontScale() = RuntimeEnvironment.setFontScale(1.3f)
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class LargeTextWave5Shots : Wave5Shots() {
    override val form = "large"
    @Before fun fontScale() = RuntimeEnvironment.setFontScale(1.3f)
}
