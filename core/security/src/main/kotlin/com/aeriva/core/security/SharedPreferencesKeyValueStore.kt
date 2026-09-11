package com.aeriva.core.security

import android.content.SharedPreferences
import androidx.core.content.edit

class SharedPreferencesKeyValueStore(
    private val preferences: SharedPreferences
) : SecureKeyValueStore {

    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun putString(key: String, value: String) {
        preferences.edit(commit = true) { putString(key, value) }
    }

    override fun remove(key: String) {
        preferences.edit(commit = true) { remove(key) }
    }

    override fun clear() {
        preferences.edit(commit = true) { clear() }
    }
}
