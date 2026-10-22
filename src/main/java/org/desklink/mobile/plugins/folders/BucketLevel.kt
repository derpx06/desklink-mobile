/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.folders

/** One tile on a Bucket level: a file, or a folder with what is inside it rolled up. */
data class BucketTile(
    val item: BucketItem,
    /** Folders: items inside (files and folders, all depths). */
    val count: Int = 0,
    val bytes: Long = 0,
    val state: BucketItem.State = item.state,
    /** Folders: the combined progress of the files moving inside. */
    val progress: ProgressSummary? = null,
)

object BucketLevel {
    /** The path's parent ("" = top). */
    fun parent(path: String) = path.substringBeforeLast('/', "")

    /** Folders first, then files, each newest first. A folder's badge rolls up from its children (any conflict wins, then moving, then waiting). */
    fun tiles(items: List<BucketItem>, at: String): List<BucketTile> {
        val tiles = items.filter { parent(it.path) == at }.map { item ->
            if (!item.dir) return@map BucketTile(item, progress = null)
            val inside = items.filter { it.path.startsWith(item.path + "/") }
            val files = inside.filter { !it.dir }
            val states = files.map { it.state }
            val rolled = when {
                BucketItem.State.CONFLICT in states -> BucketItem.State.CONFLICT
                BucketItem.State.RECEIVING in states -> BucketItem.State.RECEIVING
                BucketItem.State.SENDING in states -> BucketItem.State.SENDING
                BucketItem.State.WAITING in states -> BucketItem.State.WAITING
                else -> item.state
            }
            BucketTile(item, inside.size, files.sumOf { it.size }, rolled, BucketProgress.summary(files.mapNotNull { it.progress }))
        }
        return tiles.sortedWith(compareByDescending<BucketTile> { it.item.dir }.thenByDescending { it.item.mtime })
    }

    /** "Bucket", then each folder of [path]: (label, path to open). */
    fun crumbs(path: String, root: String): List<Pair<String, String>> {
        val parts = if (path.isEmpty()) emptyList() else path.split('/')
        return listOf(root to "") + parts.indices.map { parts[it] to parts.take(it + 1).joinToString("/") }
    }

    /** "New folder" is hidden at level 2: [at] already has [FolderSync.MAX_BUCKET_DEPTH] folders. */
    fun canMakeFolder(at: String) = (if (at.isEmpty()) 0 else at.count { it == '/' } + 1) < FolderSync.MAX_BUCKET_DEPTH

    /** Folders [moved] could go into: not itself or below, and within the depth limit. */
    fun targets(items: List<BucketItem>, moved: Collection<String>): List<String> {
        val dirs = items.filter { it.dir }.map { it.path }
        return (listOf("") + dirs).filter { target ->
            moved.none { m -> target == m || target.startsWith("$m/") } && moved.any { parent(it) != target }
        }
    }
}
