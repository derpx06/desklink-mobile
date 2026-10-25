/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared case table (the desktop's `versions_skew` test): same major.minor is not skew, a patch is not skew, nonsense never is. */
class DeviceInfoVersionTest {
    @Test
    fun skewIsMajorMinorOnly() {
        assertTrue(DeviceInfo.versionsSkew("1.3.0", "1.4.0"))
        assertTrue(DeviceInfo.versionsSkew("1.3.0", "2.3.0"))
        assertFalse(DeviceInfo.versionsSkew("1.3.0", "1.3.7"))
        assertFalse(DeviceInfo.versionsSkew("1.3.0", "1.3.0"))
        assertFalse(DeviceInfo.versionsSkew("1.3.0", "banana"))
        assertFalse(DeviceInfo.versionsSkew("1.3.0", null))
        assertFalse(DeviceInfo.versionsSkew(null, "1.4.0"))
        assertFalse(DeviceInfo.versionsSkew("1.3.0", ""))
    }

    @Test
    fun aVersionFromTheWireIsBoundedAndPlain() {
        assertEquals("1.3.0", DeviceInfo.cleanAppVersion("1.3.0"))
        assertEquals("1.3.0-rc1+build_5", DeviceInfo.cleanAppVersion("1.3.0-rc1+build_5"))
        assertNull(DeviceInfo.cleanAppVersion("x".repeat(33)))
        assertNull(DeviceInfo.cleanAppVersion("1.3 <script>"))
        assertNull(DeviceInfo.cleanAppVersion(""))
        assertNull(DeviceInfo.cleanAppVersion(null))
    }
}
