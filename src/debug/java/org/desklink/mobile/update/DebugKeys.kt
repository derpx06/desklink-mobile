/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.update

/**
 * The openly published TEST public keys of the shared fixtures (their private halves are public). They live in the debug source
 * set, so a release build cannot contain them by construction; UpdateKeys finds this class by name and a release build simply
 * does not have it. (release-required-classes.txt still asserts the two strings are absent from the release dex.)
 */
object DebugKeys {
    @JvmField val KEYS: Map<String, String> = mapOf(
        "test1" to "1ESE_TECkkgupykava9EhlyV7zD9PZRlo22v2jNoKS0",
        "test2" to "gdqQHUmHGByYeiKgAEQ7735GcnSB-3C0oQopuPHKZ-g",
    )
}
