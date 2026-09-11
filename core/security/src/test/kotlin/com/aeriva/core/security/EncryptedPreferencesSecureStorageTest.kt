package com.aeriva.core.security

import com.aeriva.core.common.AerivaDispatchers
import com.aeriva.core.logging.NoOpLogger
import com.aeriva.core.result.AerivaError
import com.aeriva.core.result.AerivaResult
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedPreferencesSecureStorageTest {

    // Local rather than reusing core:common's TestAerivaDispatchers --
    // that fixture lives in core:common's own test source set and isn't
    // visible across module boundaries without a testFixtures split.
    // Worth doing if a third module ends up needing it too; not yet.
    private class SingleDispatcherFixture(dispatcher: kotlinx.coroutines.CoroutineDispatcher) : AerivaDispatchers {
        override val main = dispatcher
        override val io = dispatcher
        override val default = dispatcher
    }

    private fun storage(store: FakeSecureKeyValueStore = FakeSecureKeyValueStore()) =
        EncryptedPreferencesSecureStorage(store, SingleDispatcherFixture(StandardTestDispatcher()), NoOpLogger) to store

    @Test
    fun get_beforeAnyWrite_returnsNull() = runTest {
        val (storage, _) = storage()

        val result = storage.get("token")

        assertEquals(AerivaResult.Success(null), result)
    }

    @Test
    fun set_thenGet_returnsWrittenValue() = runTest {
        val (storage, _) = storage()

        storage.set("token", "secret-value")
        val result = storage.get("token")

        assertEquals(AerivaResult.Success("secret-value"), result)
    }

    @Test
    fun remove_deletesTheValue() = runTest {
        val (storage, _) = storage()
        storage.set("token", "secret-value")

        storage.remove("token")
        val result = storage.get("token")

        assertEquals(AerivaResult.Success(null), result)
    }

    @Test
    fun clear_removesEverything() = runTest {
        val (storage, _) = storage()
        storage.set("token", "secret-value")
        storage.set("relay_session", "another-value")

        storage.clear()

        assertEquals(AerivaResult.Success(null), storage.get("token"))
        assertEquals(AerivaResult.Success(null), storage.get("relay_session"))
    }

    @Test
    fun get_whenStoreThrows_returnsUnknownFailure() = runTest {
        val store = FakeSecureKeyValueStore()
        val (storage, _) = storage(store)
        store.failNextWith = IllegalStateException("simulated failure")

        val result = storage.get("token")

        assertTrue(result is AerivaResult.Failure)
        assertTrue((result as AerivaResult.Failure).error is AerivaError.Unknown)
    }
}
