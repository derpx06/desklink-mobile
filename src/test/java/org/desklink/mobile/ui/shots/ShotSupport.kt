/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.shots

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.desklink.mobile.ui.compose.DeskLinkFixedTheme
import org.junit.Rule
import java.io.File
import java.io.FileOutputStream

/** Where the screenshot harness writes: `~/.cache/android-ui/shots/` unless `-Ddesklink.shots=<dir>` is given. */
object ShotDir {
    val dir: File by lazy {
        File(System.getProperty("desklink.shots") ?: (System.getProperty("user.home") + "/.cache/android-ui/shots")).also { it.mkdirs() }
    }
}

/** Text that gives away a blank where a name should be ("appear on , and", "on ."). */
val BLANK_NAME = Regex("""\s[,.;:!?]|,\s*,|%\d?\$?[sd]""")

/**
 * Renders a screen to `<name>-light.png` and `<name>-dark.png`, at the size of the test class's Robolectric
 * qualifiers (a phone class and a tablet class extend this). Debug and test builds only; no device needed.
 */
abstract class ShotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Part of every file name: the form factor, e.g. "phone" or "tablet". */
    abstract val form: String

    fun shoot(name: String, content: @Composable () -> Unit) {
        // One composition, switched from light to dark in place (a rule can set its content only once).
        val dark = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent { DeskLinkFixedTheme(compose.activity, dark.value) { content() } }
        compose.waitForIdle()
        val texts = compose.onAllNodes(androidx.compose.ui.test.isRoot(), useUnmergedTree = true).fetchSemanticsNodes().flatMap { root ->
            fun walk(n: androidx.compose.ui.semantics.SemanticsNode): List<String> =
                n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text).orEmpty().map { it.text } +
                    listOfNotNull(n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.joinToString(" ")) +
                    n.children.flatMap(::walk)
            walk(root)
        }
        texts.forEach { t -> check(!BLANK_NAME.containsMatchIn(t)) { "$form $name shows a blank where a name should be: \"$t\"" } }
        for (isDark in listOf(false, true)) {
            dark.value = isDark
            compose.waitForIdle()
            val view = compose.activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val out = File(ShotDir.dir, "$form-$name-${if (isDark) "dark" else "light"}.png")
            FileOutputStream(out).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
