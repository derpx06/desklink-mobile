/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui

import org.desklink.mobile.R
import org.desklink.mobile.ui.compose.model.device.DeviceConnection
import org.desklink.mobile.ui.compose.screen.guide.Guide
import org.desklink.mobile.ui.compose.screen.home.Blocker
import org.desklink.mobile.ui.compose.screen.home.ComputerRow
import org.desklink.mobile.ui.compose.screen.home.Everything
import org.desklink.mobile.ui.compose.screen.home.FeatureGroup
import org.desklink.mobile.ui.compose.screen.home.FeatureStatus
import org.desklink.mobile.ui.compose.screen.home.FeatureStep
import org.desklink.mobile.ui.compose.screen.home.GlideFacts
import org.desklink.mobile.ui.compose.screen.home.HomeOffers
import org.desklink.mobile.ui.compose.screen.home.HomeState
import org.desklink.mobile.ui.compose.screen.home.PaletteModel
import org.desklink.mobile.ui.compose.screen.home.PhoneFacts
import org.desklink.mobile.ui.compose.screen.home.RowAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's "Everything DeskLink does": every feature is listed, and every row has one action in every state. */
class EverythingTest {
    private fun state(
        connection: DeviceConnection = DeviceConnection.LOCAL,
        offers: HomeOffers = HomeOffers(),
        phone: PhoneFacts = PhoneFacts(glide = GlideFacts(on = true, service = true)),
    ) = HomeState("d", "arch-desk", connection, listOf(ComputerRow("d", "arch-desk", connection)), offers = offers, phone = phone)

    private fun f(id: String) = Everything.features.first { it.id == id }

    @Test
    fun everyGuideCardIsInTheList() {
        // The top-bar card is done at the computer (nothing to press here); "Can't connect?" is help, not a feature.
        val exempt = setOf("top_bar", Guide.TROUBLE)
        val listed = Everything.features.flatMap { it.guide }.toSet()
        assertEquals(emptyList<String>(), Guide.cards.map { it.id }.filter { it !in exempt && it !in listed })
    }

    @Test
    fun everyPaletteActionIsInTheList() {
        val listed = Everything.features.flatMap { it.palette }.toSet()
        assertEquals(emptyList<String>(), PaletteModel.offeredActions(state()).filter { it !in listed })
    }

    @Test
    fun idsAreUniqueAndEveryGroupHasRows() {
        assertEquals(Everything.features.size, Everything.features.map { it.id }.toSet().size)
        FeatureGroup.entries.forEach { g -> assertTrue("$g is empty", Everything.features.any { it.group == g }) }
    }

    /** Every status a row can be in: ready, each kind of step, each reason it cannot be done. */
    private val everyStatus: List<FeatureStatus> = listOf(FeatureStatus.Ready) +
        listOf(FeatureStep.TurnOn, FeatureStep.PluginPermission, FeatureStep.GlideAgain, FeatureStep.Glide(true), FeatureStep.Glide(false),
            FeatureStep.Permission("notification_access"), FeatureStep.Permission("notifications"), FeatureStep.Permission("calls"),
            FeatureStep.Permission("contacts"), FeatureStep.Permission("accessibility"), FeatureStep.Permission("other"),
        ).map(FeatureStatus::Step) +
        Blocker.entries.map { FeatureStatus.Off(it) } + FeatureStatus.Off(Blocker.DESK_OFF, R.string.everything_glide_cant)

    @Test
    fun everyRowHasAnActionWordsAndALineForEveryStatus() {
        for (feature in Everything.features) for (status in everyStatus) {
            val action = Everything.action(status)
            val expected = when (status) {
                FeatureStatus.Ready -> RowAction.OPEN
                is FeatureStatus.Step -> RowAction.STEP
                is FeatureStatus.Off -> if (status.why == null && status.blocker in setOf(Blocker.CONNECTING, Blocker.CANT_REACH, Blocker.OFFLINE)) RowAction.RETRY else RowAction.EXPLAIN
            }
            assertEquals("${feature.id} $status", expected, action)
            assertNotEquals("${feature.id} $status has no button", 0, Everything.label(feature, status))
            assertNotEquals("${feature.id} $status has no line", 0, Everything.line(feature, status))
            assertNotEquals(0, Everything.statusWord(status))
        }
    }

    @Test
    fun aRowSharesItsHomeTilesState() {
        val off = state(offers = HomeOffers(files = false, why = mapOf(PaletteModel.Action.SEND_FILES to Blocker.DESK_OFF)))
        assertEquals(FeatureStatus.Off(Blocker.DESK_OFF), Everything.status(f("send-files"), off))
        assertEquals(FeatureStatus.Ready, Everything.status(f("send-files"), state()))
        assertEquals(FeatureStatus.Off(Blocker.OFFLINE), Everything.status(f("send-files"), state(DeviceConnection.OFFLINE)))
        val clipboardOff = state(offers = HomeOffers(clipboard = false, why = mapOf(PaletteModel.Action.SEND_CLIPBOARD to Blocker.PHONE_OFF)))
        assertEquals(FeatureStatus.Step(FeatureStep.TurnOn), Everything.status(f("clipboard"), clipboardOff))
    }

