package com.aeriva.core.model.measurement

import com.aeriva.core.model.NetworkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

/**
 * AI 4 test gate (Phase 4 test-gate specification, groups DM and JT).
 *
 * Complements [DerivedJitterStatsTest] without editing it. That suite checks
 * hand-picked series; this one checks the invariants that must hold for
 * EVERY series, using an independent oracle written from Decision D4-3 and
 * not from the implementation:
 *
 *  - a pair is two adjacent list entries, both Succeeded, same method,
 *    both Warm, equal network handle (null equals null);
 *  - every member of every counted pair appears in the lineage and in
 *    sampleCount, exactly once, in send order;
 *  - the reported method is the method the pairs actually used;
 *  - no statistic is ever NaN, infinite or negative;
 *  - a sample that is not a real reading (non-finite, negative) is never
 *    used and never listed as a source.
 *
 * Series in which the counted pairs span more than one method are skipped by
 * the property tests, because D4-3 does not say how to label a stat that
 * blends methods. That is decision point DP-1 in the specification, not a
 * test gap to be closed here.
 */
class DerivedJitterStatsInvariantTest {

    private val epoch = Instant.parse("2026-09-21T00:00:00Z")
    private val context = MeasurementNetworkContext(networkState = NetworkState.unknown(epoch))

    private fun at(second: Long): Instant = epoch.plusSeconds(second)

