/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.capture

import android.graphics.Bitmap

/**
 * Reads the text out of a picture (Insert -> "Copy Text"). The implementation, [DeviceTextRecogniser], is chosen at
 * build time: `src/ocr` (Google ML Kit, `-Pdesklink.ocr=true`) or `src/noocr` (the default: none, so the phone does not
 * offer the `text` kind).
 */
interface TextRecogniser {
    /** True when this build can recognise text; false means the `text` capture kind is not offered. */
    val available: Boolean

    /** Exactly one of the callbacks runs, on the main thread. */
    fun recognise(bitmap: Bitmap, onResult: (String) -> Unit, onError: () -> Unit)
}
