/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.notes

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.NetworkPacket
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * This phone's snippets and notes (F6): the person's own, kept whatever happens to a pairing, and
 * sealed at rest with an Android Keystore AES-GCM key (the SessionGate pattern, its own alias, no
 * user check: they open without a fingerprint).
 *
 * Local edits are pushed to every desk whose link is ready: snippets at once, notes after
 * [NOTE_PUSH_DELAY_MS] without typing.
 */
object NotesStore {
    private const val TAG = "DeskLink/Notes"
    private const val KEY_ALIAS = "desklink_notes"
    private const val FILE = "notes/shelf.sealed"
    private const val NOTE_PUSH_DELAY_MS = 2_000L
    private const val OPEN_ATTEMPTS = 3
    private const val OPEN_RETRY_MS = 300L

    /** A Keystore or I/O hiccup rather than a key that is gone or a file that is damaged. */
    internal fun isTransient(error: Throwable): Boolean = generateSequence(error) { it.cause }.any {
        it is java.security.KeyStoreException || it is java.security.ProviderException ||
            (it is java.io.IOException && it !is java.io.FileNotFoundException)
    }
    private val AAD = "desklink-snippets-and-notes".toByteArray()

    private var shelf: Shelf? = null
    private lateinit var file: AtomicFile
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val pendingNotes = LinkedHashSet<String>()
    private var notePush: ScheduledFuture<*>? = null

    private val _revision = MutableStateFlow(0)

    /** Bumped on every change, for screens to redraw. */
    val revision: StateFlow<Int> = _revision

    /** Runs [job] on the shelf, loading it first; saves when it says it changed it. */
    fun <T> with(context: Context, job: (Shelf) -> Pair<T, Boolean>): T = synchronized(this) {
        val current = shelf ?: load(context.applicationContext).also { shelf = it }
        val (result, changed) = job(current)
        if (changed) {
            saveSoon()
            _revision.value++
        }
        result
    }

    private var saveQueued = false

    /** Sealed and written on the worker (never the main thread), once for a burst of changes. */
    private fun saveSoon() {
        if (saveQueued) return
        saveQueued = true
        worker.execute {
            val plain = synchronized(this) {
                saveQueued = false
                shelf?.toStored()?.toString()
            } ?: return@execute
            write(plain)
        }
    }

    /** Opens the file off the main thread, before any screen needs it. */
    fun preload(context: Context) {
        worker.execute { with(context) { Unit to false } }
    }

    private fun load(context: Context): Shelf {
        file = AtomicFile(File(context.filesDir, FILE).apply { parentFile?.mkdirs() })
        val shelf = runCatching {
            if (!file.baseFile.exists()) Shelf() else Shelf.fromStored(JSONObject(String(openWithRetry(file.readFully()), Charsets.UTF_8)))
        }.getOrElse {
            // Unreadable (key lost with a backup restore, a newer schema, damage): kept aside, never
            // overwritten, and this starts empty; a desk still holding the notes sends them back.
            Log.w(TAG, "Could not open the snippets and notes (${it.javaClass.simpleName}); starting empty")
            runCatching { file.baseFile.renameTo(File(file.baseFile.path + ".unreadable")) }
            Shelf()
        }
        if (shelf.prune(System.currentTimeMillis())) write(shelf.toStored().toString())
        return shelf
    }

    /**
     * The Keystore can fail for a moment (right after a boot, while the device is still settling) without the key being
     * lost: that is tried again before the file is judged unreadable and set aside.
     */
    private fun openWithRetry(sealed: ByteArray): ByteArray {
        var last: Throwable? = null
        repeat(OPEN_ATTEMPTS) { attempt ->
            try {
                return open(sealed)
            } catch (e: Exception) {
                last = e
                if (!isTransient(e)) throw e
                if (attempt < OPEN_ATTEMPTS - 1) Thread.sleep(OPEN_RETRY_MS)
            }
        }
        throw last!!
    }

    private fun write(plain: String) {
        runCatching {
            val sealed = seal(plain.toByteArray(Charsets.UTF_8))
            val out = file.startWrite()
            try {
                out.write(sealed)
                file.finishWrite(out)
            } catch (e: Exception) {
                file.failWrite(out)
                throw e
            }
        }.onFailure { Log.e(TAG, "Could not save the snippets and notes", it) }
    }

    // ---- sync --------------------------------------------------------------------------------------------

    /** A peer's packet: the answers to send back. */
    fun onPacket(context: Context, packet: NetworkPacket): List<NetworkPacket> {
        val kind = ShelfKind.of(packet.type) ?: return emptyList()
        return with(context) { it.handle(kind, packet) }
    }

    /** Both indexes, for a desk whose link just became ready. */
    fun indexes(context: Context): List<NetworkPacket> =
        with(context) { it.indexPackets(ShelfKind.SNIPPETS) + it.indexPackets(ShelfKind.NOTES) to false }

    private fun push(context: Context, kind: ShelfKind, ids: Collection<String>) {
        val packets = with(context) { Shelf.itemsPackets(kind, it.itemValues(kind, ids)) to false }
        if (packets.isEmpty()) return
        DeskLinkApplication.getInstance().devices.values
            .filter { it.isPaired && it.hasActiveWebRtcFeatureTransport && it.getPlugin(NotesPlugin::class.java) != null }
            .forEach { device -> packets.forEach(device::sendPacket) }
    }

