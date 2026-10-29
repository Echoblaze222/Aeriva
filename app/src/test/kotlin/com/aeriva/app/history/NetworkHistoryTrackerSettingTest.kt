package com.aeriva.app.history

import com.aeriva.core.database.NetworkHistoryRepository
import com.aeriva.core.database.NetworkStateHistoryRecord
import com.aeriva.core.model.NetworkState
import com.aeriva.core.model.TransportType
import com.aeriva.core.result.AerivaResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * The user's "Save connection history" setting as [NetworkHistoryTracker]
 * applies it: nothing is recorded while the setting is unknown or off, and
 * recording starts from the first state seen once it is known to be on.
 */
class NetworkHistoryTrackerSettingTest {

    private class FakeHistoryRepository : NetworkHistoryRepository {
        val stored = MutableStateFlow<List<NetworkStateHistoryRecord>>(emptyList())

        override suspend fun record(state: NetworkState, at: Instant): AerivaResult<Unit> {
            stored.value = listOf(
                NetworkStateHistoryRecord(state.transport, state.available, state.validated, state.metered, at)
            ) + stored.value
            return AerivaResult.Success(Unit)
        }

        override suspend fun recent(limit: Int): AerivaResult<List<NetworkStateHistoryRecord>> =
            AerivaResult.Success(stored.value.take(limit))

        override fun observeRecent(limit: Int): Flow<AerivaResult<List<NetworkStateHistoryRecord>>> =
            stored.map { AerivaResult.Success(it.take(limit)) }

        override suspend fun pruneOlderThan(retention: Duration, now: Instant): AerivaResult<Int> =
            AerivaResult.Success(0)
    }

    private val seed = NetworkState.unknown(Instant.EPOCH)
    private val wifi = NetworkState.unknown(Instant.ofEpochMilli(1)).copy(
        transport = TransportType.WIFI,
        available = true,
        validated = true
    )

    @Test
    fun nothingIsRecordedWhileTheSettingIsUnknown_thenTheCurrentStateIsRecordedOnceItIsOn() = runTest {
        val repository = FakeHistoryRepository()
        val enabled = MutableStateFlow<Boolean?>(null)
        NetworkHistoryTracker(
            states = MutableStateFlow(wifi),
            repository = repository,
            scope = backgroundScope,
            seed = seed,
            recordingEnabled = enabled
        )
        runCurrent()
        assertTrue(repository.stored.value.isEmpty())

        enabled.value = true
        runCurrent()

        assertEquals(1, repository.stored.value.size)
    }

    @Test
    fun nothingIsRecordedWhileTheSettingIsOff() = runTest {
        val repository = FakeHistoryRepository()
        NetworkHistoryTracker(
            states = MutableStateFlow(wifi),
            repository = repository,
            scope = backgroundScope,
            seed = seed,
            recordingEnabled = MutableStateFlow(false)
        )
        runCurrent()

        assertTrue(repository.stored.value.isEmpty())
    }

    @Test
    fun changesAreRecordedAgainOnceTheSettingIsTurnedBackOn() = runTest {
        val repository = FakeHistoryRepository()
        val enabled = MutableStateFlow<Boolean?>(false)
        val states = MutableStateFlow(wifi)
        NetworkHistoryTracker(
            states = states,
            repository = repository,
            scope = backgroundScope,
            seed = seed,
            recordingEnabled = enabled
        )
        runCurrent()
        assertTrue(repository.stored.value.isEmpty())

        enabled.value = true
        states.value = wifi.copy(transport = TransportType.CELLULAR, lastChangedAt = Instant.ofEpochMilli(2))
        runCurrent()

        assertEquals(1, repository.stored.value.size)
        assertEquals(TransportType.CELLULAR, repository.stored.value.single().transport)
    }
}
