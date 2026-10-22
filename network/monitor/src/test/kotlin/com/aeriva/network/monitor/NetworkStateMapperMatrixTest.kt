package com.aeriva.network.monitor

import com.aeriva.core.model.DiagnosticsStatus
import com.aeriva.core.model.NetworkQuality
import com.aeriva.core.model.TransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * AI 4 test gate (Phase 4 test-gate specification, groups CX and MN).
 *
 * Exhaustive and boundary cases for the network-context fields the
 * measurement engine relies on: VPN, captive portal, metered, blocked by
 * device policy, and the guarantee that the mapper never invents a quality
 * score or a timestamp. Complements [NetworkStateMapperTest] and does not
 * edit it.
 */
class NetworkStateMapperMatrixTest {

    private val changedAt = Instant.parse("2026-09-21T06:00:00Z")

    private val priority = listOf(
        TransportType.VPN, TransportType.ETHERNET, TransportType.WIFI, TransportType.CELLULAR, TransportType.OTHER
    )

    private fun snapshot(
        transports: Set<TransportType> = setOf(TransportType.WIFI),
        names: Set<String> = emptySet(),
        notMetered: Boolean = true,
        validated: Boolean = true,
        hasInternet: Boolean = true
    ) = RawCapabilitiesSnapshot(
        hasInternet = hasInternet,
        isValidated = validated,
        isNotMetered = notMetered,
        transports = transports,
        rawCapabilityNames = names
    )

    private fun subsetsOfTransports(): List<Set<TransportType>> =
        (0 until (1 shl priority.size)).map { mask ->
            priority.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
        }

    @Test
    fun transportResolution_followsTheDocumentedPriority_forEverySubset() {
        val bad = mutableListOf<String>()
        for (set in subsetsOfTransports()) {
            val expected = priority.firstOrNull { it in set } ?: TransportType.NONE
            val actual = NetworkStateMapper.buildNetworkState(true, snapshot(transports = set), changedAt).transport
            if (actual != expected) bad += "$set expected=$expected actual=$actual"
        }
        assertTrue("wrong transport for: $bad", bad.isEmpty())
    }

    @Test
    fun vpnFlag_isTrueExactlyWhenTheVpnTransportIsPresent_forEverySubset() {
        for (set in subsetsOfTransports()) {
            val state = NetworkStateMapper.buildNetworkState(true, snapshot(transports = set), changedAt)
            assertEquals("vpnPresent for $set", TransportType.VPN in set, state.vpnPresent)
        }
    }

    @Test
    fun vpnOverWifi_reportsBothFacts_soCallersCanReasonAboutEither() {
        val state = NetworkStateMapper.buildNetworkState(
            true, snapshot(transports = setOf(TransportType.VPN, TransportType.WIFI)), changedAt
        )
        assertEquals(TransportType.VPN, state.transport)
        assertTrue(state.vpnPresent)
    }

    @Test
    fun captivePortal_isRecognisedOnlyByTheExactCapabilityName() {
        val exact = NetworkStateMapper.buildNetworkState(true, snapshot(names = setOf("INTERNET", "CAPTIVE_PORTAL")), changedAt)
        assertTrue(exact.captivePortalReported)
        for (nearMiss in listOf("NOT_CAPTIVE_PORTAL", "captive_portal", "CAPTIVE_PORTAL_EXTRA", "PORTAL", "")) {
            val state = NetworkStateMapper.buildNetworkState(true, snapshot(names = setOf(nearMiss)), changedAt)
            assertFalse("'$nearMiss' must not be read as a captive portal", state.captivePortalReported)
        }
    }

    @Test
    fun captivePortalReport_isIndependentOfValidation() {
        // The platform can report the flag while still validated, or unvalidated with no flag. Both facts pass through untouched.
        val flaggedAndValidated = NetworkStateMapper.buildNetworkState(
            true, snapshot(names = setOf("CAPTIVE_PORTAL"), validated = true), changedAt
        )
        assertTrue(flaggedAndValidated.captivePortalReported && flaggedAndValidated.validated)
        val unflaggedUnvalidated = NetworkStateMapper.buildNetworkState(
            true, snapshot(names = emptySet(), validated = false), changedAt
        )
        assertFalse(unflaggedUnvalidated.captivePortalReported)
        assertFalse(unflaggedUnvalidated.validated)
    }

    @Test
    fun metered_isTheNegationOfNotMetered_bothWays() {
        assertTrue(NetworkStateMapper.buildNetworkState(true, snapshot(notMetered = false), changedAt).metered)
        assertFalse(NetworkStateMapper.buildNetworkState(true, snapshot(notMetered = true), changedAt).metered)
    }

    @Test
    fun blockedFlag_passesThroughInBothBranches_andDefaultsToNotBlocked() {
        assertTrue(NetworkStateMapper.buildNetworkState(true, snapshot(), changedAt, blocked = true).blockedByDevicePolicy)
        assertTrue(NetworkStateMapper.buildNetworkState(false, null, changedAt, blocked = true).blockedByDevicePolicy)
        assertFalse(NetworkStateMapper.buildNetworkState(true, snapshot(), changedAt).blockedByDevicePolicy)
        assertFalse(NetworkStateMapper.buildNetworkState(false, null, changedAt).blockedByDevicePolicy)
    }

    @Test
    fun offlineShape_leaksNoFlagsFromAStaleSnapshot() {
        // available=false must win over any snapshot still in hand: no VPN, portal, metered or validated carry-over.
        val stale = snapshot(
            transports = setOf(TransportType.VPN, TransportType.WIFI),
            names = setOf("CAPTIVE_PORTAL", "INTERNET"),
            notMetered = false,
            validated = true
        )
        val state = NetworkStateMapper.buildNetworkState(false, stale, changedAt)
        assertEquals(TransportType.NONE, state.transport)
        assertFalse(state.available)
        assertFalse(state.validated)
        assertFalse(state.metered)
        assertFalse(state.vpnPresent)
        assertFalse(state.captivePortalReported)
        assertTrue(state.capabilities.isEmpty())
    }

    @Test
    fun availableWithoutASnapshot_isTreatedAsOffline_notAsAnEmptyOnlineNetwork() {
        val state = NetworkStateMapper.buildNetworkState(true, null, changedAt)
        assertFalse(state.available)
        assertEquals(TransportType.NONE, state.transport)
    }

    @Test
    fun mapperNeverInventsAQualityScoreOrDiagnostics_inAnyBranch() {
        val online = NetworkStateMapper.buildNetworkState(true, snapshot(), changedAt)
        val offline = NetworkStateMapper.buildNetworkState(false, null, changedAt)
        for (state in listOf(online, offline)) {
            assertEquals(NetworkQuality.Unavailable, state.estimatedQuality)
            assertEquals(DiagnosticsStatus.NotAvailable, state.diagnosticsStatus)
        }
    }

    @Test
    fun lastChangedAt_isTheCallersInstant_neverTheWallClock() {
        assertEquals(changedAt, NetworkStateMapper.buildNetworkState(true, snapshot(), changedAt).lastChangedAt)
        assertEquals(changedAt, NetworkStateMapper.buildNetworkState(false, null, changedAt).lastChangedAt)
    }

    @Test
    fun capabilityNames_arePassedThroughUnchanged() {
        val names = setOf("INTERNET", "NOT_RESTRICTED", "VALIDATED")
        assertEquals(names, NetworkStateMapper.buildNetworkState(true, snapshot(names = names), changedAt).capabilities)
    }
}
