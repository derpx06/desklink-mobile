/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose.screen.transfers

import androidx.annotation.StringRes
import org.desklink.mobile.R
import org.desklink.mobile.plugins.share.PreviewKind
import org.desklink.mobile.plugins.share.TransferRecord
import org.desklink.mobile.plugins.share.previewKind
import java.util.Calendar

/** The chips over the file list. Videos count as Images (pictures and clips), office files and PDFs as Documents. */
enum class FileFilter(@StringRes val label: Int) {
    ALL(R.string.files_filter_all),
    IMAGES(R.string.files_filter_images),
    DOCUMENTS(R.string.files_filter_documents),
    ARCHIVES(R.string.files_filter_archives),
    ;

    fun matches(record: TransferRecord): Boolean = this == ALL || matches(previewKind(record.mime, record.name), record.name)

    fun matches(kind: PreviewKind, name: String): Boolean = when (this) {
        ALL -> true
        IMAGES -> kind == PreviewKind.IMAGE || kind == PreviewKind.VIDEO
        DOCUMENTS -> kind == PreviewKind.PDF || kind == PreviewKind.TEXT || (kind == PreviewKind.OTHER && name.substringAfterLast('.', "").lowercase() in OFFICE)
        ARCHIVES -> kind == PreviewKind.ARCHIVE
    }

    private companion object {
        val OFFICE = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "csv", "epub")
    }
}

/** What the list shows for one filter and limit: how many files match, and the first [limit] of them by day. Built once per input, not per frame. */
data class FilesView(val matching: Int, val groups: List<Pair<Long, List<TransferRecord>>>) {
    companion object {
        fun of(records: List<TransferRecord>, filter: FileFilter, limit: Int): FilesView {
            val matching = records.filter(filter::matches)
            return FilesView(matching.size, groupByDay(matching.take(limit)))
        }
    }
}

/** Files by day, newest first (the list arrives newest first and keeps its order inside a day). */
fun groupByDay(records: List<TransferRecord>): List<Pair<Long, List<TransferRecord>>> =
    records.groupBy { dayStart(it.time) }.toList().sortedByDescending { it.first }

fun dayStart(time: Long): Long = Calendar.getInstance().apply {
    timeInMillis = time
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** What the Bucket card's bar shows: bytes of pictures, of documents, and of everything else. */
data class BucketBreakdown(val images: Long, val documents: Long, val other: Long) {
    val total get() = images + documents + other

    companion object {
        fun of(files: List<Pair<String, Long>>): BucketBreakdown {
            var i = 0L; var d = 0L; var o = 0L
            files.forEach { (name, size) ->
                val kind = previewKind(null, name)
                when {
                    FileFilter.IMAGES.matches(kind, name) -> i += size
                    FileFilter.DOCUMENTS.matches(kind, name) -> d += size
                    else -> o += size
                }
            }
            return BucketBreakdown(i, d, o)
        }
    }
}
