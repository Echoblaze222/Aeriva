package com.aeriva.network.monitor.measurement

import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Per Decision D2-4/D2-6: this change does not invent a production
 * endpoint URL or host (an explicit stop condition for this
 * implementation task), so [MeasurementEndpointConfig.current] must
 * stay unconfigured. This test exists so that fact is enforced, not
 * just asserted in a comment -- a future change that silently hardcodes
 * a host here would fail this test, not slip through unnoticed.
 */
class MeasurementEndpointConfigTest {

    @Test
    fun current_isUnconfigured_noProductionEndpointInventedByThisChange() {
        assertNull(MeasurementEndpointConfig.current)
    }
}
