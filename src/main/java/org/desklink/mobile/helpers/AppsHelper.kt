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

    @JvmStatic
    fun appNameLookup(context: Context, packageName: String): String? {
        labels[packageName]?.let { return it }
        return try {
            val manager = context.packageManager
            val info = manager.getApplicationInfo(packageName, 0)

            manager.getApplicationLabel(info).toString().also { labels[packageName] = it }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e("AppsHelper", "Could not resolve name $packageName", e)
            null
        }
    }
}
