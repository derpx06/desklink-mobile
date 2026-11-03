/*
 * SPDX-FileCopyrightText: 2025 Mash Kyrielight <fiepi@live.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.extensions

import android.content.Intent
import android.os.Parcelable
import androidx.core.content.IntentCompat


inline fun <reified T> Intent.getParcelableCompat(key: String): T? {
    return IntentCompat.getParcelableExtra(this, key, T::class.java)
}

inline fun <reified T> Intent.getParcelableArrayListCompat(key: String): ArrayList<T>? {
    return IntentCompat.getParcelableArrayListExtra(this, key, T::class.java)
}

inline fun <reified T: Parcelable> Intent.getParcelableArrayCompat(key: String): Array<Parcelable>? {
    return IntentCompat.getParcelableArrayExtra(this, key, T::class.java)
}

/**
 * Whether this intent's extras can be read at all. Extras from another app are unmarshalled lazily and a crafted one
 * (a class this app does not have) throws [android.os.BadParcelableException] at the first read, ending the process;
 * a few of those in two minutes put the app in crash-loop safe mode. Exported entry points ask first and drop the
 * intent when it cannot be read.
 */
fun Intent.isReadable(): Boolean = runCatching { extras?.keySet(); true }.getOrDefault(false)
