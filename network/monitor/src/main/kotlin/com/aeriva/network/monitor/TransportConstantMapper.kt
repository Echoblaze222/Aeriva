package com.aeriva.network.monitor

import android.net.NetworkCapabilities
import com.aeriva.core.model.TransportType

/**
 * Maps a single android.net.NetworkCapabilities.TRANSPORT_* constant to
 * [TransportType]. This file imports NetworkCapabilities only to read its
 * `public static final int` constants, which are safe to reference from a
 * plain JVM unit test (constant field access, not an instance method call
 * -- the stub android.jar used in unit tests throws only when a real
 * method body would need to run). It does not construct or call methods
 * on a NetworkCapabilities instance; that happens only in
 * [AndroidNetworkMonitor], which is not unit tested for that reason.
 */
internal fun mapTransportConstant(transportConstant: Int): TransportType? = when (transportConstant) {
    NetworkCapabilities.TRANSPORT_VPN -> TransportType.VPN
    NetworkCapabilities.TRANSPORT_ETHERNET -> TransportType.ETHERNET
    NetworkCapabilities.TRANSPORT_WIFI -> TransportType.WIFI
    NetworkCapabilities.TRANSPORT_CELLULAR -> TransportType.CELLULAR
    NetworkCapabilities.TRANSPORT_BLUETOOTH,
    NetworkCapabilities.TRANSPORT_LOWPAN,
    NetworkCapabilities.TRANSPORT_USB,
    NetworkCapabilities.TRANSPORT_WIFI_AWARE -> TransportType.OTHER
    else -> null
}
