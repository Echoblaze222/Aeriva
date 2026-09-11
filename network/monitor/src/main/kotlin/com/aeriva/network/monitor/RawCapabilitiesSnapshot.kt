package com.aeriva.network.monitor

import com.aeriva.core.model.TransportType

/**
 * Plain-Kotlin extraction of the parts of android.net.NetworkCapabilities
 * that AERIVA actually interprets. Built by [AndroidNetworkMonitor] at the
 * callback site (Android-dependent), then handed to [NetworkStateMapper]
 * (pure, unit testable) -- this split is what makes the interpretation
 * logic testable without instrumentation. See build.gradle.kts comment.
 */
internal data class RawCapabilitiesSnapshot(
    val hasInternet: Boolean,
    val isValidated: Boolean,
    val isNotMetered: Boolean,
    val transports: Set<TransportType>,
    val rawCapabilityNames: Set<String>
)
