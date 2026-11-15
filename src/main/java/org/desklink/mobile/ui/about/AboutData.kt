/*
 * SPDX-FileCopyrightText: 2021 Maxim Leshchenko <cnmaks90@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.about

import androidx.annotation.DrawableRes

/** What the About screen shows. A null link hides its row. */
data class AboutData(
    val name: String,
    @DrawableRes val icon: Int,
    val versionName: String,
    /** "20300 · debug": the version code and the build type. */
    val build: String,
    val developer: String,
    val developerURL: String? = null,
    val bugURL: String? = null,
    val sourceCodeURL: String? = null,
    val privacyURL: String? = null,
    /** The KDE Connect authors DeskLink's code started from: credited by name, never as DeskLink's makers. */
    val upstreamAuthors: List<AboutPerson> = emptyList(),
)
