/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.share

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.desklink.mobile.R

/** What the share entry point does with what the sending app put in the Intent. */
object ShareEntry {
    /** The paired device an intent names (a direct-share target or an extra), if it names one; only ever a suggestion. */
    fun suggestedDevice(extra: String?, shortcutId: String?, paired: Set<String>): String? =
        listOfNotNull(extra, shortcutId).firstOrNull { it in paired }

    /** "Link: https://…", "Text: …" or "2 files: a.jpg, b.pdf", short enough for a dialog. */
    fun summary(context: Context, text: String?, uris: List<Uri>, streamCount: Int): String {
        val parts = mutableListOf<String>()
        if (streamCount > 0) {
            val names = uris.take(3).mapNotNull { name(context, it) }
            val more = if (streamCount > names.size) " …" else ""
            parts += context.resources.getQuantityString(R.plurals.share_confirm_files, streamCount, streamCount) + (names.takeIf { it.isNotEmpty() }?.joinToString(", ", ": ", more) ?: "")
        }
        text?.takeIf { it.isNotBlank() }?.let { parts += context.getString(R.string.share_confirm_text, it.trim().take(MAX_SHOWN)) }
        return parts.joinToString("\n").ifEmpty { context.getString(R.string.share_confirm_nothing) }
    }

    private fun name(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()?.take(60)

    const val MAX_SHOWN = 200
}
