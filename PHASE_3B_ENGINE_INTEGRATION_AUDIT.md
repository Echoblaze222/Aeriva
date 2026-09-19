# Phase 3B Engine Integration Audit

**Independent audit only. `phase-3b-measurement-engine` was not modified. No production code, existing document, or Phase 3A file was touched. main was not touched. Nothing was merged.**

All evidence below comes directly from the GitHub API (GitHub Project connector) and CircleCI API (CircleCI connector), read fresh during this audit, plus byte-for-byte file diffs computed by downloading each file via `raw.githubusercontent.com` and running `diff` -- not from any prior report's claims, including this audit's own predecessor (`PHASE_3B_REPOSITORY_STATE_RECONCILIATION.md`).

## Branch state observed

The prior reconciliation audit (read as background, not trusted uncritically) found `phase-3b-measurement-engine` at commit `d1ea16fa`, failing CI. At the start of this audit the branch had moved to **`727b2a91d2724735c3f22965ca72cf4311202370`**, three commits ahead of that state, authored between `2026-09-19T18:36:05Z` and `2026-09-19T18:48:23Z`. No further commits landed in the ~75 minutes between that last push and this audit's checks (re-verified by re-listing the branch immediately before writing this report), and its CircleCI run finished cleanly 8 minutes after the last push -- treated as settled, not mid-flight. **This audit evaluates `727b2a91d2724735c3f22965ca72cf4311202370`; if the branch moves again, it needs re-checking.**

## CI status at the audited commit

Run `677f1e0e-3401-468f-b14c-635768b7da39`, workflow `build_test_and_validate`, **succeeded**, all 4 jobs:

| Job | Outcome |
|---|---|
| build | succeeded |
| unit_tests | succeeded |
| static_checks | succeeded |
| connected_android_test | succeeded |

This is the first fully green CI run this branch has ever had (its two prior commits, `348d216a` and `d1ea16fa`, both failed at `build` with the missing-dependency compile errors the reconciliation audit documented). AI 2's three fix commits since then (add `MeasurementCapabilityClassifier`/`NetworkClient`/test-harness files from their canonical branches, add the missing `implementation(project(":core:common"))` line, fix one missing test import) resolved it.

---

## Findings, one per required question

### 1. Does it contain the canonical AI 3 capability classifier?
**YES.** `network/monitor/src/main/kotlin/com/aeriva/network/monitor/MeasurementCapabilityClassifier.kt` on this branch is **byte-identical** (`diff` returned no output, 221/221 lines) to the same path on `phase-3b-android-contract-review` -- the current, most-corrected version of AI 3's lineage (post DNS-classification fix and the WifiManager-deprecation doc/comment fix), not the older, buggy `phase-3b-android-measurement` version.

### 2. Does it use AI 4's canonical NetworkClient and NetworkClientOutcome?
**YES.** `network/monitor/src/main/kotlin/com/aeriva/network/monitor/measurement/NetworkClient.kt` is **byte-identical** (88/88 lines) to the same path on `phase-3b-measurement-tests`. `NetworkClientOutcome` is defined in this same file (`Success`, `ConnectionRefused`, `TlsHandshakeFailed`, `NetworkChangedMidCall`) -- confirmed identical as part of the same file comparison, not a separate/forked definition. The engine's test doubles (`FakeNetworkClient.kt`, `MutableClock.kt`, `ReferenceLatencyProbeExecutor.kt`) are also byte-identical to their `phase-3b-measurement-tests` originals.

