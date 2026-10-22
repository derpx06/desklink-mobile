/*
 * SPDX-FileCopyrightText: 2018 Simon Redman <simon@ergotech.com>
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.contacts

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds
import android.util.Log
import androidx.core.content.ContextCompat
import org.desklink.mobile.Device
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.R
import org.desklink.mobile.helpers.RateLimiter
import org.desklink.mobile.plugins.Plugin
import org.desklink.mobile.plugins.PluginFactory.LoadablePlugin
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Contacts on the computer (contract F5). The phone owns the book; the desk keeps a cache.
 *
 * Off by default, per desk ("Share contacts with <desk>"), READ_CONTACTS asked when turned on.
 * The complete snapshot (`uid → last-updated`) goes out on every feature-ready, a few seconds after
 * the contacts change, and when the desk asks; the desk then asks for the vCards it lacks. A uid
 * missing from a snapshot is deleted there. Turned off (or no permission): `{uids: [], sharing:
 * false}`, and the desk deletes its copy. The uid is `Contacts._ID`, so a snapshot stays small.
 */
@LoadablePlugin
class ContactsPlugin : Plugin() {
    override val displayName: String
        get() = context.getString(R.string.pref_plugin_contacts)

    override val description: String
        get() = context.getString(R.string.pref_plugin_contacts_desc)

    override val isEnabledByDefault: Boolean = false

    override val requiredPermissions: Array<String> = arrayOf(Manifest.permission.READ_CONTACTS)

    override val permissionExplanation: Int = R.string.contacts_permission_explanation

    override val supportedPacketTypes: Array<String> = arrayOf(
        DeskLinkProtocol.PACKET_TYPE_CONTACTS_REQUEST_ALL_UIDS_TIMESTAMPS,
        DeskLinkProtocol.PACKET_TYPE_CONTACTS_REQUEST_VCARDS_BY_UIDS,
    )

    override val outgoingPacketTypes: Array<String> = arrayOf(
        DeskLinkProtocol.PACKET_TYPE_CONTACTS_RESPONSE_UIDS_TIMESTAMPS,
        DeskLinkProtocol.PACKET_TYPE_CONTACTS_RESPONSE_VCARDS,
    )

    /** Contacts are read and sent here, one job at a time, off the caller's thread. */
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private var pendingChange: ScheduledFuture<*>? = null
    /** The last snapshot sent: a change that leaves it the same sends nothing. */
    @Volatile private var lastSent: Map<String, Long>? = null

