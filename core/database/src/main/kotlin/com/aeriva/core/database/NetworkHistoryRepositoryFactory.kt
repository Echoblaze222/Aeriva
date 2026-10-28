package com.aeriva.core.database

import android.content.Context
import com.aeriva.core.logging.AerivaLogger

/**
 * Builds the Room backed [NetworkHistoryRepository] without exposing the
 * database class itself. Callers that only need the repository (the app
 * module) then do not have to put Room on their own compile classpath, which
 * is what referencing [AerivaDatabase] directly would require.
 */
object NetworkHistoryRepositoryFactory {

    fun create(context: Context, logger: AerivaLogger): NetworkHistoryRepository =
        RoomNetworkHistoryRepository(
            dao = AerivaDatabaseProvider.create(context).networkStateHistoryDao(),
            logger = logger
        )
}
