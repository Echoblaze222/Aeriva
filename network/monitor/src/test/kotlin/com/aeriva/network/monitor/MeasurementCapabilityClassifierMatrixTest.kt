package com.aeriva.network.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 4 test gate (Phase 4 test-gate specification, group CL).
 *
 * Exhaustive truth table for [MeasurementCapabilityClassifier]: every
 * capability against every subset of the four permissions the classifier
 * knows about, at three API levels. The expected table below is written from
 * the capability contract (PHASE_3A domain model, Decision D4-1 vocabulary),
 * not copied from the implementation, so a change to the classifier has to
 * change a line here on purpose.
 *
 * Complements [MeasurementCapabilityClassifierTest] and does not edit it.
 * API level is deliberately NOT asserted to be irrelevant: a future
 * SDK-dependent rule (for example a new local-network permission) must be
 * free to change the answer for some levels. The three levels are here to
 * prove the function is total over the supported range.
 */
class MeasurementCapabilityClassifierMatrixTest {

    private enum class Tier { Supported, SupportedWithLimitations, Estimated }

    private data class Rule(val requires: Set<String>, val tier: Tier)

    private val internet = MeasurementCapabilityClassifier.PERMISSION_INTERNET
    private val networkState = MeasurementCapabilityClassifier.PERMISSION_ACCESS_NETWORK_STATE
    private val fineLocation = MeasurementCapabilityClassifier.PERMISSION_ACCESS_FINE_LOCATION
    private val phoneState = MeasurementCapabilityClassifier.PERMISSION_READ_PHONE_STATE

    private val allPermissions = listOf(internet, networkState, fineLocation, phoneState)

    /** The contract, one line per capability. */
    private val contract: Map<MeasurementCapability, Rule> = mapOf(
        MeasurementCapability.LATENCY to Rule(setOf(internet), Tier.Supported),
        MeasurementCapability.JITTER to Rule(setOf(internet), Tier.Supported),
        MeasurementCapability.HTTPS_REACHABILITY to Rule(setOf(internet), Tier.Supported),
        MeasurementCapability.DNS_RESPONSIVENESS to Rule(setOf(internet), Tier.Estimated),
        MeasurementCapability.PACKET_LOSS to Rule(setOf(internet), Tier.SupportedWithLimitations),
        MeasurementCapability.THROUGHPUT to Rule(setOf(internet), Tier.SupportedWithLimitations),
        MeasurementCapability.NETWORK_STABILITY to Rule(setOf(networkState), Tier.Supported),
        MeasurementCapability.NETWORK_TRANSITIONS to Rule(setOf(networkState), Tier.Supported),
        MeasurementCapability.WIFI_CHARACTERISTICS to Rule(setOf(fineLocation), Tier.SupportedWithLimitations),
        MeasurementCapability.CELLULAR_CHARACTERISTICS to Rule(setOf(phoneState, fineLocation), Tier.SupportedWithLimitations)
    )

    private fun subsets(): List<Set<String>> =
        (0 until (1 shl allPermissions.size)).map { mask ->
            allPermissions.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
        }

    private fun tierOf(c: CapabilityClassification): Tier? = when (c) {
        is CapabilityClassification.Supported -> Tier.Supported
        is CapabilityClassification.SupportedWithLimitations -> Tier.SupportedWithLimitations
        is CapabilityClassification.Estimated -> Tier.Estimated
        is CapabilityClassification.NotReliablyAvailable -> null
    }

    private fun reasonOf(c: CapabilityClassification): String? = when (c) {
        is CapabilityClassification.Supported -> null
        is CapabilityClassification.SupportedWithLimitations -> c.reason
        is CapabilityClassification.Estimated -> c.reason
        is CapabilityClassification.NotReliablyAvailable -> c.reason
    }

    @Test
    fun contractCoversEveryCapability_soANewCapabilityCannotBeAddedUnclassified() {
        assertEquals(MeasurementCapability.entries.toSet(), contract.keys)
    }

