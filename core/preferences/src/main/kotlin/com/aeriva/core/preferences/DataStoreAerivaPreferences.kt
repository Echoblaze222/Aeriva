package com.aeriva.core.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.aeriva.core.logging.AerivaLogger
import com.aeriva.core.result.AerivaError
import com.aeriva.core.result.AerivaResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * DataStore already recovers from a corrupted preferences file on its
 * own (see AerivaPreferencesFactory's ReplaceFileCorruptionHandler), so
 * unlike core:database this class does not need to distinguish
 * corruption from other failures -- by the time an exception reaches
 * here, DataStore has already decided it could not be handled
 * internally. [AerivaError.Unknown] is therefore the only failure case
 * this class produces.
 */
class DataStoreAerivaPreferences(
    private val dataStore: DataStore<Preferences>,
    private val logger: AerivaLogger
) : AerivaPreferences {

    override suspend fun <T> get(key: Preferences.Key<T>, default: T): AerivaResult<T> =
        runCatching { dataStore.data.recoveringFromIo().first()[key] ?: default }
            .fold(
                onSuccess = { AerivaResult.Success(it) },
                onFailure = { AerivaResult.Failure(it.toUnknownError()) }
            )

    override fun <T> observe(key: Preferences.Key<T>, default: T): Flow<AerivaResult<T>> =
        dataStore.data
            .recoveringFromIo()
            .map<Preferences, AerivaResult<T>> { AerivaResult.Success(it[key] ?: default) }
            .catch { emit(AerivaResult.Failure(it.toUnknownError())) }

    override suspend fun <T> set(key: Preferences.Key<T>, value: T): AerivaResult<Unit> =
        runCatching { dataStore.edit { it[key] = value } }
            .fold(
                onSuccess = { AerivaResult.Success(Unit) },
                onFailure = { AerivaResult.Failure(it.toUnknownError()) }
            )

    override suspend fun <T> remove(key: Preferences.Key<T>): AerivaResult<Unit> =
        runCatching { dataStore.edit { it.remove(key) } }
            .fold(
                onSuccess = { AerivaResult.Success(Unit) },
                onFailure = { AerivaResult.Failure(it.toUnknownError()) }
            )

    override suspend fun clear(): AerivaResult<Unit> =
        runCatching { dataStore.edit { it.clear() } }
            .fold(
                onSuccess = { AerivaResult.Success(Unit) },
                onFailure = { AerivaResult.Failure(it.toUnknownError()) }
            )

    /**
     * DataStore's own documented pattern: an IOException from the
     * underlying read is a transient read failure, not "the value is
     * unset" -- fall back to empty rather than letting it propagate as
     * a crash, while still logging so it is visible. A non-IOException
     * (e.g. a corruption handler giving up) is left to propagate to the
     * caller's runCatching/catch above.
     */
    private fun Flow<Preferences>.recoveringFromIo(): Flow<Preferences> = catch { throwable ->
        if (throwable is IOException) {
            logger.e(TAG, "Preferences read failed, using empty preferences", throwable)
            emit(emptyPreferences())
        } else {
            throw throwable
        }
    }

    private fun Throwable.toUnknownError(): AerivaError {
        logger.e(TAG, "Preferences operation failed", this)
        return AerivaError.Unknown(this)
    }

    private companion object {
        const val TAG = "AerivaPreferences"
    }
}
