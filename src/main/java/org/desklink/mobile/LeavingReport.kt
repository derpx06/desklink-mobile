package org.desklink.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import org.desklink.mobile.helpers.ThreadHelper

/**
 * Tells the desk this phone is about to go away (`desklink.heartbeat {leaving, reason}`, a state
 * report like `dozing`), so it checks the link at once instead of after its silence budget. Best
 * effort: the radio may be gone before the packet is. [report] never blocks its caller (the main
 * thread among them); the writes run on the helper thread.
 *
 * Triggers: Wi-Fi switching off and airplane mode ([LeavingReceiver]), the service ending, the
 * task being removed, and the last non-cellular network being lost (all in `BackgroundService`).
 * Reports within [COALESCE_MS] of one another are one: Wi-Fi off often brings airplane mode, a
 * network loss and the service's end together.
 */
internal class LeavingReport(
    private val now: () -> Long,
    private val send: (reason: String) -> Unit,
) {
    private var last: Long? = null

    /** Sends [reason] unless a report just went out. Whether it was sent. */
    @Synchronized
    fun report(reason: String): Boolean {
        val at = now()
        if (last?.let { at - it < COALESCE_MS } == true) return false
        last = at
        send(reason)
        return true
    }

    companion object {
        const val COALESCE_MS = 2_000L

        /** The application's reporter: every paired device, off the calling thread. */
        val shared: LeavingReport by lazy {
            LeavingReport(now = android.os.SystemClock::elapsedRealtime) { reason ->
                ThreadHelper.execute {
                    runCatching { DeskLinkApplication.getInstance().devices.values.forEach { it.sendLeaving(reason) } }
                }
            }
        }
    }
}

/** Why this broadcast means the phone is leaving the network, or null when it does not. */
internal fun leavingReasonFor(action: String?, wifiState: Int, airplaneOn: Boolean): String? = when (action) {
    WifiManager.WIFI_STATE_CHANGED_ACTION -> "wifi-off".takeIf { wifiState == WifiManager.WIFI_STATE_DISABLING }
    Intent.ACTION_AIRPLANE_MODE_CHANGED -> "airplane".takeIf { airplaneOn }
    else -> null
}

/** Registered by `BackgroundService` for Wi-Fi state and airplane mode only (nothing else refreshes on them). */
internal class LeavingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = leavingReasonFor(
            intent.action,
            intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN),
            intent.getBooleanExtra("state", false),
        ) ?: return
        LeavingReport.shared.report(reason)
    }
}
