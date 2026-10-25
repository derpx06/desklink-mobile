/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.share

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * What a share from ANOTHER app may point at. Anything that arrives in an Intent is the sender's word: it may be a `file:`
 * path into this app's private data (the pairing key lives in shared_prefs), or a URI of this app's own provider, which this
 * process can open whatever the sender could. Only `content:` URIs of other apps are taken.
 */
object ShareUris {
    /** The URIs of [uris] that may be read for a share that came from outside, in their order. */
    fun fromOutside(context: Context, uris: Collection<Uri?>): List<Uri> =
        uris.filterNotNull().filter { isAllowedFromOutside(context, it) }

    fun isAllowedFromOutside(context: Context, uri: Uri): Boolean =
        uri.scheme.equals("content", ignoreCase = true) && !isOurOwn(context, authority(uri))

    /** The provider named by the URI: userinfo and port taken off (`content://0@pkg.fileprovider/..` is `pkg.fileprovider`), lowercase. */
    internal fun authority(uri: Uri): String? =
        uri.authority?.substringAfterLast('@')?.substringBefore(':')?.lowercase(java.util.Locale.ROOT)

    private fun isOurOwn(context: Context, authority: String?): Boolean =
        authority == null || authority.startsWith(context.packageName.lowercase(java.util.Locale.ROOT)) || authority.isEmpty()

    /** Whether [file] lies in this app's private storage (by canonical path, so `..` and links do not hide it). */
    fun isInsideAppData(context: Context, file: File): Boolean {
        val target = runCatching { file.canonicalFile }.getOrNull() ?: return true
        val roots = listOfNotNull(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) context.dataDir else null,
            // The data directory itself (shared_prefs, databases) on every API level.
            context.filesDir.parentFile,
            context.filesDir, context.cacheDir, context.codeCacheDir, context.noBackupFilesDir,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) context.applicationInfo?.deviceProtectedDataDir?.let(::File) else null,
        ).mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        return roots.any { target.path == it.path || target.path.startsWith(it.path + File.separator) }
    }
}
