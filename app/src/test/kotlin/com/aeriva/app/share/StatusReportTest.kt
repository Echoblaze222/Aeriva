package com.aeriva.app.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The layout of the shared status text. It adds nothing that is not passed in. */
class StatusReportTest {

    @Test
    fun theReportListsTheStatusThenTheNetworksThenTheFooter() {
        val report = buildStatusReport(
            title = "AERIVA connection status",
            headline = "Connected",
            message = "Android's connectivity check passed on this network.",
            networksTitle = "Your networks",
            networkLines = listOf("Wi-Fi (in use): Android's check passed, Not metered"),
            footer = "Reported by Android to AERIVA on Oct 28, 8:42 PM."
        )

        assertEquals(
            "AERIVA connection status\n\n" +
                "Connected\n" +
                "Android's connectivity check passed on this network.\n\n" +
                "Your networks\n" +
                "Wi-Fi (in use): Android's check passed, Not metered\n\n" +
                "Reported by Android to AERIVA on Oct 28, 8:42 PM.",
            report
        )
    }

    @Test
    fun withNoNetworksTheNetworksSectionIsLeftOut() {
        val report = buildStatusReport(
            title = "AERIVA connection status",
            headline = "No connection",
            message = "Android reports no network.",
            networksTitle = "Your networks",
            networkLines = emptyList(),
            footer = "Reported by Android to AERIVA on Oct 28, 8:42 PM."
        )

        assertFalse(report.contains("Your networks"))
        assertTrue(report.startsWith("AERIVA connection status\n\nNo connection"))
    }
}
