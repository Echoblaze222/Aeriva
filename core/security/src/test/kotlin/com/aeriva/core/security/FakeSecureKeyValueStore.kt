package com.aeriva.core.security

internal class FakeSecureKeyValueStore : SecureKeyValueStore {

    private val values = mutableMapOf<String, String>()
    var failNextWith: Throwable? = null

    override fun getString(key: String): String? {
        failNextWith?.let { throw it.also { failNextWith = null } }
        return values[key]
    }

    override fun putString(key: String, value: String) {
        failNextWith?.let { throw it.also { failNextWith = null } }
        values[key] = value
    }

    override fun remove(key: String) {
        failNextWith?.let { throw it.also { failNextWith = null } }
        values.remove(key)
    }

    override fun clear() {
        failNextWith?.let { throw it.also { failNextWith = null } }
        values.clear()
    }
}
