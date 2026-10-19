/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.plugins.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalAttentionTest {
    private val claudeApproval = """
        ╭──────────────────────────────────────────────╮
        │ Bash command                                 │
        │   npm test                                   │
        │ Do you want to proceed?                      │
        │ ❯ 1. Yes                                     │
        │   2. Yes, and don't ask again this session  │
        │   3. No, and tell Claude what to do differently (esc) │
        ╰──────────────────────────────────────────────╯
    """.trimIndent()

    @Test
    fun typedYesNoPromptsWait() {
        for (prompt in listOf(
            "Overwrite existing file? (y/n)",
            "Proceed [Y/n] ",
            "Continue? [y/N]",
            "Really delete? (yes/no)",
            "Install these packages? [Y/n]",
        )) {
            assertEquals(prompt, TerminalAttention.WAITING, classifyAttention("building...\n$prompt"))
        }
    }

    @Test
    fun aiCodingToolApprovalsWait() {
        assertEquals(TerminalAttention.WAITING, classifyAttention(claudeApproval))
        assertEquals(
            TerminalAttention.WAITING,
            classifyAttention("Would you like to run the following command?\n\n  \$ rm -rf build\n\n Yes, proceed (y)"),
        )
        assertEquals(TerminalAttention.WAITING, classifyAttention("Do you want to make this edit to src/main.rs?"))
        assertEquals(TerminalAttention.WAITING, classifyAttention("Allow command to run?"))
    }

    @Test
    fun pressEnterAndPasswordPromptsWait() {
        assertEquals(TerminalAttention.WAITING, classifyAttention("Setup complete.\nPress Enter to continue"))
        assertEquals(TerminalAttention.WAITING, classifyAttention("Press any key to exit"))
        assertEquals(TerminalAttention.WAITING, classifyAttention("[sudo] password for manas:"))
        assertEquals(TerminalAttention.WAITING, classifyAttention("Enter passphrase for key '/home/m/.ssh/id_ed25519': "))
        assertEquals(TerminalAttention.WAITING, classifyAttention("Are you sure you want to continue connecting (yes/no)?"))
    }

    @Test
    fun finishedCommandsAreRecognised() {
        for (line in listOf(
            "    Finished `dev` profile [unoptimized + debuginfo] target(s) in 3.21s",
            "BUILD SUCCESSFUL in 42s",
            "BUILD FAILED in 9s",
            "test result: ok. 12 passed; 0 failed",
            "===== 5 passed in 0.32s =====",
        )) {
            assertEquals(line, TerminalAttention.FINISHED, classifyAttention("compiling\n$line\nuser@host:~\$ "))
        }
    }

    @Test
    fun aQuestionOutranksAFinishedBuild() {
        assertEquals(
            TerminalAttention.WAITING,
            classifyAttention("BUILD SUCCESSFUL in 42s\nDeploy to production? [y/N]"),
        )
    }

    @Test
    fun ordinaryOutputIsNothing() {
        for (text in listOf(
            "",
            "   \n\n  ",
            "user@host:~\$ ",
            "Compiling desklink v0.1.0\nCompiling serde v1.0.228",
            "the answer is yes or no, then proceed with the plan",
            "Please continue reading the documentation",
            "drwxr-xr-x  2 manas manas 4096 Sep 26 03:40 build",
        )) {
            assertEquals(text, TerminalAttention.NONE, classifyAttention(text))
        }
    }

    @Test
    fun aPromptThatScrolledAwayIsHistory() {
        val output = "Overwrite? (y/n)\n" + (1..40).joinToString("\n") { "line $it of later output" }
        assertEquals(TerminalAttention.NONE, classifyAttention(output))
    }

    @Test
    fun escapeSequencesDoNotHideAPrompt() {
        assertEquals(
            TerminalAttention.WAITING,
            classifyAttention("\u001b[1m\u001b[33mDo you want to proceed?\u001b[0m\u001b[K"),
        )
        assertEquals(TerminalAttention.WAITING, classifyAttention("\u001b]0;title\u0007Delete it? (y/n)"))
    }

    @Test
    fun onlyATypedYesNoPromptOffersYesAndNo() {
        assertTrue(offersYesNo("Overwrite? (y/n)"))
        assertTrue(offersYesNo("Proceed [Y/n]"))
        // "n" then Enter on an arrow menu would choose the highlighted "Yes".
        assertFalse(offersYesNo(claudeApproval))
        assertFalse(offersYesNo("Press Enter to continue"))
        assertFalse(offersYesNo("[sudo] password for manas:"))
        assertFalse(offersYesNo("Pick one (y/n)\n 1. alpha\n 2. beta"))
    }

    @Test
    fun theNotificationQuotesTheLastLine() {
        assertEquals("Overwrite? (y/n)", lastLine("some output\n\nOverwrite? (y/n)   \n\n"))
        assertEquals("", lastLine("\n \n"))
        assertEquals(120, lastLine("x".repeat(500)).length)
    }

    @Test
    fun theTrackerNotifiesOncePerPrompt() {
        val tracker = AttentionTracker()
        val prompt = "building\nOverwrite? (y/n)"
        assertEquals(TerminalAttention.WAITING, tracker.onSettled(prompt))
        assertEquals("a redraw of the same prompt is not news", TerminalAttention.NONE, tracker.onSettled(prompt))
        assertEquals(TerminalAttention.NONE, tracker.onSettled("$prompt\n"))

        // Answered: the screen moves on, and the same question later is a new one.
        assertEquals(TerminalAttention.NONE, tracker.onSettled("user@host:~\$ "))
        assertEquals(TerminalAttention.WAITING, tracker.onSettled(prompt))

        // A different prompt straight after is also new.
        assertEquals(TerminalAttention.WAITING, tracker.onSettled("Delete everything? [y/N]"))
    }

    @Test
    fun theTrackerReportsFinishedOnceAndCanBeReset() {
        val tracker = AttentionTracker()
        assertEquals(TerminalAttention.FINISHED, tracker.onSettled("BUILD SUCCESSFUL in 4s"))
        assertEquals(TerminalAttention.NONE, tracker.onSettled("BUILD SUCCESSFUL in 4s"))
        tracker.reset()
        assertEquals(TerminalAttention.FINISHED, tracker.onSettled("BUILD SUCCESSFUL in 4s"))
    }
}
