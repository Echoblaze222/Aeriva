package com.aeriva.app.advisor

import com.aeriva.core.model.TransportType
import com.aeriva.core.model.advisor.CandidateNetwork
import com.aeriva.core.model.advisor.CandidateNetworkId
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The "Your networks" list: one row per network Android reports, default
 * first, carrying only the flags Android reported.
 */
class NetworkRowsTest {

    private fun network(
        id: Long,
        transport: TransportType,
        validated: Boolean,
        isDefault: Boolean = false,
        metered: Boolean = false,
        captivePortal: Boolean = false,
        blocked: Boolean = false
    ) = CandidateNetwork(
        id = CandidateNetworkId(id),
        transport = transport,
        validated = validated,
        metered = metered,
        captivePortalReported = captivePortal,
        blockedByDevicePolicy = blocked,
        vpnPresent = false,
        isDefault = isDefault,
        observedAt = Instant.EPOCH
    )

    private fun kotlinx.coroutines.test.TestScope.holder(
        candidates: MutableSharedFlow<List<CandidateNetwork>>
    ) = AdvisorStateHolder(
        candidates = candidates,
        stale = MutableStateFlow(false),
        scope = backgroundScope,
        now = { Instant.ofEpochMilli(testScheduler.currentTime) }
    )

    @Test
    fun rowsListEveryNetwork_withTheDefaultFirst_andOnlyTheFlagsAndroidReported() = runTest {
        val candidates = MutableSharedFlow<List<CandidateNetwork>>(replay = 1)
        val holder = holder(candidates)
        candidates.emit(
            listOf(
                network(2, TransportType.CELLULAR, validated = true, metered = true),
                network(1, TransportType.WIFI, validated = false, isDefault = true, captivePortal = true)
            )
        )
        runCurrent()

        val rows = holder.networks.first()

        assertEquals(2, rows.size)
        assertTrue(rows[0].isDefault)
        assertEquals(TransportType.WIFI, rows[0].label.transport)
        assertFalse(rows[0].validated)
        assertTrue(rows[0].captivePortalReported)
        assertEquals(TransportType.CELLULAR, rows[1].label.transport)
        assertTrue(rows[1].validated)
        assertTrue(rows[1].metered)
        assertFalse(rows[1].isDefault)
    }

    @Test
    fun twoNetworksOfOneTransport_areNumbered_andASingleOneIsNot() = runTest {
        val candidates = MutableSharedFlow<List<CandidateNetwork>>(replay = 1)
        val holder = holder(candidates)
        candidates.emit(
            listOf(
                network(1, TransportType.WIFI, validated = true, isDefault = true),
                network(2, TransportType.CELLULAR, validated = true),
                network(3, TransportType.CELLULAR, validated = false)
            )
        )
        runCurrent()

        val rows = holder.networks.first()

        assertNull(rows.first { it.label.transport == TransportType.WIFI }.label.number)
        val mobile = rows.filter { it.label.transport == TransportType.CELLULAR }
        assertEquals(listOf(1, 2), mobile.map { it.label.number })
    }

    @Test
    fun aBlockedNetworkIsFlagged() = runTest {
        val candidates = MutableSharedFlow<List<CandidateNetwork>>(replay = 1)
        val holder = holder(candidates)
        candidates.emit(listOf(network(1, TransportType.WIFI, validated = true, isDefault = true, blocked = true)))
        runCurrent()

        assertTrue(holder.networks.first().single().blockedByDevicePolicy)
    }
}
