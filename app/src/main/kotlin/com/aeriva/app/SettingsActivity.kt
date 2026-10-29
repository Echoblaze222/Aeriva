package com.aeriva.app

import android.app.Activity
import android.os.Bundle
import android.widget.Switch
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The one setting today: whether connection changes are saved on this phone,
 * plus clearing what is saved. The switch is disabled until the stored value
 * has been read, so it never shows a default that is not the real setting.
 */
class SettingsActivity : Activity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var settingsJob: Job? = null
    private var applying = false

    private lateinit var historySwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val container = (application as AerivaApplication).container
        historySwitch = findViewById(R.id.settings_history_switch)
        historySwitch.setOnCheckedChangeListener { _, checked ->
            if (!applying) container.settings.setHistoryEnabled(checked)
        }
        findViewById<TextView>(R.id.settings_clear_history).setOnClickListener {
            container.networkHistory.clear()
        }
        findViewById<TextView>(R.id.settings_version).text =
            getString(R.string.settings_version, versionName())
    }

    override fun onStart() {
        super.onStart()
        val container = (application as AerivaApplication).container
        settingsJob?.cancel()
        settingsJob = uiScope.launch {
            container.settings.historyEnabled.collect { enabled ->
                applying = true
                historySwitch.isEnabled = enabled != null
                historySwitch.isChecked = enabled ?: true
                applying = false
            }
        }
    }

    override fun onStop() {
        settingsJob?.cancel()
        settingsJob = null
        super.onStop()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (unavailable: Exception) {
        ""
    }
}
