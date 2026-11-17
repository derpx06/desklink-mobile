/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.update

import java.net.URI

/**
 * The update system's data (docs/UPDATES.md section 2). Leaf module: nothing in pairing, transport, WebRTC, relay or the
 * plugins calls into `update`, and `update` calls none of them. Pure types here; I/O lives in UpdateChecker/UpdateStore.
 */
internal data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
    override fun compareTo(other: Version) = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val STRICT = Regex("""(0|[1-9][0-9]{0,9})\.(0|[1-9][0-9]{0,9})\.(0|[1-9][0-9]{0,9})""")

        /** Strict MAJOR.MINOR.PATCH: ASCII digits, no leading zero, three parts, each at most 2147483647. Anything else is null. */
        fun parse(s: String?): Version? {
            if (s == null || !STRICT.matches(s)) return null
            val p = s.split('.').map { it.toLong() }
            return if (p.any { it > Int.MAX_VALUE }) null else Version(p[0].toInt(), p[1].toInt(), p[2].toInt())
        }

        /** The running app's own version ("0.3.0", "0.3.0-debug", "0.3.0-rc1"): the numeric head, else null. */
        fun parseLenient(s: String?): Version? = parse(s?.takeWhile { it in '0'..'9' || it == '.' }) // ASCII only: Char.isDigit() accepts other scripts
    }
}

internal enum class Channel(val key: String) {
    BETA("beta"), STABLE("stable");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

/** One of the three rows of the sheet. [glyph] is one of [Component.GLYPHS]; unknown falls back to `star`. */
internal data class Note(val title: String, val detail: String?, val glyph: String)

internal data class Component(
    val version: Version,
    val versionCode: Int,
    val released: String?,
    val minSupported: Version,
    val requiredReason: String?,
    val rollout: Int,
    val notes: List<Note>,
    val notesUrl: String?,
    val size: Long?,
    val apkUrl: String?,
    val apkSha256: String?,
    val releaseUrl: String?,
) {
    companion object {
        val GLYPHS = setOf("refresh", "swap", "check", "files", "terminal", "speed", "shield", "star")
        const val MAX_NOTES = 8
        const val MAX_TITLE = 40
        const val MAX_DETAIL = 80
        const val MAX_REASON = 160
    }
}

/** A verified payload, Android component only. A channel that is missing, empty or null is `null` here: nothing to offer. */
internal data class Payload(val issuedAt: Long, val expires: Long, val channels: Map<Channel, Component?>)

/** Why a manifest or a fetch was discarded. Codes only, no server text or URL (section 4.2). */
internal enum class FailReason(val code: String) {
    OFFLINE("offline"), TIMEOUT("timeout"), HTTP("http"), OVERSIZED("oversized"), PARSE("parse"),
    SIGNATURE("signature"), EXPIRED("expired"), REPLAY("replay"), SCHEMA("schema"),

    /** A URL that is not https (or not the debug loopback), a redirect to another host, too many redirects. */
    URL("url");

    companion object {
        fun of(code: String?) = entries.firstOrNull { it.code == code }
    }
}

/** What the person is told about the newest release (section 4.3). */
internal sealed interface UpdateState {
    data object Disabled : UpdateState
    data object NotConfigured : UpdateState
    data object UpToDate : UpdateState
    data class Available(val component: Component) : UpdateState
    data class Required(val component: Component) : UpdateState
    data class Skipped(val component: Component) : UpdateState
    data class Snoozed(val component: Component, val until: Long) : UpdateState
}

/**
 * Allow-list for every URL out of a manifest (docs/UPDATES.md 2.4), path-pinned to the exact shapes in the shared table: the
 * RAW string is checked first (ASCII graphic characters only: no whitespace, control or non-ASCII character; no backslash,
 * `%`, `@`, `#`; at most 300), then it must match one shape IN FULL. Scheme and host compare case-insensitively; the path is
 * exact. A trailing-dot host, a port other than 443, `..`, empty segments, userinfo and an encoded slash cannot match.
 * (java.net.URI is not used: it throws where other parsers strip, and every client must agree.) Other links (store pages,
 * F-Droid, `market://`) are built by the app from its own package id, never taken from the manifest.
 */
internal object UrlAllowlist {
    private enum class Kind { TAG_DESKTOP, TAG_APP, TARBALL, APK, PLAY }

    private const val V = "([0-9]+\\.[0-9]+\\.[0-9]+)"
    private const val HEAD = "(?i:https)://(?i:github\\.com)(?::443)?/derpx06"
    private val RAW = Regex("[\\x21-\\x7e]{1,300}")
    private val TAG_D = Regex("$HEAD/DeskLink/releases/tag/v$V")
    private val TAG_A = Regex("$HEAD/desklink-mobile/releases/tag/v$V")
    private val APK = Regex("$HEAD/desklink-mobile/releases/download/v$V/desklink-$V\\.apk")
    private val TARBALL = Regex("$HEAD/DeskLink/releases/download/v$V/desklink-$V-x86_64\\.tar\\.gz")
    private val PLAY = Regex("(?i:https)://(?i:play\\.google\\.com)(?::443)?/store/apps/details\\?id=org\\.desklink\\.mobile")

    /** The shape of [u] and the version it names (both places equal, strict semver), or null. */
    private fun shape(u: String?): Pair<Kind, String?>? {
        if (u == null || !RAW.matches(u) || u.any { it == '\\' || it == '%' || it == '@' || it == '#' }) return null
        if (PLAY.matches(u)) return Kind.PLAY to null
        for ((re, kind) in listOf(TAG_D to Kind.TAG_DESKTOP, TAG_A to Kind.TAG_APP, APK to Kind.APK, TARBALL to Kind.TARBALL)) {
            val g = re.matchEntire(u)?.groupValues ?: continue
            val v = g[1]
            if (Version.parse(v) == null || (g.size > 2 && g[2] != v)) return null
            return kind to v
        }
        return null
    }

    /**
     * Is [url] allowed as [field] (`notesUrl`, `releaseUrl`, `tarball`, `apk`, `play`; null = any) of [component] (`desktop` or
     * `android`; null = any) at [version] (null = whatever version the URL names)? The shared `urlContextChecks` table.
     */
    fun context(url: String?, component: String?, field: String?, version: String?): Boolean {
        val (kind, v) = shape(url) ?: return false
        if (version != null && v != null && v != version) return false
        val ok = fun(c: String): Set<Kind> = when (c to field) {
            "desktop" to "notesUrl", "desktop" to "releaseUrl" -> setOf(Kind.TAG_DESKTOP)
            "desktop" to "tarball" -> setOf(Kind.TARBALL)
            "android" to "notesUrl", "android" to "releaseUrl" -> setOf(Kind.TAG_APP)
            "android" to "apk" -> setOf(Kind.APK)
            "android" to "play" -> setOf(Kind.PLAY)
            "desktop" to null -> setOf(Kind.TAG_DESKTOP, Kind.TARBALL)
            "android" to null -> setOf(Kind.TAG_APP, Kind.APK, Kind.PLAY)
            else -> emptySet()
        }
        return kind in (if (component == null) ok("desktop") + ok("android") else ok(component))
    }

    /** This app's release page (also `notesUrl`) for exactly [version], else null. */
    fun release(u: String?, version: String): String? = u?.takeIf { context(it, "android", "notesUrl", version) }

    /** The APK asset of exactly [version], else null. */
    fun apk(u: String?, version: String): String? = u?.takeIf { context(it, "android", "apk", version) }

    /** Any shape the shared `urlChecks` table calls allowed, whatever version it names. */
    fun any(u: String?): Boolean = shape(u) != null
}
