package com.aeriva.app

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aeriva.app.home.HomeStateHolder
import com.aeriva.core.model.DiagnosticsStatus
import com.aeriva.core.model.NetworkQuality
import com.aeriva.core.model.NetworkState
import com.aeriva.core.model.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * Drives the real [MainActivity] through connected -> offline -> connected
 * and checks what is actually rendered at each step.
 *
 * The network monitor is replaced by a test-controlled [MutableStateFlow] of
 * [NetworkState] via [AerivaApplication.uiStateOverride]. Everything between
 * that flow and the pixels is real: the real [HomeStateHolder], the real
 * NetworkState -> UI-state mapping, the real Activity and its rendering. No
 * emulator network, internet or fixed sleeps are involved; the test waits
 * for a condition to hold (with a generous timeout) and fails with what was
 * shown if it never does.
 *
 * The two connected states differ in every detail (transport, metering), so
 * any detail left over from the first connection would be detected.
 */
@RunWith(AndroidJUnit4::class)
class HomeRecoveryUiTest {

    private val app: AerivaApplication
        get() = ApplicationProvider.getApplicationContext()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun state(
        available: Boolean,
        transport: TransportType,
        metered: Boolean,
        atSecond: Long
    ) = NetworkState(
        transport = transport,
        available = available,
        validated = available,
        metered = metered,
        capabilities = emptySet(),
        estimatedQuality = NetworkQuality.Unavailable,
        diagnosticsStatus = DiagnosticsStatus.NotAvailable,
        lastChangedAt = Instant.EPOCH.plusSeconds(atSecond),
        captivePortalReported = false,
        vpnPresent = false,
        blockedByDevicePolicy = false,
        vpnUnderlyingTransport = TransportType.NONE
    )

    private val firstConnection = state(true, TransportType.WIFI, metered = false, atSecond = 1)
    private val offline = state(false, TransportType.NONE, metered = false, atSecond = 2)
    private val secondConnection = state(true, TransportType.CELLULAR, metered = true, atSecond = 3)

    @Before
    fun installControlledState() {
        // The seed is a distinct instance the flow never holds, so the holder
        // treats every value below as a real report (never "no report yet").
        val flow = MutableStateFlow(firstConnection)
        controlled = flow
        val holder = HomeStateHolder(
            networkState = flow,
            seed = NetworkState.unknown(Instant.EPOCH),
            scope = scope
        )
        app.uiStateOverride = holder.uiState
    }

    @After
    fun removeControlledState() {
        app.uiStateOverride = null
        scope.cancel()
    }

    private lateinit var controlled: MutableStateFlow<NetworkState>

    @Test
    fun screenRecoversFromOffline_andShowsOnlyTheNewConnectionsDetails() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // 1. Connected: first connection's details are rendered.
            awaitUi(scenario, "connected over Wi-Fi, not metered") { a ->
                a.headline() == a.getString(R.string.status_connected_headline) &&
                    a.detailValues() == listOf(
                        a.getString(R.string.transport_wifi),
                        a.getString(R.string.value_internet_validated),
                        a.getString(R.string.value_not_metered)
                    )
            }

            // 2. Genuine offline report: offline shown, no details, nothing stale.
            controlled.value = offline
            awaitUi(scenario, "offline with no details") { a ->
                a.headline() == a.getString(R.string.status_offline_headline) &&
                    a.detailsContainer().childCount == 0 &&
                    a.findViewById<View>(R.id.details_card).visibility == View.GONE
            }
            scenario.onActivity { a ->
                assertFalse(
                    "Wi-Fi details from the first connection must not remain while offline",
                    a.rowsText().contains(a.getString(R.string.transport_wifi))
                )
            }

            // 3. New connection: recovers and renders only the new details.
            controlled.value = secondConnection
            awaitUi(scenario, "recovered, connected over mobile data, metered") { a ->
                a.headline() == a.getString(R.string.status_connected_headline) &&
                    a.detailValues() == listOf(
                        a.getString(R.string.transport_cellular),
                        a.getString(R.string.value_internet_validated),
                        a.getString(R.string.value_metered)
                    )
            }

            // 4. Nothing from the first connection survived the round trip.
            scenario.onActivity { a ->
                val shown = a.rowsText()
                assertFalse(shown.contains(a.getString(R.string.transport_wifi)))
                assertFalse(shown.contains(a.getString(R.string.value_not_metered)))
                assertTrue(a.findViewById<View>(R.id.details_card).visibility == View.VISIBLE)
            }
        }
    }

    private fun MainActivity.headline(): String =
        findViewById<TextView>(R.id.status_headline).text.toString()

    private fun MainActivity.detailsContainer(): LinearLayout =
        findViewById(R.id.details_container)

    private fun MainActivity.detailValues(): List<String> =
        (0 until detailsContainer().childCount).map {
            detailsContainer().getChildAt(it).findViewById<TextView>(R.id.detail_value).text.toString()
        }

    /** Text of the details rows only (labels and values), not the status copy. */
    private fun MainActivity.rowsText(): String =
        (0 until detailsContainer().childCount).joinToString(" ") {
            val row = detailsContainer().getChildAt(it)
            row.findViewById<TextView>(R.id.detail_label).text.toString() + " " +
                row.findViewById<TextView>(R.id.detail_value).text.toString()
        }

    private fun MainActivity.allText(): String =
        headline() + " " + findViewById<TextView>(R.id.status_message).text.toString() +
            " " + rowsText()

    /** Polls until [condition] holds on the main thread; fails with what was shown. */
    private fun awaitUi(
        scenario: ActivityScenario<MainActivity>,
        description: String,
        timeoutMillis: Long = 10_000L,
        condition: (MainActivity) -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var matched = false
        var lastShown = ""
        while (!matched && System.currentTimeMillis() < deadline) {
            scenario.onActivity { a ->
                matched = condition(a)
                lastShown = a.allText()
            }
            if (!matched) Thread.sleep(50)
        }
        assertEquals("UI never reached: $description. Last shown: '$lastShown'", true, matched)
    }
}
