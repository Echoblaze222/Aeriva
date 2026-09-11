package com.aeriva.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [NetworkStateHistoryEntity::class],
    version = 1,
    exportSchema = true
)
abstract class AerivaDatabase : RoomDatabase() {

    abstract fun networkStateHistoryDao(): NetworkStateHistoryDao

    companion object {
        const val DATABASE_NAME = "aeriva.db"
    }
}
