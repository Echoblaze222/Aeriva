package com.aeriva.core.security

import com.aeriva.core.result.AerivaResult

/**
 * Encrypted key-value storage for the assets security doc Section 2/4
 * names explicitly: authentication tokens, relay session credentials,
 * private keys. Nothing in this codebase authenticates or connects to a
 * Relay yet (per the roadmap, those are later phases), so no specific
 * key names are declared here -- same reasoning as AerivaPreferences.
 * This module only makes storing whatever key a future feature defines
 * safe to do.
 *
 * Not a replacement for AerivaPreferences: per security doc Section 8,
 * "sensitive local data should use secure platform storage WHERE
 * APPROPRIATE" -- ordinary non-sensitive settings belong in
 * AerivaPreferences. Encryption has a real performance/complexity cost;
 * reaching for this by default for everything would be its own mistake.
 */
interface AerivaSecureStorage {
    suspend fun get(key: String): AerivaResult<String?>
    suspend fun set(key: String, value: String): AerivaResult<Unit>
    suspend fun remove(key: String): AerivaResult<Unit>
    suspend fun clear(): AerivaResult<Unit>
}
