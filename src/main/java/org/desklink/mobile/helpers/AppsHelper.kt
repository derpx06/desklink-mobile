/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/
package org.desklink.mobile.helpers

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

object AppsHelper {
    /** App labels already looked up: asking the package manager reads the disk, and most apps post many notifications. */
    private val labels = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Packages that could not be resolved, with when: not asked (and not logged with a stack) again for a while. */
    private val misses = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val MISS_MS = 10 * 60_000L

    /** A test's way to start over. */
    internal fun clearCache() { labels.clear(); misses.clear() }

    @JvmStatic
    fun appNameLookup(context: Context, packageName: String): String? {
        labels[packageName]?.let { return it }
        misses[packageName]?.let { at -> if (System.currentTimeMillis() - at < MISS_MS) return null }
        return try {
            val manager = context.packageManager
            val info = manager.getApplicationInfo(packageName, 0)

            manager.getApplicationLabel(info).toString().also { labels[packageName] = it }
        } catch (e: PackageManager.NameNotFoundException) {
            misses[packageName] = System.currentTimeMillis()
            Log.e("AppsHelper", "Could not resolve name $packageName", e)
            null
        }
    }
}
