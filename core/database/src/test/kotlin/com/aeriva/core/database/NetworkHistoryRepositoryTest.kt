package com.aeriva.core.database

import android.database.sqlite.SQLiteDatabaseCorruptException
import com.aeriva.core.logging.NoOpLogger
import com.aeriva.core.model.NetworkQuality
import com.aeriva.core.model.DiagnosticsStatus
import com.aeriva.core.model.NetworkState
import com.aeriva.core.model.TransportType
import com.aeriva.core.result.AerivaError
import com.aeriva.core.result.AerivaResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class NetworkHistoryRepositoryTest {

    private val recordedAt = Instant.parse("2026-09-11T00:00:00Z")

    private val wifiState = NetworkState(
        transport = TransportType.WIFI,
        available = true,
        validated = true,
        metered = false,
        capabilities = emptySet(),
        estimatedQuality = NetworkQuality.Unavailable,
        diagnosticsStatus = DiagnosticsStatus.NotAvailable,
        lastChangedAt = recordedAt,
        captivePortalReported = false,
        vpnPresent = false,
        blockedByDevicePolicy = false
    )

    private fun repository(dao: FakeNetworkStateHistoryDao = FakeNetworkStateHistoryDao()) =
        RoomNetworkHistoryRepository(dao, NoOpLogger) to dao

    @Test
    fun record_thenRecent_roundTripsTheState() = runTest {
        val (repository, _) = repository()

        repository.record(wifiState, at = recordedAt)
        val result = repository.recent(10)

        assertEquals(
            AerivaResult.Success(
                listOf(
                    NetworkStateHistoryRecord(
                        transport = TransportType.WIFI,
                        available = true,
                        validated = true,
                        metered = false,
                        recordedAt = recordedAt
                    )
                )
            ),
            result
        )
    }

    @Test
    fun recent_onCorruptDatabase_returnsDataCorruptedFailure() = runTest {
        val dao = FakeNetworkStateHistoryDao()
        val (repository, _) = repository(dao)
        dao.failNextWith = SQLiteDatabaseCorruptException("simulated corruption")

        val result = repository.recent(10)

        assertTrue(result is AerivaResult.Failure)
        assertTrue((result as AerivaResult.Failure).error is AerivaError.DataCorrupted)
    }

    @Test
    fun recent_onUnexpectedException_returnsUnknownFailure() = runTest {
        val dao = FakeNetworkStateHistoryDao()
        val (repository, _) = repository(dao)
        dao.failNextWith = IllegalStateException("simulated unexpected failure")

        val result = repository.recent(10)

        assertTrue(result is AerivaResult.Failure)
        assertTrue((result as AerivaResult.Failure).error is AerivaError.Unknown)
    }

    @Test
    fun observeRecent_onCorruption_emitsFailureInsteadOfThrowing() = runTest {
        val dao = FakeNetworkStateHistoryDao()
        val (repository, _) = repository(dao)
        dao.failNextWith = SQLiteDatabaseCorruptException("simulated corruption")

        val emission = repository.observeRecent(10).first()

        assertTrue(emission is AerivaResult.Failure)
        assertTrue((emission as AerivaResult.Failure).error is AerivaError.DataCorrupted)
    }

    @Test
    fun pruneOlderThan_removesOnlyEntriesOlderThanRetention() = runTest {
        val dao = FakeNetworkStateHistoryDao()
        val (repository, _) = repository(dao)
        val now = Instant.parse("2026-09-11T12:00:00Z")

        repository.record(wifiState, at = now.minus(Duration.ofDays(10)))
        repository.record(wifiState, at = now.minus(Duration.ofHours(1)))

        val result = repository.pruneOlderThan(Duration.ofDays(7), now = now)
        val remaining = repository.recent(10)

        assertEquals(AerivaResult.Success(1), result)
        assertEquals(1, (remaining as AerivaResult.Success).value.size)
    }
}
