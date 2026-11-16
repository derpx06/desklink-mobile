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
 * Words broken in the middle at a line end ("Canc|el", "Permis|sions"): every laid-out text of the merged tree whose
 * line ends between two letters. Paths, links and addresses (with `/`, `.` or `:`) may break anywhere and are skipped.
 */
object MidWord {
    /**
     * Shots allowed to break a word for now, all at font 2.0 on a 320 dp phone: long single words beside a switch or
     * a chevron ("Notifications", "screenshots") where only hyphenation would help; a test-only demo row; the desk
     * screen's waiting banner (DeskControls.kt, fixed after phase 3.5/3.6 merge).
     */
    val ACCEPTED = setOf(
        "huge-components-rows", "huge-components-tiles", "huge-fr-home-nudge", "huge-settings-files", "huge-settings-list",
        "huge-settings-notifications", "huge-w5-desk-waiting",
    )

    fun breaks(compose: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>): List<String> {
        val out = mutableListOf<String>()
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode) {
            val get = n.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult)
            if (get != null && n.layoutInfo.isPlaced) {
                val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                get.action?.invoke(results)
                for (layout in results) {
                    val text = layout.layoutInput.text.text
                    if (text.any { it == '/' || it == ':' } || Regex("""\w\.\w""").containsMatchIn(text)) continue
                    for (line in 0 until layout.lineCount - 1) {
                        val end = layout.getLineEnd(line)
                        if (end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()) {
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
        // Every word broken mid-word is listed in `midword.txt` beside the images, and fails the shot unless it is a
        // known one (MidWord.ACCEPTED).
        val broken = MidWord.breaks(compose)
        broken.forEach { File(ShotDir.dir, "midword.txt").appendText("$form-$name: $it\n") }
        check(broken.isEmpty() || "$form-$name" in MidWord.ACCEPTED) { "$form $name breaks a word in the middle:\n" + broken.joinToString("\n") }
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
