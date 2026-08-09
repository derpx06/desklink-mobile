package org.desklink.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundExecutionPolicyTest {
    @Test
    fun offersExemptionOnlyForConnectedOptimizedDevices() {
        assertTrue(
            BackgroundExecutionPolicy.shouldOfferBatteryExemption(
                hasConnectedDevice = true,
                ignoresBatteryOptimizations = false,
            ),
        )
        assertFalse(
            BackgroundExecutionPolicy.shouldOfferBatteryExemption(
                hasConnectedDevice = false,
                ignoresBatteryOptimizations = false,
            ),
        )
        assertFalse(
            BackgroundExecutionPolicy.shouldOfferBatteryExemption(
                hasConnectedDevice = true,
                ignoresBatteryOptimizations = true,
            ),
        )
    }
}
