# Phase 4 -- LatencyMeasurementEngine hardening (AI 2)

Branch `phase-4-engine-hardening`, based on `phase-3b-measurement-engine` @ `727b2a91`
(last CI-green engine state). Scope: harden the existing engine; no new engine, no new
`NetworkClient`, no domain-model change, no endpoint, no throughput/UDP/UI/WorkManager.
`main` untouched, nothing merged.

## Why this base and not `phase-4-measurement-foundation`
`phase-4-measurement-foundation` @ `9a28cc62` was a sibling of this branch (same base, `727b2a91`)
at the time this branch was written. It was CI-red when inspected then: `NetworkState` had gained
three required constructor fields, and `core:database` `NetworkHistoryRepositoryTest` (plus
`LatencyMeasurementEngineTest` and `ReferenceLatencyProbeExecutorTest`, which build `NetworkState`
directly) had not been updated. Hardening on top of a red base could not produce an attributable
green result. **Update:** the foundation branch later fixed this and reached its own CI-green tip
at `fdf54408`; see `PHASE_4_ENGINE_FOUNDATION_RECONCILIATION.md` and
`PHASE_4_ENGINE_FOUNDATION_INTEGRATION_NOTES.md` on `phase-4-engine-foundation-integration` for
how the two branches were reconciled.

## Defects found (each first demonstrated by a failing test against the unmodified engine)
1. **Caller's own timeout swallowed.** `catch (TimeoutCancellationException)` cannot tell the
   engine's deadline from a `withTimeout` the *caller* wrapped around `measure`/`measureSeries`.
   The engine returned `Failed(Timeout)` to a cancelled caller, and `measureSeries` kept going
   and returned `InsufficientEvidence` holding N timeouts that never happened.
2. **Latency inflated by dispatcher queueing.** The start reading was taken before the hop onto
   `dispatchers.io`; queueing time was reported as network latency (90 ms reported for a 50 ms
   probe in the test).
3. **Negative latency reported as a success.** A backwards `elapsedNanos` produced
   `Succeeded(valueMillis = -0.6)`.
4. **Cancelled caller received a value on decline paths.** `measure` returned `NoNetwork` /
   `CapabilityUnavailable` to an already-cancelled caller, contradicting its own KDoc.
5. **`calculatedAt` predated its samples.** The default argument `now()` was evaluated at call
   time, before any probe ran.
6. **Non-positive `timeoutMillis` accepted.** It silently turned every call into a `Timeout`
   without probing.

## Fixes (all in `LatencyMeasurementEngine.kt`)
- `withTimeoutOrNull` (documented to consume only its own timeout) instead of `withTimeout` +
  catch. A foreign `TimeoutCancellationException` is handled with `ensureActive()`: rethrown if the
  caller was cancelled, mapped to `Timeout` only if the `NetworkClient` raised its own timeout
  while the caller is still active.
- Start reading moved inside the io context, immediately before `probe`.
- `check(elapsed >= 0)`: a broken clock seam fails loudly instead of yielding a measurement.
- `ensureActive()` on entry to `measure`.
- `measureSeries(calculatedAt: Instant? = null)`; resolved with `now()` after the samples ran.
  Source-compatible for callers that pass an `Instant`.
- `require(timeoutMillis > 0)` in `init`.

## Verified as already correct (pinned by tests, no engine change)
Sequential execution and result ordering in `measureSeries`; caller cancellation of a hung probe
and mid-series; late/non-cooperative client cannot produce a success after the deadline; distinct
`NetworkClientOutcome` cases stay distinct and keep their reason; each request's context is
attached verbatim; the engine never writes `NetworkQuality`; no coroutine, socket or callback
is created outside `withContext`/`withTimeoutOrNull`, and the fake client's
cancelled/completed counters account for every probe.

## Unresolved at the time this branch was written (later resolved -- see below)
- **Decision D5-9 / D5-7 (`Unclassified`).** The contract wants unexpected exceptions mapped to
  `Failed(Unclassified(exceptionClass))`. `Unclassified` exists only on
  `phase-4-measurement-foundation`, not on this base, and the domain model was not to be changed.
  At the time this branch was written, an unexpected exception from the client propagated out of
  `measure` (pinned by tests); it was never converted into a measurement. **Resolved** in
  `phase-4-engine-foundation-integration`'s Stage 3 commits: `measure` now catches `Exception`
  (rethrowing `CancellationException` that belongs to the caller), maps everything else to
  `Unclassified`, and logs exactly one defect via a new required `AerivaLogger` constructor
  dependency. See `PHASE_4_ENGINE_FOUNDATION_RECONCILIATION.md` Section 5.A/G and
  `PHASE_4_ENGINE_FOUNDATION_INTEGRATION_NOTES.md` for the full rationale and CI evidence.
- **Stale context is by design.** The engine attaches the caller's `MeasurementNetworkContext`
  verbatim and checks `available` once, before the probe. In a series, later samples keep the
  context captured when their request was built. Only a client-reported `NetworkChangedMidCall`
  detects a switch. Closing this needs a context provider or network-monitor dependency, which is
  a design change.
- **`System.nanoTime()` and deep sleep.** Monotonic, but per Android's `SystemClock` docs it does
  not count deep sleep (`elapsedRealtimeNanos` does). Left as-is; inject the latter at the Android
  edge if needed.
- **Nothing in the engine logs a defect** (D5-10 asks for a log on backstop timeout); it has no
  logger dependency wired in. **Partially resolved:** `AerivaLogger` is now a required constructor
  dependency (Stage 3) and every `Unclassified` outcome logs exactly one defect entry. Per
  reconciliation Decision G, logging the engine's own backstop-deadline firing as a defect is
  deliberately still deferred to S4 (until a client owns the deadline, the engine's own timeout
  firing is the expected mechanism, not a defect) -- the reconciliation-branch tests assert this
  path logs zero entries.

## Merge notes for `phase-4-measurement-foundation`
Expected textual conflicts, all small: the engine's timeout catch (foundation makes it
`Timeout(MeasurementStage.Unknown)`; here it is the single `timeoutFailure()` helper -- change
one line), and `MeasurementFailure.Timeout` / `NetworkState(...)` constructor calls in
`LatencyMeasurementEngineTest`. New tests only assert `is MeasurementFailure.Timeout`, so they
survive the `Timeout` shape change unchanged.
