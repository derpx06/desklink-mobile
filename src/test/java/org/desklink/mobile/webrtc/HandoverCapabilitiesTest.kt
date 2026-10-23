/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.desklink.mobile.helpers.DeviceHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the phone's handover advertises, built from the real plugin list, against what the desk sends and accepts.
 * A type the desk may send is delivered only if the phone lists it as incoming; a type the phone sends is accepted only
 * if it lists it as outgoing. A profile type handled outside any plugin (the delta-resync `have`) was missing from both
 * lists once, so the desk never sent its `have`.
 */
@RunWith(RobolectricTestRunner::class)
class HandoverCapabilitiesTest {
    /** The desk's DESKTOP_OUTGOING and DESKTOP_INCOMING, each intersected with the shared profile (registry.rs). */
    private val deskSends = setOf(
        "desklink.clipboard",
        "desklink.clipboard.connect",
        "desklink.contacts.request_all_uids_timestamps",
        "desklink.contacts.request_vcards_by_uid",
        "desklink.findmyphone.request",
        "desklink.folders",
        "desklink.handoff",
        "desklink.lock",
        "desklink.lock.request",
        "desklink.mousepad.request",
        "desklink.mpris",
        "desklink.mpris.request",
        "desklink.notes",
        "desklink.notification",
        "desklink.notification.action",
        "desklink.notification.reply",
        "desklink.notification.request",
        "desklink.ping",
        "desklink.settings",
        "desklink.share.request",
        "desklink.shareinputdevices.request",
        "desklink.snippets",
        "desklink.sync.have",
        "desklink.telephony.request",
        "desklink.unlock.request",
    )
    private val deskAccepts = setOf(
        "desklink.battery",
        "desklink.clipboard",
        "desklink.clipboard.connect",
        "desklink.contacts.response_uids_timestamps",
        "desklink.contacts.response_vcards",
        "desklink.findmyphone.request",
        "desklink.folders",
        "desklink.lock.request",
        "desklink.mousepad.keyboardstate",
        "desklink.mousepad.request",
        "desklink.mpris",
        "desklink.mpris.request",
        "desklink.notes",
        "desklink.notification",
        "desklink.notification.request",
        "desklink.ping",
        "desklink.settings.request",
        "desklink.share.request",
        "desklink.shareinputdevices",
        "desklink.snippets",
        "desklink.sync.have",
        "desklink.telephony",
        "desklink.unlock.request",
    )

    private fun handover(): Pair<Set<String>, Set<String>> {
        val context = ApplicationProvider.getApplicationContext<Application>()
        // The real plugin list, as the application loads it at start.
        org.desklink.mobile.plugins.PluginFactory.initPluginInfo(context)
        val info = DeviceHelper.getDeviceInfo(context)
        return WebRtcFeatureProfile.capabilities(info.incomingCapabilities).toSet() to WebRtcFeatureProfile.capabilities(info.outgoingCapabilities).toSet()
    }

    @Test
    fun everyTypeTheDeskSendsIsListedAsIncomingByThePhone() {
        val (incoming, _) = handover()
        assertEquals("the desk would not send these to the phone", emptySet<String>(), deskSends - incoming)
    }

    @Test
    fun everyTypeTheDeskAcceptsIsListedAsOutgoingByThePhone() {
        val (_, outgoing) = handover()
        assertEquals("the desk would not accept these from the phone", emptySet<String>(), deskAccepts - outgoing)
    }

    @Test
    fun noProfileTypeIsInNeitherListOfTheHandover() {
        val (incoming, outgoing) = handover()
        val profile = WebRtcFeatureProfile.all
        assertEquals("advertised by no plugin and no handover rule", emptySet<String>(), profile - incoming - outgoing)
        assertTrue(profile.size >= 32)
        assertTrue("plugins loaded: " + org.desklink.mobile.plugins.PluginFactory.availablePlugins.size, org.desklink.mobile.plugins.PluginFactory.availablePlugins.size > 20)
    }
}
