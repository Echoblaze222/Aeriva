package com.aeriva.app

import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aeriva.app.home.HomeUiState
import com.aeriva.app.home.headlineRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launches the real launcher Activity against the real process-level
 * [AppContainer] (real AndroidNetworkMonitor, real ConnectivityManager on
 * the emulator). The assertion is not "some hardcoded text is visible" but
 * that what the screen renders equals what the shared, monitor-driven state
 * currently says, and that it has left the initial "no report yet" state
 * (which cannot happen without the grace period elapsing or a real report).
 *
 * The expected value depends on the emulator's connectivity at run time
 * (Connected, or Offline / No network reported), so the test accepts any
 * real state but requires the UI to agree with it.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityLaunchTest {

    private val app: AerivaApplication
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun launcherActivity_launches_showsBrand_andRendersTheMonitorDrivenState() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val wordmark = activity.findViewById<TextView>(R.id.brand_wordmark)
                assertEquals("AERIVA", wordmark.text.toString())
            }

            awaitRenderedStateMatchesSharedState(scenario)
        }
    }

    @Test
    fun configurationChange_resubscribes_andStillRendersTheSharedState() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitRenderedStateMatchesSharedState(scenario)

            scenario.recreate()

            awaitRenderedStateMatchesSharedState(scenario)
        }
    }

    private fun awaitRenderedStateMatchesSharedState(
        scenario: ActivityScenario<MainActivity>,
        timeoutMillis: Long = 10_000L
    ) {
        val holder = app.container.homeState
        val deadline = System.currentTimeMillis() + timeoutMillis
        var lastShown = ""
        var lastExpected = ""
        var settled = false

        while (System.currentTimeMillis() < deadline && !settled) {
            scenario.onActivity { activity ->
                val state = holder.uiState.value
                lastShown = activity.findViewById<TextView>(R.id.status_headline).text.toString()
                lastExpected = activity.getString(state.headlineRes())
                settled = state != HomeUiState.Initializing && lastShown == lastExpected
            }
            if (!settled) Thread.sleep(250)
        }

        assertTrue(
            "UI did not converge on the shared state within ${timeoutMillis}ms " +
                "(shown='$lastShown', expected='$lastExpected')",
            settled
        )
        assertNotEquals("", lastShown)
    }
}
