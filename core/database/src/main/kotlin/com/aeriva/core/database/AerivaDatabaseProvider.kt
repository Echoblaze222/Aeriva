package com.aeriva.core.database

import android.content.Context
import androidx.room.Room
import com.aeriva.core.logging.AerivaLogger

/**
 * Builds [AerivaDatabase]. Deliberately does NOT call
 * fallbackToDestructiveMigration(): per
 * PHASE_0_PLATFORM_VALIDATION.md's "Database migration failure"
 * scenario, a schema mismatch must surface as a handled failure the
 * repository layer can report, not silently wipe the user's history.
 * Room does not throw at build() time for a bad migration or a
 * corrupted file -- that surfaces on first real query, which is why
 * corruption handling lives in the repository (see
 * RoomNetworkHistoryRepository), not here. This provider's job is only
 * to construct the database without an unsafe fallback configured.
 */
object AerivaDatabaseProvider {

    fun create(context: Context): AerivaDatabase =
        Room.databaseBuilder(
            context.applicationContext,
            AerivaDatabase::class.java,
            AerivaDatabase.DATABASE_NAME
        ).build()

    /**
     * Explicit, deliberate recovery step for an unrecoverable corrupt
     * database -- NOT called automatically by anything in this module.
     * A composition root should call this only after a repository call
     * has reported [com.aeriva.core.result.AerivaError.DataCorrupted]
     * and the user (or a defined policy) has confirmed starting over is
     * acceptable, then log the event and rebuild via [create]. Silently
     * invoking this on first corruption would be exactly the "silently
     * destroy data" outcome Phase 0 flags as unacceptable.
     */
    fun deleteDatabaseFile(context: Context, logger: AerivaLogger): Boolean {
        val deleted = context.applicationContext.deleteDatabase(AerivaDatabase.DATABASE_NAME)
        logger.w(TAG, "Deleted AERIVA database file as an explicit recovery step: $deleted")
        return deleted
    }

    private const val TAG = "AerivaDatabaseProvider"
}
