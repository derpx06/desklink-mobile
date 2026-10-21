/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.plugins.terminal

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * The terminal's quick-command chips, per computer. Tapping one types it and Enter. Never set:
 * the defaults; set to nothing: none (the person removed them all).
 */
object QuickCommands {
    const val PREFS = "desklink_quick_commands"
    val DEFAULTS = listOf("git status", "cargo build", "cargo test", "ls", "git pull")

    /** A chip is one line; anything longer than this is not a quick command. */
    const val MAX_LENGTH = 200

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(prefs: SharedPreferences, deviceId: String): List<String> =
        prefs.getString(deviceId, null)?.let(::decode) ?: DEFAULTS

    fun save(prefs: SharedPreferences, deviceId: String, commands: List<String>) =
        prefs.edit { putString(deviceId, encode(commands)) }

    /** Trimmed, one line each, no blanks or repeats, in the order given. */
    fun clean(commands: List<String>): List<String> =
        commands.map { it.replace('\n', ' ').replace('\r', ' ').trim().take(MAX_LENGTH) }.filter(String::isNotEmpty).distinct()

    internal fun encode(commands: List<String>): String = clean(commands).joinToString("\n")

    internal fun decode(stored: String): List<String> = stored.split('\n').filter(String::isNotEmpty)
}
