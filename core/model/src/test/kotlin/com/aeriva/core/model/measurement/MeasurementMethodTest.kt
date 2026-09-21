package com.aeriva.core.model.measurement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementMethodTest {

    @Test
    fun everyConstant_isRegistered() {
        assertTrue(MeasurementMethod.isRegistered(MeasurementMethod.HTTPS_H1_WARM_EXCHANGE))
        assertTrue(MeasurementMethod.isRegistered(MeasurementMethod.HTTPS_H1_COLD_TOTAL))
        assertTrue(MeasurementMethod.isRegistered(MeasurementMethod.HTTPS_REACHABILITY))
        assertTrue(MeasurementMethod.isRegistered(MeasurementMethod.UDP_ECHO_TRAIN))
        assertTrue(MeasurementMethod.isRegistered(MeasurementMethod.HTTPS_DOWNLOAD_STREAM))
        assertTrue(MeasurementMethod.isRegistered(MeasurementMethod.HTTPS_UPLOAD_STREAM))
    }

    @Test
    fun noDuplicateValues() {
        val values = listOf(
            MeasurementMethod.HTTPS_H1_WARM_EXCHANGE,
            MeasurementMethod.HTTPS_H1_COLD_TOTAL,
            MeasurementMethod.HTTPS_REACHABILITY,
            MeasurementMethod.UDP_ECHO_TRAIN,
            MeasurementMethod.HTTPS_DOWNLOAD_STREAM,
            MeasurementMethod.HTTPS_UPLOAD_STREAM
        )
        assertEquals(values.size, values.distinct().size)
        assertEquals(values.toSet(), MeasurementMethod.ALL)
    }

    @Test
    fun theRetiredFreeTextDefault_isNotInTheRegistry() {
        // The Phase 3B engine's current free-text default. Decision D3-7
        // retires it; wiring the engine itself off it is deferred (see
        // this change's implementation notes), but the registry itself
        // must not contain it.
        assertFalse(MeasurementMethod.isRegistered("tcp-round-trip"))
    }

    @Test
    fun unregisteredString_isNotRegistered() {
        assertFalse(MeasurementMethod.isRegistered("made-up-method"))
    }
}