    @Test
    fun thisPhonesFeaturesSayTheirOneStep() {
        val n = f(Everything.NOTIFICATIONS)
        assertEquals(FeatureStatus.Step(FeatureStep.TurnOn), Everything.status(n, state(offers = HomeOffers(why = mapOf(n.id to Blocker.PHONE_OFF)))))
        assertEquals(FeatureStatus.Step(FeatureStep.Permission("notification_access")), Everything.status(n, state(offers = HomeOffers(why = mapOf(n.id to Blocker.PERMISSION)))))
        assertEquals(FeatureStatus.Step(FeatureStep.Permission("notification_access")), Everything.status(n, state(phone = PhoneFacts(missing = setOf("notification_access")))))
        assertEquals(FeatureStatus.Ready, Everything.status(n, state()))
        // A settings page opens whatever the link: "off at the computer" is only said while the computer can be asked.
        assertEquals(FeatureStatus.Ready, Everything.status(n, state(DeviceConnection.OFFLINE, HomeOffers(why = mapOf(n.id to Blocker.DESK_OFF)))))
        assertEquals(FeatureStatus.Off(Blocker.DESK_OFF), Everything.status(n, state(offers = HomeOffers(why = mapOf(n.id to Blocker.DESK_OFF)))))
        assertEquals(FeatureStatus.Step(FeatureStep.Permission("calls")), Everything.status(f(Everything.CALLS), state(phone = PhoneFacts(missing = setOf("calls")))))
        // Pages of this phone work offline.
        assertEquals(FeatureStatus.Ready, Everything.status(f("terminals"), state(DeviceConnection.OFFLINE)))
        assertEquals(FeatureStatus.Ready, Everything.status(f("bucket"), state(DeviceConnection.OFFLINE)))
    }

    @Test
    fun glideHasARowForEveryState() {
        val g = f(Everything.GLIDE)
        fun glide(facts: GlideFacts, connection: DeviceConnection = DeviceConnection.LOCAL) = Everything.status(g, state(connection, phone = PhoneFacts(glide = facts)))
        assertEquals(FeatureStatus.Off(Blocker.OFFLINE), glide(GlideFacts(on = true, service = true), DeviceConnection.OFFLINE))
        assertEquals(FeatureStatus.Off(Blocker.DESK_OFF, R.string.everything_glide_cant), glide(GlideFacts(deskCan = false)))
        assertEquals(FeatureStatus.Step(FeatureStep.Glide(asking = false)), glide(GlideFacts()))
        assertEquals(FeatureStatus.Step(FeatureStep.Glide(asking = true)), glide(GlideFacts(asking = true)))
        assertEquals(FeatureStatus.Step(FeatureStep.Permission("accessibility")), glide(GlideFacts(on = true)))
        assertEquals(FeatureStatus.Step(FeatureStep.GlideAgain), glide(GlideFacts(on = true, service = true, refusedLocked = true)))
        assertEquals(FeatureStatus.Ready, glide(GlideFacts(on = true, service = true)))
        // The words: asking says so and offers Allow; off offers Turn on; a refusal offers Try again; the computer that can't says why.
        assertEquals(R.string.settings_allow, Everything.label(g, glide(GlideFacts(asking = true))))
        assertEquals(R.string.everything_glide_asking, Everything.line(g, glide(GlideFacts(asking = true))))
        assertEquals(R.string.home_turn_on, Everything.label(g, glide(GlideFacts())))
        assertEquals(R.string.terminal_retry, Everything.label(g, glide(GlideFacts(on = true, service = true, refusedLocked = true))))
        assertEquals(R.string.everything_glide_cant, Everything.line(g, glide(GlideFacts(deskCan = false))))
        assertEquals(RowAction.EXPLAIN, Everything.action(glide(GlideFacts(deskCan = false))))
    }

    @Test
    fun aStepOpensThePageAfterwardsButNeverSendsSomething() {
        assertTrue(Everything.navigates("settings:control"))
        assertTrue(Everything.navigates(PaletteModel.Action.OPEN_TERMINALS))
        assertEquals(false, Everything.navigates(PaletteModel.Action.RING))
        assertEquals(false, Everything.navigates(PaletteModel.Action.SEND_FILES))
        val glide = f(Everything.GLIDE)
        assertTrue(Everything.opensAfter(glide, FeatureStep.Glide(asking = true)))
        assertEquals(false, Everything.opensAfter(glide, FeatureStep.GlideAgain))
        assertEquals(false, Everything.opensAfter(f("ring"), FeatureStep.TurnOn))
    }
}
