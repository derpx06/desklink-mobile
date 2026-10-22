/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.folders

import kotlin.math.exp
import kotlin.math.roundToLong

/** How far one Bucket file has moved. */
data class Progress(
    val done: Long,
    val total: Long,
    /** Seconds left, or null while unknown (first seconds, stalled, unsteady). */
    val etaSeconds: Long?,
    /** Smoothed bytes a second (0 while unknown). */
    val bytesPerSecond: Long,
    /** The link dropped: it resumes from the checkpoint. */
    val paused: Boolean = false,
) {
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    val started: Boolean get() = done > 0
}

/** All files in flight together, for the Bucket header, the Home card and the notification. */
data class ProgressSummary(val files: Int, val done: Long, val total: Long, val etaSeconds: Long?, val bytesPerSecond: Long, val paused: Boolean) {
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * The speed of one transfer, smoothed (an exponentially weighted average with a ~3 s time
 * constant), and the time left from it. No estimate in the first [WARM_UP_MS], after [STALL_MS]
 * without bytes, or while the latest speed is far from the average.
 */
class SpeedMeter(private val startedAt: Long) {
    private var lastAt = startedAt
    private var lastBytes = 0L
    private var lastMoved = startedAt
    private var smoothed = 0.0
    private var instant = 0.0

    fun update(bytes: Long, now: Long) {
        val dt = now - lastAt
        if (dt <= 0) return
        val moved = (bytes - lastBytes).coerceAtLeast(0)
        instant = moved * 1000.0 / dt
        val alpha = 1 - exp(-dt.toDouble() / TIME_CONSTANT_MS)
        smoothed = if (smoothed == 0.0) instant else alpha * instant + (1 - alpha) * smoothed
        if (moved > 0) lastMoved = now
        lastAt = now
        lastBytes = bytes
    }

    fun bytesPerSecond(now: Long): Long = if (now - lastMoved > STALL_MS) 0 else smoothed.roundToLong()

    fun etaSeconds(remaining: Long, now: Long): Long? {
        if (now - startedAt < WARM_UP_MS || now - lastMoved > STALL_MS || smoothed <= 0) return null
        // Unsteady: the latest speed is off by more than 4x either way.
        if (instant > 0 && (instant > smoothed * UNSTEADY_FACTOR || instant * UNSTEADY_FACTOR < smoothed)) return null
        return (remaining / smoothed).roundToLong().coerceAtLeast(1)
    }

    companion object {
        const val TIME_CONSTANT_MS = 3_000.0
        const val WARM_UP_MS = 2_000L
        const val STALL_MS = 5_000L
        const val UNSTEADY_FACTOR = 4.0
    }
}

object BucketProgress {
    /** Screens redraw a file's progress at most this often (4 a second). */
    const val UPDATE_EVERY_MS = 250L

    fun summary(items: Collection<Progress>): ProgressSummary? {
        if (items.isEmpty()) return null
        val done = items.sumOf { it.done.coerceAtMost(it.total) }
        val total = items.sumOf { it.total }
        val speed = items.filter { !it.paused }.sumOf { it.bytesPerSecond }
        val known = items.filter { it.started && !it.paused }.let { moving -> moving.isNotEmpty() && moving.all { it.etaSeconds != null } }
        val eta = if (known && speed > 0) ((total - done).coerceAtLeast(0) / speed).coerceAtLeast(1) else null
        return ProgressSummary(items.size, done, total, eta, speed, items.all { it.paused })
    }

    /** Words for the numbers, so the formatting can be tested without resources. */
    data class Words(
        val ofTotal: String = "%1\$s of %2\$s",
        val joined: String = "%1\$s · %2\$s",
        val seconds: String = "%d s left",
        val minutes: String = "%d min left",
        val hours: String = "%1\$d h %2\$d min left",
        val waiting: String = "Waiting…",
        val paused: String = "Paused",
        val perSecond: String = "%s/s",
    )

