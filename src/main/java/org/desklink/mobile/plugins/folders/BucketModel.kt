/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.folders

import android.net.Uri

// What the Bucket screens show: an item, the Bucket with one desk, its phase,
// and where a file belongs (FolderRef). Data only; BucketEngine fills it.

/** F8 P4: which pair a file belongs to and where (an offer's `folder`/`relpath`/`mtime`). */
data class FolderRef(val folder: String, val relpath: String, val mtime: Long?)

/** What the Bucket screen shows for one item. */
data class BucketItem(
    /** The last segment of [path]. */
    val name: String,
    val size: Long,
    val mtime: Long,
    /** Where it was put in: this phone, or the desk; null when not known. */
    val from: Side?,
    val state: State,
    /** The document, once it is on this phone. */
    val uri: Uri?,
    /** While it is sending or receiving: how far, and the time left. */
    val progress: Progress? = null,
    /** Where it is in the Bucket ("A/B/file"); a folder's items are its children. */
    val path: String = name,
    val dir: Boolean = false,
    /** Brought back after a delete here, because the computer edited it meanwhile. */
    val restored: Boolean = false,
) {
    enum class State { SYNCED, SENDING, RECEIVING, WAITING, CONFLICT }
}

/** The Bucket with one desk, as the screens show it. */
data class BucketView(
    val deviceId: String,
    /** Null until the Bucket is on (or while asking the desk). */
    val treeUri: String?,
    val phase: Phase,
    val items: List<BucketItem>,
    /** Things in the folder that cannot sync, with why ("Not synced (N)"). */
    val notSynced: List<Pair<String, Skip>>,
    val lastSync: Long?,
    val bytes: Long,
    /** The desk wants to turn the Bucket on: the phone picks its folder. */
    val offered: Boolean,
    /** An incoming file was refused for lack of space. */
    val storageFull: Boolean,
    /** Items being copied in: done / total, or null. */
    val adding: Pair<Int, Int>?,
    /** Everything in flight together (header, Home card, notification); null when nothing moves. */
    val summary: ProgressSummary? = null,
    /** "Pause sync" from the notification or the menu. */
    val userPaused: Boolean = false,
)

enum class Phase { OFF, ASKING, WAITING, NOT_ON_DESK, SCANNING, SYNCING, UP_TO_DATE, TOO_MANY, NO_ACCESS, HELD_EMPTY, LISTING_FAILED }
