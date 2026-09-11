package com.aeriva.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Plain interface (the @Dao annotation does not change that) -- tests
 * can hand-implement a fake of this without Room or an Android runtime.
 * See NetworkHistoryRepositoryTest.
 */
@Dao
interface NetworkStateHistoryDao {

    @Insert
    suspend fun insert(entity: NetworkStateHistoryEntity): Long

    @Query("SELECT * FROM network_state_history ORDER BY recordedAtEpochMillis DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<NetworkStateHistoryEntity>

    @Query("SELECT * FROM network_state_history ORDER BY recordedAtEpochMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<NetworkStateHistoryEntity>>

    /**
     * Retention pruning. Nothing calls this automatically yet -- wiring
     * a schedule (WorkManager or similar) is Phase "Background Work",
     * not this change. Exposed now so the repository/call site can be
     * written and tested against a real query today.
     */
    @Query("DELETE FROM network_state_history WHERE recordedAtEpochMillis < :beforeEpochMillis")
    suspend fun deleteOlderThan(beforeEpochMillis: Long): Int
}
