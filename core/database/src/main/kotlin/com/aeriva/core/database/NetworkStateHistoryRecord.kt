package com.aeriva.core.database

import com.aeriva.core.model.TransportType
import java.time.Instant

/**
 * See NetworkStateHistoryEntity for why this is narrower than
 * core:model's NetworkState.
 */
data class NetworkStateHistoryRecord(
    val transport: TransportType,
    val available: Boolean,
    val validated: Boolean,
    val metered: Boolean,
    val recordedAt: Instant
)
