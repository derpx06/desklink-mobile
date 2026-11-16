/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/** Share / Open with on a file DeskLink no longer may read once crashed the app (SecurityException on the main thread). */
class OpenElsewhereTest {
    private val intent = mockk<Intent>()

    @Test
    fun aGrantDeskLinkNoLongerHoldsIsSaidNotThrown() {
        val context = mockk<Context> { every { startActivity(any()) } throws SecurityException("no permission") }
        assertEquals(OpenElsewhere.Outcome.NOT_ALLOWED, OpenElsewhere.start(context) { intent })
    }

    @Test
    fun aPathOutsideTheProvidersRootsIsSaidNotThrown() {
        val context = mockk<Context>()
        assertEquals(OpenElsewhere.Outcome.NOT_ALLOWED, OpenElsewhere.start(context) { throw IllegalArgumentException("not in roots") })
    }

    @Test
    fun noAppAndSuccessAreTold() {
        val none = mockk<Context> { every { startActivity(any()) } throws ActivityNotFoundException() }
        assertEquals(OpenElsewhere.Outcome.NO_APP, OpenElsewhere.start(none) { intent })
        val ok = mockk<Context> { every { startActivity(any()) } returns Unit }
        assertEquals(OpenElsewhere.Outcome.STARTED, OpenElsewhere.start(ok) { intent })
    }
}
