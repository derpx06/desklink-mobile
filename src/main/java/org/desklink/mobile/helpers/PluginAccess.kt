/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import org.desklink.mobile.plugins.Plugin

/**
 * A permission or special access (notification access, Do Not Disturb) granted while a computer is connected, from a desk's
 * Allow, Android's dialog or a settings page: the plugin that waited for it is loaded now, not at the next link rebuild
 * (`Device.addPlugin` skips a plugin whose required access is missing, and only a reload loads it). Only the affected plugins
 * of the affected devices are reloaded, never the links; nothing changed, nothing reloads.
 */
object PluginAccess {
    /** What a reload has to do for one device. */
    class Change(
        /** Plugins whose access was granted: a reload loads them. */
        val reload: Set<String>,
        /** Loaded plugins that gained optional access they read only in `onCreate`: destroyed first, so the reload creates them anew. */
        val recreate: Set<String>,
    ) {
        fun isNone() = reload.isEmpty() && recreate.isEmpty()
    }

    /**
     * Pure. [noRequired] and [noOptional] are the device's plugins that were found without required or optional access when
     * they were last loaded; [enabled] says whether the person has the plugin on for this computer.
     */
    fun waiting(
        enabled: (String) -> Boolean,
        loaded: Set<String>,
        noRequired: Map<String, Plugin>,
        noOptional: Map<String, Plugin>,
    ): Change {
        val granted = noRequired.filter { (key, plugin) -> enabled(key) && plugin.checkRequiredPermissions() }.keys
        val gained = noOptional.filter { (key, plugin) -> enabled(key) && key in loaded && key !in granted && plugin.checkOptionalPermissions() }.keys
        // Loaded already (it asked to load without its access) and now has it: `addPlugin` keeps the instance, so it is created anew.
        return Change(reload = granted + gained, recreate = gained + granted.filter { it in loaded })
    }

    /** Pure: the plugins a reload loaded that were not loaded before it. */
    fun newlyLoaded(before: Set<String>, after: Set<String>): Set<String> = after - before

    /**
     * A plugin loaded while the link is already feature-ready missed the feature-ready snapshots: [send] makes each one speak
     * now. A link not ready yet sends them itself when it is.
     */
    fun announce(loadedNow: Set<String>, linkReady: Boolean, send: (String) -> Unit) {
        if (linkReady) loadedNow.forEach(send)
    }

    /** Every paired, reachable computer, off the main thread: [Asks.report] and the notification listener call this first. */
    @JvmStatic
    fun reloadGranted() {
        val devices = runCatching { org.desklink.mobile.DeskLinkApplication.getInstance().devices.values.toList() }.getOrDefault(emptyList())
        devices.forEach { device -> runCatching { device.reloadIfAccessGranted() } }
    }
}
