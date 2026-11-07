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
        /** The emulator changed. [settled]: a whole frame is in (the end of a synchronized update, a resize), so showing it now is safe. */
        fun onScreenChanged(settled: Boolean)
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
    private var scan = SCAN_TEXT
    private val stash = ByteArray(STASH_MAX)
    private var stashLen = 0
    private var stringBytes = 0

    override fun beginSnapshot() {
        synchronized(lock) {
            syncing = false
            syncGeneration++
            held.reset()
            scan = SCAN_TEXT
        }
        // A fresh emulator rather than the `ESC c` the snapshot starts with: RIS does not leave the
        // alternate buffer (a shell painted onto vim's buffer stays there after vim exits) and does
        // not clear the per-row wrap flags, so copied text would still join rows that are not one line.
        synchronized(lock) { emulator = newEmulator(emulator.mColumns, emulator.mRows) }
        listener?.onSnapshot()
    }

    override fun write(data: ByteArray) {
        if (data.isEmpty()) return
        val (changed, settled) = synchronized(lock) {
            filterLocked(data)
            (changedLocked to settledLocked).also { changedLocked = false; settledLocked = false }
        }
        // Not while a frame is being held (the screen is exactly what it was, a draw would be wasted), and
        // not while history is replayed: that is every frame the program drew, fast-forwarded. The
        // screen is shown once, when the replay ends.
        if (changed && !quiet) listener?.onScreenChanged(settled) else if (changed) replayed = true
        observer?.onOutput(this)
    }

    // Set by the code that feeds the emulator, read and cleared by `write`. Guarded by `lock`.
    private var changedLocked = false
    private var settledLocked = false

    private fun appendLocked(bytes: ByteArray, count: Int) {
        emulator.append(bytes, count)
        changedLocked = true
    }

    /**
     * The last [count] lines a person would see, trailing blank rows skipped: read row by row from the
     * bottom, so Home's every-second preview never builds the whole screen's text under the lock.
     */
    fun tailLines(count: Int): List<String> = synchronized(lock) {
        val out = ArrayDeque<String>()
        var r = emulator.mRows - 1
        while (r >= 0 && out.size < count) {
            val line = emulator.screen.getSelectedText(0, r, emulator.mColumns, r).trimEnd()
            if (out.isNotEmpty() || line.isNotEmpty()) out.addFirst(line)
            r--
        }
        out.toList()
    }

    /**
     * Cleans [data] on its way to the emulator, with state kept across chunks (a sequence may be cut
     * anywhere):
     *
     *  * synchronized output (`CSI ? 2026 h` / `l`) holds a frame until it is complete;
     *  * APC, PM and SOS strings (`ESC _`, `ESC ^`, `ESC X` ... ST) are dropped: Claude Code asks the kitty
     *    graphics protocol if it is there with one at start-up, and Termux prints its payload as text;
     *  * kitty keyboard protocol sequences with a `=` or `<` marker (`CSI = 5 ; 1 u`) are dropped for the
     *    same reason.
     *
     * Sets [changedLocked] when the emulator got bytes, and [settledLocked] when a synchronized frame ended
     * and nothing but whitespace or a cursor show/hide followed it in this chunk.
     */
    private fun filterLocked(data: ByteArray) {
        val out = ByteArray(data.size + STASH_MAX + 2)
        var n = 0
        fun put(b: Byte) { out[n++] = b }
        // Shown output after the end of a frame: the screen is no longer a whole frame.
        fun content() { if (!syncing) settledLocked = false }
        fun flushRun() {
            if (n == 0) return
            if (syncing) held.write(out, 0, n) else appendLocked(out.copyOf(n), n)
            n = 0
        }
        for (b in data) {
            when (scan) {
                SCAN_TEXT -> if (b == ESC) {
                    scan = SCAN_ESC
                } else {
                    put(b)
                    if (b != SPACE && b != CR && b != LF) content()
                }
                SCAN_ESC -> when (b) {
                    '_'.code.toByte(), '^'.code.toByte(), 'X'.code.toByte() -> { scan = SCAN_STRING; stringBytes = 0 }
                    '['.code.toByte() -> { scan = SCAN_CSI; stashLen = 0 }
                    ESC -> { put(ESC); content() } // the first ESC was a literal one; this one may start something
                    else -> { put(ESC); put(b); content(); scan = SCAN_TEXT }
                }
                SCAN_CSI -> {
                    val c = b.toInt() and 0xff
                    if (c in 0x20..0x3f && stashLen < STASH_MAX) {
                        stash[stashLen++] = b
                    } else if (c in 0x40..0x7e && stashLen < STASH_MAX) {
                        scan = SCAN_TEXT
                        val params = String(stash, 0, stashLen, Charsets.ISO_8859_1)
                        when {
                            c == 'h'.code && params == "?2026" -> { flushRun(); beginSyncLocked() }
                            c == 'l'.code && params == "?2026" -> { flushRun(); flushHeldLocked() }
                            // DECRQM: "is synchronized output supported?" Claude Code, neovim and helix draw
                            // each frame between `?2026h` and `l` only if told yes; Termux says "unknown".
                            c == 'p'.code && params == "?2026\$" -> {
                                if (!quiet) reply(("\u001b[?2026;" + (if (syncing) 1 else 2) + "\$y").toByteArray())
                            }
                            c == 'u'.code && (params.startsWith("=") || params.startsWith("<")) -> Unit
                            // Colon-separated SGR (`38:2::r:g:b`, `4:3`), which Termux prints as text.
                            c == 'm'.code && ':' in params -> {
                                content()
                                val plain = semicolonSgr(params)
                                if (plain.isNotEmpty()) {
                                    put(ESC); put('['.code.toByte())
                                    for (ch in plain) put(ch.code.toByte())
                                    put(b)
                                }
                            }
                            else -> {
                                if (params != "?25" || (c != 'h'.code && c != 'l'.code)) content()
                                put(ESC); put('['.code.toByte())
                                for (i in 0 until stashLen) put(stash[i])
                                put(b)
                            }
                        }
                    } else {
                        // Not a sequence we know the end of: give it to the emulator as it came.
                        scan = SCAN_TEXT
                        content()
                        put(ESC); put('['.code.toByte())
                        for (i in 0 until stashLen) put(stash[i])
                        if (b == ESC) scan = SCAN_ESC else put(b)
                    }
                }
                SCAN_STRING -> when {
                    b == ESC -> scan = SCAN_STRING_ESC
                    b == ST8 -> scan = SCAN_TEXT
                    ++stringBytes > MAX_STRING_BYTES -> scan = SCAN_TEXT
                }
                else -> { // SCAN_STRING_ESC
                    scan = if (b == '\\'.code.toByte()) SCAN_TEXT else if (b == ESC) SCAN_STRING_ESC else SCAN_STRING
                }
            }
        }
        flushRun()
        if (syncing && held.size() > MAX_HELD_BYTES) flushHeldLocked()
    }

    private fun beginSyncLocked() {
        if (syncing) return
        syncing = true
        val generation = ++syncGeneration
        // A program that dies mid-frame must not freeze the screen: show what there is after a moment.
        FLUSHER.schedule({
            val flushed = synchronized(lock) {
                if (syncing && syncGeneration == generation) {
                    flushHeldLocked()
                    changedLocked = false
                    settledLocked = false
                    true
                } else {
                    false
                }
            }
            // Not drawn mid-replay either: the end of the replay draws it.
            if (flushed) {
                if (quiet) replayed = true else listener?.onScreenChanged(true)
            }
        }, SYNC_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun flushHeldLocked() {
        if (!syncing) return
        syncing = false
        syncGeneration++
        if (held.size() > 0) {
            val bytes = held.toByteArray()
            held.reset()
            appendLocked(bytes, bytes.size)
        }
        settledLocked = true
    }

    override fun setReplaying(replaying: Boolean) {
        quiet = replaying
        if (!replaying && replayed) {
            replayed = false
            listener?.onScreenChanged(true)
        }
    }

    /** Output landed while [quiet]: the view has not been told. */
    @Volatile
    private var replayed = false

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
        if (changed) listener?.onScreenChanged(true)
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
        /**
         * SGR parameters with ISO 8613-6 colon sub-parameters, in the semicolon form Termux reads:
         * `38:2::r:g:b` and `38:2:r:g:b` become `38;2;r;g;b`, `38:5:n` `38;5;n`, `4:0` `24`, any
         * other underline style `4`. A colon form with no semicolon equivalent (underline colour,
         * `58`) is dropped.
         */
        fun semicolonSgr(params: String): String = params.split(';').mapNotNull { group ->
            if (':' !in group) return@mapNotNull group
            val sub = group.split(':')
            when (sub[0]) {
                "38", "48" -> when (sub.getOrNull(1)) {
                    "2" -> {
                        val rgb = sub.drop(2).takeLast(3)
                        if (rgb.size == 3) "${sub[0]};2;" + rgb.joinToString(";") { it.ifEmpty { "0" } } else null
                    }
                    "5" -> sub.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { "${sub[0]};5;$it" }
                    else -> null
                }
                "4" -> if (sub.getOrNull(1) == "0") "24" else "4"
                else -> null
            }
        }.joinToString(";")

        const val DEFAULT_TRANSCRIPT_ROWS = 5000
        private const val MIN_CELLS = 4
        private const val ESC: Byte = 0x1b
        private const val SPACE: Byte = 0x20
        private const val CR: Byte = 0x0d
        private const val LF: Byte = 0x0a
        private const val ST8: Byte = 0x9c.toByte()
        private const val SCAN_TEXT = 0
        private const val SCAN_ESC = 1
        private const val SCAN_CSI = 2
        private const val SCAN_STRING = 3
        private const val SCAN_STRING_ESC = 4
        private const val STASH_MAX = 48
        private const val MAX_STRING_BYTES = 1 shl 16
        private const val SYNC_TIMEOUT_MS = 150L
        private const val MAX_HELD_BYTES = 1 shl 20
        private val FLUSHER: java.util.concurrent.ScheduledExecutorService =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "DeskLink-TerminalSync").apply { isDaemon = true } }
    }
}
