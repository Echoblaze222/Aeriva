package com.aeriva.core.model.measurement

import com.aeriva.core.model.NetworkState

/**
 * Phase 3A skeleton type -- see PHASE_3A_NETWORK_MEASUREMENT_DOMAIN_MODEL.md
 * Section 14. Wraps the existing Phase 2 [NetworkState] rather than
 * duplicating any of its fields. [wifiRssi] and [cellularSignalStrength]
 * are deliberately nullable and are NOT populated by anything in this
 * phase: per the Phase 2 audit, obtaining either requires
 * ACCESS_FINE_LOCATION, which is not currently requested and whose
 * addition needs its own separate, written justification. A null value
 * here is the expected, permitted state, not an error.
 *
 * Deliberately does not carry precise device location -- see the
 * design doc's Section 14 for why that is out of scope here.
 */
data class MeasurementNetworkContext(
    val networkState: NetworkState,
    val wifiRssi: Int? = null,
    val cellularSignalStrength: Int? = null
)
