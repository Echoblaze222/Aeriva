package com.aeriva.core.model.measurement

import com.aeriva.core.model.NetworkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DerivedJitterStatsTest {

    private val now = Instant.parse("2026-09-20T00:00:00Z")
    private val context = MeasurementNetworkContext(networkState = NetworkState.unknown(now))

    private fun succeeded(
        id: Long,
        valueMillis: Double,
        at: Instant,
        warm: Boolean = true,
        networkHandle: Long? = 1L,
        method: String = MeasurementMethod.HTTPS_H1_WARM_EXCHANGE
    ) = LatencyMeasurement.Succeeded(
        id = id,
        context = context,
        measuredAt = at,
        method = method,
        valueMillis = valueMillis,
        sampleCount = 1,
        evidence = ProbeEvidence(
            networkHandle = networkHandle,
            addressFamily = AddressFamily.IPv4,
            negotiatedProtocol = "http/1.1",
            connectionState = if (warm) ConnectionState.Warm else ConnectionState.Cold,
            proxyUsed = false,
            phases = null,
            serverProcessingMillis = null,
            serverRegionId = null,
            bytesSent = 8,
            bytesReceived = 8,
            engineElapsedMillis = null
        )
    )

    private fun failed(id: Long, at: Instant) = LatencyMeasurement.Failed(
        id = id,
        context = context,
        measuredAt = at,
        method = MeasurementMethod.HTTPS_H1_WARM_EXCHANGE,
        failure = MeasurementFailure.Timeout(MeasurementStage.Request)
    )

    @Test
    fun emptySeries_returnsNull() {
        assertNull(DerivedJitterStats.from(emptyList(), now))
    }

    @Test
    fun singleSample_returnsNull_noPairPossible() {
        val series = listOf(succeeded(1, 40.0, now))
        assertNull(DerivedJitterStats.from(series, now))
    }

    @Test
    fun twoConsecutiveWarmSuccesses_sameMethodAndNetwork_formOnePair() {
        val series = listOf(
            succeeded(1, 40.0, now),
            succeeded(2, 44.0, now.plusMillis(1000))
        )

        val stats = DerivedJitterStats.from(series, now)

        assertEquals(1, stats?.pairCount)
        assertEquals(4.0, stats?.meanAbsIpdvMillis)
        assertEquals(JitterDefinition.MEAN_ABS_CONSECUTIVE_DIFFERENCE, stats?.definition)
    }

    @Test
    fun failedSample_breaksAdjacency_neverBridged() {
        val series = listOf(
            succeeded(1, 40.0, now),
            failed(2, now.plusMillis(1000)),
            succeeded(3, 44.0, now.plusMillis(2000))
        )

        assertNull(DerivedJitterStats.from(series, now))
    }

    @Test
    fun coldSample_isNeverPaired() {
        val series = listOf(
            succeeded(1, 40.0, now, warm = false),
            succeeded(2, 44.0, now.plusMillis(1000), warm = false)
        )

        assertNull(DerivedJitterStats.from(series, now))
    }

    @Test
    fun differentMethod_breaksAdjacency() {
        val series = listOf(
            succeeded(1, 40.0, now, method = MeasurementMethod.HTTPS_H1_WARM_EXCHANGE),
            succeeded(2, 44.0, now.plusMillis(1000), method = MeasurementMethod.HTTPS_H1_COLD_TOTAL)
        )

        assertNull(DerivedJitterStats.from(series, now))
    }

    @Test
    fun differentNetworkHandle_breaksAdjacency() {
        val series = listOf(
            succeeded(1, 40.0, now, networkHandle = 1L),
            succeeded(2, 44.0, now.plusMillis(1000), networkHandle = 2L)
        )

        assertNull(DerivedJitterStats.from(series, now))
    }

    @Test
    fun bothNullNetworkHandle_countsAsEqual() {
        val series = listOf(
            succeeded(1, 40.0, now, networkHandle = null),
            succeeded(2, 44.0, now.plusMillis(1000), networkHandle = null)
        )

        assertEquals(1, DerivedJitterStats.from(series, now)?.pairCount)
    }

    @Test
    fun outOfOrderSeries_isRejected() {
        val series = listOf(
            succeeded(1, 40.0, now.plusMillis(2000)),
            succeeded(2, 44.0, now)
        )

        assertNull(DerivedJitterStats.from(series, now))
    }

    @Test
    fun fewerThanThreePairs_isInsufficientConfidence() {
        val series = listOf(
            succeeded(1, 40.0, now),
            succeeded(2, 44.0, now.plusMillis(1000))
        )

        assertEquals(Confidence.Insufficient, DerivedJitterStats.from(series, now)?.confidence)
    }

    @Test
    fun sourceMeasurementIds_areInSendOrder() {
        val series = listOf(
            succeeded(1, 40.0, now),
            succeeded(2, 44.0, now.plusMillis(1000)),
            succeeded(3, 41.0, now.plusMillis(2000))
        )

        val ids = DerivedJitterStats.from(series, now)?.sourceMeasurementIds
        assertTrue(ids == listOf(1L, 2L, 3L))
    }
}
