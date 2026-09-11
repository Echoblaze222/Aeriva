package com.aeriva.core.logging

/**
 * AERIVA's logging boundary. Deliberately a plain Kotlin interface with
 * no android.util.Log import, for the same reason NetworkStateMapper has
 * no android.net import (see network:monitor build.gradle.kts): callers
 * like [com.aeriva.network.monitor.AndroidNetworkMonitor] take this as a
 * constructor dependency, so their own logic stays unit testable with a
 * plain fake instead of requiring Robolectric just to observe a log call.
 *
 * Per engineering standards (06_ENGINEERING_STANDARDS.md) and security
 * doc Section on not logging sensitive user traffic: implementations of
 * this interface are responsible for deciding what is safe to emit
 * (e.g. stripping IPs/hostnames before release-build logging), not the
 * callers. Callers pass plain descriptive strings; they do not redact.
 */
interface AerivaLogger {
    fun i(tag: String, message: String)
    fun w(tag: String, message: String)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}
