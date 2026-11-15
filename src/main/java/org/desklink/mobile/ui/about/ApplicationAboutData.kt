/*
 * SPDX-FileCopyrightText: 2021 Maxim Leshchenko <cnmaks90@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.about

import android.content.Context
import android.os.Build
import org.desklink.mobile.BuildConfig
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R

fun getApplicationAboutData(context: Context): AboutData = AboutData(
    name = context.getString(R.string.app_name),
    icon = R.drawable.desklink_mark,
    versionName = BuildConfig.VERSION_NAME.removeSuffix("-debug"),
    build = "${BuildConfig.VERSION_CODE} · ${BuildConfig.BUILD_TYPE}",
    developer = context.getString(R.string.about_developer),
    developerURL = context.getString(R.string.about_developer_url),
    bugURL = context.getString(R.string.about_issues_url),
    sourceCodeURL = context.getString(R.string.source_code_url),
    privacyURL = context.getString(R.string.privacy_policy_url),
    // KDE Connect for Android, which DeskLink grew from. Names kept for attribution; their copyright notices,
    // with contact details, stay in the source files they wrote.
    upstreamAuthors = listOf(
        AboutPerson("Albert Vaca Cintora", R.string.maintainer_and_developer),
        AboutPerson("Aleix Pol", R.string.developer),
        AboutPerson("Inoki Shaw", R.string.apple_support),
        AboutPerson("Matthijs Tijink", R.string.developer),
        AboutPerson("Nicolas Fella", R.string.developer),
        AboutPerson("Philip Cohn-Cort", R.string.developer),
        AboutPerson("Piyush Aggarwal", R.string.developer),
        AboutPerson("Simon Redman", R.string.developer),
        AboutPerson("Erik Duisters", R.string.developer),
        AboutPerson("Isira Seneviratne", R.string.developer),
        AboutPerson("Vineet Garg", R.string.developer),
        AboutPerson("Anjani Kumar", R.string.bug_fixes_and_general_improvements),
        AboutPerson("Samoilenko Yuri", R.string.samoilenko_yuri_task),
        AboutPerson("Aniket Kumar", R.string.aniket_kumar_task),
        AboutPerson("Àlex Fiestas", R.string.alex_fiestas_task),
        AboutPerson("Daniel Tang", R.string.bug_fixes_and_general_improvements),
        AboutPerson("Maxim Leshchenko", R.string.maxim_leshchenko_task),
        AboutPerson("Holger Kaelberer", R.string.holger_kaelberer_task),
        AboutPerson("Saikrishna Arcot", R.string.saikrishna_arcot_task),
        AboutPerson("ShellWen Chen", R.string.shellwen_chen_task),
    ),
)

/**
 * The lines "Copy details" puts on the clipboard for a bug report: the app's version and build, Android's version,
 * the phone model and how many computers are paired. No names, addresses, ids or keys.
 */
fun aboutReportDetails(about: AboutData, pairedComputers: Int = pairedComputerCount()): String = listOf(
    "${about.name} ${about.versionName} (build ${about.build})",
    "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    "Phone: ${Build.MANUFACTURER} ${Build.MODEL}",
    "Paired computers: $pairedComputers",
).joinToString("\n")

private fun pairedComputerCount(): Int = DeskLinkApplication.getInstance().devices.values.count { it.isPaired }
