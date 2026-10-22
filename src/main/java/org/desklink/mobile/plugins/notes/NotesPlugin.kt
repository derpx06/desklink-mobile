/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.notes

import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.R
import org.desklink.mobile.helpers.RateLimiter
import org.desklink.mobile.helpers.ThreadHelper
import org.desklink.mobile.plugins.Plugin
import org.desklink.mobile.plugins.PluginFactory.LoadablePlugin
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.desklink.mobile.webrtc.SyncDigest

/**
 * Snippets and notes with this desk (F6). Neither side owns them: both send an index on
 * feature-ready and fetch what they lack; [NotesStore] keeps them and pushes local edits. Turning
 * this off or unpairing never deletes them (they are the person's).
 */
@LoadablePlugin
class NotesPlugin : Plugin() {
    override val displayName: String
        get() = context.getString(R.string.notes_title)

    override val description: String
        get() = context.getString(R.string.pref_plugin_notes_desc)

    override val supportedPacketTypes: Array<String> = arrayOf(DeskLinkProtocol.PACKET_TYPE_SNIPPETS, DeskLinkProtocol.PACKET_TYPE_NOTES)

    override val outgoingPacketTypes: Array<String> = supportedPacketTypes

    override fun onFeatureReady() {
        ThreadHelper.execute {
            // Per index: on a new link the desk may already hold exactly this one.
            NotesStore.indexes(context).groupBy { it.type }.forEach { (type, packets) ->
                val feature = if (type == DeskLinkProtocol.PACKET_TYPE_NOTES) SyncDigest.NOTES else SyncDigest.SNIPPETS
                device.sendSnapshot(feature, SyncDigest.rev(packets.map { it.serialize() }), packets)
            }
        }
    }

    /** A desk's index, fetches and pushes: a busy sync is a few packets a second, a flood is dropped. */
    private val limiter = RateLimiter(MAX_PACKETS_PER_MINUTE, 60_000, "DeskLink/Notes")

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        if (ShelfKind.of(np.type) == null) return false
        if (!limiter.allow(np.type)) return true
        ThreadHelper.execute { NotesStore.onPacket(context, np).forEach(device::sendPacket) }
        return true
    }

    companion object {
        /** Per type per desk: a 4,000-note first sync is ~40 fetch replies plus pushes, well under this. */
        const val MAX_PACKETS_PER_MINUTE = 240
    }
}
