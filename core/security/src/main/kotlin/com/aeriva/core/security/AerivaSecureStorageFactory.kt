package com.aeriva.core.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.aeriva.core.common.AerivaDispatchers
import com.aeriva.core.logging.AerivaLogger

/**
 * If the Android Keystore key backing the encrypted file is gone (e.g.
 * OS-level restore to new hardware, Keystore reset) the existing
 * encrypted values are cryptographically unreadable -- there is no
 * "recover the data" option, unlike the database/preferences corruption
 * cases where recovery was deliberately left as a manual, non-automatic
 * step. Here, automatically deleting the unreadable file and starting
 * over is the only rational choice, not a shortcut; it is still logged
 * so the event is visible rather than silent.
 */
object AerivaSecureStorageFactory {

    private const val FILE_NAME = "aeriva_secure_prefs"

    fun create(context: Context, logger: AerivaLogger, dispatchers: AerivaDispatchers): AerivaSecureStorage {
        val appContext = context.applicationContext
        val preferences = try {
            buildEncryptedPreferences(appContext)
        } catch (e: Exception) {
            logger.e(TAG, "Secure storage unusable (Keystore key lost or invalid), resetting", e)
            appContext.deleteSharedPreferences(FILE_NAME)
            buildEncryptedPreferences(appContext)
        }
        return EncryptedPreferencesSecureStorage(
            SharedPreferencesKeyValueStore(preferences),
            dispatchers,
            logger
        )
    }

    private fun buildEncryptedPreferences(context: Context) = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private const val TAG = "AerivaSecureStorageFactory"
}
