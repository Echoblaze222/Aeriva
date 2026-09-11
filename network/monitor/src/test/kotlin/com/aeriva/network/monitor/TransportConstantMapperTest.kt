package com.aeriva.network.monitor

import android.net.NetworkCapabilities
import com.aeriva.core.model.TransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransportConstantMapperTest {

    @Test
    fun vpn_mapsToVpn() {
        assertEquals(TransportType.VPN, mapTransportConstant(NetworkCapabilities.TRANSPORT_VPN))
    }

    @Test
    fun wifi_mapsToWifi() {
        assertEquals(TransportType.WIFI, mapTransportConstant(NetworkCapabilities.TRANSPORT_WIFI))
    }

    @Test
    fun cellular_mapsToCellular() {
        assertEquals(TransportType.CELLULAR, mapTransportConstant(NetworkCapabilities.TRANSPORT_CELLULAR))
    }

    @Test
    fun ethernet_mapsToEthernet() {
        assertEquals(TransportType.ETHERNET, mapTransportConstant(NetworkCapabilities.TRANSPORT_ETHERNET))
    }

    @Test
    fun bluetooth_mapsToOther() {
        assertEquals(TransportType.OTHER, mapTransportConstant(NetworkCapabilities.TRANSPORT_BLUETOOTH))
    }

    @Test
    fun unknownConstant_mapsToNull() {
        assertNull(mapTransportConstant(-1))
    }
}
