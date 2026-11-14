/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The rule (BlockedActions): a blocked feature shows one action that does it, never only instructions. Any English string that
 * reads like a manual step must be listed with the action shown beside it; a string listed but gone or reworded is listed no more.
 */
class BlockedActionsTest {
    private val step = Regex(
        """Settings,|Settings →|Settings ->|\b[Tt]urn (it |that |them )?on\b|[Oo]pen DeskLink|\b[Gg]o to (Settings|the|DeskLink|Android)|""" +
            """in DeskLink on|in DeskLink\\'s settings|\b[Ii]n Settings\b|\b[Ii]n Android settings\b""",
    )

    private fun strings(): Map<String, String> {
        val dir = File("src/main/res/values").takeIf { it.isDirectory } ?: File("desklink-mobile/src/main/res/values")
        return dir.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }!!.flatMap { file ->
            Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(file.readText()).map { it.groupValues[1] to it.groupValues[2] }.toList()
        }.toMap()
    }

    @Test
    fun everyStringThatNamesAStepHasItsActionListed() {
        val unlisted = strings().filter { (name, text) -> step.containsMatchIn(text) && name !in BlockedActions.STRINGS }
        assertTrue(
            "These say what to do by hand with no action beside them: add the button and list it in BlockedActions, or say what the " +
                "button does instead:\n" + unlisted.entries.joinToString("\n") { "${it.key}: ${it.value.take(140)}" },
            unlisted.isEmpty(),
        )
    }

    @Test
    fun theListHasNoStaleEntries() {
        val all = strings()
        val stale = BlockedActions.STRINGS.keys.filter { it !in all || !step.containsMatchIn(all.getValue(it)) }
        assertTrue("Listed but gone or no longer a step: $stale", stale.isEmpty())
        assertTrue(BlockedActions.STRINGS.values.all { it.isNotBlank() })
    }

    @Test
    fun everyComputerSideBlockAsksForAKeyTheComputerKnows() {
        val toDesk = org.desklink.mobile.plugins.desksettings.Asks.TO_DESK
        assertTrue(BlockedActions.ASK_ON_COMPUTER.values.all { it in toDesk })
        assertTrue("every toDesk key has its place on the phone", toDesk.all { it in BlockedActions.ASK_ON_COMPUTER.values })
        // Each place really shows the button: the key appears in an AskOnComputer call or an askDesk call in the code.
        val root = File("src/main/java").takeIf { it.isDirectory } ?: File("desklink-mobile/src/main/java")
        val code = root.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        toDesk.forEach { key -> assertTrue("$key has an Ask on computer", Regex("""AskOnComputer\([^)]*"$key"|askDesk\((ASK_UNLOCK|"$key")""").containsMatchIn(code) || (key == "unlockNearby" && code.contains("ASK_UNLOCK = \"unlockNearby\""))) }
    }
}
