/*
 * SPDX-FileCopyrightText: 2018 Philip Cohn-Cort <cliabhach@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/
package org.desklink.mobile.ui

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions

/**
 * Utilities for working with android [Themes][android.content.res.Resources.Theme].
 */
object ThemeUtil {
    @Suppress("MemberVisibilityCanBePrivate")
    const val LIGHT_MODE: String = "light"
    @Suppress("MemberVisibilityCanBePrivate")
    const val DARK_MODE: String = "dark"
    const val DEFAULT_MODE: String = "default"

    /** Use the wallpaper's colors (Android 12+); off uses the DeskLink palette. */
    const val KEY_DYNAMIC_COLORS: String = "dynamic_colors"

    fun applyTheme(themePref: String) {
        when (themePref) {
            LIGHT_MODE -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }

            DARK_MODE -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            }

            else -> {
                if (themePref == DEFAULT_MODE) {
                    Log.d("ThemeUtil", "Theme preference not set, using system default.")
                } else {
                    Log.w("ThemeUtil", "Unknown theme preference: $themePref, falling back to system default.")
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                } else {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY)
                }
            }
        }
    }

    /**
     * Called when an activity is created for the first time to reliably load the correct theme.
     */
    fun setUserPreferredTheme(application: Application) {
        val appTheme = PreferenceManager
            .getDefaultSharedPreferences(application)
            .getString("theme_pref", DEFAULT_MODE)!!
        // Read at each activity's creation, so turning it off applies to the next screen opened.
        DynamicColors.applyToActivitiesIfAvailable(
            application,
            DynamicColorsOptions.Builder()
                .setPrecondition { activity, _ ->
                    PreferenceManager.getDefaultSharedPreferences(activity).getBoolean(KEY_DYNAMIC_COLORS, false)
                }
                .build(),
        )
        applyTheme(appTheme)
    }
}
