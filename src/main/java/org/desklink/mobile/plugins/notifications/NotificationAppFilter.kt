/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.notifications

import android.content.Context

/** The per-app notification filter (Choose apps, hide contents) for other features that follow the same rules. */
class NotificationAppFilter(context: Context) {
    private val database = AppDatabase.getInstance(context)

    /** Reads the per-app settings into memory; call off the main thread. */
    fun preload() = database.preload()

    fun isReady() = database.isReady()

    fun isEnabled(packageName: String) = database.isEnabled(packageName)

    fun blocksContents(packageName: String) = database.getPrivacy(packageName, AppDatabase.PrivacyOptions.BLOCK_CONTENTS)
}
