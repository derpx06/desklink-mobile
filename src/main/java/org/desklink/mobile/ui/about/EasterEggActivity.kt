/*
 * SPDX-FileCopyrightText: 2021 Maxim Leshchenko <cnmaks90@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.about

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import org.desklink.mobile.ui.compose.KdeTheme
import org.desklink.mobile.ui.compose.screen.easteregg.EasterEggScreen

class EasterEggActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_NOSENSOR

        setContent {
            KdeTheme(this) {
                EasterEggScreen()
            }
        }
    }
}