    private fun pushNoteSoon(context: Context, id: String) = synchronized(pendingNotes) {
        pendingNotes += id
        notePush?.cancel(false)
        notePush = worker.schedule({
            val ids = synchronized(pendingNotes) { pendingNotes.toList().also { pendingNotes.clear() } }
            push(context.applicationContext, ShelfKind.NOTES, ids)
        }, NOTE_PUSH_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    // ---- local edits -----------------------------------------------------------------------------------------

    /** Live snippets, newest first, at most [Shelf.MAX_SNIPPETS]. */
    fun snippets(context: Context): List<Snippet> = with(context) { s ->
        s.snippets.values.filter { !it.deleted }.sortedByDescending { it.modified }.take(Shelf.MAX_SNIPPETS) to false
    }

    sealed interface PinResult {
        data object Pinned : PinResult
        data object Empty : PinResult
        data object TooLong : PinResult
    }

    /** Pins [text] as a snippet; an identical one moves to the top. */
    fun pin(context: Context, text: String): PinResult {
        val clean = text.trimEnd('\n')
        if (clean.isBlank()) return PinResult.Empty
        if (clean.toByteArray().size > Shelf.MAX_SNIPPET_BYTES) return PinResult.TooLong
        val id = with(context) { s ->
            val id = s.snippets.values.firstOrNull { !it.deleted && it.text == clean }?.id ?: UUID.randomUUID().toString()
            s.snippets[id] = Snippet(id, clean, modified = later(s.snippets[id]?.modified))
            id to true
        }
        worker.execute { push(context.applicationContext, ShelfKind.SNIPPETS, listOf(id)) }
        return PinResult.Pinned
    }

    fun deleteSnippet(context: Context, id: String) {
        val done = with(context) { s ->
            val old = s.snippets[id]?.takeIf { !it.deleted } ?: return@with false to false
            s.snippets[id] = old.copy(text = "", title = "", deleted = true, modified = later(old.modified))
            true to true
        }
        if (done) worker.execute { push(context.applicationContext, ShelfKind.SNIPPETS, listOf(id)) }
    }

    /** Live notes: pinned first, then newest (N3). */
    fun notes(context: Context): List<Note> = with(context) { s ->
        s.notes.values.filter { !it.deleted }.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.modified }) to false
    }

    fun note(context: Context, id: String): Note? = with(context) { s -> s.notes[id]?.takeIf { !it.deleted } to false }

    /** A new note (`parent` 0); its id, or null at [Shelf.MAX_NOTES]. */
    fun newNote(context: Context, title: String = "", body: String = ""): String? {
        val id = with(context) { s ->
            if (s.notes.values.count { !it.deleted } >= Shelf.MAX_NOTES) return@with null to false
            val now = System.currentTimeMillis()
            val id = UUID.randomUUID().toString()
            s.notes[id] = Note(id, title.trim().take(Shelf.MAX_NOTE_TITLE_CHARS), clampBody(body), created = now, modified = now)
            id to true
        } ?: return null
        pushNoteSoon(context, id)
        return id
    }

    /** Saves an edit (title, body, pinned or deleted): `parent` = the last agreed version, `modified` = now. */
    fun editNote(context: Context, id: String, change: (Note) -> Note) {
        val changed = with(context) { s ->
            val before = s.notes[id] ?: return@with false to false
            var after = change(before).let { it.copy(id = before.id, title = it.title.takeCodePoints(Shelf.MAX_NOTE_TITLE_CHARS)) }
            if (!Shelf.syncable(after.body) || after.sameContent(before)) return@with false to false
            if (after.deleted) after = after.copy(title = "", body = "")
            s.notes[id] = after.copy(parent = s.synced[id] ?: 0, modified = later(before.modified))
            true to true
        }
        if (changed) pushNoteSoon(context, id)
    }

    /** Now, but strictly after [previous] even within one millisecond. */
    private fun later(previous: Long?) = maxOf(System.currentTimeMillis(), (previous ?: 0) + 1)

    /** Cut at [Shelf.MAX_SYNCABLE_NOTE_BYTES] (a share can be longer), on a character boundary. */
    fun clampBody(body: String): String {
        if (body.toByteArray().size <= Shelf.MAX_SYNCABLE_NOTE_BYTES) return body
        var end = minOf(body.length, Shelf.MAX_SYNCABLE_NOTE_BYTES)
        while (end > 0 && body.substring(0, end).toByteArray().size > Shelf.MAX_SYNCABLE_NOTE_BYTES) end -= 1024.coerceAtMost(end)
        if (end > 0 && Character.isHighSurrogate(body[end - 1])) end--
        return body.substring(0, end)
    }

    // ---- sealing ----------------------------------------------------------------------------------------------

    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** `iv (12) | ciphertext+tag`. */
    private fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        cipher.updateAAD(AAD)
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > 12 + 16) { "too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12)) }
        cipher.updateAAD(AAD)
        return cipher.doFinal(sealed, 12, sealed.size - 12)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }
}
