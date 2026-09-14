package com.aeriva.core.security

import com.aeriva.core.common.AerivaDispatchers
import com.aeriva.core.logging.AerivaLogger
import com.aeriva.core.result.AerivaError
import com.aeriva.core.result.AerivaResult
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException

/**
 * SharedPreferences (and therefore EncryptedSharedPreferences) is
 * blocking I/O, so every call runs on [AerivaDispatchers.io] -- this is
 * core:common's first real consumer, not a speculative dependency.
 */
internal class EncryptedPreferencesSecureStorage(
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
        onFailure = { AerivaResult.Failure(it.toAerivaError()) }
    )

    /**
     * A per-value decrypt failure (corrupted ciphertext, still-valid
     * Keystore key -- distinct from a lost/invalid key, which
     * [AerivaSecureStorageFactory] handles at construction time, before
     * any [get]/[set]/[remove]/[clear] call here is possible) surfaces
     * from the underlying crypto layer as a
     * [java.security.GeneralSecurityException] -- Jetpack Security's
     * own reference docs state EncryptedSharedPreferences.create()
     * itself throws this type "when a bad master key or keyset has been
     * attempted", and javax.crypto's own AEAD tag-mismatch exceptions
     * (what a tampered/truncated ciphertext blob triggers) are
     * GeneralSecurityException subclasses per the JDK's own class
     * hierarchy. Interface methods like SharedPreferences.getString()
     * cannot declare checked exceptions absent from their parent
     * signature, so a real implementation must wrap a checked
     * GeneralSecurityException in an unchecked exception to propagate
     * it through getString() -- checking the cause chain as well as the
     * exception itself covers that wrapping without assuming one
     * specific wrapper type.
     *
     * This is deliberately mapped to the existing [AerivaError.DataCorrupted]
     * case (already defined for exactly this class of "storage engine
     * is fine, this specific data is not" failure -- see
     * core:database's identical pattern for SQLiteDatabaseCorruptException)
     * rather than a new error type or a silent fallback to null/default.
     */
    private fun Throwable.toAerivaError(): AerivaError {
        logger.e(TAG, "Secure storage operation failed", this)
        return if (this is GeneralSecurityException || cause is GeneralSecurityException) {
            AerivaError.DataCorrupted(this)
        } else {
            AerivaError.Unknown(this)
        }
    }

    private companion object {
        const val TAG = "AerivaSecureStorage"
    }
}
