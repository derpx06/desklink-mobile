/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc.terminal

import org.desklink.mobile.transport.TransportCallback
import org.desklink.mobile.transport.TransportError
import org.desklink.mobile.transport.TransportErrorCode
import org.desklink.mobile.webrtc.SessionPassword
import org.desklink.mobile.webrtc.SessionPasswordDenial
import org.desklink.mobile.webrtc.WebRtcWireBinding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Outbound half of the data channel. Implemented by the WebRTC coordinator. */
fun interface TerminalSender {
    fun send(messageType: String, payload: ByteArray, callback: TransportCallback)
}

/**
 * Where one terminal's bytes end up: an emulator in production, a recorder in tests.
 * Called only on the client's executor, except [resize] which the UI may also call.
 */
interface TerminalSink {
    /** The host is repainting from scratch: its data starts with `ESC c`. Drop the old screen. */
    fun beginSnapshot()

    fun write(data: ByteArray)

    /**
     * While true, replies the emulator generates to queries in [write]n data must be dropped.
     * Replayed history contains queries the program already had answered, and a stale reply
     * typed into a shell shows up as garbage at the prompt.
     */
    fun setReplaying(replaying: Boolean)

    fun resize(rows: Int, cols: Int)

    /** The session ended on the desktop; [exitStatus] is null when it was closed rather than exited. */
    fun onEnded(exitStatus: Long?) {}
}

fun interface TerminalSinkFactory {
    /** [reply] carries the emulator's answers to queries back to the host as keystrokes. */
    fun create(terminalId: String, rows: Int, cols: Int, reply: (ByteArray) -> Unit): TerminalSink
}

enum class TerminalLinkState { DISCONNECTED, CONNECTED }

data class TerminalSessionState(
    val id: String,
    val title: String,
    val rows: Int,
    val cols: Int,
    /** Null while running. */
    val exitStatus: Long?,
    /** Receiving output on the current link. */
    val attached: Boolean,
    /** Open in the UI; re-attached automatically after a reconnect. */
    val wanted: Boolean,
    /** No longer exists on the desktop. */
    val gone: Boolean,
    /** Another phone has it open. */
    val attachedElsewhere: Boolean = false,
    /** The foreground process's folder, `~/…`. */
    val cwd: String? = null,
    /** Unix seconds. */
    val startedAt: Long? = null,
    /** "zsh". */
    val shell: String? = null,
)

data class TerminalClientState(
    val link: TerminalLinkState = TerminalLinkState.DISCONNECTED,
    val sessions: List<TerminalSessionState> = emptyList(),
    val listLoaded: Boolean = false,
    /** The desktop user has not enabled terminal access for this phone. */
    val notAllowed: Boolean = false,
    val error: String? = null,
    /** A session the phone just created; the UI opens it and calls [TerminalClient.consumeCreated]. */
    val createdId: String? = null,
    /** Agent CLIs the desktop can start, from [TerminalProtocol.KNOWN_LAUNCHERS]. */
    val launchers: List<String> = emptyList(),
    /** The desktop wants its session password before anything else; see [TerminalClient.unlock]. */
    val passwordNeeded: SessionPasswordDenial? = null,
    /** The person at the desk ended this phone's sessions; the UI says so, returns to the list and
     * calls [TerminalClient.consumeEnded]. */
    val endedByComputer: Boolean = false,
    /** Folders the desk offers for a new session ("~" first); empty from an older desk. */
    val folders: List<String> = emptyList(),
    /** Counts the desk's accepted lists: a list after a given one proves the password was taken. */
    val listSerial: Int = 0,
)

/** A long job in a session ended: what the desk's `finished` event says. */
data class TerminalJobFinished(val terminalId: String, val title: String, val job: String?, val secs: Long?)

/**
 * Per-device terminal state machine.
 *
 * Everything runs on the client's own single thread, so libwebrtc's callback thread and the
 * coordinator lock never wait on it, and every public method is a non-blocking enqueue that is
 * safe to call from any thread.
 *
 * Resync: the client tracks `nextOffset` per terminal, the position in the desktop's output
 * stream it has consumed. A reconnect (or a detected gap) re-attaches with that offset; the
 * desktop answers with the exact missing bytes, or with a snapshot when they have aged out of
 * its ring, in which case the local screen is reset. Duplicate and overlapping chunks are
 * trimmed by offset, so a replay can never render twice.
 */
