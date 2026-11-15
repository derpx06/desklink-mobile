/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.screen

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R

/**
 * A desk's ask to see this screen, as a pop-up over whatever app is open: "<desk> wants to see your screen", Allow / Not now.
 * Allow goes straight on to Android's own consent prompt (which cannot be skipped) and the share starts when that is accepted;
 * Not now (or Back) tells the desk. Nothing is allowed without the person's tap here. Not exported.
 */
class ScreenAskActivity : AppCompatActivity() {
    private var dialog: AlertDialog? = null
    private var deviceId: String? = null
    private var answered = false
    private val timeout = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_DEVICE_ID)
        val device = id?.let { runCatching { DeskLinkApplication.getInstance().getDevice(it) }.getOrNull() }
        if (device == null || !device.isPaired) return finish()
        deviceId = id
        showing = id
        shown = id to android.os.SystemClock.elapsedRealtime()
        ScreenControlPlugin.cancelAsk(this, id)
        dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.screen_share_prompt_title, device.name))
            .setMessage(R.string.screen_share_prompt_text)
            .setPositiveButton(R.string.screen_share_prompt_allow) { _, _ -> allow() }
            .setNegativeButton(R.string.screen_share_prompt_not_now) { _, _ -> notNow() }
            .setOnCancelListener { notNow() }
            .create()
            .apply { setCanceledOnTouchOutside(false) }
        dialog?.show()
        // The desk stops waiting after the same time: the pop-up goes with it.
        timeout.postDelayed({ answered = true; finish() }, ScreenControlPlugin.PROMPT_TIMEOUT_MS)
    }

    private fun allow() {
        answered = true
        deviceId?.let { startActivity(ScreenControlPlugin.projectionIntent(this, it)) }
        finish()
    }

    private fun notNow() {
        if (answered) return
        answered = true
        deviceId?.let { ScreenControlPlugin.declined(this, it) }
        finish()
    }

    override fun onDestroy() {
        timeout.removeCallbacksAndMessages(null)
        dialog?.dismiss()
        if (showing == deviceId) showing = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_DEVICE_ID = "deviceId"

        /** The desk whose ask is on screen now, if any: the heads-up fallback is not posted over it. */
        @Volatile
        var showing: String? = null
            private set

        @Volatile
        private var shown: Pair<String, Long>? = null

        /** Whether the pop-up for [deviceId] came up at [since] or later (it may have been answered already). */
        fun shownSince(deviceId: String, since: Long): Boolean = shown?.let { it.first == deviceId && it.second >= since } == true
    }
}
