/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose.update

import org.desklink.mobile.update.InstallSource
import org.desklink.mobile.update.Note

/** Everything the sheet shows, as plain data (so a shot test needs no engine). The manifest's text is plain text, drawn as such. */
internal data class UpdateSheetModel(
    val version: String,
    val installed: String,
    val beta: Boolean,
    val notes: List<Note>,
    val source: InstallSource,
    /** "24 MB", for the GitHub line; null when the manifest has no size. */
    val sizeText: String?,
    /** The full SHA-256 of the APK, GitHub installs only. */
    val sha256: String?,
    val required: Boolean = false,
    /** The manifest's own sentence for a required update; null = the standard one. */
    val requiredReason: String? = null,
    /** "0.4" in "computers running DeskLink 0.4 or newer". */
    val requiredMajorMinor: String = "",
)

internal class UpdateSheetActions(
    val onPrimary: () -> Unit,
    val onNotNow: () -> Unit,
    val onSkip: () -> Unit,
    val onCopyChecksum: (String) -> Unit,
)
