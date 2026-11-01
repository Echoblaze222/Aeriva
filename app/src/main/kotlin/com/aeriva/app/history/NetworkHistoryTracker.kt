package com.aeriva.app.history

import com.aeriva.core.database.NetworkHistoryRepository
import com.aeriva.core.database.NetworkStateHistoryRecord
import com.aeriva.core.model.NetworkState
import com.aeriva.core.result.AerivaResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * Records each real change in the connection and exposes the latest ones for
 * the Home "Recent changes" card. Everything stays in the app's own database
 * on this phone; nothing is sent anywhere.
 *
 * What counts as a change: a different transport, availability, internet check
 * result or metered flag. A new state that differs only in its timestamp is the
 * same observation and is not recorded again. The initial placeholder state
 * ([seed]) is not a real observation and is never recorded.
 *
 * Recording follows [recordingEnabled] (the user's setting). `null` means the
 * setting has not been read yet: nothing is recorded and monitoring waits for
 * the real value, so a user who turned history off is never recorded in the
 * first moments after start. While it is `false` nothing is recorded; turning
 * it back on records from the next change onward.
 *
 * On start it removes anything older than [retention]. [clear] removes
 * everything. A failed database call is logged by the repository and does not
 * stop the connection monitoring.
 */
class NetworkHistoryTracker(
    states: Flow<NetworkState>,
    private val repository: NetworkHistoryRepository,
    private val scope: CoroutineScope,
    seed: NetworkState,
    retention: Duration = Duration.ofDays(30),
    limit: Int = 10,
    private val recordingEnabled: StateFlow<Boolean?> = MutableStateFlow(true),
    private val now: () -> Instant = Instant::now
) {

    /** Newest first. Empty until something has been recorded. */
    val recent: StateFlow<List<NetworkStateHistoryRecord>> = repository.observeRecent(limit)
        .map { result -> if (result is AerivaResult.Success) result.value else emptyList() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch {
            repository.pruneOlderThan(retention, now())
            recordingEnabled.first { it != null }
            states
                .filter { it != seed }
                .distinctUntilChanged { previous, current -> sameObservation(previous, current) }
                .collect { if (recordingEnabled.value == true) repository.record(it, now()) }
        }
    }

    /** Deletes every recorded change. Deleting everything older than "now" does exactly that. */
    fun clear() {
        scope.launch { repository.pruneOlderThan(Duration.ZERO, now()) }
    }
}

private fun sameObservation(previous: NetworkState, current: NetworkState): Boolean =
    previous.transport == current.transport &&
        previous.available == current.available &&
        previous.validated == current.validated &&
        previous.metered == current.metered