class TerminalClient(
    private val sender: TerminalSender,
    private val sinkFactory: TerminalSinkFactory,
    private val executor: Executor = newExecutor("DeskLink-Terminal"),
    private val discard: (String, Throwable?) -> Unit = { _, _ -> },
    /** The device's session password, shared with its screen. Kept across reconnects: the desktop
     * asks once per connection, and the next one is offered it again. */
    private val password: AtomicReference<String?> = AtomicReference(),
    /** Called on the client thread for each `finished` event; the notifier decides whether to alert. */
    private val onJobFinished: (TerminalJobFinished) -> Unit = {},
) {
    private class Session(val id: String) {
        var info: TerminalSessionInfo? = null
        var wanted = false
        var attached = false

        /** Set while an attach is in flight; chunks that do not continue the stream are then dropped. */
        var awaitingAttach = false
        var nextOffset: Long? = null
        var snapshotNext = -1L
        var inSnapshot = false
        var viewRows = 0
        var viewCols = 0
        var gone = false

        /** A terminal screen is showing it, so its screen must outlive the session's end. */
        var displayed = false
    }

    private val _state = MutableStateFlow(TerminalClientState())
    val state: StateFlow<TerminalClientState> = _state.asStateFlow()

    // Confined to `executor`.
    private var wire: WebRtcWireBinding? = null
    private var linkUp = false
    private var listLoaded = false
    private var notAllowed = false
    private var error: String? = null
    private var createdId: String? = null
    private var launchers: List<String> = emptyList()
    private var passwordNeeded: SessionPasswordDenial? = null
    private var endedByComputer = false
    private var folders: List<String> = emptyList()
    private var listSerial = 0

    /**
     * A password from [unlock] that the desktop has not yet accepted. It goes out on one list only:
     * every request that carries a wrong password counts as a guess, so nothing else carries it
     * until the list comes back and proves it.
     */
    private var unproven: String? = null

    /** A create the desktop has not answered yet, re-sent once the password is given. */
    private var pendingCreate: TerminalControl? = null
    private val sessions = LinkedHashMap<String, Session>()
    private val pendingInput = LinkedHashMap<String, ByteArrayOutputStream>()
    private var pendingInputBytes = 0
    private var flushQueued = false

    /** Sinks are created on the UI thread as well (a view needs one before the first layout). */
    private val sinks = ConcurrentHashMap<String, TerminalSink>()
    private val inboundBytes = AtomicLong()

    // ---- coordinator entry points ----------------------------------------------------------

    /** The feature transport is ready on [wire]. Also called when the same peer recovers. */
    fun onLinkReady(wire: WebRtcWireBinding) = post {
        if (this.wire == wire && linkUp) return@post
        this.wire = wire
        linkUp = true
        sessions.values.forEach { it.attached = false; it.awaitingAttach = false }
        sendControl(TerminalAction.LIST)
        sessions.values.filter { it.wanted }.forEach(::sendAttach)
        publish()
    }

    /** The peer for [wire] is gone. Ignored if a newer peer has already taken over. */
    fun onLinkLost(wire: WebRtcWireBinding) = post {
        if (this.wire != wire) return@post
        markLinkDown()
    }

    fun onControl(wire: WebRtcWireBinding, payload: ByteArray) = post {
        if (this.wire != wire || !linkUp) return@post
        val control = try {
            TerminalControl.fromJson(payload).also { it.validateFromHost(wire) }
        } catch (invalid: IllegalArgumentException) {
            discard("Rejected terminal control", invalid)
            return@post
        }
        handleControl(control)
    }

    fun onChunk(wire: WebRtcWireBinding, payload: ByteArray) {
        // Bounded: if the phone cannot keep up, drop. The offset gap this leaves is detected and
        // repaired by a resync, which is why dropping is safe.
        if (inboundBytes.addAndGet(payload.size.toLong()) > MAX_INBOUND_BYTES) {
            inboundBytes.addAndGet(-payload.size.toLong())
            return
        }
        post {
            inboundBytes.addAndGet(-payload.size.toLong())
            if (this.wire != wire || !linkUp) return@post
            val chunk = try {
                TerminalChunk.decodeFromHost(payload)
            } catch (invalid: IllegalArgumentException) {
                discard("Rejected terminal chunk", invalid)
                return@post
            }
            handleChunk(chunk)
        }
    }

    // ---- UI API ------------------------------------------------------------------------------

    /** The screen for [id], created on first use. Safe from any thread. */
    fun sink(id: String, rows: Int, cols: Int): TerminalSink = sinks.computeIfAbsent(id) {
        sinkFactory.create(id, rows.coerceIn(MIN_ROWS, TerminalProtocol.MAX_DIMENSION), cols.coerceIn(MIN_COLS, TerminalProtocol.MAX_DIMENSION)) { bytes ->
            sendReply(id, bytes)
        }
    }

    /** The screen for [id] if one already exists, for read-only previews; never creates or attaches one. */
    fun screenIfOpen(id: String): TerminalSink? = sinks[id]

    fun refresh() = post { if (linkUp) sendControl(TerminalAction.LIST) }

    /**
     * Asks for a new session running the login shell, or [command] as an argument vector, in [cwd]
     * (absolute or `~/…`; the desk refuses a folder that is not there with `bad-cwd`).
     */
    fun create(rows: Int, cols: Int, command: List<String>? = null, cwd: String? = null) = post {
        if (!linkUp) return@post
        val create = control(TerminalAction.CREATE).copy(
            rows = rows.coerceIn(MIN_ROWS, TerminalProtocol.MAX_DIMENSION),
            cols = cols.coerceIn(MIN_COLS, TerminalProtocol.MAX_DIMENSION),
            command = command,
            cwd = cwd?.takeIf { TerminalControl.validCwd(it) },
        )
        pendingCreate = create
        sendControl(create)
    }

    /**
     * The person gave the desktop's session password. It is tried on the list alone; once the list
     * comes back the open sessions are attached again and a refused create is repeated.
     */
    fun unlock(password: String) = post {
        // A second tap while one is being tried would be a second guess.
        if (!linkUp || unproven != null || password.isEmpty() || !SessionPassword.fits(password)) return@post
        unproven = password
        passwordNeeded = null
        sendControl(control(TerminalAction.LIST).copy(password = password))
        publish()
    }

    /**
     * The user declined to enter the password: forget the session they were creating, so a later
     * unlock (to browse the list) does not start it behind their back.
     */
    fun cancelUnlock() = post {
        pendingCreate = null
    }

    /**
     * The terminal is on screen with the given cell grid: attach it if it is not, otherwise just
     * resize. Called on every layout change, so it must stay cheap when nothing changed.
     */
    fun attachOrResize(id: String, rows: Int, cols: Int) = post {
        val session = session(id)
        val r = rows.coerceIn(MIN_ROWS, TerminalProtocol.MAX_DIMENSION)
        val c = cols.coerceIn(MIN_COLS, TerminalProtocol.MAX_DIMENSION)
        val changed = session.viewRows != r || session.viewCols != c
        session.displayed = true
        session.viewRows = r
        session.viewCols = c
        if (!session.wanted) {
            session.wanted = true
            if (linkUp) sendAttach(session) else sinks[id]?.resize(r, c)
            publish()
        } else if (!session.awaitingAttach) {
            // While an attach is in flight the screen keeps the desktop's geometry, see sendAttach.
            sinks[id]?.resize(r, c)
            if (changed && session.attached) sendResize(session)
        }
    }

    /** Attaches again after a failed attach or a refusal that has since been lifted. */
    fun reattach(id: String) = post {
        val session = sessions[id] ?: return@post
        if (!linkUp || session.attached || session.awaitingAttach || session.gone) return@post
        session.wanted = true
        sendAttach(session)
        publish()
    }

    /** Stops output. The session keeps running and can be re-attached from its last offset. */
    fun detach(id: String) = post { detachNow(id) }

    fun detachAll() = post { sessions.keys.toList().forEach(::detachNow) }

    /**
     * The terminal screen for [id] went away (the user left it, not necessarily the session).
     * The session stays attached; only a session that no longer exists on the desktop is forgotten.
     */
    fun leave(id: String) = post {
        sessions[id]?.displayed = false
        purge()
    }

    /** Ends the session on the desktop. */
    fun close(id: String) = post {
        if (linkUp) sendControl(control(TerminalAction.CLOSE).copy(terminalId = id))
    }

    fun sendInput(id: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        post {
            val session = sessions[id]
            if (!linkUp || session == null || !session.attached) return@post
            if (pendingInputBytes + bytes.size > MAX_PENDING_INPUT_BYTES) {
                error = "Input dropped: the desktop is not keeping up"
                publish()
                return@post
            }
            pendingInput.getOrPut(id) { ByteArrayOutputStream() }.write(bytes, 0, bytes.size)
            pendingInputBytes += bytes.size
            if (!flushQueued) {
                flushQueued = true
                // Queued behind whatever input is already waiting: keystrokes typed while this
                // thread was busy leave together, an idle one leaves at once.
                executor.execute { guarded { flushInput() } }
            }
        }
    }

    /**
     * The emulator's answer to a terminal query in the output. Dropped while a window at the desk
     * shows the session: that window answers too, and the program would read the second answer as
     * typed keys. Keystrokes use [sendInput] and are never gated.
     */
    private fun sendReply(id: String, bytes: ByteArray) = post {
        if (sessions[id]?.info?.deskWindow != true) sendInput(id, bytes)
    }

    /** Stops the client's thread. Called when its device is unpaired or the coordinator closes. */
    fun shutdown() {
        (executor as? ExecutorService)?.shutdown()
    }

    fun consumeCreated() = post { if (createdId != null) { createdId = null; publish() } }

    fun clearError() = post { if (error != null) { error = null; publish() } }

    fun consumeEnded() = post { if (endedByComputer) { endedByComputer = false; publish() } }

    // ---- state machine (executor only) ----------------------------------------------------------

    private fun handleControl(control: TerminalControl) {
        when (control.action) {
            TerminalAction.SESSIONS -> {
                val unlocked = unproven != null
                unproven?.let(password::set)
                unproven = null
                val wasRefused = notAllowed || unlocked
                notAllowed = false
                passwordNeeded = null
                listLoaded = true
                listSerial++
                // Absent from an older desktop: it can start a shell only.
                launchers = control.launchers.orEmpty()
                folders = control.folders.orEmpty()
                val listed = control.sessions.orEmpty()
                val ids = listed.map { it.terminalId }.toSet()
                sessions.values.filter { it.id !in ids }.forEach { markGone(it) }
                listed.forEach { info ->
                    session(info.terminalId).apply {
                        this.info = info
                        gone = false
                    }
                }
                // The host's own order is the list's order.
                val order = listed.map { it.terminalId }
                val kept = LinkedHashMap<String, Session>()
                order.forEach { id -> sessions[id]?.let { kept[id] = it } }
                sessions.filterKeys { it !in kept }.forEach { (id, s) -> kept[id] = s }
                sessions.clear()
                sessions.putAll(kept)
                listed.filter { it.exitStatus != null }.forEach { sinks[it.terminalId]?.onEnded(it.exitStatus) }
                // The desk window took the size (it typed or resized last): draw at the size the
                // program now draws for. Typing here takes it back; the desktop does that.
                listed.forEach { info ->
                    if (sessions[info.terminalId]?.attached == true) sinks[info.terminalId]?.resize(info.rows, info.cols)
                }
                // Access was refused and is allowed again (the person retried or gave the password):
                // pick the open sessions back up from where they left off. One attempt per retry,
                // never a loop.
                if (wasRefused && linkUp) {
                    sessions.values.filter { it.wanted && !it.attached && !it.awaitingAttach && !it.gone }.forEach(::sendAttach)
                }
                if (unlocked && linkUp) pendingCreate?.let { sendControl(it.copy(password = password.get())) }
            }
            TerminalAction.CREATED -> {
                notAllowed = false
                passwordNeeded = null
                pendingCreate = null
                val id = control.terminalId!!
                session(id).apply {
                    info = info ?: TerminalSessionInfo(id, "", control.rows ?: MIN_ROWS, control.cols ?: MIN_COLS)
                    gone = false
                }
                createdId = id
            }
            TerminalAction.ATTACHED -> {
                notAllowed = false
                passwordNeeded = null
                val session = sessions[control.terminalId!!] ?: return
                session.attached = true
                session.awaitingAttach = false
                sinks[session.id]?.setReplaying(false)
                val rows = control.rows
                val cols = control.cols
                if (rows != null && cols != null) {
                    session.info = (session.info ?: TerminalSessionInfo(session.id, "", rows, cols))
                        .copy(rows = rows, cols = cols)
                }
                // Now the phone's geometry takes over. The resize is sent after the attach, which is
                // what claims the write lease: only the writer may resize.
                if (session.viewRows > 0) {
                    sinks[session.id]?.resize(session.viewRows, session.viewCols)
                    sendResize(session)
                }
            }
            TerminalAction.CLOSED -> {
                val session = sessions[control.terminalId!!] ?: return
                if (control.error == TerminalProtocol.ERROR_NOT_ALLOWED) {
                    // Access was revoked while attached. The session still runs on the desktop; this
                    // phone may no longer watch it. It stays open here and re-attaches once allowed.
                    notAllowed = true
                    session.attached = false
                    session.awaitingAttach = false
                    sinks[session.id]?.setReplaying(false)
                    purge()
                    publish()
                    return
                }
                if (control.error == TerminalProtocol.ERROR_TERMINAL_ENDED) {
                    // Ended from the desk: the session still runs there and access stands. It is only
                    // detached, and is not picked up again until the person opens it.
                    endedByComputer = true
                    session.wanted = false
                    session.attached = false
                    session.awaitingAttach = false
                    sinks[session.id]?.setReplaying(false)
                    purge()
                    publish()
                    return
                }
                session.wanted = false
                session.attached = false
                session.awaitingAttach = false
                if (control.exitStatus != null) {
                    session.info = session.info?.copy(exitStatus = control.exitStatus)
                    sinks[session.id]?.setReplaying(false)
                    sinks[session.id]?.onEnded(control.exitStatus)
                } else {
                    markGone(session)
                }
            }
            TerminalAction.ERROR -> handleError(control)
            TerminalAction.FINISHED -> {
                val id = control.terminalId!!
                onJobFinished(TerminalJobFinished(id, sessions[id]?.info?.title.orEmpty(), control.job, control.secs))
                return
            }
            else -> Unit // unreachable: validateFromHost refused client-authored actions
        }
        purge()
        publish()
    }

    private fun handleError(control: TerminalControl) {
        val message = control.error ?: "Unknown terminal error"
        if (message == TerminalProtocol.ERROR_NOT_ALLOWED) {
            notAllowed = true
            pendingCreate = null
            // Nothing will attach until the user enables it on the desktop.
            sessions.values.forEach {
                it.attached = false
                it.awaitingAttach = false
                sinks[it.id]?.setReplaying(false)
            }
            return
        }
        SessionPassword.denial(message)?.let { denial ->
            // The open session stays wanted and is attached again once the password is given.
            control.terminalId?.let { sessions[it] }?.takeIf { it.awaitingAttach }?.let { session ->
                session.awaitingAttach = false
                sinks[session.id]?.setReplaying(false)
            }
            // Not a refusal of terminal access: the desktop wants its session password first. A
            // request sent without it, answered while a password is being tried or after one was
            // refused, must not hide that answer.
            if (denial == SessionPasswordDenial.Required &&
                (unproven != null || passwordNeeded == SessionPasswordDenial.Wrong)
            ) {
                return
            }
            // A refused password is never offered again: each one counts as a guess.
            if (denial != SessionPasswordDenial.Required) {
                password.set(null)
                unproven = null
            }
            notAllowed = false
            passwordNeeded = denial
            return
        }
        if (message == TerminalProtocol.ERROR_TERMINAL_ENDED) {
            // Follows the `closed` for each session it detached: see what is still running.
            if (linkUp) sendControl(TerminalAction.LIST)
            return
        }
        error = message
        if (control.terminalId == null) pendingCreate = null
        // A failed attach must not leave the session waiting for output that will never come.
        control.terminalId?.let { id ->
            sessions[id]?.takeIf { it.awaitingAttach }?.let { session ->
                session.awaitingAttach = false
                session.wanted = false
                sinks[id]?.setReplaying(false)
                if (message == TerminalProtocol.ERROR_NO_SUCH_TERMINAL) {
                    markGone(session)
                } else if (linkUp) {
                    // Anything else (the desktop is backed up) says nothing about whether the session
                    // exists; the list settles that. It stays open and can be attached again.
                    sendControl(TerminalAction.LIST)
                }
            }
        }
    }

    private fun handleChunk(chunk: TerminalChunk) {
        val session = sessions[chunk.terminalId] ?: return
        val sink = sinks[chunk.terminalId] ?: return
        when (chunk.kind) {
            ChunkKind.OUTPUT -> {
                session.inSnapshot = false
                val next = session.nextOffset ?: 0L
                val end = chunk.offset + chunk.data.size
                when {
                    end <= next -> Unit // already seen
                    chunk.offset > next -> {
                        // Bytes are missing (dropped under load, or reordered around an attach).
                        if (!session.awaitingAttach) sendAttach(session)
                    }
                    else -> {
                        val skip = (next - chunk.offset).toInt()
                        sink.write(if (skip == 0) chunk.data else chunk.data.copyOfRange(skip, chunk.data.size))
                        session.nextOffset = end
                    }
                }
            }
            ChunkKind.SNAPSHOT -> {
                // Pieces of one snapshot carry consecutive offsets; the first one is the stream
                // position output resumes from, and it is the only one that names it.
                val continues = session.inSnapshot && chunk.offset == session.snapshotNext
                if (!continues) {
                    sink.beginSnapshot()
                    session.nextOffset = chunk.offset
                }
                session.inSnapshot = true
                session.snapshotNext = chunk.offset + chunk.data.size
                sink.write(chunk.data)
            }
            ChunkKind.INPUT -> Unit // refused by decodeFromHost
        }
    }

    private fun sendAttach(session: Session) {
        session.nextOffset = session.nextOffset ?: 0L
        session.awaitingAttach = true
        session.inSnapshot = false
        val sink = sinks[session.id]
        sink?.setReplaying(true)
        // The history and snapshot the desktop answers with are laid out for its geometry. Replaying
        // them into a differently sized screen wraps every long line, so the screen takes the
        // desktop's size first and only follows the phone's once the attach has completed.
        val host = session.info
        if (host != null) sink?.resize(host.rows, host.cols) else if (session.viewRows > 0) sink?.resize(session.viewRows, session.viewCols)
        sendControl(control(TerminalAction.ATTACH).copy(terminalId = session.id, offset = session.nextOffset))
    }

    private fun sendResize(session: Session) {
        // The desktop's geometry is now the phone's (this device holds the lease when it attaches),
        // which is what the next reconnect must lay its replay out for.
        session.info = session.info?.copy(rows = session.viewRows, cols = session.viewCols)
        sendControl(
            control(TerminalAction.RESIZE).copy(
                terminalId = session.id,
                rows = session.viewRows,
                cols = session.viewCols,
            ),
        )
    }

    private fun flushInput() {
        flushQueued = false
        val batches = pendingInput.entries.toList()
        pendingInput.clear()
        pendingInputBytes = 0
        for ((id, buffer) in batches) {
            val bytes = buffer.toByteArray()
            var index = 0
            while (index < bytes.size && linkUp) {
                val end = minOf(bytes.size, index + TerminalProtocol.MAX_CHUNK_BYTES)
                val chunk = TerminalChunk(ChunkKind.INPUT, id, 0, bytes.copyOfRange(index, end))
                sender.send(TerminalProtocol.CHUNK_MESSAGE_TYPE, chunk.encode(), failureHandler)
                index = end
            }
        }
    }

    private fun markGone(session: Session) {
        if (!session.gone) sinks[session.id]?.onEnded(null)
        session.gone = true
        session.wanted = false
        session.attached = false
        session.awaitingAttach = false
        session.info = null
        sinks[session.id]?.setReplaying(false)
    }

    /** Forgets sessions that are gone from the desktop and that no screen is showing any more. */
    private fun purge() {
        val forgotten = sessions.values.filter { it.gone && !it.displayed }.map { it.id }
        forgotten.forEach { id ->
            sessions.remove(id)
            sinks.remove(id)
        }
        if (forgotten.isNotEmpty()) publish()
    }

    private fun detachNow(id: String) {
        val session = sessions[id] ?: return
        if (!session.wanted && !session.attached) return
        session.wanted = false
        session.attached = false
        session.awaitingAttach = false
        if (linkUp) sendControl(control(TerminalAction.DETACH).copy(terminalId = id))
        publish()
    }

    private fun markLinkDown() {
        linkUp = false
        unproven = null
        pendingCreate = null
        sessions.values.forEach { it.attached = false; it.awaitingAttach = false }
        pendingInput.clear()
        pendingInputBytes = 0
        publish()
    }

    private fun session(id: String): Session {
        validateTerminalId(id)
        require(sessions.size < TerminalProtocol.MAX_SESSIONS || sessions.containsKey(id)) { "Too many terminal sessions" }
        return sessions.getOrPut(id) { Session(id) }
    }

    /** Leaving needs no password; everything else carries it while it is known. */
    private fun control(action: TerminalAction): TerminalControl =
        TerminalControl.create(action, requireNotNull(wire) { "No terminal link" })
            .copy(password = if (action == TerminalAction.DETACH) null else password.get())

    private fun sendControl(action: TerminalAction) = sendControl(control(action))

    private fun sendControl(control: TerminalControl) {
        sender.send(TerminalProtocol.CONTROL_MESSAGE_TYPE, control.toJson(), failureHandler)
    }

    /** A closed channel means the link is down; anything else is reported and the link stays up. */
    private val failureHandler = object : TransportCallback {
        override fun onSuccess() = Unit
        override fun onFailure(error: TransportError) {
            post {
                if (error.code == TransportErrorCode.CLOSED) {
                    if (linkUp) markLinkDown()
                } else {
                    this@TerminalClient.error = error.message
                    publish()
                }
            }
        }
    }

    private fun publish() {
        _state.value = TerminalClientState(
            link = if (linkUp) TerminalLinkState.CONNECTED else TerminalLinkState.DISCONNECTED,
            sessions = sessions.values.mapNotNull { session ->
                val info = session.info ?: if (session.wanted || session.gone) {
                    TerminalSessionInfo(session.id, "", session.viewRows, session.viewCols)
                } else {
                    return@mapNotNull null
                }
                TerminalSessionState(
                    id = session.id,
                    title = info.title,
                    rows = info.rows,
                    cols = info.cols,
                    exitStatus = info.exitStatus,
                    attached = session.attached,
                    wanted = session.wanted,
                    gone = session.gone,
                    attachedElsewhere = info.attached,
                    cwd = info.cwd,
                    startedAt = info.startedAt,
                    shell = info.shell,
                )
            },
            listLoaded = listLoaded,
            notAllowed = notAllowed,
            error = error,
            createdId = createdId,
            launchers = launchers,
            passwordNeeded = passwordNeeded,
            endedByComputer = endedByComputer,
            folders = folders,
            listSerial = listSerial,
        )
    }

    /** Runs [action] on the client thread; a failure is reported, never allowed to kill the thread. */
    private inline fun post(crossinline action: () -> Unit) {
        try {
            executor.execute { guarded { action() } }
        } catch (_: RejectedExecutionException) {
            // Shut down with its device; a screen still holding this client just goes quiet.
        }
    }

    private inline fun guarded(action: () -> Unit) {
        try {
            action()
        } catch (failure: Exception) {
            discard("Terminal client task failed", failure)
        }
    }

    companion object {
        /** Bytes of undelivered host output tolerated before chunks are dropped and resynced. */
        const val MAX_INBOUND_BYTES = 4L * 1024 * 1024

        /** Undelivered keystrokes tolerated before new ones are refused. */
        const val MAX_PENDING_INPUT_BYTES = 256 * 1024

        private const val MIN_ROWS = 2
        private const val MIN_COLS = 2

        fun newExecutor(name: String): Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, name).apply { isDaemon = true }
        }
    }
}
