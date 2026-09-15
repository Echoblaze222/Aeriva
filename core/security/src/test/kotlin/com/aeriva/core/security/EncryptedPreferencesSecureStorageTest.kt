package com.aeriva.core.security

import com.aeriva.core.common.AerivaDispatchers
import com.aeriva.core.logging.NoOpLogger
import com.aeriva.core.result.AerivaError
import com.aeriva.core.result.AerivaResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedPreferencesSecureStorageTest {

    // Local rather than reusing core:common's TestAerivaDispatchers --
    // that fixture lives in core:common's own test source set and isn't
    // visible across module boundaries without a testFixtures split.
    // Worth doing if a third module ends up needing it too; not yet.
    private class SingleDispatcherFixture(dispatcher: CoroutineDispatcher) : AerivaDispatchers {
        override val main = dispatcher
        override val io = dispatcher
        override val default = dispatcher
    }

    // scheduler MUST be the same TestCoroutineScheduler runTest itself is
    // driving (its TestScope.testScheduler), not a fresh
    // StandardTestDispatcher()'s own independent one -- storage.get()
    // does withContext(dispatchers.io) { ... }, and kotlinx-coroutines-test
    // throws IllegalStateException the moment a coroutine switches onto a
    // dispatcher backed by a different scheduler than the one runTest is
    // managing. This is what actually failed in CI (all 5 tests, same
    // exception) -- passing the scheduler through is the fix, not a
    // workaround.
    private fun storage(
        scheduler: TestCoroutineScheduler,
        store: FakeSecureKeyValueStore = FakeSecureKeyValueStore()
    ) = EncryptedPreferencesSecureStorage(
        store,
        SingleDispatcherFixture(StandardTestDispatcher(scheduler)),
        NoOpLogger
    ) to store

    @Test
    fun get_beforeAnyWrite_returnsNull() = runTest {
        val (storage, _) = storage(testScheduler)

        val result = storage.get("token")

        assertEquals(AerivaResult.Success(null), result)
    }

    @Test
    fun set_thenGet_returnsWrittenValue() = runTest {
        val (storage, _) = storage(testScheduler)

        storage.set("token", "secret-value")
        val result = storage.get("token")

        assertEquals(AerivaResult.Success("secret-value"), result)
    }

    @Test
    fun remove_deletesTheValue() = runTest {
        val (storage, _) = storage(testScheduler)
        storage.set("token", "secret-value")

        storage.remove("token")
        val result = storage.get("token")

        assertEquals(AerivaResult.Success(null), result)
    }

    @Test
    fun get_whenStoreThrows_returnsUnknownFailure() = runTest {
        val store = FakeSecureKeyValueStore()
        val (storage, _) = storage(testScheduler, store)
        store.failNextWith = IllegalStateException("simulated failure")

        val result = storage.get("token")

        assertTrue(result is AerivaResult.Failure)
        assertTrue((result as AerivaResult.Failure).error is AerivaError.Unknown)
    }

    @Test
    fun get_whenStoreThrowsGeneralSecurityException_returnsDataCorruptedNotUnknown() = runTest {
        // K.2: corrupted encrypted ciphertext with a still-valid
        // Keystore key surfaces from the real crypto layer as a
        // GeneralSecurityException (or an unchecked wrapper around one)
        // -- see EncryptedPreferencesSecureStorage's own doc comment
        // for why. This must be distinguishable from an ordinary
        // Unknown failure so a caller can show a real corruption/
        // recovery state instead of treating it identically to any
        // other error.
        val store = FakeSecureKeyValueStore()
        val (storage, _) = storage(testScheduler, store)
        store.failNextWith = java.security.GeneralSecurityException("simulated corrupted ciphertext")

        val result = storage.get("token")

        assertTrue(result is AerivaResult.Failure)
        assertTrue((result as AerivaResult.Failure).error is AerivaError.DataCorrupted)
    }

    @Test
    fun get_whenStoreThrowsWrappedGeneralSecurityException_returnsDataCorrupted() = runTest {
        // A real implementation may need to wrap the checked
        // GeneralSecurityException in an unchecked exception to
        // propagate it through SharedPreferences.getString(), whose
        // interface signature declares no checked exceptions. The
        // mapping must see through that wrapper via the cause chain,
        // not just an exact type match on the outermost exception.
        val store = FakeSecureKeyValueStore()
        val (storage, _) = storage(testScheduler, store)
        store.failNextWith = RuntimeException(
            "wrapped",
            java.security.GeneralSecurityException("simulated corrupted ciphertext")
        )

        val result = storage.get("token")

        assertTrue(result is AerivaResult.Failure)
        assertTrue((result as AerivaResult.Failure).error is AerivaError.DataCorrupted)
    }

    @Test
    fun get_whenStoreThrowsIllegalArgumentException_returnsDataCorruptedNotUnknown() = runTest {
        // The other real corruption shape, distinct from the
        // GeneralSecurityException one above: EncryptedSharedPreferences's
        // own source (getDecryptedObject(String)) base64-decodes the
        // stored ciphertext text BEFORE the AEAD decrypt call is ever
        // reached, and a malformed base64 string throws
        // IllegalArgumentException there -- with no GeneralSecurityException
        // anywhere in its cause chain. Real on-disk corruption (a partial
        // write, a byte landing on base64 padding) can plausibly produce
        // either shape, and both mean the same thing at this layer:
        // storage engine fine, this stored value is not -- see
        // EncryptedPreferencesSecureStorage's own doc comment on why this
        // is mapped the same as the GeneralSecurityException case rather
        // than falling through to a generic Unknown.
        val store = FakeSecureKeyValueStore()
        val (storage, _) = storage(testScheduler, store)
        store.failNextWith = IllegalArgumentException("simulated malformed base64 ciphertext")

        val result = storage.get("token")

        assertTrue(result is AerivaResult.Failure)
        assertTrue((result as AerivaResult.Failure).error is AerivaError.DataCorrupted)
    }

    @Test
    fun get_whenStoreThrowsWrappedIllegalArgumentException_returnsDataCorrupted() = runTest {
        val store = FakeSecureKeyValueStore()
        val (storage, _) = storage(testScheduler, store)
        store.failNextWith = RuntimeException(
            "wrapped",
            IllegalArgumentException("simulated malformed base64 ciphertext")
        )

        val result = storage.get("token")

        assertTrue(result is AerivaResult.Failure)
        assertTrue((result as AerivaResult.Failure).error is AerivaError.DataCorrupted)
    }

    @Test
    fun clear_removesEverything() = runTest {
        val (storage, _) = storage(testScheduler)
        storage.set("token", "secret-value")
        storage.set("relay_session", "another-value")

        storage.clear()

        assertEquals(AerivaResult.Success(null), storage.get("token"))
        assertEquals(AerivaResult.Success(null), storage.get("relay_session"))
    }
}
