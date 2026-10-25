/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.webrtc

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Transfers whose file is already in place, remembered across a crash or a lost `complete`: when the sender offers the same
 * id and token again, the answer is "I have all of it" and `complete`, never a second full copy. Small on purpose: the
 * newest [MAX_RECORDS] for at most [MAX_AGE_MS]; a sender that comes back later than that simply sends it again.
 */
class DeliveredTransfers(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    data class Record(val transferId: String, val token: String, val deviceId: String, val filename: String, val totalSize: Long, val fileHash: String, val at: Long)

    @Synchronized
    fun add(record: Record) {
        val kept = load().filterNot { it.transferId == record.transferId } + record
        save(kept.sortedBy { it.at }.takeLast(MAX_RECORDS))
    }

    /** The record for exactly this transfer (same id, token, peer, name and size), or null. */
    @Synchronized
    fun find(transferId: String, token: String, deviceId: String, filename: String?, totalSize: Long?): Record? =
        load().firstOrNull {
            it.transferId == transferId && it.token == token && it.deviceId == deviceId &&
                (filename == null || it.filename == filename) && (totalSize == null || it.totalSize == totalSize)
        }

    @Synchronized
    fun forgetPeer(deviceId: String) = save(load().filterNot { it.deviceId == deviceId })

    @Synchronized
    fun forget(transferId: String) = save(load().filterNot { it.transferId == transferId })

    private fun load(): List<Record> = runCatching {
        if (!file.isFile) return emptyList()
        val array = JSONArray(file.readText())
        val cutoff = now() - MAX_AGE_MS
        (0 until array.length()).mapNotNull { index ->
            runCatching {
                array.getJSONObject(index).let {
                    Record(it.getString("id"), it.getString("token"), it.getString("device"), it.getString("name"), it.getLong("size"), it.getString("hash"), it.getLong("at"))
                }
            }.getOrNull()?.takeIf { it.at >= cutoff }
        }
    }.getOrDefault(emptyList())

    private fun save(records: List<Record>) {
        runCatching {
            val array = JSONArray()
            records.forEach {
                array.put(JSONObject().put("id", it.transferId).put("token", it.token).put("device", it.deviceId).put("name", it.filename).put("size", it.totalSize).put("hash", it.fileHash).put("at", it.at))
            }
            val tmp = File(file.path + ".tmp")
            tmp.writeText(array.toString())
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
    }

    companion object {
        /** Everything remembered as delivered by [deviceId], gone with its pairing. */
        fun forgetDevice(context: android.content.Context, deviceId: String) {
            val store = DeliveredTransfers(File(context.applicationContext.filesDir, "webrtc-delivered.json"))
            store.forgetPeer(deviceId)
        }

        const val MAX_RECORDS = 50
        const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
    }
}
