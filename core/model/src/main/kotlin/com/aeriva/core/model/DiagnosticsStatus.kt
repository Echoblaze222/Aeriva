package com.aeriva.core.model

/**
 * Diagnostics availability for the current network. Populated starting
 * with the diagnostics feature (Phase 3+); Phase 2 code must only ever
 * produce [NotAvailable].
 */
sealed interface DiagnosticsStatus {
    data object NotAvailable : DiagnosticsStatus

    data class Available(val summary: String) : DiagnosticsStatus
}
