/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.desklink.mobile.Device
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.desklink.mobile.plugins.sftp.SftpPlugin
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class PhoneFileRoot(val name: String, val uri: Uri)

/**
 * Root-scoped file operations for the authenticated WebRTC file-control
 * channel. Peers receive random entry IDs; Android URIs and filesystem paths
 * never cross the wire.
 */
class WebRtcPhoneFileBrowser(
    private val context: Context,
    private val rootsProvider: () -> List<PhoneFileRoot>,
) {
    constructor(context: Context, device: Device) : this(context, {
        val plugin = device.getPluginIncludingWithoutPermissions(SftpPlugin::class.java.simpleName)
            as? SftpPlugin
        plugin?.authorizedStorageRoots().orEmpty().map { PhoneFileRoot(it.displayName, it.uri) }
    })

    private data class Entry(
        val rootId: String,
        val parentId: String?,
        val document: DocumentFile,
    )

    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    fun handle(payload: ByteArray): ByteArray {
        val request = JSONObject(String(payload, Charsets.UTF_8))
        val requestId = request.getString("requestId")
        require(request.getInt("browserVersion") == VERSION) { "Unsupported phone-file protocol version" }
        require(requestId.length in 1..128) { "Invalid phone-file request ID" }
        return runCatching {
            val result = when (request.getString("action")) {
                "roots" -> roots()
                "list" -> list(requiredEntryId(request))
                "metadata" -> metadata(requiredEntryId(request))
                "create-folder" -> createFolder(requiredEntryId(request), requiredName(request))
                "rename" -> rename(requiredEntryId(request), requiredName(request))
                "move" -> move(requiredEntryId(request), request.getString("destinationId"))
                "delete" -> delete(requiredEntryId(request))
                "download" -> downloadInfo(
                    requiredEntryId(request),
                    request.getString("transferId"),
                )
                else -> error("Unsupported phone-file operation")
            }
            JSONObject()
                .put("browserVersion", VERSION)
                .put("requestId", requestId)
                .put("ok", true)
                .put("result", result)
        }.getOrElse { error ->
            JSONObject()
                .put("browserVersion", VERSION)
                .put("requestId", requestId)
                .put("ok", false)
                .put("error", error.message ?: "Phone-file operation failed")
        }.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Opens an authorized entry as a streaming DeskLink file payload. The
     * caller hands the packet to the normal checkpointed WebRTC transfer
     * manager; this class never exposes an Android URI or filesystem path.
     */
    @Synchronized
    fun downloadPacket(payload: ByteArray): NetworkPacket {
        val request = JSONObject(String(payload, Charsets.UTF_8))
        require(request.getInt("browserVersion") == VERSION) {
            "Unsupported phone-file protocol version"
        }
        require(request.getString("action") == "download") {
            "Phone-file request is not a download"
        }
        val entryId = requiredEntryId(request)
        val transferId = request.getString("transferId")
            .also { validateTransferId(it) }
        val current = entry(entryId)
        require(current.document.isFile && current.document.canRead()) {
            "Entry is not a readable file"
        }
        val size = current.document.length()
        require(size in 0..MAX_TRANSFER_BYTES) { "Phone file exceeds the transfer limit" }
        val input = requireNotNull(context.contentResolver.openInputStream(current.document.uri)) {
            "Could not open phone file"
        }
        return NetworkPacket(DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST).apply {
            this["filename"] = current.document.name ?: "download"
            this["transferId"] = transferId
            this["open"] = false
            this.payload = NetworkPacket.Payload(input, size)
        }
    }

    private fun roots(): JSONObject {
        entries.clear()
        val roots = JSONArray()
        rootsProvider().take(MAX_ROOTS).forEach { root ->
            val document = documentForRoot(root.uri)
                ?: return@forEach
            require(document.exists() && document.isDirectory && document.canRead()) {
                "Authorized storage root is unavailable"
            }
            val id = opaqueId()
            entries[id] = Entry(id, null, document)
            roots.put(entryJson(id, root.name, entries.getValue(id)))
        }
        return JSONObject().put("entries", roots)
    }

    private fun list(entryId: String): JSONObject {
        val parent = entry(entryId)
        require(parent.document.isDirectory && parent.document.canRead()) { "Entry is not a readable folder" }
        val children = JSONArray()
        parent.document.listFiles()
            .sortedWith(compareByDescending<DocumentFile> { it.isDirectory }.thenBy { it.name?.lowercase() })
            .take(MAX_ENTRIES)
            .forEach { child ->
                val id = opaqueId()
                entries[id] = Entry(parent.rootId, entryId, child)
                children.put(entryJson(id, child.name ?: "Unnamed", entries.getValue(id)))
            }
        return JSONObject().put("parentId", entryId).put("entries", children)
    }

    private fun downloadInfo(entryId: String, transferId: String): JSONObject {
        validateTransferId(transferId)
        val current = entry(entryId)
        require(current.document.isFile && current.document.canRead()) {
            "Entry is not a readable file"
        }
        val size = current.document.length()
        require(size in 0..MAX_TRANSFER_BYTES) { "Phone file exceeds the transfer limit" }
        return JSONObject()
            .put("transferId", transferId)
            .put("name", current.document.name ?: "download")
            .put("size", size)
    }

    private fun metadata(entryId: String): JSONObject =
        JSONObject().put("entry", entryJson(entryId, entry(entryId).document.name ?: "Unnamed", entry(entryId)))

    private fun createFolder(parentId: String, name: String): JSONObject {
        val parent = entry(parentId)
        require(parent.document.isDirectory && parent.document.canWrite()) { "Folder is not writable" }
        require(parent.document.findFile(name) == null) { "An entry with this name already exists" }
        val created = requireNotNull(parent.document.createDirectory(name)) { "Could not create folder" }
        val id = opaqueId()
        entries[id] = Entry(parent.rootId, parentId, created)
        return JSONObject().put("entry", entryJson(id, name, entries.getValue(id)))
    }

    private fun rename(entryId: String, name: String): JSONObject {
        val current = entry(entryId)
        require(current.parentId != null) { "Storage roots cannot be renamed" }
        require(current.document.canWrite() && current.document.renameTo(name)) { "Could not rename entry" }
        return JSONObject().put("entry", entryJson(entryId, name, current))
    }

    private fun move(entryId: String, destinationId: String): JSONObject {
        val source = entry(entryId)
        val destination = entry(destinationId)
        require(source.parentId != null) { "Storage roots cannot be moved" }
        require(source.rootId == destination.rootId) { "Cross-root moves are not allowed" }
        require(destination.document.isDirectory && destination.document.canWrite()) {
            "Destination is not a writable folder"
        }
        val sourceParent = entry(requireNotNull(source.parentId))
        val movedUri = if (source.document.uri.scheme == "content") {
            DocumentsContract.moveDocument(
                context.contentResolver,
                source.document.uri,
                sourceParent.document.uri,
                destination.document.uri,
            )
        } else {
            val sourceFile = File(requireNotNull(source.document.uri.path))
            val destinationFile = File(requireNotNull(destination.document.uri.path), sourceFile.name)
            sourceFile.takeIf { it.renameTo(destinationFile) }?.let(Uri::fromFile)
        }
        requireNotNull(movedUri) { "Could not move entry" }
        entries[entryId] = Entry(
            source.rootId,
            destinationId,
            documentForRoot(movedUri) ?: error("Moved entry is unavailable"),
        )
        return JSONObject().put(
            "entry",
            entryJson(entryId, entries.getValue(entryId).document.name ?: "Unnamed", entries.getValue(entryId)),
        )
    }

    private fun delete(entryId: String): JSONObject {
        val current = entry(entryId)
        require(current.parentId != null) { "Storage roots cannot be deleted" }
        require(current.document.canWrite() && current.document.delete()) { "Could not delete entry" }
        entries.entries.removeAll { (key, value) ->
            value.rootId == current.rootId && (value.document.uri == current.document.uri || key == entryId)
        }
        return JSONObject().put("deletedEntryId", entryId)
    }

    private fun entryJson(id: String, displayName: String, entry: Entry): JSONObject = JSONObject()
        .put("entryId", id)
        .put("rootId", entry.rootId)
        .put("parentId", entry.parentId)
        .put("name", displayName.take(MAX_NAME_LENGTH))
        .put("directory", entry.document.isDirectory)
        .put("size", entry.document.length().coerceAtLeast(0))
        .put("modified", entry.document.lastModified().coerceAtLeast(0))
        .put("mimeType", entry.document.type ?: JSONObject.NULL)
        .put("readable", entry.document.canRead())
        .put("writable", entry.document.canWrite())

    private fun entry(id: String): Entry = requireNotNull(entries[id]) { "Unknown or stale phone-file entry ID" }

    private fun requiredEntryId(request: JSONObject): String = request.getString("entryId")
        .also { require(it.length in 1..128) { "Invalid phone-file entry ID" } }

    private fun requiredName(request: JSONObject): String = request.getString("name").trim()
        .also { name ->
            require(
                name.isNotEmpty() && name.length <= MAX_NAME_LENGTH &&
                    name !in setOf(".", "..") && '/' !in name && '\\' !in name && '\u0000' !in name
            ) { "Invalid phone-file name" }
        }

    private fun validateTransferId(value: String) {
        require(value.length in 1..128 && value.all {
            it.isLetterOrDigit() || it == '-' || it == '_'
        }) { "Invalid phone-file transfer ID" }
    }

    private fun documentForRoot(uri: Uri): DocumentFile? = when (uri.scheme) {
        "content" -> DocumentFile.fromTreeUri(context, uri) ?: DocumentFile.fromSingleUri(context, uri)
        "file" -> uri.path?.let(::File)?.let(DocumentFile::fromFile)
        else -> null
    }

    private fun opaqueId(): String = UUID.randomUUID().toString()

    companion object {
        const val MESSAGE_TYPE = "desklink.file.browser.v1"
        private const val VERSION = 1
        private const val MAX_ROOTS = 32
        // Keeps one JSON response comfortably below the 128 KiB WebRTC
        // envelope limit even when names and metadata are near their maxima.
        private const val MAX_ENTRIES = 256
        private const val MAX_NAME_LENGTH = 255
        private const val MAX_TRANSFER_BYTES = 4L * 1024L * 1024L * 1024L
    }
}
