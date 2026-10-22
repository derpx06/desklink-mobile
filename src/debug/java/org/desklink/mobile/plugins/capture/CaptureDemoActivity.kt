/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.capture

import android.app.Activity
import android.os.Bundle

/**
 * Debug builds only: a made-up desk's capture request, for screenshots of each capture screen
 * (`am start -n org.desklink.mobile.debug/org.desklink.mobile.plugins.capture.CaptureDemoActivity --es kind scan --es target file`).
 * Nothing is sent: there is no such desk.
 */
class CaptureDemoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = CaptureRequest.of(
            "demo-desk", java.util.UUID.randomUUID().toString(),
            intent.getStringExtra("kind") ?: "photo", intent.getStringExtra("target") ?: "clipboard",
        )
        if (request != null) PhoneCapture.request(this, "manas-82xf", request)
        finish()
    }
}
