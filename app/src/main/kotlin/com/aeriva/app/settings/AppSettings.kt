package com.aeriva.app.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.aeriva.core.preferences.AerivaPreferences
import com.aeriva.core.result.AerivaResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The user's settings, stored on this phone by the existing preferences
 * module. Each value is `null` until the stored value has been read, so
 * nothing acts on a default before the real setting is known (a user who turned
 * history off must not have a state recorded in the first moments after start).
 * A failed read falls back to the default.
 */
class AppSettings(
    private val preferences: AerivaPreferences,
    private val scope: CoroutineScope
) {

    /** Whether connection changes are saved. Default on. */
    val historyEnabled: StateFlow<Boolean?> = observe(HISTORY_ENABLED, default = true)

    /** Whether the first run welcome card has been dismissed. Default not yet. */
    val welcomeDismissed: StateFlow<Boolean?> = observe(WELCOME_DISMISSED, default = false)

    fun setHistoryEnabled(enabled: Boolean) {
        scope.launch { preferences.set(HISTORY_ENABLED, enabled) }
    }

    fun dismissWelcome() {
        scope.launch { preferences.set(WELCOME_DISMISSED, true) }
    }

    private fun observe(key: Preferences.Key<Boolean>, default: Boolean): StateFlow<Boolean?> =
        preferences.observe(key, default)
            .map { result -> if (result is AerivaResult.Success) result.value else default }
            .stateIn(scope, SharingStarted.Eagerly, null)

    internal companion object {
        val HISTORY_ENABLED = booleanPreferencesKey("history_enabled")
        val WELCOME_DISMISSED = booleanPreferencesKey("welcome_dismissed")
    }
}
