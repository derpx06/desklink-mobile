/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.terminal

import kotlinx.coroutines.flow.update

/** What a terminal's recent output says about whether it needs the person. */
enum class TerminalAttention { NONE, WAITING, FINISHED }

private val ANSI = Regex("\u001b\\[[0-9;?]*[ -/]*[@-~]|\u001b\\][^\u0007\u001b]*(\u0007|\u001b\\\\)")

private fun ci(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

/** Prompts that block on the person. Checked against the last few lines only. */
private val WAITING_PATTERNS = listOf(
    ci("""[(\[]\s*y(es)?\s*/\s*n(o)?\s*[)\]]"""), // (y/n) [Y/n] [y/N] (yes/no)
    ci("""do you want to (proceed|continue|make this edit|create|run|allow|overwrite|apply|delete)"""), // Claude Code
    ci("""would you like to (proceed|continue|run|apply|allow|make)"""), // Codex
    ci("""allow (this )?(command|codex|claude|tool)\b.*\?"""),
    ci("""\bpress (enter|return|any key)\b"""),
    ci("""\bhit enter\b"""),
    ci("""(continue|proceed)\?\s*$"""),
    ci("""are you sure\b.*\?"""),
    ci("""^\s*[❯>›]?\s*1\.\s+yes\b"""), // an approval menu with its cursor on "1. Yes"
    ci("""\bno, and tell \w+ what to do"""), // Claude Code / Codex "3. No, and tell ... differently"
    ci("""(^|\s)(\[sudo\] )?(password|passphrase)( for [^:]+)?:\s*$"""),
)

/** Output that ends a long-running command. */
private val FINISHED_PATTERNS = listOf(
    ci("""\bbuild (successful|failed)\b"""),
    ci("""^\s*finished .*target\(s\) in\b"""), // cargo
    ci("""\btest result: (ok|failed)\b"""),
    ci("""\b\d+ (passed|failed)\b.* in [\d.]+s"""), // pytest
    ci("""\b(all done|task completed|process finished)\b"""),
)

private const val LINES_CONSIDERED = 14

/**
 * Classifies the visible tail of a terminal. Only the last [LINES_CONSIDERED] non-blank lines
 * count: a prompt that scrolled away is history, not a request. Waiting outranks finished, so a
 * build that ends by asking a question is reported as the question.
 */
fun classifyAttention(recentOutput: String): TerminalAttention {
    val tail = tailLines(recentOutput)
    if (tail.isEmpty()) return TerminalAttention.NONE
    if (tail.any { line -> WAITING_PATTERNS.any { it.containsMatchIn(line) } }) return TerminalAttention.WAITING
    if (tail.any { line -> FINISHED_PATTERNS.any { it.containsMatchIn(line) } }) return TerminalAttention.FINISHED
    return TerminalAttention.NONE
}

/**
 * Whether the waiting prompt is a typed y/n question, so a notification may offer Yes / No.
 * An arrow-key menu is deliberately not one: answering it with "n" and Enter would pick the
 * highlighted option, which is "Yes".
 */
fun offersYesNo(recentOutput: String): Boolean {
    val yesNo = ci("""[(\[]\s*y(es)?\s*/\s*n(o)?\s*[)\]]""")
    val menu = ci("""^\s*[❯>›]?\s*\d\.\s+\S""")
    val tail = tailLines(recentOutput)
    return tail.any { yesNo.containsMatchIn(it) } && tail.none { menu.containsMatchIn(it) }
}

/** The line to quote in a notification: the last non-blank one. */
fun lastLine(recentOutput: String): String = tailLines(recentOutput).lastOrNull()?.trim()?.take(120).orEmpty()

private fun tailLines(text: String): List<String> =
    ANSI.replace(text, "").lines().filter { it.isNotBlank() }.takeLast(LINES_CONSIDERED)

/**
 * Turns "the output settled" events into notifications, once per prompt.
 *
 * The same unchanged prompt seen again (a redraw, a cursor blink) does not notify twice, and a
 * prompt that was answered and later reappears does.
 */
class AttentionTracker {
    private var notifiedKey: String? = null

    /** The attention to raise for [screenTail] now, or NONE if there is nothing new. */
    fun onSettled(screenTail: String): TerminalAttention {
        val attention = classifyAttention(screenTail)
        if (attention == TerminalAttention.NONE) {
            notifiedKey = null
            return TerminalAttention.NONE
        }
        val key = attention.name + "|" + tailLines(screenTail).takeLast(4).joinToString("\n") { it.trim() }
        if (key == notifiedKey) return TerminalAttention.NONE
        notifiedKey = key
        return attention
    }

    /** The person answered or looked: the next identical prompt is new again. */
    fun reset() {
        notifiedKey = null
    }
}

/** Which terminal, if any, the person is looking at right now. Notifications skip it. */
object TerminalForeground {
    @Volatile
    var terminalId: String? = null
}

/**
 * Which computers have a terminal that finished or is waiting for the person while it was not on screen: the
 * coral dot on the Terminals tab. Raised where the notification is raised, cleared when the Terminals tab is
 * opened (or a terminal is).
 */
object TerminalAttentionBadge {
    private val devices = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val pending: kotlinx.coroutines.flow.StateFlow<Set<String>> get() = devices

    fun raise(deviceId: String) = devices.update { it + deviceId }
    fun clear(deviceId: String) = devices.update { it - deviceId }
    fun clearAll() { devices.value = emptySet() }
    fun has(deviceId: String): Boolean = deviceId in devices.value
}