    private fun succeeded(
        id: Long,
        valueMillis: Double,
        second: Long = id,
        warm: Boolean = true,
        networkHandle: Long? = 1L,
        method: String = MeasurementMethod.HTTPS_H1_WARM_EXCHANGE,
        withEvidence: Boolean = true
    ) = LatencyMeasurement.Succeeded(
        id = id,
        context = context,
        measuredAt = at(second),
        method = method,
        valueMillis = valueMillis,
        sampleCount = 1,
        evidence = if (!withEvidence) null else ProbeEvidence(
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

    private fun failed(id: Long, second: Long = id) = LatencyMeasurement.Failed(
        id = id,
        context = context,
        measuredAt = at(second),
        method = MeasurementMethod.HTTPS_H1_WARM_EXCHANGE,
        failure = MeasurementFailure.Timeout(MeasurementStage.Request)
    )

    // ------------------------------------------------------------------
    // Independent oracle (Decision D4-3).
    // ------------------------------------------------------------------

    private data class Expected(
        val pairCount: Int,
        val meanAbsIpdv: Double,
        val pdvRange: Double,
        val lineage: List<Long>,
        val methods: Set<String>
    )

    private fun oracle(series: List<LatencyMeasurement>): Expected? {
        for (i in 1 until series.size) {
            if (series[i].measuredAt.isBefore(series[i - 1].measuredAt)) return null
        }
        val diffs = mutableListOf<Double>()
        val members = linkedMapOf<Long, Double>()
        val methods = mutableSetOf<String>()
        for (i in 1 until series.size) {
            val a = series[i - 1] as? LatencyMeasurement.Succeeded ?: continue
            val b = series[i] as? LatencyMeasurement.Succeeded ?: continue
            if (a.method != b.method) continue
            val ea = a.evidence ?: continue
            val eb = b.evidence ?: continue
            if (ea.connectionState != ConnectionState.Warm) continue
            if (eb.connectionState != ConnectionState.Warm) continue
            if (ea.networkHandle != eb.networkHandle) continue
            diffs += kotlin.math.abs(b.valueMillis - a.valueMillis)
            members[a.id] = a.valueMillis
            members[b.id] = b.valueMillis
            methods += a.method
        }
        if (diffs.isEmpty()) return null
        return Expected(
            pairCount = diffs.size,
            meanAbsIpdv = diffs.sum() / diffs.size,
            pdvRange = members.values.max() - members.values.min(),
            lineage = members.keys.sorted(),
            methods = methods
        )
    }

    private fun describe(series: List<LatencyMeasurement>): String = series.joinToString(prefix = "[", postfix = "]") {
        when (it) {
            is LatencyMeasurement.Succeeded ->
                "#${it.id}:${it.valueMillis}${if (it.evidence?.connectionState == ConnectionState.Warm) "w" else "c"}" +
                    "/${it.method.substringAfterLast('-')}/h${it.evidence?.networkHandle}"
            is LatencyMeasurement.Failed -> "#${it.id}:FAIL"
        }
    }

    private fun randomSeries(random: Random): List<LatencyMeasurement> {
        val length = random.nextInt(0, 13)
        val methods = listOf(
            MeasurementMethod.HTTPS_H1_WARM_EXCHANGE,
            MeasurementMethod.HTTPS_H1_WARM_EXCHANGE,
            MeasurementMethod.HTTPS_H1_WARM_EXCHANGE,
            MeasurementMethod.HTTPS_REACHABILITY
        )
        val handles = listOf(1L, 1L, 1L, 2L, null)
        return (1..length).map { index ->
            val id = index.toLong()
            if (random.nextInt(100) < 20) {
                failed(id)
            } else {
                succeeded(
                    id = id,
                    valueMillis = random.nextInt(1, 200).toDouble(),
                    warm = random.nextInt(100) < 85,
                    networkHandle = handles[random.nextInt(handles.size)],
                    method = methods[random.nextInt(methods.size)],
                    withEvidence = random.nextInt(100) >= 5
                )
            }
        }
    }

    /** Runs the property over a fixed seed so any failure is reproducible. */
    private fun mismatches(check: (DerivedJitterStats?, Expected?) -> String?): Pair<List<String>, Int> {
        val random = Random(20260921)
        val found = mutableListOf<String>()
        var evaluated = 0
        repeat(600) {
            val series = randomSeries(random)
            val expected = oracle(series)
            if (expected != null && expected.methods.size > 1) return@repeat // DP-1, see class KDoc
            evaluated++
            val actual = DerivedJitterStats.from(series, epoch)
            val problem = check(actual, expected)
            if (problem != null) found += "${describe(series)} -> $problem"
        }
        return found to evaluated
    }

    private fun assertNoMismatches(label: String, check: (DerivedJitterStats?, Expected?) -> String?) {
        val (found, evaluated) = mismatches(check)
        assertTrue("property is vacuous: only $evaluated series evaluated", evaluated >= 300)
        assertTrue(
            "$label: ${found.size} of $evaluated series violate the invariant. First 3:\n" +
                found.take(3).joinToString("\n"),
            found.isEmpty()
        )
    }

    @Test
    fun property_resultPresence_matchesOracle() = assertNoMismatches("presence") { actual, expected ->
        if ((actual == null) != (expected == null)) "expected ${expected != null}, got ${actual != null}" else null
    }

    @Test
    fun property_pairCountAndMean_matchOracle() = assertNoMismatches("pairCount/mean") { actual, expected ->
        if (actual == null || expected == null) null
        else if (actual.pairCount != expected.pairCount) "pairCount ${actual.pairCount} != ${expected.pairCount}"
        else if (kotlin.math.abs(actual.meanAbsIpdvMillis - expected.meanAbsIpdv) > 1e-9) {
            "mean ${actual.meanAbsIpdvMillis} != ${expected.meanAbsIpdv}"
        } else null
    }

    @Test
    fun property_lineage_listsEveryMemberOfEveryCountedPair_inSendOrder() =
        assertNoMismatches("lineage") { actual, expected ->
            if (actual == null || expected == null) null
            else if (actual.sourceMeasurementIds != expected.lineage) {
                "lineage ${actual.sourceMeasurementIds} != ${expected.lineage}"
            } else null
        }

    @Test
    fun property_sampleCount_equalsDistinctLineageSize() = assertNoMismatches("sampleCount") { actual, expected ->
        if (actual == null || expected == null) null
        else if (actual.sampleCount != expected.lineage.size) {
            "sampleCount ${actual.sampleCount} != ${expected.lineage.size}"
        } else if (actual.sampleCount != actual.sourceMeasurementIds.distinct().size) {
            "sampleCount ${actual.sampleCount} != distinct lineage ${actual.sourceMeasurementIds.distinct().size}"
        } else null
    }

    @Test
    fun property_pdvRange_isMaxMinusMinOverEveryCountedSample() = assertNoMismatches("pdvRange") { actual, expected ->
        if (actual == null || expected == null) null
        else if (kotlin.math.abs(actual.pdvRangeMillis - expected.pdvRange) > 1e-9) {
            "pdvRange ${actual.pdvRangeMillis} != ${expected.pdvRange}"
        } else null
    }

    @Test
    fun property_reportedMethod_isTheMethodTheCountedPairsUsed() = assertNoMismatches("method") { actual, expected ->
        if (actual == null || expected == null) null
        else if (setOf(actual.method) != expected.methods) "method ${actual.method} != ${expected.methods}"
        else null
    }

    @Test
    fun property_structure_pairsAndSamplesAreConsistent() = assertNoMismatches("structure") { actual, _ ->
        if (actual == null) null
        else if (actual.sampleCount < actual.pairCount + 1) "sampleCount < pairCount + 1"
        else if (actual.sampleCount > actual.pairCount * 2) "sampleCount > 2 * pairCount"
        else if (!actual.meanAbsIpdvMillis.isFinite() || actual.meanAbsIpdvMillis < 0.0) "mean not finite and >= 0"
        else if (!actual.pdvRangeMillis.isFinite() || actual.pdvRangeMillis < 0.0) "range not finite and >= 0"
        else null
    }

    // ------------------------------------------------------------------
    // Explicit cases: the defects the property tests point at, in
    // hand-checkable form.
    // ------------------------------------------------------------------

    @Test
    fun breakThenResume_countsBothRunsCompletely() {
        // Two valid pairs separated by a failure: (40,44) and (10,12).
        val series = listOf(
            succeeded(1, 40.0), succeeded(2, 44.0), failed(3), succeeded(4, 10.0), succeeded(5, 12.0)
        )
        val stats = DerivedJitterStats.from(series, epoch)
        assertNotNull(stats)
        assertEquals(2, stats!!.pairCount)
        assertEquals(3.0, stats.meanAbsIpdvMillis, 1e-9)
        assertEquals("every pair member must be listed as a source", listOf(1L, 2L, 4L, 5L), stats.sourceMeasurementIds)
        assertEquals("four distinct samples took part in a pair", 4, stats.sampleCount)
        assertEquals("range covers sample #4 (10 ms) through sample #2 (44 ms)", 34.0, stats.pdvRangeMillis, 1e-9)
    }

    @Test
    fun coldBreakThenResume_countsResumedRunCompletely() {
        // A cold sample in the middle splits the series into two warm runs.
        val series = listOf(
            succeeded(1, 20.0), succeeded(2, 22.0),
            succeeded(3, 90.0, warm = false),
            succeeded(4, 30.0), succeeded(5, 31.0)
        )
        val stats = DerivedJitterStats.from(series, epoch)
        assertNotNull(stats)
        assertEquals(listOf(1L, 2L, 4L, 5L), stats!!.sourceMeasurementIds)
        assertEquals(4, stats.sampleCount)
    }

    @Test
    fun reportedMethod_isThatOfTheCountedPairs_notOfTheFirstSuccessfulSample() {
        // #1 uses a different method and pairs with nothing. All counted pairs use reachability.
        val series = listOf(
            succeeded(1, 40.0, method = MeasurementMethod.HTTPS_H1_WARM_EXCHANGE),
            succeeded(2, 50.0, method = MeasurementMethod.HTTPS_REACHABILITY),
            succeeded(3, 52.0, method = MeasurementMethod.HTTPS_REACHABILITY),
            succeeded(4, 51.0, method = MeasurementMethod.HTTPS_REACHABILITY)
        )
        val stats = DerivedJitterStats.from(series, epoch)
        assertNotNull(stats)
        assertEquals(2, stats!!.pairCount)
        assertEquals(MeasurementMethod.HTTPS_REACHABILITY, stats.method)
        assertFalse("sample #1 never paired, so it is not a source", 1L in stats.sourceMeasurementIds)
    }

    @Test
    fun nonFiniteSamples_neverProduceNonFiniteStatistics() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val series = listOf(succeeded(1, 40.0), succeeded(2, 44.0), succeeded(3, bad), succeeded(4, 46.0))
            val stats = DerivedJitterStats.from(series, epoch)
            if (stats != null) {
                assertTrue("mean must be finite for bad=$bad, was ${stats.meanAbsIpdvMillis}", stats.meanAbsIpdvMillis.isFinite())
                assertTrue("range must be finite for bad=$bad, was ${stats.pdvRangeMillis}", stats.pdvRangeMillis.isFinite())
                assertFalse("invalid sample #3 must not be a source for bad=$bad", 3L in stats.sourceMeasurementIds)
            }
        }
    }

