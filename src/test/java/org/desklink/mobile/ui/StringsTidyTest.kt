/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The English resources are tidy: no stray quote or text inside a plural, no space before a colon, one spelling of Wi-Fi and cancelled. */
class StringsTidyTest {
    // The English resources: the default and British ones.
    private val files = listOf("src/main/res/values", "src/main/res/values-en-rGB").map(::File).filter(File::isDirectory)
        .flatMap { dir -> dir.listFiles { file -> file.name.startsWith("strings") && file.extension == "xml" }!!.toList() }

    private fun problems(check: (String, String) -> Boolean): List<String> = files.flatMap { file ->
        file.readLines().mapIndexedNotNull { index, line -> if (check(file.name, line)) "${file.name}:${index + 1}: ${line.trim()}" else null }
    }

    /** An unescaped quote in a string's text is dropped by aapt, or ends it early: only \" belongs there. */
    @Test fun noStrayQuoteInAStringsText() = assertEquals(
        emptyList<String>(),
        problems { _, line -> Regex(""">([^<]*)</(?:string|item)>""").find(line)?.groupValues?.get(1)?.let { Regex("""(?<!\\)"""").containsMatchIn(it) } == true },
    )

    @Test fun noTextSitsDirectlyInsideAPlurals() = assertEquals(emptyList<String>(), problems { _, line -> Regex("""<plurals[^>]*>[^<\s]""").containsMatchIn(line) })

    @Test fun noSpaceBeforeAColonInShownText() = assertEquals(emptyList<String>(), problems { _, line -> Regex(""">[^<]* :\s[^<]*<""").containsMatchIn(line) })

    @Test fun oneSpellingOfWiFiAndCancelled() = assertEquals(
        emptyList<String>(),
        problems { _, line -> Regex(""">[^<]*(?:WiFi|Wifi|Wi‑Fi|[Cc]anceled)""").containsMatchIn(line) },
    )

    /** The two sides are "phone" and "computer": no "remote device" or "the desktop" in what a person reads. */
    @Test fun noRemoteDeviceOrDesktopInShownText() = assertEquals(
        emptyList<String>(),
        problems { _, line -> Regex(""">[^<]*(?:[Rr]emote device|[Tt]he desktop|[Oo]ther device)""").containsMatchIn(line) },
    )
}
