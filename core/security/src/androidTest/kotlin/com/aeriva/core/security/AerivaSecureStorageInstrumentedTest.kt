package com.aeriva.core.security

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aeriva.core.common.DefaultAerivaDispatchers
import com.aeriva.core.logging.NoOpLogger
import com.aeriva.core.result.AerivaResult
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real Android Keystore-backed EncryptedSharedPreferences,
 * unlike EncryptedPreferencesSecureStorageTest's fake-store unit tests.
 * Needs a device/emulator -- a JVM unit test cannot create Keystore
 * keys. This is the piece that actually proves encryption/decryption
 * round-trips correctly.
 */
@RunWith(AndroidJUnit4::class)
class AerivaSecureStorageInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val storage = AerivaSecureStorageFactory.create(context, NoOpLogger, DefaultAerivaDispatchers())

    @After
    fun tearDown() = runTest {
        storage.clear()
    }

    @Test
    fun set_thenGet_roundTripsThroughRealEncryption() = runTest {
        storage.set("token", "real-secret-value")

        val result = storage.get("token")

        assertEquals(AerivaResult.Success("real-secret-value"), result)
    }
}
