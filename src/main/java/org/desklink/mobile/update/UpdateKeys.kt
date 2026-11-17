/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.update

import org.desklink.mobile.BuildConfig

/**
 * The compiled-in Ed25519 public keys (docs/UPDATES.md 2.6): `k1` current, `k2` next, base64url of the 32-byte key. A blank
 * slot is not a key: until the owner fills them in (from the workspace `docs/update/keys.json`), every manifest fails
 * closed. A DEBUG build trusts the openly published TEST keys too (the shared fixtures' test1/test2, whose private halves are
 * public; they live in src/debug, so a release build cannot contain them) so the local test server verifies; a release build never contains them (release-required-classes.txt
 * `#forbidden-string` still asserts it).
 */
internal object UpdateKeys {
    const val K1 = ""
    const val K2 = ""

    /** The keys a build trusts: the real ones; a DEBUG build also the published test keys, found by name in the debug source set. [debug] is a parameter so a test can check both. */
    fun forBuild(debug: Boolean): Map<String, ByteArray> {
        val pairs = listOf("k1" to K1, "k2" to K2) + if (debug) testKeys().toList() else emptyList()
        return pairs.mapNotNull { (kid, s) -> UpdateVerifier.base64Url(s)?.takeIf { it.size == 32 }?.let { kid to it } }.toMap()
    }

    @Suppress("UNCHECKED_CAST")
    private fun testKeys(): Map<String, String> = runCatching {
        Class.forName("org.desklink.mobile.update.DebugKeys").getField("KEYS").get(null) as Map<String, String>
    }.getOrDefault(emptyMap())

    fun all(): Map<String, ByteArray> = forBuild(BuildConfig.DEBUG)
}
