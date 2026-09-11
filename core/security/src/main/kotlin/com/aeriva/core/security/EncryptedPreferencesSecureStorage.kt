package com.aeriva.core.security

import com.aeriva.core.common.AerivaDispatchers
import com.aeriva.core.logging.AerivaLogger
import com.aeriva.core.result.AerivaError
import com.aeriva.core.result.AerivaResult
import kotlinx.coroutines.withContext

/**
 * SharedPreferences (and therefore EncryptedSharedPreferences) is
 * blocking I/O, so every call runs on [AerivaDispatchers.io] -- this is
 * core:common's first real consumer, not a speculative dependency.
 */
class EncryptedPreferencesSecureStorage(
    private val store: SecureKeyValueStore,
    private val dispatchers: AerivaDispatchers,
    private val logger: AerivaLogger
) : AerivaSecureStorage {

    override suspend fun get(key: String): AerivaResult<String?> = withContext(dispatchers.io) {
        runCatching { store.getString(key) }.toResult()
    }

    override suspend fun set(key: String, value: String): AerivaResult<Unit> = withContext(dispatchers.io) {
        runCatching { store.putString(key, value) }.toResult()
    }

    override suspend fun remove(key: String): AerivaResult<Unit> = withContext(dispatchers.io) {
        runCatching { store.remove(key) }.toResult()
    }

    override suspend fun clear(): AerivaResult<Unit> = withContext(dispatchers.io) {
        runCatching { store.clear() }.toResult()
    }

    private fun <T> Result<T>.toResult(): AerivaResult<T> = fold(
        onSuccess = { AerivaResult.Success(it) },
        onFailure = {
            logger.e(TAG, "Secure storage operation failed", it)
            AerivaResult.Failure(AerivaError.Unknown(it))
        }
    )

    private companion object {
        const val TAG = "AerivaSecureStorage"
    }
}
