package com.aeriva.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single recorded network-state transition. Deliberately narrower
 * than core:model's NetworkState -- estimatedQuality and
 * diagnosticsStatus are Phase 3 (Measurement Engine) concerns that
 * Phase 2 always reports as Unavailable/NotAvailable, and the raw
 * capability set is verbose and not yet needed for history. Persisting
 * fields nothing can populate yet would be storing data this
 * application cannot honestly claim to have measured.
 */
@Entity(tableName = "network_state_history")
data class NetworkStateHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val transport: String,
    val available: Boolean,
    val validated: Boolean,
    val metered: Boolean,
    val recordedAtEpochMillis: Long
)
