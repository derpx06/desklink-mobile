/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.update

import android.content.Context
import android.os.Build

/** Where this install came from: it decides the one action the popup offers (docs/UPDATES.md 5.2). Nothing is installed by the app. */
internal enum class InstallSource { GOOGLE_PLAY, FDROID, GITHUB, OTHER_STORE;

    companion object {
        private val FDROID_CLIENTS = setOf("org.fdroid.fdroid", "org.fdroid.basic", "com.machiav3lli.fdroid", "eu.bubu1.fdroidclassic")
        private val SIDELOAD = setOf("com.google.android.packageinstaller", "com.android.packageinstaller")

        /** Pure: an installer package name (null = adb or a file manager) to a source. */
        fun classify(installer: String?): InstallSource = when {
            installer == "com.android.vending" -> GOOGLE_PLAY
            installer in FDROID_CLIENTS -> FDROID
            installer == null || installer in SIDELOAD -> GITHUB
            else -> OTHER_STORE
        }

        fun detect(context: Context): InstallSource = classify(
            runCatching {
                val pm = context.packageManager
                if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(context.packageName).installingPackageName
                else @Suppress("DEPRECATION") pm.getInstallerPackageName(context.packageName)
            }.getOrNull(),
        )
    }
}