    /** "340 MB of 1.2 GB · 2 min left", "Waiting…" before bytes move, "Paused" while the link is down. */
    fun line(p: Progress, size: (Long) -> String, w: Words = Words()): String = when {
        p.paused -> w.joined.format(w.paused, w.ofTotal.format(size(p.done), size(p.total)))
        !p.started -> w.waiting
        else -> {
            val amount = w.ofTotal.format(size(p.done), size(p.total))
            p.etaSeconds?.let { w.joined.format(amount, eta(it, w)) } ?: amount
        }
    }

    fun eta(seconds: Long, w: Words = Words()): String = when {
        seconds < 60 -> w.seconds.format(seconds.coerceAtLeast(1))
        seconds < 3600 -> w.minutes.format((seconds + 30) / 60)
        else -> w.hours.format(seconds / 3600, (seconds % 3600) / 60)
    }

    /** One file in flight, for the notification. */
    data class Flight(val name: String, val progress: Progress, val sending: Boolean)

    /** What the "Syncing Bucket" notification says. */
    data class Note(val title: String, val text: String, val lines: List<String>, val subText: String?, val fraction: Float)

    /** The notification's words (English defaults keep the builder testable). */
    data class NoteWords(
        val titleOne: String = "Syncing Bucket · 1 file",
        val titleMany: String = "Syncing Bucket · %d files",
        val about: String = "about %s",
        val line: String = "%1\$s — %2\$s",
        val percent: String = "%1\$s · %2\$d%%",
        val more: String = "+%d more",
        val paused: String = "Paused · waiting for %s",
        val sending: String = "Sending to %s",
        val receiving: String = "Receiving from %s",
        val both: String = "Sending to and receiving from %s",
        val doneOne: String = "Bucket up to date · 1 file synced",
        val doneMany: String = "Bucket up to date · %d files synced",
    )

    /** Lines in the expanded notification (then "+N more"). */
    const val MAX_NOTE_LINES = 5

    /**
     * "Syncing Bucket · 3 files", "1.1 GB of 2.4 GB · 12 MB/s · about 4 min left", one line per file
     * (moving ones first: "video.mp4 — 340 MB of 1.2 GB · 62%", then "Waiting…"), and the direction.
     */
    fun note(desk: String, flights: List<Flight>, linkDown: Boolean, size: (Long) -> String, w: Words = Words(), n: NoteWords = NoteWords()): Note? {
        val sum = summary(flights.map { it.progress }) ?: return null
        val title = if (sum.files == 1) n.titleOne else n.titleMany.format(sum.files)
        val text = if (linkDown) {
            n.paused.format(desk)
        } else {
            listOfNotNull(
                w.ofTotal.format(size(sum.done), size(sum.total)),
                sum.bytesPerSecond.takeIf { it > 0 }?.let { w.perSecond.format(size(it)) },
                sum.etaSeconds?.let { n.about.format(eta(it, w)) },
            ).joinToString(" · ")
        }
        val ordered = flights.sortedWith(compareByDescending<Flight> { it.progress.started }.thenByDescending { it.progress.fraction })
        val lines = ordered.take(MAX_NOTE_LINES).map { f ->
            val state = if (f.progress.started) n.percent.format(w.ofTotal.format(size(f.progress.done), size(f.progress.total)), (f.progress.fraction * 100).toInt()) else w.waiting
            n.line.format(f.name, state)
        } + listOfNotNull((ordered.size - MAX_NOTE_LINES).takeIf { it > 0 }?.let { n.more.format(it) })
        val sending = flights.any { it.sending }
        val receiving = flights.any { !it.sending }
        val direction = when {
            sending && receiving -> n.both
            sending -> n.sending
            else -> n.receiving
        }.format(desk)
        return Note(title, text, lines, direction, sum.fraction)
    }

    fun doneText(files: Int, n: NoteWords = NoteWords()) = if (files == 1) n.doneOne else n.doneMany.format(files)
}
