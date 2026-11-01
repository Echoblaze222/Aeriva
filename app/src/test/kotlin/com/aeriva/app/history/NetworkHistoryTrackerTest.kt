package com.aeriva.app.history

import com.aeriva.core.database.NetworkHistoryRepository
import com.aeriva.core.database.NetworkStateHistoryRecord
import com.aeriva.core.model.NetworkState
import com.aeriva.core.model.TransportType
import com.aeriva.core.result.AerivaResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Drives [NetworkHistoryTracker] with a fake in memory repository and virtual
 * time. Covers what is recorded (real changes only, never the placeholder, never
 * a repeat), the 30 day prune on start, and clearing.
 *
 * The Room database itself is covered by NetworkStateHistoryDaoTest and
 * NetworkHistoryRepositoryTest in :core:database. This does not touch it.
 */
class NetworkHistoryTrackerTest {

    private class FakeHistoryRepository : NetworkHistoryRepository {
        val stored = MutableStateFlow<List<NetworkStateHistoryRecord>>(emptyList())
        val pruneRetentions = mutableListOf<Duration>()

        override suspend fun record(state: NetworkState, at: Instant): AerivaResult<Unit> {
            val record = NetworkStateHistoryRecord(
                transport = state.transport,
                available = state.available,
                validated = state.validated,
                metered = state.metered,
                recordedAt = at
            )
            stored.value = listOf(record) + stored.value
            return AerivaResult.Success(Unit)
        }

        override suspend fun recent(limit: Int): AerivaResult<List<NetworkStateHistoryRecord>> =
            AerivaResult.Success(stored.value.take(limit))

        override fun observeRecent(limit: Int): Flow<AerivaResult<List<NetworkStateHistoryRecord>>> =
            stored.map { AerivaResult.Success(it.take(limit)) }

        override suspend fun pruneOlderThan(retention: Duration, now: Instant): AerivaResult<Int> {
            pruneRetentions += retention
            val cutoff = now.minus(retention)
            val before = stored.value.size
            stored.value = stored.value.filter { !it.recordedAt.isBefore(cutoff) }
            return AerivaResult.Success(before - stored.value.size)
        }
    }

    private val seed = NetworkState.unknown(Instant.EPOCH)

    private fun state(
        at: Long,
        transport: TransportType = TransportType.WIFI,
        available: Boolean = true,
        validated: Boolean = true,
        metered: Boolean = false
    ) = NetworkState.unknown(Instant.ofEpochMilli(at)).copy(
        transport = transport,
        available = available,
        validated = validated,
        metered = metered
    )

    @Test
    fun thePlaceholderStateIsNeverRecorded() = runTest {
        val repository = FakeHistoryRepository()
        val states = MutableSharedFlow<NetworkState>()
        NetworkHistoryTracker(
            states = states,
            repository = repository,
            scope = backgroundScope,
            seed = seed,
            now = { Instant.ofEpochMilli(testScheduler.currentTime) }
        )
        runCurrent()

        states.emit(seed)
        states.emit(state(at = 1))
        runCurrent()

        assertEquals(1, repository.stored.value.size)
        assertEquals(TransportType.WIFI, repository.stored.value.single().transport)
    }

    @Test
    fun aStateThatDiffersOnlyInItsTimestampIsNotRecordedAgain() = runTest {
        val repository = FakeHistoryRepository()
        val states = MutableSharedFlow<NetworkState>()
        NetworkHistoryTracker(
            states = states,
            repository = repository,
            scope = backgroundScope,
            seed = seed,
            now = { Instant.ofEpochMilli(testScheduler.currentTime) }
        )
        runCurrent()

        states.emit(state(at = 1))
        states.emit(state(at = 2))
        runCurrent()
        assertEquals(1, repository.stored.value.size)

        states.emit(state(at = 3, validated = false))
        states.emit(state(at = 4, transport = TransportType.CELLULAR, validated = true))
        states.emit(state(at = 5, available = false, validated = false, transport = TransportType.NONE))
        runCurrent()

        assertEquals(4, repository.stored.value.size)
        assertEquals(TransportType.NONE, repository.stored.value.first().transport)
    }

    @Test
    fun theMeteredFlagChangingIsARecordedChange() = runTest {
        val repository = FakeHistoryRepository()
        val states = MutableSharedFlow<NetworkState>()
        NetworkHistoryTracker(
            states = states,
            repository = repository,
            scope = backgroundScope,
            seed = seed,
            now = { Instant.ofEpochMilli(testScheduler.currentTime) }
        )
        runCurrent()

        states.emit(state(at = 1, metered = false))
        states.emit(state(at = 2, metered = true))
        runCurrent()

        assertEquals(2, repository.stored.value.size)
    }

    @Test
    fun onStartItRemovesEverythingOlderThanThirtyDays() = runTest {
        val repository = FakeHistoryRepository()
        NetworkHistoryTracker(
            states = MutableSharedFlow(),
            repository = repository,
            scope = backgroundScope,
            seed = seed,
            now = { Instant.ofEpochMilli(testScheduler.currentTime) }
        )
        runCurrent()

        assertEquals(listOf(Duration.ofDays(30)), repository.pruneRetentions)
    }

    @Test
    fun clearingRemovesEveryRecordedChange() = runTest {
        val repository = FakeHistoryRepository()
        val states = MutableSharedFlow<NetworkState>()
        val tracker = NetworkHistoryTracker(
            states = states,
            repository = repository,
            scope = backgroundScope,
            seed = seed,
            now = { Instant.ofEpochMilli(testScheduler.currentTime) }
        )
        runCurrent()
        states.emit(state(at = 1))
        runCurrent()
        assertEquals(1, tracker.recent.value.size)

        advanceTimeBy(1_000L)
        tracker.clear()
        runCurrent()

        assertTrue(repository.stored.value.isEmpty())
        assertTrue(tracker.recent.value.isEmpty())
    }
}
