/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.shots

import java.io.File

/** Where the screenshot harness writes: `~/.cache/android-ui/shots/` unless `-Pshots=<dir>` is given. */
object ShotDir {
    val dir: File by lazy {
        File(System.getProperty("desklink.shots") ?: (System.getProperty("user.home") + "/.cache/android-ui/shots")).also { it.mkdirs() }
    }
}
