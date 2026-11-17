/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.update

import org.desklink.mobile.BuildConfig

/**
 * The compiled-in Ed25519 public keys (docs/UPDATES.md 2.6): `k1` current, `k2` next, base64url of the 32-byte key. A blank
 * slot is not a key: until the owner fills them in (from the workspace `docs/update/keys.json`), every manifest fails
 * closed. A DEBUG build trusts the openly published TEST keys instead (the shared fixtures' test1/test2, whose private halves are
 * public) so the local test server verifies; a release build never contains them (release-required-classes.txt
 * `#forbidden-string`, and R8 folds the `BuildConfig.DEBUG` branch away).
 */
internal object UpdateKeys {
    const val K1 = ""
    const val K2 = ""
    const val TEST_K1 = "1ESE_TECkkgupykava9EhlyV7zD9PZRlo22v2jNoKS0"
    const val TEST_K2 = "gdqQHUmHGByYeiKgAEQ7735GcnSB-3C0oQopuPHKZ-g"

    /** The keys a build trusts: the real ones; a DEBUG build also the published test keys. [debug] is a parameter so a test can check both. */
    fun forBuild(debug: Boolean): Map<String, ByteArray> {
        val pairs = listOf("k1" to K1, "k2" to K2) + if (debug) listOf("test1" to TEST_K1, "test2" to TEST_K2) else emptyList()
        return pairs.mapNotNull { (kid, s) -> UpdateVerifier.base64Url(s)?.takeIf { it.size == 32 }?.let { kid to it } }.toMap()
    }

    fun all(): Map<String, ByteArray> = forBuild(BuildConfig.DEBUG)
}
