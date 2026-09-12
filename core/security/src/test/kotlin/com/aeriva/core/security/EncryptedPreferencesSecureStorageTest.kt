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
    fun clear_removesEverything() = runTest {
        val (storage, _) = storage(testScheduler)
        storage.set("token", "secret-value")
        storage.set("relay_session", "another-value")

        storage.clear()

        assertEquals(AerivaResult.Success(null), storage.get("token"))
        assertEquals(AerivaResult.Success(null), storage.get("relay_session"))
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
}
