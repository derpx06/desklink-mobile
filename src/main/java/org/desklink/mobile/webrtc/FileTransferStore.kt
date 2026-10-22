/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import org.json.JSONObject
import java.io.File
import java.io.IOException

/** What survives a link drop or a crash for one transfer (either direction). */
data class FileCheckpoint(
    val transferId: String,
    /** "send" or "receive". */
    val direction: String,
    val token: String,
    val deviceId: String,
    val filename: String,
    val totalSize: Long,
    val mtime: Long,
    /** Blocks verified (receive) or acknowledged (send) and made durable. */
    val blocksDone: Int,
    /** A send: where the file is read from (a `file:`/`content:` URI, or the private spool copy of a stream). */
    val sourceUri: String? = null,
    /** A send that spooled a stream-only payload: the spool file is deleted with the transfer. */
    val spooled: Boolean = false,
    /** Offer extras: clipboard, mimeType, captureId, folder, relpath, contentSha256. */
    val meta: Map<String, String> = emptyMap(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("v", 2).put("id", transferId).put("dir", direction).put("token", token).put("device", deviceId)
        .put("name", filename).put("size", totalSize).put("mtime", mtime).put("blocks", blocksDone)
        .put("spooled", spooled)
        .also { json ->
            sourceUri?.let { json.put("source", it) }
            json.put("meta", JSONObject(meta))
        }

    companion object {
        fun fromJson(json: JSONObject): FileCheckpoint? = runCatching {
            if (json.getInt("v") != 2) return null
            val metaJson = json.optJSONObject("meta") ?: JSONObject()
            FileCheckpoint(
                transferId = json.getString("id"),
                direction = json.getString("dir").also { require(it == "send" || it == "receive") },
                token = json.getString("token"),
                deviceId = json.getString("device"),
                filename = json.getString("name"),
                totalSize = json.getLong("size"),
                mtime = json.getLong("mtime"),
                blocksDone = json.getInt("blocks"),
                sourceUri = json.optString("source").takeIf { json.has("source") && it.isNotEmpty() },
                spooled = json.optBoolean("spooled", false),
                meta = metaJson.keys().asSequence().associateWith { metaJson.getString(it) },
            ).takeIf { it.totalSize in 0..FileV2.MAX_TRANSFER_BYTES && it.blocksDone in 0..FileV2.blockCount(it.totalSize) }
        }.getOrNull()
    }
}

/**
 * The transfer directory: for each transfer id (always a validated UUID, so nothing a peer says
 * ever picks a path) a checkpoint (`.json`), the partial file (`.part`), the block-hash sidecar
 * (`.blocks`, 32 bytes per block) and, for a spooled stream, the source copy (`.spool`).
 */
class FileTransferStore(val root: File) {
    init {
        check(root.mkdirs() || root.isDirectory) { "Could not create DeskLink transfer directory" }
    }

    /**
     * Transfers v1 left `.send` copies and partial files named by their old ids: nothing reads them
     * any more (v2 shares no state with v1), so anything that is not a v2 file is deleted.
     */
    fun purgeLegacy(): Int {
        var removed = 0
        root.listFiles().orEmpty().forEach { file ->
            if (file.isFile && !V2_NAME.matches(file.name) && runCatching { file.delete() }.getOrDefault(false)) removed++
        }
        return removed
    }

    private fun safe(id: String): String {
        validateTransferId(id)
        return id
    }

    fun partial(id: String) = File(root, ".desklink-${safe(id)}.part")
    fun spool(id: String) = File(root, ".desklink-${safe(id)}.spool")
    private fun blocksFile(id: String) = File(root, ".desklink-${safe(id)}.blocks")
    private fun checkpointFile(id: String) = File(root, ".desklink-${safe(id)}.json")

    fun exists(id: String) = checkpointFile(id).exists()

    /** Atomic and durable (the file system journals the rename); false if it could not be written. */
    fun save(checkpoint: FileCheckpoint): Boolean =
        atomicWrite(checkpointFile(checkpoint.transferId)) { it.write(checkpoint.toJson().toString().toByteArray(Charsets.UTF_8)) }

    /** Null when absent or unreadable (an unreadable one is treated as no checkpoint, never trusted). */
    fun load(id: String): FileCheckpoint? = runCatching {
        val file = checkpointFile(id)
        if (!file.isFile || file.length() > MAX_CHECKPOINT_BYTES) return null
        FileCheckpoint.fromJson(JSONObject(String(file.readBytes(), Charsets.UTF_8)))
            ?.takeIf { it.transferId == id }
    }.getOrNull()

    fun list(): List<FileCheckpoint> = root.listFiles { f -> f.name.startsWith(".desklink-") && f.name.endsWith(".json") }
        .orEmpty()
        .mapNotNull { runCatching { load(it.name.removePrefix(".desklink-").removeSuffix(".json")) }.getOrNull() }
        .sortedBy { it.transferId }

    /** Writes the first [count] hashes; atomic, so a crash leaves the old or the new list, never half. */
    fun writeBlocks(id: String, hashes: List<ByteArray>, count: Int = hashes.size): Boolean =
        atomicWrite(blocksFile(id)) { out -> for (i in 0 until count) out.write(hashes[i]) }

    fun readBlocks(id: String): List<ByteArray> = runCatching {
        val file = blocksFile(id)
        if (!file.isFile || file.length() > FileV2.MAX_TRANSFER_BYTES / FileV2.BLOCK_BYTES * 32) return emptyList()
        val bytes = file.readBytes()
        List(bytes.size / 32) { bytes.copyOfRange(it * 32, it * 32 + 32) }
    }.getOrDefault(emptyList())

    /** Deletes everything kept for [id]. */
    fun remove(id: String) {
        runCatching {
            checkpointFile(id).delete()
            File(checkpointFile(id).path + ".tmp").delete()
            partial(id).delete()
            blocksFile(id).delete()
            File(blocksFile(id).path + ".tmp").delete()
            spool(id).delete()
        }
    }

    /** Write to a temporary file, fsync, rename over [target]: a crash leaves the old content or the new, never half. */
    private fun atomicWrite(target: File, write: (java.io.OutputStream) -> Unit): Boolean = try {
        val tmp = File(target.path + ".tmp")
        java.io.FileOutputStream(tmp).use { out ->
            write(out)
            out.flush()
            out.fd.sync()
        }
        java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        true
    } catch (error: IOException) {
        false
    } catch (error: IllegalArgumentException) {
        false
    }

    /** Deletes the checkpoint and the hash list but keeps the partial file (it was delivered or moved). */
    fun removeMetadata(id: String) {
        runCatching {
            checkpointFile(id).delete()
            File(checkpointFile(id).path + ".tmp").delete()
            blocksFile(id).delete()
            File(blocksFile(id).path + ".tmp").delete()
        }
    }

    companion object {
        const val MAX_CHECKPOINT_BYTES = 16L * 1024
        private val V2_NAME = Regex("""\.desklink-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(json|part|blocks|spool)(\.tmp)?""")
    }
}
