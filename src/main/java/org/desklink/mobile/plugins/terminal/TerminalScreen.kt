/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.terminal

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import org.desklink.mobile.webrtc.terminal.TerminalSink

/**
 * One terminal's screen: a Termux [TerminalEmulator] fed with the desktop's bytes.
 *
 * Engine route: Termux's `terminal-emulator` (Apache-2.0) is used unmodified. Its stock
 * `TerminalView` is welded to a local PTY [TerminalSession], which does not exist here, so the
 * emulator is built directly with this class as its [TerminalOutput] and drawn by our own view.
 *
 * Lives in application scope (owned by the terminal client), not in the activity, so a rotation
 * keeps the whole screen and scrollback. The emulator is not thread-safe: the client thread
 * writes, the UI thread draws, both under [lock].
 */
class TerminalScreen(
    rows: Int,
    cols: Int,
    private val reply: (ByteArray) -> Unit,
    private val observer: Observer? = null,
    private val transcriptRows: Int = DEFAULT_TRANSCRIPT_ROWS,
) : TerminalSink {
    /** Called on the client thread after output landed. Must not block. */
    interface Observer {
        fun onOutput(screen: TerminalScreen)
        fun onEnded(screen: TerminalScreen, exitStatus: Long?)
    }

    /** Called on the client thread, so a UI implementation must post. */
    interface Listener {
        fun onScreenChanged()
        fun onSnapshot()
        fun onBell()
    }

    val lock = Any()

    @Volatile
    var listener: Listener? = null

    /** The terminal title the program set (OSC 0/2), or null. */
    @Volatile
    var title: String? = null
        private set

    /** Set while replayed history is being fed: the emulator's replies to queries in it are dropped. */
    @Volatile
    private var quiet = false

    private val output = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {
            if (!quiet && count > 0) reply(data.copyOfRange(offset, offset + count))
        }

        override fun titleChanged(oldTitle: String?, newTitle: String?) {
            title = newTitle
        }

        // OSC 52 lets a program on the desktop write the phone's clipboard, or ask to read it.
        // Both are refused: a `cat` of the wrong file must not be able to do either.
        override fun onCopyTextToClipboard(text: String?) = Unit

        override fun onPasteTextFromClipboard() = Unit

        override fun onBell() {
            listener?.onBell()
        }

        override fun onColorsChanged() = Unit
    }

    // Guarded by `lock`. Replaced wholesale by a snapshot, never held outside `withEmulator`.
    private var emulator = newEmulator(cols, rows)

    private fun newEmulator(cols: Int, rows: Int) =
        TerminalEmulator(output, cols, rows, transcriptRows, QuietSessionClient)

    val rows: Int get() = synchronized(lock) { emulator.mRows }
    val columns: Int get() = synchronized(lock) { emulator.mColumns }

    /** Runs [block] with the emulator, under the lock the writer thread also takes. */
    fun <T> withEmulator(block: (TerminalEmulator) -> T): T = synchronized(lock) { block(emulator) }

    // Synchronized output (DEC mode 2026, which Termux ignores): between `CSI ? 2026 h` and `l` the
    // program is drawing one frame; showing it half done is the tearing Codex and Claude Code avoid
    // by asking for it. Guarded by `lock`.
    private var syncing = false
    private var syncGeneration = 0
    private val held = java.io.ByteArrayOutputStream()
    private var carry = ByteArray(0)

    override fun beginSnapshot() {
        synchronized(lock) {
            syncing = false
            syncGeneration++
            held.reset()
            carry = ByteArray(0)
        }
        // A fresh emulator rather than the `ESC c` the snapshot starts with: RIS does not leave the
        // alternate buffer (a shell painted onto vim's buffer stays there after vim exits) and does
        // not clear the per-row wrap flags, so copied text would still join rows that are not one line.
        synchronized(lock) { emulator = newEmulator(emulator.mColumns, emulator.mRows) }
        listener?.onSnapshot()
    }

    override fun write(data: ByteArray) {
        if (data.isEmpty()) return
        synchronized(lock) { filterLocked(data) }
        listener?.onScreenChanged()
        observer?.onOutput(this)
    }

    /** Splits [data] at the synchronized-output markers: a frame is held back until it is complete. */
    private fun filterLocked(data: ByteArray) {
        val buf = if (carry.isEmpty()) data else carry + data
        carry = ByteArray(0)
        var segment = 0
        var i = 0
        while (i < buf.size) {
            if (buf[i] != ESC) {
                i++
                continue
            }
            when (val marker = markerAt(buf, i)) {
                MARKER_PARTIAL -> {
                    emitLocked(buf, segment, i)
                    carry = buf.copyOfRange(i, buf.size)
                    return
                }
                MARKER_NONE -> i++
                else -> {
                    emitLocked(buf, segment, i)
                    i += SYNC_BEGIN.size
                    segment = i
                    if (marker == MARKER_BEGIN) beginSyncLocked() else flushHeldLocked()
                }
            }
        }
        emitLocked(buf, segment, buf.size)
        if (syncing && held.size() > MAX_HELD_BYTES) flushHeldLocked()
    }

    private fun emitLocked(buf: ByteArray, from: Int, to: Int) {
        if (to <= from) return
        if (syncing) held.write(buf, from, to - from) else emulator.append(buf.copyOfRange(from, to), to - from)
    }

    private fun beginSyncLocked() {
        if (syncing) return
        syncing = true
        val generation = ++syncGeneration
        // A program that dies mid-frame must not freeze the screen: show what there is after a moment.
        FLUSHER.schedule({
            val flushed = synchronized(lock) { if (syncing && syncGeneration == generation) { flushHeldLocked(); true } else false }
            if (flushed) listener?.onScreenChanged()
        }, SYNC_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun flushHeldLocked() {
        if (!syncing) return
        syncing = false
        syncGeneration++
        if (held.size() > 0) {
            val bytes = held.toByteArray()
            held.reset()
            emulator.append(bytes, bytes.size)
        }
    }

    /** Whether the bytes at [at] are a synchronized-output marker, the start of one cut by the chunk end, or neither. */
    private fun markerAt(buf: ByteArray, at: Int): Int {
        val available = buf.size - at
        for ((marker, pattern) in listOf(MARKER_BEGIN to SYNC_BEGIN, MARKER_END to SYNC_END)) {
            val n = minOf(available, pattern.size)
            var same = true
            for (k in 0 until n) if (buf[at + k] != pattern[k]) { same = false; break }
            if (same) return if (available >= pattern.size) marker else MARKER_PARTIAL
        }
        return MARKER_NONE
    }

    override fun setReplaying(replaying: Boolean) {
        quiet = replaying
    }

    override fun resize(rows: Int, cols: Int) {
        val changed = synchronized(lock) {
            val r = rows.coerceAtLeast(MIN_CELLS)
            val c = cols.coerceAtLeast(MIN_CELLS)
            if (r == emulator.mRows && c == emulator.mColumns) {
                false
            } else {
                emulator.resize(c, r)
                true
            }
        }
        if (changed) listener?.onScreenChanged()
    }

    override fun onEnded(exitStatus: Long?) {
        observer?.onEnded(this, exitStatus)
    }

    /** All text, scrollback included, for tests and copy-all. */
    fun transcript(): String = synchronized(lock) { emulator.screen.transcriptText }

    /** The visible screen's last [lastRows] rows as plain text; what a person would see. */
    fun tailText(lastRows: Int): String = synchronized(lock) {
        val screenRows = emulator.mRows
        emulator.screen.getSelectedText(0, (screenRows - lastRows).coerceAtLeast(0), emulator.mColumns, screenRows - 1)
    }

    /**
     * The emulator wants a client for logging and the cursor style; there is no local session.
     * Every method is a no-op, so no android.util.Log call can be reached from a JVM test.
     */
    private object QuietSessionClient : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) = Unit
        override fun onTitleChanged(changedSession: TerminalSession) = Unit
        override fun onSessionFinished(finishedSession: TerminalSession) = Unit
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) = Unit
        override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }

    companion object {
        const val DEFAULT_TRANSCRIPT_ROWS = 5000
        private const val MIN_CELLS = 4
        private const val ESC: Byte = 0x1b
        private val SYNC_BEGIN = "\u001b[?2026h".toByteArray()
        private val SYNC_END = "\u001b[?2026l".toByteArray()
        private const val MARKER_NONE = 0
        private const val MARKER_BEGIN = 1
        private const val MARKER_END = 2
        private const val MARKER_PARTIAL = 3
        private const val SYNC_TIMEOUT_MS = 150L
        private const val MAX_HELD_BYTES = 1 shl 20
        private val FLUSHER: java.util.concurrent.ScheduledExecutorService =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "DeskLink-TerminalSync").apply { isDaemon = true } }
    }
}
