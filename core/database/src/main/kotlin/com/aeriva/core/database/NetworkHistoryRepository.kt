package com.aeriva.core.database

import android.database.sqlite.SQLiteDatabaseCorruptException
import com.aeriva.core.logging.AerivaLogger
import com.aeriva.core.model.NetworkState
import com.aeriva.core.model.TransportType
import com.aeriva.core.result.AerivaError
import com.aeriva.core.result.AerivaResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.Instant

interface NetworkHistoryRepository {
    suspend fun record(state: NetworkState, at: Instant = Instant.now()): AerivaResult<Unit>
    suspend fun recent(limit: Int): AerivaResult<List<NetworkStateHistoryRecord>>
    fun observeRecent(limit: Int): Flow<AerivaResult<List<NetworkStateHistoryRecord>>>
    suspend fun pruneOlderThan(retention: Duration, now: Instant = Instant.now()): AerivaResult<Int>
}

class RoomNetworkHistoryRepository(
    private val dao: NetworkStateHistoryDao,
    private val logger: AerivaLogger
) : NetworkHistoryRepository {

    override suspend fun record(state: NetworkState, at: Instant): AerivaResult<Unit> =
        runCatching {
            dao.insert(
                NetworkStateHistoryEntity(
                    transport = state.transport.name,
                    available = state.available,
                    validated = state.validated,
                    metered = state.metered,
                    recordedAtEpochMillis = at.toEpochMilli()
                )
            )
        }.fold(
            onSuccess = { AerivaResult.Success(Unit) },
            onFailure = { AerivaResult.Failure(it.toAerivaError(logger)) }
        )

    override suspend fun recent(limit: Int): AerivaResult<List<NetworkStateHistoryRecord>> =
        runCatching { dao.recent(limit).map { it.toDomain() } }
            .fold(
                onSuccess = { AerivaResult.Success(it) },
                onFailure = { AerivaResult.Failure(it.toAerivaError(logger)) }
            )

    override fun observeRecent(limit: Int): Flow<AerivaResult<List<NetworkStateHistoryRecord>>> =
        dao.observeRecent(limit)
            .map<List<NetworkStateHistoryEntity>, AerivaResult<List<NetworkStateHistoryRecord>>> { entities ->
                AerivaResult.Success(entities.map { it.toDomain() })
            }
            .catch { throwable -> emit(AerivaResult.Failure(throwable.toAerivaError(logger))) }

    override suspend fun pruneOlderThan(retention: Duration, now: Instant): AerivaResult<Int> =
        runCatching { dao.deleteOlderThan(now.minus(retention).toEpochMilli()) }
            .fold(
                onSuccess = { AerivaResult.Success(it) },
                onFailure = { AerivaResult.Failure(it.toAerivaError(logger)) }
            )

    private companion object {
        const val TAG = "NetworkHistoryRepository"
    }

    private fun Throwable.toAerivaError(logger: AerivaLogger): AerivaError {
        logger.e(TAG, "network_state_history operation failed", this)
        return if (this is SQLiteDatabaseCorruptException) {
            AerivaError.DataCorrupted(this)
        } else {
            AerivaError.Unknown(this)
        }
    }
}

private fun NetworkStateHistoryEntity.toDomain(): NetworkStateHistoryRecord =
    NetworkStateHistoryRecord(
        // TransportType.valueOf() throws IllegalArgumentException on an
        // unrecognized name -- deliberately NOT caught here, so a
        // corrupted/foreign row surfaces as a real failure through the
        // same runCatching in the calling repository method rather than
        // silently defaulting to a transport the row didn't actually
        // record.
        transport = TransportType.valueOf(transport),
        available = available,
        validated = validated,
        metered = metered,
        recordedAt = Instant.ofEpochMilli(recordedAtEpochMillis)
    )
