/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.helpers

import android.content.Context
import android.net.Uri

/**
 * Small durable store for interrupted file transfers.
 *
 * The transfer protocol remains packet-compatible: this store only records
 * optional transferId/offset metadata and never replaces device identity or
 * pairing preferences.
 */
data class TransferCheckpoint @JvmOverloads constructor(
    val transferId: String,
    val uri: String,
    val offset: Long,
    val totalSize: Long,
    val state: String,
    val filename: String? = null,
    val deviceId: String? = null,
    val sha256: String? = null,
    val transferToken: String? = null,
    val sessionId: Long? = null,
    val connectionGeneration: Long? = null,
    val direction: String? = null,
)

class TransferCheckpointStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(transferId: String): TransferCheckpoint? {
        if (!isSafeId(transferId)) return null
        val uri = preferences.getString(key(transferId, "uri"), null) ?: return null
        return TransferCheckpoint(
            transferId = transferId,
            uri = uri,
            offset = preferences.getLong(key(transferId, "offset"), 0L),
            totalSize = preferences.getLong(key(transferId, "total"), -1L),
            state = preferences.getString(key(transferId, "state"), "failed") ?: "failed",
            filename = preferences.getString(key(transferId, "filename"), null),
            deviceId = preferences.getString(key(transferId, "deviceId"), null),
            sha256 = preferences.getString(key(transferId, "sha256"), null),
            transferToken = preferences.getString(key(transferId, "token"), null),
            sessionId = preferences.getLong(key(transferId, "sessionId"), Long.MIN_VALUE)
                .takeUnless { it == Long.MIN_VALUE },
            connectionGeneration = preferences.getLong(
                key(transferId, "generation"),
                Long.MIN_VALUE,
            ).takeUnless { it == Long.MIN_VALUE },
            direction = preferences.getString(key(transferId, "direction"), null),
        )
    }

    /** Commit synchronously because a process death immediately after a chunk is expected. */
    fun save(checkpoint: TransferCheckpoint): Boolean {
        if (!isSafeId(checkpoint.transferId)
            || checkpoint.offset < 0
            || checkpoint.totalSize < 0
            || checkpoint.offset > checkpoint.totalSize
            || runCatching { Uri.parse(checkpoint.uri) }.getOrNull() == null
        ) return false
        return preferences.edit()
            .putString(key(checkpoint.transferId, "uri"), checkpoint.uri)
            .putLong(key(checkpoint.transferId, "offset"), checkpoint.offset)
            .putLong(key(checkpoint.transferId, "total"), checkpoint.totalSize)
            .putString(key(checkpoint.transferId, "state"), checkpoint.state)
            .putString(key(checkpoint.transferId, "filename"), checkpoint.filename)
            .putString(key(checkpoint.transferId, "deviceId"), checkpoint.deviceId)
            .putString(key(checkpoint.transferId, "sha256"), checkpoint.sha256)
            .putString(key(checkpoint.transferId, "token"), checkpoint.transferToken)
            .putString(key(checkpoint.transferId, "direction"), checkpoint.direction)
            .also { editor ->
                checkpoint.sessionId?.let { editor.putLong(key(checkpoint.transferId, "sessionId"), it) }
                    ?: editor.remove(key(checkpoint.transferId, "sessionId"))
                checkpoint.connectionGeneration?.let {
                    editor.putLong(key(checkpoint.transferId, "generation"), it)
                } ?: editor.remove(key(checkpoint.transferId, "generation"))
            }
            .commit()
    }

    fun remove(transferId: String): Boolean {
        if (!isSafeId(transferId)) return false
        return preferences.edit()
            .remove(key(transferId, "uri"))
            .remove(key(transferId, "offset"))
            .remove(key(transferId, "total"))
            .remove(key(transferId, "state"))
            .remove(key(transferId, "filename"))
            .remove(key(transferId, "deviceId"))
            .remove(key(transferId, "sha256"))
            .remove(key(transferId, "token"))
            .remove(key(transferId, "sessionId"))
            .remove(key(transferId, "generation"))
            .remove(key(transferId, "direction"))
            .commit()
    }

    fun list(): List<TransferCheckpoint> = preferences.all.keys
        .asSequence()
        .filter { it.startsWith("transfer.") && it.endsWith(".uri") }
        .map { it.removePrefix("transfer.").removeSuffix(".uri") }
        .distinct()
        .mapNotNull(::load)
        .sortedBy(TransferCheckpoint::transferId)
        .toList()

    private fun key(transferId: String, field: String) = "transfer.$transferId.$field"

    private fun isSafeId(transferId: String): Boolean =
        transferId.isNotEmpty()
            && transferId.length <= 128
            && transferId.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private companion object {
        const val PREFERENCES = "desklink.transfer.checkpoints"
    }
}
