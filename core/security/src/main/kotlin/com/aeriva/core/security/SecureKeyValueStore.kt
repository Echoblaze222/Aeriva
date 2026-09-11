package com.aeriva.core.security

/**
 * The four operations [EncryptedPreferencesSecureStorage] actually
 * needs from android.content.SharedPreferences, instead of depending on
 * that whole interface directly. Real production abstraction, not a
 * test seam invented for its own sake: it is what
 * [SharedPreferencesKeyValueStore] genuinely adapts EncryptedSharedPreferences
 * to. The payoff is that tests can implement this 4-method interface by
 * hand instead of needing a full SharedPreferences fake or an Android
 * Keystore-backed instrumented test for logic that has nothing to do
 * with encryption itself.
 */
internal interface SecureKeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
    fun clear()
}
