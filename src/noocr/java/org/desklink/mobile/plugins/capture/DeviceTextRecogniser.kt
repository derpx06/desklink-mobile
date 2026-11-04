/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.capture

import android.graphics.Bitmap

/** The default build: no text recognition (switch it on with `-Pdesklink.ocr=true`). */
object DeviceTextRecogniser : TextRecogniser {
    override val available = false

    override fun recognise(bitmap: Bitmap, onResult: (String) -> Unit, onError: () -> Unit) = onError()
}
