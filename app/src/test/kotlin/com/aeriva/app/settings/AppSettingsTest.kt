package com.aeriva.app.settings

import androidx.datastore.preferences.core.Preferences
import com.aeriva.core.preferences.AerivaPreferences
import com.aeriva.core.result.AerivaResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [AppSettings] on top of an in memory [AerivaPreferences]. The real DataStore
 * is covered by DataStoreAerivaPreferencesTest in :core:preferences.
 */
class AppSettingsTest {

    private class FakePreferences : AerivaPreferences {
        private val values = MutableStateFlow<Map<String, Any>>(emptyMap())

        @Suppress("UNCHECKED_CAST")
        private fun <T> read(key: Preferences.Key<T>, default: T): T = (values.value[key.name] as T?) ?: default

        override suspend fun <T> get(key: Preferences.Key<T>, default: T): AerivaResult<T> =
            AerivaResult.Success(read(key, default))

        override fun <T> observe(key: Preferences.Key<T>, default: T): Flow<AerivaResult<T>> =
            values.map { AerivaResult.Success(read(key, default)) }

        override suspend fun <T> set(key: Preferences.Key<T>, value: T): AerivaResult<Unit> {
            values.value = values.value + (key.name to (value as Any))
            return AerivaResult.Success(Unit)
        }

        override suspend fun <T> remove(key: Preferences.Key<T>): AerivaResult<Unit> {
            values.value = values.value - key.name
            return AerivaResult.Success(Unit)
        }

        override suspend fun clear(): AerivaResult<Unit> {
            values.value = emptyMap()
            return AerivaResult.Success(Unit)
        }
    }

    @Test
    fun historyIsOnAndWelcomeIsNotDismissed_byDefault() = runTest {
        val settings = AppSettings(FakePreferences(), backgroundScope)
        runCurrent()

        assertEquals(true, settings.historyEnabled.value)
        assertEquals(false, settings.welcomeDismissed.value)
    }

    @Test
    fun turningHistoryOffIsStored() = runTest {
        val settings = AppSettings(FakePreferences(), backgroundScope)
        runCurrent()

        settings.setHistoryEnabled(false)
        runCurrent()

        assertEquals(false, settings.historyEnabled.value)
    }

    @Test
    fun dismissingTheWelcomeIsStored() = runTest {
        val settings = AppSettings(FakePreferences(), backgroundScope)
        runCurrent()

        settings.dismissWelcome()
        runCurrent()

        assertEquals(true, settings.welcomeDismissed.value)
    }

    @Test
    fun aStoredValueIsReadBackByANewSettingsObject() = runTest {
        val preferences = FakePreferences()
        AppSettings(preferences, backgroundScope).setHistoryEnabled(false)
        runCurrent()

        val reopened = AppSettings(preferences, backgroundScope)
        runCurrent()

        assertEquals(false, reopened.historyEnabled.value)
    }
}
