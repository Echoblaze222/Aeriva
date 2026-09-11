package com.aeriva.core.model

/**
 * Normalized transport for the current default network.
 *
 * The architecture doc's example tree (Section 6) lists WIFI, CELLULAR,
 * ETHERNET, VPN, and NONE. [OTHER] is an addition beyond that example:
 * Android can also report Bluetooth, LoWPAN, USB, and Wi-Fi Aware
 * transports, and collapsing those into NONE would be wrong -- NONE must
 * mean "no active network," not "a transport we didn't bother to name."
 * Collapsing them into an uncovered case would violate the "must not
 * infer unsupported values" rule from the opposite direction. See Phase 2
 * decision log.
 */
enum class TransportType {
    WIFI,
    CELLULAR,
    ETHERNET,
    VPN,
    OTHER,
    NONE
}
