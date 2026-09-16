# AERIVA Phase 3A -- Network Measurement Domain Model

Design and domain-model document. Where this document is accompanied
by Kotlin types (Section 19 explains exactly which files and why),
those types are a **minimal, illustrative skeleton** proving the
domain boundary compiles and is testable -- they are not the
production measurement engine, contain no active probing, no
Android-framework dependency, and no scoring algorithm beyond what's
needed to prove the boundary itself works.

## 1. Purpose

Design the domain model and data boundaries the future AERIVA network
measurement engine will use, keeping five concepts -- OBSERVATION,
MEASUREMENT, ESTIMATION, PREDICTION, RECOMMENDATION -- distinguishable
at the type level, so a future refactor cannot silently collapse them
into one generic score without the type system itself objecting. This
follows directly from `PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md`
Section 7's own framing of that collapse as the single riskiest
mistake this area of the codebase could make.

## 2. Current repository assumptions -- verified, not carried over

(Re-inspected directly for this task, at `main` commit `f0580b5`. Per
this task's own instruction: nothing below is assumed to exist because
an earlier document mentioned it.)

- **`core:model`** is a pure `kotlin.jvm` module (not an Android
  library), zero production dependencies, `junit4` for tests only --
  confirmed by reading its `build.gradle.kts` directly, which states
  this is deliberate: "Pure Kotlin domain models, zero dependencies."
  This is the existing convention Section 19's new files extend,
  rather than a new module being introduced.
- **Existing `core:model` types:** `NetworkState` (the Phase 2
  connectivity snapshot: transport, available, validated, metered,
  capabilities, `estimatedQuality`, `diagnosticsStatus`,
  `lastChangedAt: Instant`), `NetworkQuality` (`Unavailable` |
  `Measured(score: Int, label: String)`), `DiagnosticsStatus`
  (`NotAvailable` | `Available(summary: String)`), `TransportType`.
- **A specific, concrete tension this audit found and must state
  plainly:** `NetworkQuality.Measured(score: Int, label: String)` --
  once Phase 3 actually populates it -- is *itself* an instance of the
  exact anti-pattern this task warns against: a single generic
  int-plus-label collapsing whatever richer measurement/derived/
  estimation lineage produced it. This document does not modify
  `NetworkQuality` (that would be scope creep beyond a design task, and
  `NetworkQuality`'s own doc comment already correctly scopes it to
  Phase 2 always returning `Unavailable`). It instead treats this as an
  **open decision, listed explicitly in Section 18**: `NetworkQuality`
  will need to either be extended to reference the richer lineage
  defined here, or be explicitly documented as a *display-only summary*
  that is always derived from, and never a substitute for, the richer
  types this document defines. Silently populating `Measured(score,
  label)` directly from a measurement, without that lineage, would
  reproduce the exact collapse this task exists to prevent.
- **Existing Result/error abstraction:** `AerivaResult<T>`
  (`Success`/`Failure`) and `AerivaError` (`Unsupported`,
  `PermissionRequired`, `DataCorrupted`, `Unknown`), in `core:result`.
  Its own doc comment is explicit that this models "what a single call
  returned" -- a call-level outcome (did the operation that was
  attempted succeed or fail), not a domain-level judgment. This
  distinction matters directly for this design (Section 12): "the
  latency probe's network call timed out" is an `AerivaResult.Failure`;
  "we have three measurements but they're too old and too sparse to
  recommend anything" is a **domain-level `InsufficientEvidence`
  outcome from a call that itself succeeded** -- conflating the two
  would make a legitimate "not enough evidence yet" answer look like a
  bug report.
- **Existing dispatcher seam:** `AerivaDispatchers` interface +
  `TestAerivaDispatchers` fake, `core:common`. Reused as-is; this
  design adds no second dispatcher abstraction.
