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

    override fun beginSnapshot() {
        // A fresh emulator rather than the `ESC c` the snapshot starts with: RIS does not leave the
        // alternate buffer (a shell painted onto vim's buffer stays there after vim exits) and does
        // not clear the per-row wrap flags, so copied text would still join rows that are not one line.
        synchronized(lock) { emulator = newEmulator(emulator.mColumns, emulator.mRows) }
        listener?.onSnapshot()
    }

    override fun write(data: ByteArray) {
        if (data.isEmpty()) return
        synchronized(lock) { appendLocked(data) }
        listener?.onScreenChanged()
        observer?.onOutput(this)
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

    private fun appendLocked(data: ByteArray) {
        emulator.append(data, data.size)
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
    }
}
