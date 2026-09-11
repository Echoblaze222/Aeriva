package com.aeriva.core.preferences

import androidx.datastore.preferences.core.Preferences
import com.aeriva.core.result.AerivaResult
import kotlinx.coroutines.flow.Flow

/**
 * Generic typed key-value storage. No AERIVA-specific keys are declared
 * here or anywhere in this module -- no feature has asked for a
 * persisted setting yet (architecture doc's example settings, like
 * data-saver mode or a battery-optimization opt-in flag, belong to
 * their own features' Phase, not to this foundation module). Callers
 * define and own their own Preferences.Key<T> constants; this module
 * only owns making reads/writes of whatever key they define safe.
 */
interface AerivaPreferences {
    suspend fun <T> get(key: Preferences.Key<T>, default: T): AerivaResult<T>
    fun <T> observe(key: Preferences.Key<T>, default: T): Flow<AerivaResult<T>>
    suspend fun <T> set(key: Preferences.Key<T>, value: T): AerivaResult<Unit>
    suspend fun <T> remove(key: Preferences.Key<T>): AerivaResult<Unit>
    suspend fun clear(): AerivaResult<Unit>
}
