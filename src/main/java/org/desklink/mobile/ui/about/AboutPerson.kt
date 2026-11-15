/*
 * SPDX-FileCopyrightText: 2021 Maxim Leshchenko <cnmaks90@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.about

import androidx.annotation.StringRes

/** A credited person: a name and, optionally, what they did. Contact details stay in the source notices. */
data class AboutPerson(val name: String, @StringRes val task: Int? = null)
