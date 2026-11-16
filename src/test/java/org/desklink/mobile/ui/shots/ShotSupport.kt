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
 * What a screen reader or a thumb cannot use: a clickable (or switch, or tab) under 48 dp either way, or without a name
 * (its text or content description, merged from its children), and a text field with no label at all. Walks the merged tree.
 */
object A11y {
    const val MIN_DP = 48f

    fun violations(compose: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>, minDp: Float = MIN_DP): List<String> {
        val density = compose.activity.resources.displayMetrics.density
        val out = mutableListOf<String>()
        fun name(n: androidx.compose.ui.semantics.SemanticsNode): String {
            val c = n.config
            val texts = c.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text).orEmpty().map { it.text }
            val desc = c.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription).orEmpty()
            return (desc + texts).joinToString(" ").trim()
        }
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode) {
            val c = n.config
            val clickable = c.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick) != null
            val editable = c.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText) != null
            val shown = !c.contains(androidx.compose.ui.semantics.SemanticsProperties.InvisibleToUser) && n.layoutInfo.isPlaced && n.boundsInRoot.width > 0f
            if ((clickable || editable) && shown) {
                // The laid-out size, not the visible part: a row scrolled half out of view is still a full-size target.
                val w = n.size.width / density
                val h = n.size.height / density
                val label = name(n)
                val what = "\"$label\" ${"%.0f".format(w)}x${"%.0f".format(h)} dp at ${n.boundsInRoot.left.toInt()},${n.boundsInRoot.top.toInt()}"
                if (w < minDp - 0.5f || h < minDp - 0.5f) out += "touch target under $minDp dp: $what"
                if (label.isEmpty()) out += "no name: $what"
            }
            n.children.forEach(::walk)
        }
        compose.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().forEach(::walk)
        return out
    }
}

/**
 * Words cut where a person would read them wrong: a line that ends between two letters ("Canc|el", "Permis|sions"), and a
 * one-word label cut short ("Clipbo…"). Walks every laid-out text of the unmerged tree. A word with `/`, `:` or a dotted
 * name in it (a path, a link, an address) may break anywhere: only that word is skipped, not the rest of its text.
 */
object MidWord {
    /**
     * The breaks accepted for now, each as its whole `midword.txt` line: any other break, also a new one in the same
     * screenshot, fails the shot. A test-only demo sheet (four tiles across, a status line of three words); the desk
     * screen's name capsule, which shortens a long name on purpose at large text (TalkBack still reads it whole).
     */
    val ACCEPTED = setOf(
        "huge-components-rows: \"Failed\" breaks at \"F|ailed\"",
        "huge-components-rows: \"Failed\" breaks at \"Fa|iled\"",
        "huge-components-rows: \"Failed\" breaks at \"Fai|led\"",
        "huge-components-rows: \"Failed\" breaks at \"Fail|ed\"",
        "huge-components-rows: \"Failed\" breaks at \"Faile|d\"",
        "huge-components-tiles: \"Terminal\" breaks at \"Termin|al\"",
        "huge-components-tiles: \"Ring computer\" breaks at \"comput|er\"",
        "huge-components-tiles: \"Lock computer\" breaks at \"comput|er\"",
        "huge-components-tiles: \"Unlock arch-desk\" breaks at \"ch-des|k\"",
        "huge-w5-desk-view: \"arch-desk\" is cut short",
        "huge-w5-desk-granted: \"arch-desk\" is cut short",
        "huge-w5-desk-keys: \"arch-desk\" is cut short",
        "huge-w5-desk-waiting: \"arch-desk\" is cut short",
        "large-w5-desk-view: \"arch-desk\" is cut short",
        "large-w5-desk-granted: \"arch-desk\" is cut short",
        "large-w5-desk-keys: \"arch-desk\" is cut short",
        "large-w5-desk-waiting: \"arch-desk\" is cut short",
    )

    private fun loose(word: String) = word.any { it == '/' || it == ':' } || Regex("""\w\.\w""").containsMatchIn(word)

    fun breaks(compose: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>): List<String> {
        val out = mutableListOf<String>()
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode) {
            val get = n.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult)
            if (get != null && n.layoutInfo.isPlaced) {
                val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                get.action?.invoke(results)
                for (layout in results) {
                    val text = layout.layoutInput.text.text
                    // Ellipsized, not just taller than its box (hasVisualOverflow says that too, for a line height).
                    val ellipsized = layout.lineCount > 0 && layout.isLineEllipsized(layout.lineCount - 1)
                    if (ellipsized && text.isNotBlank() && text.none(Char::isWhitespace) && !loose(text)) {
                        out += "\"${text.take(60)}\" is cut short"
                    }
                    for (line in 0 until layout.lineCount - 1) {
                        val end = layout.getLineEnd(line)
                        if (end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()) {
                            val from = text.lastIndexOf(' ', end - 1) + 1
                            val to = text.indexOf(' ', end).let { if (it < 0) text.length else it }
                            if (loose(text.substring(from, to))) continue
                            out += "\"${text.take(60)}\" breaks at \"${text.substring(maxOf(0, end - 6), end)}|${text.substring(end, minOf(text.length, end + 6))}\""
                        }
                    }
                }
            }
            n.children.forEach(::walk)
        }
        compose.onAllNodes(androidx.compose.ui.test.isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        return out.distinct()
    }
}

/**
 * Renders a screen to `<name>-light.png` and `<name>-dark.png`, at the size of the test class's Robolectric
 * qualifiers (a phone class and a tablet class extend this). Debug and test builds only; no device needed.
 */
abstract class ShotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Part of every file name: the form factor, e.g. "phone" or "tablet". */
    abstract val form: String

    /** Lays the screen out right to left (a qualifier alone does not reach Compose under Robolectric). */
    open val rtl: Boolean = false

    fun shoot(name: String, content: @Composable () -> Unit) {
        // One composition, switched from light to dark in place (a rule can set its content only once).
        val dark = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            DeskLinkFixedTheme(compose.activity, dark.value) {
                val direction = if (rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides direction) { content() }
            }
        }
        compose.waitForIdle()
        val texts = compose.onAllNodes(androidx.compose.ui.test.isRoot(), useUnmergedTree = true).fetchSemanticsNodes().flatMap { root ->
            fun walk(n: androidx.compose.ui.semantics.SemanticsNode): List<String> =
                n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text).orEmpty().map { it.text } +
                    listOfNotNull(n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.joinToString(" ")) +
                    n.children.flatMap(::walk)
            walk(root)
        }
        val bad = A11y.violations(compose)
        check(bad.isEmpty()) { "$form $name is not accessible:\n" + bad.joinToString("\n") }
        texts.forEach { t -> check(!BLANK_NAME.containsMatchIn(t)) { "$form $name shows a blank where a name should be: \"$t\"" } }
        // Every word broken mid-word or cut short is listed in `midword.txt` beside the images, and fails the shot unless
        // that very line is a known one (MidWord.ACCEPTED).
        val broken = MidWord.breaks(compose)
        broken.forEach { File(ShotDir.dir, "midword.txt").appendText("$form-$name: $it\n") }
        val unexpected = broken.filter { "$form-$name: $it" !in MidWord.ACCEPTED }
        check(unexpected.isEmpty()) { "$form $name breaks a word in the middle:\n" + unexpected.joinToString("\n") }
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