    @Test
    fun negativeLatencySample_isNotTreatedAsARealReading() {
        // DerivedLatencyStats rejects negative values; jitter must not disagree about what a reading is.
        val series = listOf(succeeded(1, -5.0), succeeded(2, 10.0), succeeded(3, 12.0), succeeded(4, 11.0))
        val stats = DerivedJitterStats.from(series, epoch)
        if (stats != null) {
            assertFalse("negative sample #1 must not be a source", 1L in stats.sourceMeasurementIds)
            assertTrue(stats.pdvRangeMillis <= 2.0 + 1e-9)
        }
    }

    // ------------------------------------------------------------------
    // Boundaries the existing suite does not pin.
    // ------------------------------------------------------------------

    @Test
    fun equalTimestamps_areAcceptedAsNonDecreasing() {
        val series = listOf(succeeded(1, 40.0, second = 5), succeeded(2, 44.0, second = 5))
        val stats = DerivedJitterStats.from(series, epoch)
        assertNotNull("non-decreasing order permits equal timestamps", stats)
        assertEquals(1, stats!!.pairCount)
    }

    @Test
    fun outOfOrderFailedSample_stillRejectsTheWholeSeries() {
        val series = listOf(succeeded(1, 40.0, second = 1), failed(2, second = 9), succeeded(3, 44.0, second = 5))
        assertNull(DerivedJitterStats.from(series, epoch))
    }

