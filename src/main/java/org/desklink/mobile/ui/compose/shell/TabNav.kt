/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose.shell

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import org.desklink.mobile.R

/** The five places of the app, in the desk's order and with its names. */
enum class AppTab(val key: String, @StringRes val label: Int, @DrawableRes val icon: Int) {
    HOME("home", R.string.tab_home, R.drawable.sym_home),
    FILES("files", R.string.tab_files, R.drawable.sym_folder),
    NOTES("notes", R.string.tab_notes, R.drawable.sym_edit_note),
    TERMINALS("terminals", R.string.tab_terminals, R.drawable.sym_terminal),
    SETTINGS("settings", R.string.tab_settings, R.drawable.sym_settings),
    ;

    companion object {
        fun fromKey(key: String?): AppTab = entries.firstOrNull { it.key == key } ?: HOME
    }
}

/**
 * Which tab is showing, and each tab's own back stack of routes. A tab keeps its stack when another tab is
 * selected, so coming back finds it as it was. Back pops the selected tab's stack, then goes Home, and only
 * then leaves the app.
 */
class TabNav(selected: AppTab = AppTab.HOME, stacks: Map<AppTab, List<String>> = emptyMap()) {
    var selected by mutableStateOf(selected)
    private val stacks = mutableStateMapOf<AppTab, List<String>>().apply { putAll(stacks.filterValues { it.isNotEmpty() }) }

    fun stack(tab: AppTab): List<String> = stacks[tab].orEmpty()

    fun top(tab: AppTab): String? = stacks[tab]?.lastOrNull()

    fun push(tab: AppTab, route: String) {
        if (top(tab) != route) stacks[tab] = stack(tab) + route
    }

    /** Pops the top route of [tab]; false when it was already at its root. */
    fun pop(tab: AppTab): Boolean {
        val current = stack(tab)
        if (current.isEmpty()) return false
        if (current.size == 1) stacks.remove(tab) else stacks[tab] = current.dropLast(1)
        return true
    }

    fun reset(tab: AppTab) {
        stacks.remove(tab)
    }

    fun select(tab: AppTab) {
        selected = tab
    }

    /** Whether [back] would do something here (so the system back can be left to the activity otherwise). */
    val canGoBack: Boolean get() = stack(selected).isNotEmpty() || selected != AppTab.HOME

    /** The back button: pops the selected tab, else returns to Home. False when already at Home's root. */
    fun back(): Boolean {
        if (pop(selected)) return true
        if (selected != AppTab.HOME) {
            selected = AppTab.HOME
            return true
        }
        return false
    }

    fun snapshot(): List<String> = listOf(selected.key) + AppTab.entries.flatMap { tab ->
        stack(tab).map { "${tab.key}|$it" }
    }

    companion object {
        fun restore(saved: List<String>): TabNav {
            val stacks = AppTab.entries.associateWith { mutableListOf<String>() }
            saved.drop(1).forEach { entry ->
                val bar = entry.indexOf('|')
                if (bar > 0) stacks[AppTab.fromKey(entry.substring(0, bar))]?.add(entry.substring(bar + 1))
            }
            return TabNav(AppTab.fromKey(saved.firstOrNull()), stacks)
        }

        val Saver: Saver<TabNav, Any> = listSaver(save = { it.snapshot() }, restore = { restore(it) })
    }
}
