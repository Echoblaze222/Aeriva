package com.aeriva.app.advisor

import com.aeriva.core.model.advisor.CandidateNetwork
import com.aeriva.network.monitor.advisor.AdvisorSelector
import com.aeriva.network.monitor.advisor.AdvisorTimingConfig
import com.aeriva.network.monitor.advisor.RecommendationGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import java.time.Duration
import java.time.Instant

/**
 * Turns the candidate list into the card state: select, then gate (dwell and
 * cooldown), then map to a card state. Stale (the monitor says events may have
 * been lost) has priority over everything and withdraws any recommendation
 * (ADVISOR_UI_SPEC.md decision D1).
 *
 * The gate needs time to pass without a new event (a recommendation appears
 * only after the dwell), so after each evaluation this re-evaluates when the
 * gate says its next decision is due. [uiState] is [AdvisorUiState.Absent]
 * until the first candidate list arrives (decision D2).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdvisorStateHolder(
    candidates: Flow<List<CandidateNetwork>>,
    stale: Flow<Boolean>,
    scope: CoroutineScope,
    private val now: () -> Instant = Instant::now,
    timing: AdvisorTimingConfig = AdvisorTimingConfig()
) {

    private data class Snapshot(val candidates: List<CandidateNetwork>, val stale: Boolean)

    private val gate = RecommendationGate(timing, now)
    private val labeler = NetworkLabeler()

    val uiState: StateFlow<AdvisorUiState> = combine(candidates, stale) { list, isStale ->
        Snapshot(list, isStale)
    }
        .transformLatest { snapshot ->
            if (snapshot.stale) {
                gate.reset()
                emit(AdvisorUiState.Stale)
                return@transformLatest
            }
            labeler.update(snapshot.candidates)
            emit(evaluate(snapshot.candidates))
            while (true) {
                val dueAt = gate.nextEvaluationAt() ?: break
                delay(Duration.between(now(), dueAt).toMillis().coerceAtLeast(0L) + 1L)
                emit(evaluate(snapshot.candidates))
            }
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = AdvisorUiState.Absent
        )

    private fun evaluate(candidates: List<CandidateNetwork>): AdvisorUiState =
        gate.update(AdvisorSelector.select(candidates)).toUiState(candidates, labeler)
}