    @Test
    fun truthTable_everyCapability_everyPermissionSubset_atEveryApiLevel() {
        val mismatches = mutableListOf<String>()
        var evaluated = 0
        for (sdk in listOf(26, 34, 36)) {
            for ((capability, rule) in contract) {
                for (granted in subsets()) {
                    evaluated++
                    val actual = tierOf(MeasurementCapabilityClassifier.classify(capability, sdk, granted))
                    val expected = if (granted.containsAll(rule.requires)) rule.tier else null
                    if (actual != expected) {
                        mismatches += "$capability sdk=$sdk granted=${granted.map { it.substringAfterLast('.') }} " +
                            "expected=${expected ?: "NotReliablyAvailable"} actual=${actual ?: "NotReliablyAvailable"}"
                    }
                }
            }
        }
        assertEquals("expected 3 * 10 * 16 evaluations", 480, evaluated)
        assertTrue("${mismatches.size} mismatches, first 5:\n" + mismatches.take(5).joinToString("\n"), mismatches.isEmpty())
    }

    @Test
    fun everyNonSupportedAnswer_carriesAReasonAPersonCouldRead() {
        for (capability in MeasurementCapability.entries) {
            for (granted in subsets()) {
                val result = MeasurementCapabilityClassifier.classify(capability, 34, granted)
                val reason = reasonOf(result)
                if (result !is CapabilityClassification.Supported) {
                    assertTrue("$capability with $granted must explain itself", !reason.isNullOrBlank() && reason.length >= 12)
                }
            }
        }
    }

    @Test
    fun honestyFloor_noPermissionSetEverUpgradesTheHardCapabilitiesToPlainSupported() {
        // A timed DNS lookup is confounded by caching; loss and throughput carry real limits.
        val alwaysQualified = listOf(
            MeasurementCapability.DNS_RESPONSIVENESS,
            MeasurementCapability.PACKET_LOSS,
            MeasurementCapability.THROUGHPUT
        )
        for (capability in alwaysQualified) {
            for (sdk in listOf(26, 34, 36)) {
                val everything = MeasurementCapabilityClassifier.classify(capability, sdk, allPermissions.toSet())
                assertFalse("$capability must never be plain Supported", everything is CapabilityClassification.Supported)
            }
        }
    }

    @Test
    fun permissionsAreIsolated_networkStateDoesNotUnlockProbesAndInternetDoesNotUnlockLocationReadings() {
        val onlyNetworkState = setOf(networkState)
        for (capability in listOf(
            MeasurementCapability.LATENCY, MeasurementCapability.JITTER, MeasurementCapability.HTTPS_REACHABILITY,
            MeasurementCapability.DNS_RESPONSIVENESS, MeasurementCapability.PACKET_LOSS, MeasurementCapability.THROUGHPUT
        )) {
            assertTrue(
                "$capability must not be unlocked by ACCESS_NETWORK_STATE alone",
                MeasurementCapabilityClassifier.classify(capability, 34, onlyNetworkState) is CapabilityClassification.NotReliablyAvailable
            )
        }
        val onlyInternet = setOf(internet)
        for (capability in listOf(MeasurementCapability.WIFI_CHARACTERISTICS, MeasurementCapability.CELLULAR_CHARACTERISTICS)) {
            assertTrue(
                "$capability must not be unlocked by INTERNET alone",
                MeasurementCapabilityClassifier.classify(capability, 34, onlyInternet) is CapabilityClassification.NotReliablyAvailable
            )
        }
    }

    @Test
    fun cellularCharacteristics_needsBothPermissions_neverEither() {
        for (single in listOf(setOf(phoneState), setOf(fineLocation))) {
            assertTrue(
                MeasurementCapabilityClassifier.classify(MeasurementCapability.CELLULAR_CHARACTERISTICS, 34, single)
                    is CapabilityClassification.NotReliablyAvailable
            )
        }
    }

    @Test
    fun unknownPermissionStrings_areIgnored_andNeverGrantAnything() {
        val noise = setOf("android.permission.CAMERA", "com.example.INTERNET", "INTERNET", "android.permission.internet")
        for (capability in MeasurementCapability.entries) {
            assertTrue(
                "$capability must not be unlocked by look-alike permission strings",
                MeasurementCapabilityClassifier.classify(capability, 34, noise) is CapabilityClassification.NotReliablyAvailable
            )
        }
    }
}
