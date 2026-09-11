package com.aeriva.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real Room-generated DAO against an in-memory database,
 * unlike NetworkHistoryRepositoryTest's fake-DAO unit tests. Needs a
 * device/emulator to run -- this is the piece that actually proves the
 * @Query strings and schema are correct, which the fake DAO cannot.
 */
@RunWith(AndroidJUnit4::class)
class NetworkStateHistoryDaoTest {

    private lateinit var database: AerivaDatabase
    private lateinit var dao: NetworkStateHistoryDao

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AerivaDatabase::class.java
        ).build()
        dao = database.networkStateHistoryDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun insertThenRecent_returnsNewestFirst() = runTest {
        dao.insert(entity(recordedAtEpochMillis = 1_000, transport = "WIFI"))
        dao.insert(entity(recordedAtEpochMillis = 2_000, transport = "CELLULAR"))

        val recent = dao.recent(limit = 10)

        assertEquals(listOf("CELLULAR", "WIFI"), recent.map { it.transport })
    }

    @Test
    fun observeRecent_emitsOnInsert() = runTest {
        dao.insert(entity(recordedAtEpochMillis = 1_000, transport = "WIFI"))

        val emitted = dao.observeRecent(limit = 10).first()

        assertEquals(1, emitted.size)
        assertEquals("WIFI", emitted.first().transport)
    }

    @Test
    fun deleteOlderThan_removesOnlyOlderRows() = runTest {
        dao.insert(entity(recordedAtEpochMillis = 1_000, transport = "WIFI"))
        dao.insert(entity(recordedAtEpochMillis = 5_000, transport = "CELLULAR"))

        val deleted = dao.deleteOlderThan(beforeEpochMillis = 3_000)
        val remaining = dao.recent(limit = 10)

        assertEquals(1, deleted)
        assertEquals(listOf("CELLULAR"), remaining.map { it.transport })
    }

    private fun entity(recordedAtEpochMillis: Long, transport: String) = NetworkStateHistoryEntity(
        transport = transport,
        available = true,
        validated = true,
        metered = false,
        recordedAtEpochMillis = recordedAtEpochMillis
    )
}
