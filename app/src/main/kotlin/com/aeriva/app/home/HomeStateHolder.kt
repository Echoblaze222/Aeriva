package com.aeriva.app.home

import com.aeriva.core.model.NetworkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

/**
 * Turns the shared [NetworkState] stream into [HomeUiState].
 *
 * [seed] is the exact instance `ConnectionStateRepository` published before
 * the monitor produced anything. While [networkState] still holds that very
 * instance (identity, not equality) nothing real has been observed yet. A
 * genuine offline report maps to a structurally similar value but is a
 * different instance with its own timestamp, so it is NOT mistaken for the
 * seed. StateFlow only replaces its value when the new one is not `equals`
 * to the old one, which a real report (new `lastChangedAt`) always is.
 *
 * If no real report arrives within [noReportDelayMillis], the state becomes
 * [HomeUiState.NoNetworkReported]. That timer lives here (process scope),
 * not in the Activity, so it is not restarted by rotation or by
 * stop/start. `transformLatest` cancels the pending timer the moment a real
 * report arrives.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeStateHolder(
    networkState: StateFlow<NetworkState>,
    seed: NetworkState,
    scope: CoroutineScope,
    noReportDelayMillis: Long = DEFAULT_NO_REPORT_DELAY_MILLIS
) {

    val uiState: StateFlow<HomeUiState> = networkState
        .transformLatest { state ->
            if (state === seed) {
                emit(HomeUiState.Initializing)
                delay(noReportDelayMillis)
                emit(HomeUiState.NoNetworkReported)
            } else {
                emit(state.toHomeUiState())
            }
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = HomeUiState.Initializing
        )

    companion object {
        const val DEFAULT_NO_REPORT_DELAY_MILLIS = 3_000L
    }
}
