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
        fun parseLenient(s: String?): Version? = parse(s?.takeWhile { it.isDigit() || it == '.' })
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
 * `%`, `@`, `#`), then it must match one shape. Scheme and host compare case-insensitively; the path is exact. A trailing-dot
 * host, a port other than 443, `..`, empty segments, userinfo and an encoded slash cannot match. (java.net.URI is not used: it
 * throws where other parsers strip, and every client must agree.) Other links (store pages, F-Droid, `market://`) are built
 * by the app from its own package id, never taken from the manifest.
 */
internal object UrlAllowlist {
    private const val V = "v([0-9]+\\.[0-9]+\\.[0-9]+)"
    private const val HEAD = "(?i:https)://(?i:github\\.com)(?::443)?/derpx06"
    private val RAW = Regex("[\\x21-\\x7e]{1,300}")
    private val TAG = Regex("$HEAD/(?:DeskLink|desklink-mobile)/releases/tag/$V")
    private val APP_TAG = Regex("$HEAD/desklink-mobile/releases/tag/$V")
    private val APK = Regex("$HEAD/desklink-mobile/releases/download/$V/desklink-([0-9]+\\.[0-9]+\\.[0-9]+)\\.apk")
    private val TARBALL = Regex("$HEAD/DeskLink/releases/download/$V/desklink-([0-9]+\\.[0-9]+\\.[0-9]+)-x86_64\\.tar\\.gz")
    private val PLAY = Regex("(?i:https)://(?i:play\\.google\\.com)(?::443)?/store/apps/details\\?id=org\\.desklink\\.mobile")

    private fun clean(u: String?): String? = u?.takeIf { RAW.matches(it) && it.none { c -> c == '\\' || c == '%' || c == '@' || c == '#' } }

    private fun semver(s: String) = Version.parse(s) != null

    /** This app's release page (also `notesUrl`): `.../desklink-mobile/releases/tag/v<version>` for exactly [version]. */
    fun release(u: String?, version: String): String? = clean(u)?.takeIf { APP_TAG.matchEntire(it)?.groupValues?.get(1) == version }

    /** The APK asset of exactly [version]: `.../desklink-mobile/releases/download/v<version>/desklink-<version>.apk`. */
    fun apk(u: String?, version: String): String? = clean(u)?.takeIf { m -> APK.matchEntire(m)?.groupValues?.let { it[1] == version && it[2] == version } == true }

    /** Any shape the shared `urlChecks` table calls allowed, whatever version it names. */
    fun any(u: String?): Boolean = clean(u)?.let { m ->
        TAG.matchEntire(m)?.groupValues?.get(1)?.let(::semver) == true ||
            APK.matchEntire(m)?.groupValues?.let { it[1] == it[2] && semver(it[1]) } == true ||
            TARBALL.matchEntire(m)?.groupValues?.let { it[1] == it[2] && semver(it[1]) } == true ||
            PLAY.matches(m)
    } == true
}
