package com.aeriva.core.model.measurement

import com.aeriva.core.model.NetworkState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the claim PHASE_3A_NETWORK_MEASUREMENT_DOMAIN_MODEL.md Section
 * 1 makes: the OBSERVATION/MEASUREMENT/ESTIMATION/PREDICTION/
 * RECOMMENDATION boundary is enforceable at the type level, not just
 * describable in prose. Each `describe` function below is exhaustive
 * (no `else` branch) -- if a future change collapsed two tiers into
 * one type, or added an unhandled case, this file would fail to
 * compile, which is the point.
 */
class MeasurementBoundaryTest {

    private val context = MeasurementNetworkContext(networkState = NetworkState.unknown(NOW))

    @Test
    fun latencyMeasurement_succeededAndFailed_areDistinctAndExhaustive() {
        val succeeded: LatencyMeasurement = LatencyMeasurement.Succeeded(
            id = 1L,
            context = context,
            measuredAt = NOW,
            method = "tcp-round-trip",
            valueMillis = 42.0,
            sampleCount = 5
        )
        val failed: LatencyMeasurement = LatencyMeasurement.Failed(
            id = 2L,
            context = context,
            measuredAt = NOW,
            method = "tcp-round-trip",
            failure = MeasurementFailure.Timeout
        )

        assertEquals("succeeded", describe(succeeded))
        assertEquals("failed", describe(failed))
    }

    private fun describe(measurement: LatencyMeasurement): String = when (measurement) {
        is LatencyMeasurement.Succeeded -> "succeeded"
        is LatencyMeasurement.Failed -> "failed"
    }

    @Test
    fun latencyEstimation_withNoEvidence_isInsufficientEvidence_notFabricated() {
        val estimation: LatencyEstimation = LatencyEstimation.InsufficientEvidence

        assertTrue(estimation is LatencyEstimation.InsufficientEvidence)
        // Exhaustive, no else: proves Estimated's valueMillis can never
        // be read from the InsufficientEvidence case -- there is no
        // value to read.
        val result = when (estimation) {
            is LatencyEstimation.InsufficientEvidence -> "no evidence"
            is LatencyEstimation.Estimated -> "estimated: ${estimation.valueMillis}"
        }
        assertEquals("no evidence", result)
    }

    @Test
    fun latencyPrediction_withEvidence_carriesLineageToSourceMeasurements() {
        val prediction = LatencyPrediction.Predicted(
            targetDescription = "home Wi-Fi, weekday evenings",
            predictedAt = NOW,
            valueMillis = 55.0,
            sourceHistoryIds = listOf(1L, 2L, 3L),
            methodId = "7-day-historical-average",
            confidence = Confidence.of(sampleCount = 3, consistent = true),
            freshness = Freshness.withoutExpiry(NOW)
        )

        assertEquals(listOf(1L, 2L, 3L), prediction.sourceHistoryIds)
        assertEquals(Confidence.Low, prediction.confidence)
    }

    @Test
    fun connectivityRecommendation_withoutSufficientEvidence_isNoRecommendation() {
        val recommendation: ConnectivityRecommendation =
            ConnectivityRecommendation.NoRecommendation(reason = "insufficient evidence for any candidate network")

        val explanation = when (recommendation) {
            is ConnectivityRecommendation.NoRecommendation -> recommendation.reason
            is ConnectivityRecommendation.Recommended -> recommendation.explanation
        }
        assertEquals("insufficient evidence for any candidate network", explanation)
    }

    companion object {
        private val NOW: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }
}