    @Test
    fun warmSampleWithoutEvidence_neverPairs() {
        val series = listOf(
            succeeded(1, 40.0, withEvidence = false),
            succeeded(2, 44.0, withEvidence = false)
        )
        assertNull(DerivedJitterStats.from(series, epoch))
    }

    @Test
    fun nullHandleAndNonNullHandle_doNotPair() {
        val series = listOf(succeeded(1, 40.0, networkHandle = null), succeeded(2, 44.0, networkHandle = 7L))
        assertNull(DerivedJitterStats.from(series, epoch))
    }

    @Test
    fun differentNonNullHandles_doNotPair_evenWithMatchingMethodAndState() {
        val series = listOf(succeeded(1, 40.0, networkHandle = 7L), succeeded(2, 44.0, networkHandle = 8L))
        assertNull(DerivedJitterStats.from(series, epoch))
    }

    @Test
    fun calculatedAt_isTheCallersValue_neverTheWallClock() {
        val stamp = Instant.parse("2001-02-03T04:05:06Z")
        val stats = DerivedJitterStats.from(listOf(succeeded(1, 40.0), succeeded(2, 44.0)), stamp)
        assertEquals(stamp, stats!!.calculatedAt)
    }

    @Test
    fun definition_isAlwaysTheNamedMeanAbsoluteConsecutiveDifference() {
        val stats = DerivedJitterStats.from(listOf(succeeded(1, 40.0), succeeded(2, 44.0)), epoch)
        assertEquals(JitterDefinition.MEAN_ABS_CONSECUTIVE_DIFFERENCE, stats!!.definition)
    }

    @Test
    fun inputList_isNotMutated() {
        val series = listOf(succeeded(1, 40.0), failed(2), succeeded(3, 10.0), succeeded(4, 12.0))
        val before = series.toList()
        DerivedJitterStats.from(series, epoch)
        assertEquals(before, series)
    }
}
