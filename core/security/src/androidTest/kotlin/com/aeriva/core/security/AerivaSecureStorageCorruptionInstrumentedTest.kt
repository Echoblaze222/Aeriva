package com.aeriva.core.security

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aeriva.core.common.DefaultAerivaDispatchers
import com.aeriva.core.logging.NoOpLogger
import com.aeriva.core.result.AerivaError
import com.aeriva.core.result.AerivaResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * K.2: reproduces "corrupted encrypted file while the correct Keystore
 * key still exists" against the real Android Keystore and real
 * EncryptedSharedPreferences -- not simulated. This is a genuinely
 * different scenario from AerivaSecureStorageFactory's lost/invalid-key
 * recovery path (see that class's own doc comment): there, the
 * MasterKey/Keystore entry itself is gone or unusable, discovered when
 * EncryptedSharedPreferences.create() is called, before any value is
 * ever read. Here, the Keystore key is untouched and fully functional;
 * only the ciphertext bytes for one stored value, already on disk, have
 * been damaged (simulating disk corruption, a partial write, or
 * tampering) -- discovered only when that specific value is read.
 *
 * Needs a device/emulator -- a JVM unit test cannot create real
 * Keystore keys or exercise real Tink decryption failures. This is the
 * one place in this codebase that can prove K.2's real, on-device
 * behavior; EncryptedPreferencesSecureStorageTest's fake-store unit
 * tests (see get_whenStoreThrowsGeneralSecurityException_...) prove the
 * mapping logic in isolation, quickly and deterministically, but cannot
 * prove the real crypto layer actually throws what that logic expects.
 */
@RunWith(AndroidJUnit4::class)
class AerivaSecureStorageCorruptionInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun get_onRealCorruptedCiphertext_returnsDataCorrupted_withoutDestroyingOtherEntries() = runTest {
        // Start from a clean, real, Keystore-backed store.
        context.deleteSharedPreferences(AerivaSecureStorageFactory.FILE_NAME)
        val storage = AerivaSecureStorageFactory.create(context, NoOpLogger, DefaultAerivaDispatchers())
        storage.set("target_key", "original-value")

        // Corrupt the raw ciphertext on disk for the one real user
        // entry, leaving Tink's own keyset bootstrap entries untouched.
        // Those two names are fixed, public constants Jetpack Security
        // itself stores in plaintext (unencrypted) alongside the
        // encrypted user data, specifically so the library can read
        // them without needing to decrypt anything first -- corrupting
        // one of those would test key/keyset loss, the scenario
        // AerivaSecureStorageFactory already handles, not this one.
        // Locate the real on-disk preferences file. Context has no
        // public getter for this path (confirmed against Android's own
        // Context reference -- an earlier version of this test called a
        // getSharedPreferencesPath() that does not exist and failed to
        // compile). The standard, well-established construction is
        // filesDir's parent (the app's private data directory) plus
        // shared_prefs/<name>.xml, which is where getSharedPreferences()
        // itself is documented to create the file.
        val prefsFile = File(context.filesDir.parentFile, "shared_prefs/${AerivaSecureStorageFactory.FILE_NAME}.xml")
        val original = prefsFile.readText()
        val keysetNames = setOf(
            "__androidx_security_crypto_encrypted_prefs_key_keyset__",
            "__androidx_security_crypto_encrypted_prefs_value_keyset__"
        )
        val entryRegex = Regex("<string name=\"([^\"]+)\">([^<]*)</string>")
        val userEntries = entryRegex.findAll(original).filter { it.groupValues[1] !in keysetNames }.toList()
        assertEquals(
            "Expected exactly the one user entry set above -- if this fails, the file's " +
                "structure no longer matches what this test assumes and it must be revisited, " +
                "not force-corrected.",
            1,
            userEntries.size
        )
        val valueRange = userEntries.first().groups[2]!!.range
        val corruptedCiphertext = userEntries.first().groupValues[2].reversed()
        prefsFile.writeText(original.replaceRange(valueRange, corruptedCiphertext))

        // Reopen fresh from disk with the SAME, still-valid Keystore
        // key -- this is the "valid key, corrupted ciphertext" scenario.
        val reopened = AerivaSecureStorageFactory.create(context, NoOpLogger, DefaultAerivaDispatchers())
        val result = reopened.get("target_key")

        assertTrue(
            "Expected a Failure, not fabricated/default data standing in for the corrupted value",
            result is AerivaResult.Failure
        )
        assertTrue(
            "Expected DataCorrupted specifically, not a generic Unknown failure",
            (result as AerivaResult.Failure).error is AerivaError.DataCorrupted
        )

        // The store as a whole must survive -- one corrupted entry must
        // not destroy or become unusable for everything else.
        val setAfterCorruption = reopened.set("safe_key", "safe-value")
        assertEquals(AerivaResult.Success(Unit), setAfterCorruption)
        assertEquals(AerivaResult.Success("safe-value"), reopened.get("safe_key"))

        reopened.clear()
    }
}
