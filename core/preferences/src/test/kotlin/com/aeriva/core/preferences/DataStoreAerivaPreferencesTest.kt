package com.aeriva.core.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aeriva.core.logging.NoOpLogger
import com.aeriva.core.result.AerivaResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Uses a real DataStore backed by a JVM temp file (no Robolectric, no
 * Android runtime) -- this exercises the actual read/write/edit path,
 * not a fake. NOTE: this depends on datastore-preferences being usable
 * on a plain JVM test classpath, which I could not confirm by actually
 * running it in this environment (no Gradle/Android SDK available
 * here) -- verify this test executes as part of the real build.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreAerivaPreferencesTest {

    private lateinit var file: File
    private lateinit var preferences: AerivaPreferences

    private val stringKey = stringPreferencesKey("test_string")
    private val boolKey = booleanPreferencesKey("test_bool")

    @Before
    fun createPreferences() {
        file = File.createTempFile("aeriva_preferences_test", ".preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()),
            produceFile = { file }
        )
        preferences = DataStoreAerivaPreferences(dataStore, NoOpLogger)
    }

    @After
    fun deleteFile() {
        file.delete()
    }

    @Test
    fun get_beforeAnyWrite_returnsDefault() = runTest {
        val result = preferences.get(stringKey, "default")

        assertEquals(AerivaResult.Success("default"), result)
    }

    @Test
    fun set_thenGet_returnsWrittenValue() = runTest {
        preferences.set(boolKey, true)
        val result = preferences.get(boolKey, false)

        assertEquals(AerivaResult.Success(true), result)
    }

    @Test
    fun remove_revertsToDefault() = runTest {
        preferences.set(stringKey, "value")
        preferences.remove(stringKey)
        val result = preferences.get(stringKey, "default")

        assertEquals(AerivaResult.Success("default"), result)
    }

    @Test
    fun clear_removesEverything() = runTest {
        preferences.set(stringKey, "value")
        preferences.set(boolKey, true)

        preferences.clear()

        assertEquals(AerivaResult.Success("default"), preferences.get(stringKey, "default"))
        assertEquals(AerivaResult.Success(false), preferences.get(boolKey, false))
    }
}
