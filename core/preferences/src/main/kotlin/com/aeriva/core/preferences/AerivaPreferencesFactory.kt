package com.aeriva.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.aeriva.core.logging.AerivaLogger
import kotlinx.coroutines.CoroutineScope

/**
 * A composition root should call [create] exactly once per process and
 * hold the result as a singleton -- DataStore documents that creating
 * more than one instance for the same file corrupts reads/writes. No
 * such composition root exists yet (app module has no DI wiring), so
 * nothing in this codebase calls this yet.
 */
object AerivaPreferencesFactory {

    private const val PREFERENCES_FILE_NAME = "aeriva_preferences"

    fun create(context: Context, logger: AerivaLogger, scope: CoroutineScope): AerivaPreferences {
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { throwable ->
                logger.e(TAG, "Preferences file corrupted, resetting to empty", throwable)
                emptyPreferences()
            },
            scope = scope,
            produceFile = { context.applicationContext.preferencesDataStoreFile(PREFERENCES_FILE_NAME) }
        )
        return DataStoreAerivaPreferences(dataStore, logger)
    }

    private const val TAG = "AerivaPreferencesFactory"
}