- **Existing clock seam:** a plain `() -> Instant` function,
  constructor-injected (e.g. `AndroidNetworkMonitor`'s `now`). Reused
  as-is per `PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md` Section 1 --
  this design's freshness/staleness semantics (Section 11) take an
  `Instant` and a "now" value as plain parameters, not a new `Clock`
  interface.
- **Existing pure-mapping-function convention:** `NetworkStateMapper`
  (`network:monitor`) -- no `android.*` import, takes plain data,
  returns `NetworkState`. This design's types follow the same
  Android-import-free convention, for the same reason (testable without
  an emulator, per Phase 3 test strategy Section 2).
- **Existing persistence-boundary convention:** `NetworkStateHistoryEntity`
  (`core:database`) is deliberately **narrower** than `NetworkState`
  itself -- it omits `estimatedQuality`/`diagnosticsStatus`/raw
  capabilities specifically because "Phase 2 always reports
  Unavailable/NotAvailable" and "persisting fields nothing can populate
  yet would be storing data this application cannot honestly claim to
  have measured" (that file's own comment). Section 15 of this document
  applies the identical principle: no Room entity is proposed here for
  anything this phase cannot honestly populate.
- **Existing failure-mode/testability requirements:** carried forward
  from `PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md` Sections 2-6
  without re-deriving them.

## 3. Domain terminology

Defined once here and used consistently through the rest of this
document and (where Kotlin types exist) their KDoc:

- **Observation:** a directly platform-reported fact, at a point in
  time, with no computation applied beyond reading it.
- **Measurement:** the result of AERIVA deliberately performing an
  active probe of a network characteristic.
- **Derived value:** something computed from one or more measurements
  (or observations) via defined, deterministic arithmetic -- no
  inference, no historical extrapolation.
- **Estimation:** a value produced when direct measurement was
  unavailable or incomplete, inferred from whatever evidence *is*
  available.
- **Prediction:** a value about the future (or about an unmeasured
  time/place), produced from historical evidence.
- **Recommendation:** an action suggested to the user, distinguishable
  from, and always traceable to, the evidence that produced it.
- **Evidence:** the observations/measurements/derived values a
  higher-tier result (estimation, prediction, recommendation) is
  actually based on -- see Section 13, Data Lineage.
- **Confidence:** an explicit, defined statement of how much a
  consumer should trust a given result, given its evidence (Section
  10) -- never a bare unexplained number.
- **Freshness:** an explicit statement of how current a result is
  relative to when it was produced and (where applicable) when it
  stops being trustworthy (Section 11).
- **Insufficient evidence:** a legitimate, first-class domain outcome
  -- not a failure, not a fabricated default value -- meaning "AERIVA
  correctly determined it does not have enough basis to produce this
  result yet."

## 4. Observation model

An observation is what Phase 2's existing `NetworkState` already *is*
-- this document does not duplicate it. `NetworkState.lastChangedAt`
is the observation timestamp; `transport`/`available`/`validated`/
`metered`/`capabilities` are the observed platform facts.

**Explicitly, per this task's own instruction:** an observation is
*not* automatically a measurement of network quality. `NetworkState`
being `available = true, transport = WIFI` says nothing about that
Wi-Fi connection's actual latency, jitter, packet loss, or throughput
-- those require a deliberately performed measurement (Section 5).
Treating "Android says Wi-Fi is connected and validated" as if it were
"this Wi-Fi connection is fast" is exactly the platform-callback-as-
performance-measurement conflation this task explicitly warns against.

**What Phase 3 adds to the observation tier, not yet in `NetworkState`:**
Wi-Fi RSSI and cellular signal strength, per the Phase 2 audit's own
findings, both require `ACCESS_FINE_LOCATION` and are not currently
requested (Phase 2 audit Section 3/10). This document defines where
they *would* attach (a `MeasurementNetworkContext` wrapping a
`NetworkState` snapshot plus optional signal fields -- Section 14) but
does not request the permission or add the fields as populated data;
per the Phase 2 audit, that is a permission decision requiring its own
written justification, not a Phase 3A design decision.

## 5. Measurement model

A measurement is the result of deliberately performing one probe of one
characteristic (latency, jitter, packet loss, throughput -- per the
Phase 2 audit and Phase 3 test strategy, the only ones currently
justified; DNS/connection-establishment timing remain "if eventually
justified," per this task's own instruction, and are not designed
further here).

Required fields, per this task's own list, and why each exists:

- **value + unit** -- a measurement without an explicit unit is a
  latent bug waiting for a millis-vs-seconds mismatch.
- **timestamp** -- when the measurement was taken (Section 11
  distinguishes this from when a *derived* value was calculated from
  it).
- **duration**, where relevant (throughput inherently has one; a
  single latency ping's "duration" *is* its value, so this field is
  measurement-type-specific, not universal).
- **network context** -- which `NetworkState`/`MeasurementNetworkContext`
  was active when the measurement was taken (Section 14) -- a latency
  number is meaningless without knowing whether it was Wi-Fi or
  cellular.
- **measurement method** -- e.g. "TCP round-trip to a specific known
  endpoint" -- so a future reader (or a future different method) can
  tell whether two measurements are even comparable.
- **sample count** -- a single ping and a 20-sample average are not
  the same confidence tier, even if their headline number matches.
- **validity / failure state** -- see Section 12; a measurement type
  must be able to represent "attempted and failed" distinctly from
  "attempted and succeeded with value X," and never conflate the two.
- **confidence/evidence metadata**, where appropriate -- see Section
  10; not every measurement needs its own confidence score (a single
  successful probe's value is just what it is), but an *aggregated*
  measurement result (Section 6) generally does.

**Partial results are a first-class case, not an edge case:** per this
task's explicit example, latency succeeding while throughput fails in
the same measurement pass must be representable as "one result
present, one absent-with-a-reason," never as a partial object with
silently-zeroed fields standing in for the failed part.

## 6. Derived / calculated values

A derived value (average latency, percentile latency, jitter
calculated from a latency series, packet-loss rate, throughput
statistics, a rolling window, a stability indicator, a confidence
calculation itself) is **deterministic arithmetic over one or more
measurements**, not inference and not history-based extrapolation --
that's what separates it from estimation (Section 7) and prediction
(Section 8).

A derived value must reference the measurements it was computed from
(Section 13, lineage) and must remain **a distinct type from the raw
measurement**, even when, numerically, a derived value over a single
sample equals that sample's raw value -- the type distinction exists
so "this is a raw reading" vs. "this is a computed statistic" is never
ambiguous from the type alone, independent of what the numbers happen
to be in any particular case.

## 7. Estimation model

An estimate is produced when direct measurement was unavailable or
incomplete -- example (illustrative, not implemented): inferring
"probably poor" connectivity from an unusually high number of recent
connection-validation failures, when no direct throughput measurement
exists for the current network.

Required fields, per this task's list: **source evidence** (which
observations/measurements it's actually based on -- Section 13),
**estimation method** (a named, specific method -- "inferred from
validation-failure frequency," not an unlabeled black box),
**confidence** (Section 10), **timestamp**, **freshness** (Section
11), and **uncertainty**, where meaningful (e.g., a range rather than
a single point value, if the method naturally produces one).

**An estimate must never be represented as if it were directly
measured** -- the type system must make it structurally impossible to
read an estimate's value out through whatever accessor represents a
raw measurement's value (Section 17's testability implication makes
this concrete: a test must be able to prove this, not just assert it
in a comment).

## 8. Prediction model

A prediction is about the future (or about an unmeasured place/time),
based on historical evidence or another explicitly defined predictive
method -- example (illustrative, not implemented): "this network has
historically been poor between 6-8pm on weekdays, based on N days of
history."

**This task explicitly does not want a machine-learning system built
to satisfy this phase**, and none is designed here -- the model only
needs to establish the boundary. Required fields: **prediction
target** (what is being predicted -- a place, a time window, a
network), **prediction time** (when the prediction was generated --
distinct from the target time/place it's *about*), **source
history/window** (which historical evidence it draws on -- Section
13), **confidence**, **freshness**, an optional **model/method
identifier** (even a simple "N-day historical average" needs a name,
so a future, more sophisticated method can be distinguished from it
without guessing which one produced an old, persisted prediction), and
**uncertainty**, where meaningful.

## 9. Recommendation model

A recommendation is an action suggested to the user, and must remain
distinguishable from the evidence supporting it -- this task's own
example: AERIVA observes/measures several networks or locations, then
recommends one based on the user's selected activity. **The
recommendation is not itself a measurement**, and must not be stored
or read as one.

Required fields, per this task's list: **recommendation target** (the
network/location/action being recommended), **activity/profile** (what
the recommendation is *for* -- "gaming" vs. "large download" plausibly
recommend differently from the same underlying evidence), **supporting
evidence** (Section 13), **decision criteria** (what rule/threshold
turned that evidence into this specific recommendation -- e.g. "lowest
predicted jitter among networks with sufficient recent evidence," even
before that rule is actually implemented, the *field* for stating it
must exist), **confidence**, **freshness**, **generated timestamp**,
**expiration/staleness information** (Section 11), and
**reason/explanation data** (a human-readable account a UI could
eventually show -- "recommended because measured latency was lower and
jitter was more stable over the last hour," not just a bare
recommendation with no stated reason).

## 10. Confidence semantics

**No arbitrary numerical scoring "because a score is convenient."**
Per this task's explicit instruction, if a numeric confidence
representation is used anywhere, this document defines exactly what it
means and what evidence supports it, rather than a bare 0-100 the
reader has to guess at.

This design's confidence model: a **small, closed, named set of tiers**
(illustrative in the accompanying Kotlin skeleton: `Insufficient`,
`Low`, `Medium`, `High`), each tier's meaning defined in terms of
**sample count and consistency**, not a black-box formula -- e.g.
(illustrative thresholds, not a claim these exact numbers are
implemented or correct for production): `Insufficient` when sample
count is below a defined minimum; `Low`/`Medium`/`High` distinguished
by sample count *and* how consistent (low-variance) the samples are.
A named tier that states its own basis is preferred over a raw float,
specifically so "confidence: 0.73" never appears anywhere without an
answer to "0.73 based on what."

**AERIVA must be able to say "insufficient evidence" instead of
manufacturing a result** -- this is not merely a `Low` confidence
tier; it is a distinct, explicit outcome (Section 12) that a caller
must handle separately from "here is a real, if weakly-supported,
result."

## 11. Freshness semantics

Distinguished explicitly, per this task's instruction:

- **When something was observed/measured** -- the timestamp already
  required on every observation/measurement (Sections 4-5).
- **When a derived value was calculated** -- may be later than the
  measurements it's derived from (e.g. a nightly aggregation job
  computing yesterday's average today); this must be its own,
  separate timestamp field, not reused from the source measurements.
- **When a prediction/recommendation was generated** -- again
  separate from, and typically later than, the evidence timestamps
  feeding it (Section 8-9's "generated timestamp"/"prediction time"
  fields).
- **When information becomes stale or expires** -- an explicit,
  queryable property (illustrative: a "valid until" instant, or a
  computed "is this still fresh given the current time" function taking
  "now" as a parameter, per the clock-injection requirement below),
  not something a consumer has to infer by comparing raw timestamps
  itself every time.

**Monotonic vs. wall-clock time:** per this task's instruction to
consider this distinction -- wall-clock time (`Instant`, matching the
existing `NetworkState.lastChangedAt` convention) is appropriate for
*persisted, cross-session* freshness ("this measurement is 3 hours
old"), since that comparison must survive process death and reboots.
A monotonic clock would only matter for *within-a-single-measurement-
session* duration timing (e.g., a throughput probe's own elapsed-time
measurement, where wall-clock adjustments -- NTP sync, timezone/DST
changes -- could otherwise corrupt a single short-lived duration
calculation). This document recommends: **wall-clock `Instant` for all
persisted timestamps** (matching existing convention), **and flags,
as an open decision (Section 18), whether any single in-flight
measurement's own duration calculation needs a monotonic source**
instead of wall-clock subtraction -- a decision that belongs with
whoever implements the actual timing code, not this design document.

**Testability compatibility, confirmed against
`PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md`:** every freshness
calculation in this design takes "now" as an explicit parameter (a
plain `Instant`, supplied by the existing `() -> Instant` seam),
exactly matching that document's Section 6 requirement and this
codebase's existing `AndroidNetworkMonitor` convention -- no freshness
function in this design calls `Instant.now()` internally.

## 12. Failure semantics

Every failure mode this task lists is represented as an explicit,
named outcome -- never silently converted into a fake value. Mapped to
where each actually belongs, since not all of them are the same kind
of thing:

| Failure mode | Where it belongs |
|---|---|
| Unavailable network | An observation-tier fact (`NetworkState.available = false`) -- not itself a measurement failure |
| Measurement timeout | A named `MeasurementFailure` case |
| Cancellation | A named `MeasurementFailure` case, distinct from timeout -- per Phase 3 test strategy Section 5, cancellation must not emit a result after the fact |
| Partial measurement | Represented at the measurement-*set* level (Section 5: latency present, throughput absent-with-reason), not as a single measurement with some fields silently zeroed |
| Endpoint failure | A named `MeasurementFailure` case |
| TLS/security failure | A named `MeasurementFailure` case, distinct from a generic endpoint failure -- distinguishing them matters for whether a retry is ever sensible |
| Invalid response | A named `MeasurementFailure` case |
| Stale data | A freshness-tier concern (Section 11), not a failure -- a stale result is a real result that's simply old, which is a different thing from a failed attempt |
| Insufficient evidence | A distinct, first-class domain outcome (Section 10), not an `AerivaError` -- the *call* to compute an estimate/prediction/recommendation can succeed while correctly returning "insufficient evidence" |
| Permission unavailable | An `AerivaError.PermissionRequired` (existing type, `core:result`) -- this is a call-level failure, not a domain-tier concept, and this design reuses the existing type rather than duplicating it |
| Unsupported platform capability | An `AerivaError.Unsupported` (existing type) for the same reason as above |
| Network transition during measurement | A named `MeasurementFailure` case distinct from the others -- per Phase 3 test strategy Section 4/5, this needs to be distinguishable from a plain endpoint failure, since the correct response (discard vs. retry vs. flag as unreliable) may differ |

**A failed measurement must never accidentally become a zero-quality
or zero-latency measurement** -- structurally enforced by keeping
"the probe failed" and "the probe succeeded with a low value" as
different sealed cases, not different values of the same field.

## 13. Data lineage

The minimum practical structure to answer "what evidence produced this
result," per this task's explicit instruction not to over-engineer
this into event sourcing:

**Each higher-tier result carries a list of identifiers (not full
copies) of the lower-tier results it depends on** -- a recommendation
references the prediction/derived-value IDs it used; those reference
the measurement IDs they were computed from; a measurement references
the observation (`NetworkState`) snapshot active when it was taken.
This is a simple reference chain, not a full audit log of every
intermediate computation step -- exactly the "minimum practical
structure" this task asks for, not a complete event-sourcing system.

```
Recommendation  --(references)-->  Prediction / DerivedValue IDs
Prediction      --(references)-->  Measurement IDs (+ its own time window)
DerivedValue    --(references)-->  Measurement IDs
Measurement     --(references)-->  Observation (NetworkState) snapshot
```

Illustrative only (this exact ID type is not implemented): each tier's
identifier could be as simple as a generated `Long`/`UUID` plus the
timestamp already required on that tier -- sufficient to look the
source back up later without needing a separate graph-database-style
lineage system.

## 14. Network context

Per this task's instruction to design this "only where technically and
legally appropriate," respecting the Phase 2 audit's permission/privacy
findings directly rather than treating them as someone else's problem:

**`MeasurementNetworkContext`** (illustrative type, minimal skeleton):
wraps the existing `NetworkState` snapshot active at measurement time,
plus **optional** fields for Wi-Fi RSSI / cellular signal strength --
optional specifically because, per the Phase 2 audit, obtaining them
requires `ACCESS_FINE_LOCATION`, which is not currently requested and
whose addition requires its own written, Play-policy-aware
justification (Phase 2 audit Section 10). A context without those
fields populated is not an error state -- it's the expected, permitted
state until/unless that permission decision is separately made.

**Explicitly not designed here, per this task's own instruction:**
exact user location. Nothing in this model represents or requires
precise device location; "network context" means *which network and
what its observable characteristics were*, not *where the device
physically was*. If a future feature genuinely needs location (the
Phase 2 audit's own goal-D discussion), that is a separate, later
decision with its own justification -- this document does not build
around it speculatively.

**Network/provider identity, connection generation:** the Phase 2
audit already covers what's obtainable and at what permission cost
(cellular `CellInfo` needs `ACCESS_FINE_LOCATION` too, and Android
10+'s cached-vs-live caveat applies -- Phase 2 audit Section 3). This
design's `MeasurementNetworkContext` has room for these as optional
fields for the same reason as signal strength; none are populated by
anything in this phase.

## 15. Persistence boundary

Per this task's explicit instruction not to automatically create a
Room entity for every object, and following the exact principle
`NetworkStateHistoryEntity`'s own doc comment already states for this
codebase ("persisting fields nothing can populate yet would be storing
data this application cannot honestly claim to have measured"):

**No new Room entity is proposed by this document.** Nothing in this
phase populates real measurement/estimation/prediction/recommendation
data yet -- creating persistence for it now would be persisting shapes
with no real data behind them, the exact thing the existing entity's
own comment already argues against. When Phase 3B (or later) actually
produces real measurement results, the entity for that specific,
real, populated type should be added then, deliberately narrower than
the full domain type in the same way `NetworkStateHistoryEntity` is
narrower than `NetworkState` -- storing what can honestly be claimed,
not the domain model's full shape by default.

**Domain model vs. persistence model vs. Android/platform model,** as
three deliberately separate things (this task's own framing): the
domain types this document defines (Sections 4-9) have zero Android
dependency (Section 2's `core:model` convention); a future persistence
model (Room entities) would live in `core:database`, narrower and
serialization-shaped, exactly like the existing entity; an
Android/platform model (actual `ConnectivityManager`/socket-touching
code) belongs in `network:monitor` or a future measurement-specific
module, and should map *into* the domain types defined here rather
than the domain types depending on it.

## 16. Testability implications

Every requirement from `PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md`'s
own list (empty input, invalid input, partial results, conflicting
results, stale results, duplicate results, ordering, concurrent
measurements, cancellation, timeout, numerical edge cases, insufficient
evidence) is satisfiable against the types this document defines using
**only JUnit4 and hand-constructed values** -- no mocking framework, no
Android dependency, matching that document's own Section 1 finding
about this codebase's existing convention:

- **Empty/invalid/partial/numerical-edge-case input** -- exercised
  directly against derived-value/aggregation functions once they exist
  (Section 6), the same way `NetworkStateMapperTest` exercises
  `NetworkStateMapper` today.
- **Conflicting/duplicate/stale/ordering** -- exercised by constructing
  two (or more) measurement/derived values with deliberately
  conflicting values, duplicate identifiers, or timestamps out of
  order, and asserting the aggregation/lineage logic's defined
  behavior -- no real clock or real concurrency needed.
- **Concurrent measurements / cancellation / timeout** -- these are
  properties of the *engine* that will eventually produce these domain
  values (Phase 3 test strategy Sections 3/5), not of the domain types
  themselves; this document's types are plain, immutable data holders
  with no concurrency behavior of their own to test yet.
- **Insufficient evidence** -- directly testable today: a test
  asserting that a confidence/estimation/prediction/recommendation
  function given zero or below-threshold evidence returns the
  `InsufficientEvidence` case, not a fabricated value (Section 10).
- **The type-level distinctness itself** -- a test can assert this
  structurally (e.g., that an exhaustive `when` over the sealed
  hierarchy has no path that reads a raw measurement value out of an
  estimation, prediction, or recommendation case) rather than only
  testing arithmetic correctness within each case.

## 17. Explicit non-goals

Restated, since this is a design document and a later reader should
not need to infer scope from what's merely absent:

- Full production measurement engine, ping/latency/packet-loss/
  throughput engines, production recommendation algorithm,
  machine-learning prediction system -- none implemented.
- Supabase, VPS, Community Connectivity Intelligence, monetization,
  VPN, UI, analytics dashboard -- none touched.
- `WorkManager` scheduling -- not added; the Phase 3 test strategy's
  own deferred "scheduler seam" decision (its Section 6) remains
  deferred here too.
- No Room entity added (Section 15).
- No new dependency added (Section 19 confirms this explicitly for
  whatever Kotlin files accompany this document).
- No change to `NetworkQuality`, `NetworkState`, `AerivaResult`, or
  `AerivaError` -- the tension with `NetworkQuality` (Section 2) is
  recorded as an open decision, not resolved by editing that type.

## 18. Open decisions before measurement implementation

1. **`NetworkQuality.Measured(score, label)`'s relationship to this
   design** (Section 2) -- extend it to reference this document's
   lineage types, or explicitly document it as a display-only summary
   that must always be derived from them and never populated directly
   from a raw measurement.
2. **Monotonic vs. wall-clock time for a single in-flight measurement's
   own duration calculation** (Section 11) -- this document recommends
   wall-clock for all *persisted* timestamps, but leaves the
   in-flight-duration question for whoever implements the actual
   timing code.
3. **Exact confidence-tier thresholds** (Section 10's illustrative
   sample-count/consistency thresholds are explicitly not a claim of
   correctness for production use).
4. **Whether `MeasurementNetworkContext`'s optional Wi-Fi/cellular
   signal fields are ever populated** -- gated entirely on the Phase 2
   audit's separate, written location-permission-justification
   decision (Section 14), not decided here.
5. **Serialization strategy**, if/when these types (or their eventual
   Room-entity counterparts) need to cross a process/network/storage
   boundary -- deliberately not decided in this phase; per this task's
   instruction not to add a dependency for convenience, no
   serialization library is added by this document, and none of the
   accompanying Kotlin skeleton types require one (they are in-process
   only).
6. **Where the eventual Measurement Provider / Network Client seams**
   (`PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md` Section 6) actually
   live -- `network:monitor`, or a new module -- is not decided here;
   this document's types have no opinion on where the code that
   *produces* them lives, only on the shape of what it produces.

## 19. Phase 3 implementation prerequisites / what accompanies this document

**Kotlin domain-model skeleton, added in this same commit, and why it
belongs in Phase 3A specifically rather than being deferred to pure
documentation:** the core claim of this document (Section 1's purpose)
is that the five-tier boundary is *enforceable at the type level*, not
just describable in prose. A reviewer cannot verify that claim from
prose alone -- only from types that actually compile and a test that
would actually fail if the boundary were collapsed. The skeleton is
kept deliberately minimal per this task's own instruction:

- Added to the existing `core:model` module (Section 2) -- zero new
  dependencies, zero new modules, matching its established
  zero-dependency-pure-Kotlin convention exactly.
- One illustrative concrete type per tier (a single "latency"
  measurement/derived/estimation/prediction/recommendation example
  each) -- not one type per eventual metric (jitter, packet loss,
  throughput), since this task asks for the *boundary*, not full
  coverage of every metric the Phase 2 audit and Phase 3 test strategy
  already scoped.
- `Confidence` and `Freshness` as small, closed, named types (Section
  10-11), reused across every tier rather than each tier inventing its
  own.
- `MeasurementFailure` as a closed sealed type covering exactly the
  failure modes Section 12's table assigns to the measurement tier --
  not the observation-tier or call-level ones, which correctly stay in
  `NetworkState`/`AerivaError` instead of being duplicated here.
- A handful of JUnit4 tests proving: an exhaustive `when` over the
  measurement-tier sealed hierarchy compiles (type-level distinctness),
  a below-threshold sample count produces `Confidence.Insufficient`
  rather than a fabricated tier, and a freshness check given an
  explicit "now" correctly identifies a result as stale without
  calling a real clock.

**This sandbox could not compile or run these tests directly** --
this repository's own established constraint (first documented in
`PHASE_1_VALIDATION_REPORT.md`, and unchanged since): this working
environment's network egress does not reach Maven Central or Google's
Maven repository, so no Gradle build can resolve dependencies here,
regardless of how small the change is. Per this task's own instruction
not to claim validation unless a command actually ran successfully,
Section "Validation" of the final report accompanying this document
states plainly whether a real, observed CI run (via this project's
already-connected CircleCI) confirmed compilation and test results
before any pass/fail claim is made -- this document does not claim
success on the strength of the code merely "looking correct."

**Before the production measurement engine (Phase 3B) can begin:**

1. The open decisions in Section 18, at minimum items 1 (`NetworkQuality`)
   and 4 (location-gated fields), need actual answers -- not
   necessarily final, but at least a recorded direction.
2. The Measurement Provider / Network Client seams
   (`PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md` Section 6) need
   actual interface definitions, which depend on this document's
   measurement-tier shape existing first (now satisfied) and on
   deciding where that code lives (Section 18, item 6).
3. A real, observed CI pass on whatever Kotlin skeleton accompanies
   this document -- not just "it compiled in principle."
4. Per the Phase 2 audit's own exit criteria (`PHASE_2_ANDROID_PLATFORM_AUDIT.md`
   Section 14), the `WorkManager`/scheduler decision remains a
   separate, still-open prerequisite this document does not resolve.
