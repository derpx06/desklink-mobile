package org.desklink.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager

/**
 * Tells the desk this phone is about to go away (`desklink.heartbeat {leaving, reason}`, a state
 * report like `dozing`), so it checks the link at once instead of after its silence budget. Best
 * effort: the radio may be gone before the packet is. [report] never blocks its caller for more than
 * the bounded wait it is given (the main thread among them); the writes run on a thread of their own.
 *
 * Triggers: Wi-Fi switching off and airplane mode ([LeavingReceiver]), the service ending, the
 * task being removed, and the last non-cellular network being lost (all in `BackgroundService`).
 * Reports within [COALESCE_MS] of one another are one: Wi-Fi off often brings airplane mode, a
 * network loss and the service's end together.
 */
internal class LeavingReport(
    private val now: () -> Long,
    /** Runs [work] off the calling thread (a thread of its own; tests run it in place). */
    private val run: (Runnable) -> Unit = { work -> Thread(work, "desklink-leaving").apply { isDaemon = true }.start() },
    private val send: (reason: String) -> Unit,
) {
    private var last: Long? = null

    /**
     * Sends [reason] unless a report just went out, on a thread of its own. With [waitMs] > 0 the
     * caller waits at most that long for the write (the service's `onDestroy`, before it stops
     * anything: bounded, best effort, never an unbounded wait on the main thread). Whether it was sent.
     */
    fun report(reason: String, waitMs: Long = 0): Boolean {
        synchronized(this) {
            val at = now()
            if (last?.let { at - it < COALESCE_MS } == true) return false
            last = at
        }
        val done = java.util.concurrent.CountDownLatch(1)
        run(Runnable { try { send(reason) } finally { done.countDown() } })
        if (waitMs > 0) done.await(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        return true
    }

    companion object {
        const val COALESCE_MS = 2_000L

        /** `onDestroy` waits at most this long for the report to be written. */
        const val DESTROY_WAIT_MS = 200L

        /** The application's reporter: every paired device. Replaceable by a test. */
        @Volatile
        internal var shared: LeavingReport = LeavingReport(now = android.os.SystemClock::elapsedRealtime) { reason ->
            runCatching { DeskLinkApplication.getInstance().devices.values.forEach { it.sendLeaving(reason) } }
        }
    }
}

/** The `state` boolean extra of `ACTION_AIRPLANE_MODE_CHANGED` (`Intent.EXTRA_AIRPLANE_MODE` does not exist; the platform documents "state"). */
internal const val AIRPLANE_STATE_EXTRA = "state"

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
            intent.getBooleanExtra(AIRPLANE_STATE_EXTRA, false),
        ) ?: return
        LeavingReport.shared.report(reason)
    }
}
