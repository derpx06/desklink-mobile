/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.capture

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** ML Kit's bundled Latin recogniser (proprietary; only in builds with `-Pdesklink.ocr=true`). */
object DeviceTextRecogniser : TextRecogniser {
    override val available = true

    override fun recognise(bitmap: Bitmap, onResult: (String) -> Unit, onError: () -> Unit) {
        // One client per picture, so it is closed when the picture is done (it holds a native model).
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        client.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result -> onResult(result.text) }
            .addOnFailureListener { onError() }
            .addOnCompleteListener { client.close() }
    }
}