    private val observer = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) = onContactsChanged()
    }

    override fun onCreate(): Boolean {
        context.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
        // Turned on while connected: the desk gets the book now, not at the next connection.
        if (device.hasActiveWebRtcFeatureTransport) background { sendSnapshot(force = true) }
        return true
    }

    override fun onDestroy() {
        context.contentResolver.unregisterContentObserver(observer)
        worker.shutdownNow()
    }

    override fun onFeatureReady() {
        background { sendSnapshot(force = true) }
    }

    override fun onTurnedOff() {
        device.sendPacket(ContactsBook.notSharingPacket())
    }

    /** Each request reads the contacts database: a desk's Refresh and its fetches, never a flood. */
    private val limiter = RateLimiter(MAX_REQUESTS_PER_MINUTE, 60_000, TAG)

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        if (np.type in supportedPacketTypes && !limiter.allow(np.type)) return true
        when (np.type) {
            DeskLinkProtocol.PACKET_TYPE_CONTACTS_REQUEST_ALL_UIDS_TIMESTAMPS -> background { sendSnapshot(force = true) }
            DeskLinkProtocol.PACKET_TYPE_CONTACTS_REQUEST_VCARDS_BY_UIDS -> {
                val uids = np.getStringList("uids").orEmpty().filter(::validUid).distinct().take(MAX_UIDS_PER_REQUEST)
                background { sendVcards(uids) }
            }
            else -> return false
        }
        return true
    }

    /** After [onDestroy] the worker is gone and late work is dropped. */
    private fun background(job: () -> Unit) {
        runCatching { worker.execute(job) }
    }

    /** The contacts database changed: one snapshot once it has been quiet for a moment. */
    private fun onContactsChanged() = synchronized(this) {
        pendingChange?.cancel(false)
        pendingChange = runCatching { worker.schedule({ sendSnapshot(force = false) }, CHANGE_DEBOUNCE_SECONDS, TimeUnit.SECONDS) }.getOrNull()
    }

    private fun sendSnapshot(force: Boolean) {
        if (!force && !device.hasActiveWebRtcFeatureTransport) return // the next feature-ready sends it
        val stamps = runCatching { readStamps(context) }.getOrElse {
            Log.w(TAG, "Could not read contacts", it)
            return
        }
        if (!force && stamps == lastSent) return
        ContactsBook.snapshotPackets(stamps).forEach(device::sendPacket)
        lastSent = stamps
    }

    private fun sendVcards(uids: List<String>) {
        if (uids.isEmpty()) return
        val cards = runCatching { readCards(context, uids) }.getOrElse {
            Log.w(TAG, "Could not read contacts", it)
            return
        }
        ContactsBook.vcardPackets(cards.associate { it.uid to ContactsBook.vcard(it) }).forEach { device.sendPacket(it) }
    }

    companion object {
        private const val TAG = "DeskLink/Contacts"
        private const val CHANGE_DEBOUNCE_SECONDS = 5L
        /** K2 asks ≤ 200; 1,000 tolerates a desk that batches more, and bounds one request's reading. */
        const val MAX_UIDS_PER_REQUEST = 1000

        /** Per type per desk: a 3,000-contact first sync is 15 fetches of 200. */
        const val MAX_REQUESTS_PER_MINUTE = 60

        /** A contact's photo as stored is read only up to this; thumbnails are a few KiB. */
        const val MAX_PHOTO_SOURCE_BYTES = 1024 * 1024
        /** SQLite's bound-argument limit is 999. */
        private const val QUERY_CHUNK = 200

        private fun validUid(uid: String) = uid.length in 1..19 && uid.all(Char::isDigit)

        private fun granted(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

        /** This phone shares its contacts with [device] (K4: calls then carry `contactUid`). */
        fun sharesWith(context: Context, device: Device): Boolean =
            device.isPluginEnabled(Plugin.getPluginKey(ContactsPlugin::class.java)) && granted(context)

        /** K4: the uid of the contact with [number], or else exactly named [name]. */
        fun uidFor(context: Context, number: String?, name: String?): String? = runCatching {
            if (!granted(context)) return null
            val resolver = context.contentResolver
            val byNumber = number?.let {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(it))
                resolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            }
            val id = byNumber ?: name?.let {
                resolver.query(
                    ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts._ID),
                    "${ContactsContract.Contacts.DISPLAY_NAME} = ?", arrayOf(it), null,
                )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            }
            id?.toString()
        }.getOrNull()

        /** K1: every contact's uid and last-updated time. */
        private fun readStamps(context: Context): Map<String, Long> {
            val stamps = HashMap<String, Long>()
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) stamps[c.getLong(0).toString()] = c.getLong(1)
            }
            return stamps
        }

        /** K3: the cards for [uids] that still exist. */
        private fun readCards(context: Context, uids: List<String>): List<ContactCard> {
            val builders = LinkedHashMap<String, CardBuilder>()
            val projection = arrayOf(
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DISPLAY_NAME,
                ContactsContract.Data.CONTACT_LAST_UPDATED_TIMESTAMP,
                ContactsContract.Data.DATA1, ContactsContract.Data.DATA2, ContactsContract.Data.DATA3,
                ContactsContract.Data.DATA4, ContactsContract.Data.DATA5, ContactsContract.Data.DATA6,
            )
            for (chunk in uids.chunked(QUERY_CHUNK)) {
                context.contentResolver.query(
                    ContactsContract.Data.CONTENT_URI, projection,
                    "${ContactsContract.Data.CONTACT_ID} IN (${chunk.joinToString(",") { "?" }})", chunk.toTypedArray(), null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val uid = c.getLong(0).toString()
                        val b = builders.getOrPut(uid) { CardBuilder(uid, c.getString(2), c.getLong(3)) }
                        fun s(i: Int) = c.getString(i)?.trim().orEmpty()
                        when (c.getString(1)) {
                            CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE ->
                                // DATA3 family, DATA2 given, DATA5 middle, DATA4 prefix, DATA6 suffix.
                                b.name = listOf(s(6), s(5), s(8), s(7), s(9))
                            CommonDataKinds.Phone.CONTENT_ITEM_TYPE ->
                                s(4).takeIf(String::isNotEmpty)?.let { b.phones += ContactsBook.phoneType(c.getInt(5)) to it }
                            CommonDataKinds.Email.CONTENT_ITEM_TYPE -> s(4).takeIf(String::isNotEmpty)?.let { b.emails += it }
                            // DATA1 company, DATA5 department.
                            CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> b.org = listOf(s(4), s(8)).filter(String::isNotEmpty)
                            CommonDataKinds.Note.CONTENT_ITEM_TYPE -> b.note = s(4).take(ContactsBook.MAX_NOTE_CHARS)
                        }
                    }
                }
            }
            return builders.values.map { b ->
                ContactCard(
                    uid = b.uid, displayName = b.displayName, name = b.name,
                    phones = b.phones.distinctBy { it.second.filter(Char::isDigit) }.take(20),
                    emails = b.emails.distinct().take(20), org = b.org, note = b.note,
                    photo = photo(context, b.uid.toLong()), updatedAt = b.updatedAt,
                )
            }
        }

        private class CardBuilder(val uid: String, val displayName: String?, val updatedAt: Long) {
            var name: List<String> = emptyList()
            val phones = mutableListOf<Pair<String, String>>()
            val emails = mutableListOf<String>()
            var org: List<String> = emptyList()
            var note: String? = null
        }

        /** The contact's thumbnail as a JPEG at most 96 px on its long side, or null. */
        private fun photo(context: Context, id: Long): ByteArray? = runCatching {
            val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id)
            val bytes = ContactsContract.Contacts.openContactPhotoInputStream(context.contentResolver, uri, false)?.use { input ->
                val buffer = ByteArray(MAX_PHOTO_SOURCE_BYTES + 1)
                var read = 0
                while (read < buffer.size) {
                    val n = input.read(buffer, read, buffer.size - read)
                    if (n < 0) break
                    read += n
                }
                if (read > MAX_PHOTO_SOURCE_BYTES) return null
                buffer.copyOf(read)
            } ?: return null
            // Decoded at a power-of-two reduction already near the target: bounded bitmap memory.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val options = BitmapFactory.Options().apply { inSampleSize = ContactsBook.sampleSize(bounds.outWidth, bounds.outHeight) }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            val (w, h) = ContactsBook.photoSize(bitmap.width, bitmap.height)
            val scaled = if (w == bitmap.width && h == bitmap.height) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, true)
            ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        }.getOrNull()
    }
}
