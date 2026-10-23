/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.helpers

import org.junit.Assert.assertEquals
import org.junit.Test

class SizesTest {
    @Test
    fun sizesAreBinaryLikeTheComputerShowsThem() {
        assertEquals("0 B", Sizes.iec(0))
        assertEquals("1023 B", Sizes.iec(1023))
        assertEquals("1 KiB", Sizes.iec(1024))
        assertEquals("1.5 MiB", Sizes.iec(1_572_864))
        assertEquals("19 MiB", Sizes.iec(20_000_000)) // the 20 MB file the computer calls 19 MiB
        assertEquals("1 GiB", Sizes.iec(1L shl 30))
        assertEquals("286 MiB", Sizes.iec(300_000_000))
    }
}
