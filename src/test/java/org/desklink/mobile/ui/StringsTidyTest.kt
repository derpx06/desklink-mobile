/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The English resources are tidy: no stray quote or text inside a plural, no space before a colon, one spelling of Wi-Fi and cancelled. */
class StringsTidyTest {
    private val files = File("src/main/res/values").listFiles { file -> file.name.startsWith("strings") && file.extension == "xml" }!!.toList()

    private fun problems(check: (String, String) -> Boolean): List<String> = files.flatMap { file ->
        file.readLines().mapIndexedNotNull { index, line -> if (check(file.name, line)) "${file.name}:${index + 1}: ${line.trim()}" else null }
    }

    @Test fun noStrayQuoteEndsAString() = assertEquals(emptyList<String>(), problems { _, line -> Regex("""[^\\=>]"</(?:string|item)>""").containsMatchIn(line) && !line.contains("=\"") })

    @Test fun noTextSitsDirectlyInsideAPlurals() = assertEquals(emptyList<String>(), problems { _, line -> Regex("""<plurals[^>]*>[^<\s]""").containsMatchIn(line) })

    @Test fun noSpaceBeforeAColonInShownText() = assertEquals(emptyList<String>(), problems { _, line -> Regex(""">[^<]* :\s[^<]*<""").containsMatchIn(line) })

    @Test fun oneSpellingOfWiFiAndCancelled() = assertEquals(
        emptyList<String>(),
        problems { _, line -> Regex(""">[^<]*(?:WiFi|Wifi|Wi‑Fi|[Cc]anceled)""").containsMatchIn(line) },
    )
}
