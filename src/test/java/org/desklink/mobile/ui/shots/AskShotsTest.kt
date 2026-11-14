/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.desklink.mobile.plugins.desksettings.AskBook
import org.desklink.mobile.plugins.desksettings.AskConsentContent
import org.desklink.mobile.plugins.desksettings.Asks
import org.desklink.mobile.ui.compose.components.AskOnComputerRow
import org.desklink.mobile.ui.compose.components.IncomingAsksBanner
import org.desklink.mobile.ui.compose.components.RowGroup
import org.desklink.mobile.ui.compose.tokens
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Ask on phone / Ask on computer, drawn: the heads-up, the consent screen, the banner, and the Ask on computer rows in each state. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xhdpi")
class AskShotsTest : ShotTest() {
    override val form = "phone"

    @Test
    fun theHeadsUp() {
        val notice = Asks.notice(compose.activity, "desk", "Workbench", Asks.Feature.CONTACTS)
        val builder = android.app.Notification.Builder.recoverBuilder(compose.activity, notice)
        val views = builder.createHeadsUpContentView() ?: builder.createContentView()
        shoot("ask-notification") {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                AndroidView(factory = { ctx -> views.apply(ctx, android.widget.FrameLayout(ctx)).also { it.setBackgroundColor(android.graphics.Color.WHITE) } })
            }
        }
    }

    @Test
    fun theConsentScreen() = shoot("ask-consent") {
        AskConsentContent("Workbench asks to see your contacts", "Android asks next: tap Allow there.") {}
    }

    @Test
    fun theBanner() = shoot("ask-banner") {
        Column(Modifier.fillMaxSize().background(tokens.bg)) {
            IncomingAsksBanner(listOf(Asks.Incoming("desk", "Workbench", Asks.Feature.CALLS, 0)), {}, {})
        }
    }

    @Test
    fun theAskOnComputerRows() = shoot("ask-on-computer") {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            RowGroup {
                AskOnComputerRow(null, "Workbench") {}
                AskOnComputerRow(AskBook.State.WAITING, "Workbench") {}
                AskOnComputerRow(AskBook.State.DECLINED, "Workbench") {}
                AskOnComputerRow(AskBook.State.TIMED_OUT, "Workbench") {}
                AskOnComputerRow(AskBook.State.UNDELIVERED, "Workbench") {}
                AskOnComputerRow(AskBook.State.BLOCKED, "Workbench") {}
            }
        }
    }
}
