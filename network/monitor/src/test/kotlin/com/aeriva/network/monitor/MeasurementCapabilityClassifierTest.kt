package com.aeriva.network.monitor

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class MeasurementCapabilityClassifierTest {

    @Test
    fun latency_withInternetPermission_isSupported() {
        val result = MeasurementCapabilityClassifier.classify(
            capability = MeasurementCapability.LATENCY,
            sdkInt = 34,
            grantedPermissions = setOf(MeasurementCapabilityClassifier.PERMISSION_INTERNET)
        )

        assertEquals(CapabilityClassification.Supported, result)
    }

    @Test
    fun latency_withoutInternetPermission_isNotReliablyAvailable() {
        val result = MeasurementCapabilityClassifier.classify(
            capability = MeasurementCapability.LATENCY,
            sdkInt = 34,
            grantedPermissions = emptySet()
        )

        assertTrue(result is CapabilityClassification.NotReliablyAvailable)
    }

    @Test
    fun packetLoss_withInternetPermission_isSupportedWithLimitations() {
        val result = MeasurementCapabilityClassifier.classify(
            capability = MeasurementCapability.PACKET_LOSS,
            sdkInt = 34,
            grantedPermissions = setOf(MeasurementCapabilityClassifier.PERMISSION_INTERNET)
        )

        assertTrue(result is CapabilityClassification.SupportedWithLimitations)
    }

    @Test
    fun throughput_withoutInternetPermission_isNotReliablyAvailable() {
        val result = MeasurementCapabilityClassifier.classify(
            capability = MeasurementCapability.THROUGHPUT,
            sdkInt = 34,
            grantedPermissions = emptySet()
        )

        assertTrue(result is CapabilityClassification.NotReliablyAvailable)
    }

    @Test
    fun networkStability_withAccessNetworkState_isSupported() {
        val result = MeasurementCapabilityClassifier.classify(
            capability = MeasurementCapability.NETWORK_STABILITY,
            sdkInt = 34,
            grantedPermissions = setOf(MeasurementCapabilityClassifier.PERMISSION_ACCESS_NETWORK_STATE)
        )

        assertEquals(CapabilityClassification.Supported, result)
    }

    @Test
    fun wifiCharacteristics_withoutFineLocation_isNotReliablyAvailable() {
        val result = MeasurementCapabilityClassifier.classify(
            capability = MeasurementCapability.WIFI_CHARACTERISTICS,
            sdkInt = 34,
            grantedPermissions = emptySet()
        )

        assertTrue(result is CapabilityClassification.NotReliablyAvailable)
    }

    @Test
    fun wifiCharacteristics_withFineLocation_isSupportedWithLimitations() {
        val result = MeasurementCapabilityClassifier.classify(
            capability = MeasurementCapability.WIFI_CHARACTERISTICS,
            sdkInt = 34,
            grantedPermissions = setOf(MeasurementCapabilityClassifier.PERMISSION_ACCESS_FINE_LOCATION)
        )

        assertTrue(result is CapabilityClassification.SupportedWithLimitations)
    }

    @Test
    fun cellularCharacteristics_requiresBothReadPhoneStateAndFineLocation() {
        val onlyLocation = MeasurementCapabilityClassifier.classify(
            capability = MeasurementCapability.CELLULAR_CHARACTERISTICS,
            sdkInt = 34,
            grantedPermissions = setOf(MeasurementCapabilityClassifier.PERMISSION_ACCESS_FINE_LOCATION)
        )
        val onlyPhoneState = MeasurementCapabilityClassifier.classify(
            capability = MeasurementCapability.CELLULAR_CHARACTERISTICS,
            sdkInt = 34,
            grantedPermissions = setOf(MeasurementCapabilityClassifier.PERMISSION_READ_PHONE_STATE)
        )
        val both = MeasurementCapabilityClassifier.classify(
            capability = MeasurementCapability.CELLULAR_CHARACTERISTICS,
            sdkInt = 34,
            grantedPermissions = setOf(
                MeasurementCapabilityClassifier.PERMISSION_READ_PHONE_STATE,
                MeasurementCapabilityClassifier.PERMISSION_ACCESS_FINE_LOCATION
            )
        )

        assertTrue(onlyLocation is CapabilityClassification.NotReliablyAvailable)
        assertTrue(onlyPhoneState is CapabilityClassification.NotReliablyAvailable)
        assertTrue(both is CapabilityClassification.SupportedWithLimitations)
    }

    @Test
    fun everyCapability_hasAClassification_exhaustiveWhenCompiles() {
        // Not a behavioral assertion -- this test exists so that adding a
        // new MeasurementCapability case without updating the classifier
        // fails at compile time (the `when` in classify() is exhaustive,
        // no else branch), not silently at runtime.
        for (capability in MeasurementCapability.entries) {
            val result = MeasurementCapabilityClassifier.classify(
                capability = capability,
                sdkInt = 34,
                grantedPermissions = emptySet()
            )
            assertTrue(
                result is CapabilityClassification.Supported ||
                    result is CapabilityClassification.SupportedWithLimitations ||
                    result is CapabilityClassification.Estimated ||
                    result is CapabilityClassification.NotReliablyAvailable
            )
        }
    }
}
