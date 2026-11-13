/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.screen

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** One capture prompt per request: a rotation under the system dialog must not stack a second one nobody can answer. */
@RunWith(RobolectricTestRunner::class)
class ScreenProjectionRotationTest {
    @Test
    fun aRotationDoesNotAskAgain() {
        val controller = Robolectric.buildActivity(ScreenProjectionActivity::class.java).setup()
        assertNotNull("the first start asks", shadowOf(controller.get()).nextStartedActivityForResult)
        controller.recreate()
        assertNull("the recreated activity waits for the first answer", shadowOf(controller.get()).nextStartedActivityForResult)
    }
}