### 3. Does it correctly use the Phase 3A measurement domain types?
**YES.** `LatencyMeasurementEngine.kt` imports `DerivedLatencyStats`, `LatencyMeasurement`, `MeasurementFailure`, and `MeasurementNetworkContext` from `com.aeriva.core.model.measurement`. Direct diff of each against `main`'s merged Phase 3A versions: `LatencyMeasurement.kt`, `MeasurementFailure.kt`, and `MeasurementNetworkContext.kt` are **unchanged** on this branch. `DerivedLatencyStats.kt` carries AI 4's already-known, already-reviewed `.from()` addition (also byte-identical to its canonical `phase-3b-measurement-tests` version) -- additive to the Phase 3A type, not a rewrite. `NetworkQuality.kt` (the file Phase 3A's own design doc flagged as a future "generic score" risk) is also **unchanged** -- the engine does not touch it (see also Finding 9).

### 4. Does it duplicate or redefine any existing contract?
**NO.** Every dependency file checked (classifier, `NetworkClient`, test harness) is byte-identical to its canonical source, confirmed by direct `diff`, not by trusting the commit messages' own claims of byte-identity. The engine's `classify` constructor parameter defaults to `MeasurementCapabilityClassifier::classify` (the real object) rather than a local reimplementation. No second `NetworkClient`-shaped interface or second `DerivedLatencyStats`-shaped aggregation function exists anywhere in the branch's file tree (121 files total, listed and reviewed).

### 5. Does LatencyMeasurementEngine have clean dependency direction?
**YES.** The engine (`network:monitor`) depends downward on `core:model` (domain types) and `core:common` (`AerivaDispatchers`) -- confirmed by its imports and by the one `build.gradle.kts` change this branch made (`implementation(project(":core:common"))`, additive, does not touch AI 4's existing `testImplementation` lines). No `core:model` file imports `android.*` (checked directly). Nothing in `core:model` or `core:common` references `network:monitor`, the engine, or Android -- domain layer has no upward/outward dependency on this feature layer.

### 6. Are timeout and cancellation boundaries correct?
**YES, and this is well-documented in the code itself.** `measure()` wraps the probe in `withTimeout(timeoutMillis)` and only catches `TimeoutCancellationException` specifically -- a real caller-initiated `CancellationException` (which `TimeoutCancellationException` is a subtype of, so the `catch` clause's specificity matters) is never caught and propagates unchanged, meaning `measure()` never returns a value for a genuinely cancelled attempt. This exact distinction is covered by two separate, passing tests: `measure_onTimeout_returnsMeasuredFailedTimeout_andCancelsClientCleanly` and `measure_onCallerCancellation_propagates_andEmitsNoOutcome`.

### 7. Are measurement failures mapped correctly without collapsing transport failures into domain failures?
**YES.** `toMeasurement()` maps each `NetworkClientOutcome` case to a distinct `MeasurementFailure` subtype: `ConnectionRefused` -> `MeasurementFailure.EndpointFailure`, `TlsHandshakeFailed` -> `MeasurementFailure.TlsFailure`, `NetworkChangedMidCall` -> `MeasurementFailure.NetworkChangedDuringMeasurement`, plus a separate `InvalidResponse` case for a successful transport response carrying the wrong payload. Timeout is mapped separately, outside this function, to `MeasurementFailure.Timeout`. This is a `when` over a sealed interface with **no `else` branch** -- the compiler enforces exhaustiveness, so a future `NetworkClientOutcome` case would force a compile error here rather than silently falling into a generic bucket. A dedicated test exists specifically for this property: `measure_transportOutcome_isMappedToDistinctDomainFailure_notOneGenericFailure` (passing, per the green CI run above).

### 8. Does it accidentally introduce WorkManager, Supabase, UI, persistence, VPN, or unrelated functionality?
**NO.** The full 121-file branch tree was listed and reviewed; the only files that differ from `main` are the ones already named in Findings 1-3 (the engine's own two files, the classifier + its test, `NetworkClient` + test harness, `DerivedLatencyStats.from()` + test, `TestAerivaDispatchers` testFixtures migration, and two `build.gradle.kts` additions). A direct case-insensitive search across every downloaded file for `workmanager`, `supabase`, `vpnservice`, `androidx.work`, and `androidx.room` (new usage, distinct from `core:database`'s pre-existing Room setup from Phase 1) returned nothing.

### 9. Does it introduce generic network scores or bypass the Phase 3A measurement lineage?
**NO.** `NetworkQuality.kt` (home of the `Measured(score: Int, label: String)` type Phase 3A's own design doc flagged as an anti-pattern risk) is unmodified on this branch -- the engine never references or populates it. Every output type (`LatencyMeasurement`, `MeasurementFailure`, `DerivedLatencyStats`) flows through Phase 3A's own sealed-type lineage; nothing is collapsed into a bare score/label pair.

### 10. Does the implementation actually perform real latency measurement rather than simulated/fake measurement?
**Nuanced -- the engine's own logic is real, but nothing production-real is wired to it yet.** `measure()` calls `networkClient.probe(request.target)` through the injected `NetworkClient` interface and times it with `System.nanoTime()` around the actual suspend call -- this is genuine elapsed-time measurement of whatever the injected client does, not a hardcoded or simulated number. However: **`NetworkClient` itself is declared, in its own KDoc on `phase-3b-measurement-tests`, as intentionally implementation-free** ("No implementation of this interface is added by Phase 3B... the real socket-backed implementation is production measurement-engine work"). Checking the full 121-file tree on `phase-3b-measurement-engine`: the only implementation of `NetworkClient` anywhere is `FakeNetworkClient` (test source set only). **No concrete, socket/HTTP-backed production implementation of `NetworkClient` exists on this branch, or on any other branch checked in this or the prior audit.** So today, nothing in the repository can produce an actual on-device latency number end-to-end -- the engine is real, correctly structured, and tested against a fake; a real transport implementation is the next gap, not something this branch's scope claimed to close (its own KDoc is explicit that jitter/packet-loss/throughput and, implicitly, a real transport are out of scope for this phase).

---

## Summary table

| # | Question | Verdict |
|---|---|---|
| 1 | Canonical AI 3 classifier | PASS -- byte-identical |
| 2 | Canonical AI 4 NetworkClient/Outcome | PASS -- byte-identical |
| 3 | Correct Phase 3A domain type usage | PASS -- unmodified types, additive DerivedLatencyStats only |
| 4 | No duplicated/redefined contracts | PASS |
| 5 | Clean dependency direction | PASS |
| 6 | Timeout/cancellation boundaries | PASS -- tested explicitly, two dedicated tests |
| 7 | Failure mapping, no collapsing | PASS -- exhaustive `when`, dedicated test |
| 8 | No forbidden additions | PASS -- full tree reviewed |
| 9 | No generic-score bypass | PASS -- NetworkQuality.kt untouched |
| 10 | Real vs. simulated measurement | PARTIAL -- engine logic is real; no production `NetworkClient` implementation exists anywhere yet (REQUIRES DECISION, not a defect in this branch) |

## CI evidence

| Commit | Run | Outcome |
|---|---|---|
| `348d216a` (engine's first commit, no deps) | `666d57af` | failed -- build |
| `d1ea16fa` (core:common/model deps only) | `5aeed0db` | failed -- build |
| `51e67d9b` (network:monitor deps brought in) | `0b3fa311` | failed -- build (missing gradle dep line, fixed next) |
| `3973c093` (gradle dep line added) | `8e5b894b` | failed -- unit_tests (missing test import, fixed next) |
| `727b2a91` (test import fixed) -- **audited commit** | `677f1e0e` | **succeeded, all 4 jobs** |

## Not done (per this task's scope)

No production code, Phase 3A file, or `phase-3b-measurement-engine` branch content was modified. No merge was performed. No opinion is offered here on whether/when to merge -- that remains an explicit decision for Anita, not this audit.

---

*End of audit report.*
