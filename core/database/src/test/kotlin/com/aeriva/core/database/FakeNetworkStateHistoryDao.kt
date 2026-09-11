package com.aeriva.core.database

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hand-implemented fake -- NetworkStateHistoryDao is a plain interface
 * (the @Dao annotation is only meaningful to Room's annotation
 * processor), so this needs no Room dependency or Android runtime.
 * [failNextWith] lets tests simulate a corrupted/failed database
 * operation without a real SQLite file.
 */
class FakeNetworkStateHistoryDao : NetworkStateHistoryDao {

    private val state = MutableStateFlow<List<NetworkStateHistoryEntity>>(emptyList())
    private var nextId = 1L
    var failNextWith: Throwable? = null

    override suspend fun insert(entity: NetworkStateHistoryEntity): Long {
        failNextWith?.let { throw it.also { failNextWith = null } }
        val stored = entity.copy(id = nextId++)
        state.value = (state.value + stored).sortedByDescending { it.recordedAtEpochMillis }
        return stored.id
    }

    override suspend fun recent(limit: Int): List<NetworkStateHistoryEntity> {
        failNextWith?.let { throw it.also { failNextWith = null } }
        return state.value.take(limit)
    }

    override fun observeRecent(limit: Int): Flow<List<NetworkStateHistoryEntity>> =
        state.asStateFlow().let { flow ->
            kotlinx.coroutines.flow.flow {
                failNextWith?.let { throw it.also { failNextWith = null } }
                flow.collect { emit(it.take(limit)) }
            }
        }

    override suspend fun deleteOlderThan(beforeEpochMillis: Long): Int {
        failNextWith?.let { throw it.also { failNextWith = null } }
        val before = state.value.size
        state.value = state.value.filter { it.recordedAtEpochMillis >= beforeEpochMillis }
        return before - state.value.size
    }
}
