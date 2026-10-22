# Phase 4 -- Engine/Foundation Integration Notes (AI 2)

What was actually done on `phase-4-engine-foundation-integration`, and the CI evidence for it.
The plan this executed is `PHASE_4_ENGINE_FOUNDATION_RECONCILIATION.md` (branch
`phase-4-engine-foundation-reconciliation`); this document records execution, not planning.

## Branch and commits

Base: `phase-4-measurement-foundation` @ `fdf54408d06ed568f1e2202eb75da46f6024fa7b`.
Integrates: `phase-4-engine-hardening` @ `158f16947461fef0a7c983949f61e90beeb15dda`.
Final commit: `a21ed84de1c0f778762ced7d6e7db645fc47c946`.

| Commit | Stage | Files | CI (4 jobs) |
|---|---|---|---|
| `19f86b1`, `c603ec2` | 1: mechanical merge | `LatencyMeasurementEngine.kt`, `LatencyMeasurementEngineTest.kt`, `PHASE_4_ENGINE_HARDENING_NOTES.md` | green |
| `5dec29c` | 2: `NetworkState` defaults removed (DD-5) | `NetworkState.kt`, `NetworkStateTest.kt` (new) | green |
| `351b10a` | 3 (1/2): engine + fakes | `LatencyMeasurementEngine.kt`, `FakeNetworkClient.kt`, `FakeNetworkClientTest.kt` | red (expected -- test file not yet updated to match, see below) |
| `aa033ed` | 3 (2/2): test file | `LatencyMeasurementEngineTest.kt` | green |
| `5348334` | 3 fix-up | `LatencyMeasurementEngineTest.kt` (1-line unused-import removal) | green |

No production code changed beyond what the reconciliation document specified. `main` untouched,
nothing merged, nothing force-pushed. `phase-4-test-gate` not modified. No `INTERNET` permission,
no OkHttp, no production `NetworkClient`, no endpoint change, no Phase 3C.

### Why commit `351b10a` is red, and why that is not a defect
GitHub's content-commit API accepts one file's content per call for a large payload without a
practical single-call size ceiling being tested here, so Stage 3's four files were pushed as two
consecutive commits, engine+fakes first and the matching test file second. Between those two
pushes the engine's new constructor signature and catch semantics did not yet match the still
old-style test file, so the intermediate commit fails to compile. This is exactly the git-history
equivalent of a mid-edit save and is expected; the CI gate that matters is the *last* commit of the
stage, `5348334`.

### The fix-up commit
After pushing `aa033ed`, a full-content diff of the pushed file against the local working tree
(`git diff origin/... HEAD`, not a partial check) found one discrepancy: an unused
`import org.junit.Assert.fail` left over from reconciling an earlier, separately-stashed working
tree against the verified Stage 1 commit. No test in the file calls `fail()` after the Stage 3
reshaping (each reshaped test now asserts an explicit expected outcome). This was caught by content
comparison before relying on CI, then removed and re-verified byte-identical (`git diff` empty)
before the final CI run. No other discrepancy was found.

## Verification method
Every push in this session was followed by `git fetch` + `git diff origin/<branch> HEAD` against
the exact local content that was sent, confirming byte-for-byte equality before treating any commit
as "landed as intended." This catches transcription mismatches between what was written locally and
what the push tool actually stored, independent of and prior to CI.

## Repository-wide sweep -- before and after

Method: `git grep` across the entire tracked tree (`git ls-files`, all extensions, every module and
source set including `androidTest`), never a module-local or path-scoped search, run once against
the tree immediately before any Stage 2/3 edit and again against the final pushed tree. The full
script is reproducible from the queries below; each was re-run explicitly against the final `a21ed84`
tree during this session, not carried over by assumption from the planning document.

| Check | Before | After |
|---|---|---|
| `NetworkState(...)` constructor sites (excl. declaration) | 6, all explicit | 6, all explicit (unchanged -- Stage 2 removed the default, not any call site) |
| Bare `MeasurementFailure.Timeout` object references (real code, not comments) | 1 (the `timeoutFailure()` helper -- X2) | 0 |
| `MeasurementFailure.Unclassified` reachable from the engine | no | yes (Stage 3) |
| `AerivaLogger` required by the engine's constructor | no | yes, no default (Stage 3, DD-4) |
| `NetworkState` primary-constructor defaults | 3 (`= false`) | 0 (Stage 2, DD-5), pinned by `NetworkStateTest` |
| `catch` clauses in the engine's `measure` | 1 (`TimeoutCancellationException` only) | 4 (`TimeoutCancellationException` -> `CancellationException` -> `NonMonotonicElapsedClockException` -> `Exception`), `Error` never caught |
| `"tcp-round-trip"` pinned as a literal in the engine test | yes (1 site) | no (N9 -- asserts against the request's own default instead) |
| `android.permission.INTERNET` added to a manifest | no | no (only the pre-existing classifier constant and test data reference the string) |
| `okhttp` in any build/catalog file | no | no |
| `MeasurementEndpointConfig.current` | `null` | `null` (unchanged) |
| `AerivaLogger` production implementers | `NoOpLogger`, `AndroidLogcatLogger` | unchanged (both still the only two; matches the engine's new parameter type) |

No forbidden addition (`INTERNET`, OkHttp, a production `NetworkClient`, an endpoint change) was
introduced at any point in this session, before or after.

## Tests

39 tests in `LatencyMeasurementEngineTest` (36 pre-existing hardening tests, all preserved -- 4
reshaped to their new expected outcomes per the approved decisions, intent unchanged; 32
byte-for-byte unchanged -- plus N1-N9 from the reconciliation document). 5 in `FakeNetworkClientTest`
(1 reshaped -- N8). 1 in the new `NetworkStateTest` (N10). All 45 pass individually, confirmed via
CircleCI's per-test results on the final commit, not inferred from a green run summary alone.

Every foundation test (`NetworkStateMapperTest`, `MeasurementFailureTaxonomyTest`,
`DerivedJitterStatsTest`, `MeasurementBoundaryTest`, `MeasurementMethodTest`,
`MeasurementEndpointConfigTest`, `NetworkHistoryRepositoryTest`, `ReferenceLatencyProbeExecutorTest`)
is untouched by any commit in this branch and passed as part of the same green `unit_tests` job.

## CI evidence (final commit `a21ed84`, CircleCI run `abea1406`)

| Job | Result |
|---|---|
| `build` | succeeded |
| `static_checks` | succeeded |
| `unit_tests` | succeeded (39/39 engine tests, 5/5 fake-client tests, 1/1 NetworkStateTest, individually confirmed) |
| `connected_android_test` | succeeded |

## Open items carried forward (unchanged from the reconciliation document, not addressed by this
branch, and not claimed as resolved)
- `Unclassified` mapping for `SecurityException` specifically (decline -> `CapabilityUnavailable` +
  defect log) -- required before S3, needs the permission adapter this branch does not add.
- The engine's own backstop-deadline firing becoming a logged defect -- intentionally deferred to
  S4 (Decision G); this branch's own new test asserts the current, correct-for-now zero-log
  behavior.
- Stale measurement context and the `System.nanoTime()` vs `SystemClock.elapsedRealtimeNanos()`
  question -- unchanged from the hardening branch's own notes; required before S3 and before
  real-device validation respectively, not something a domain-model/engine-only change resolves.
- `phase-4-test-gate`'s own reported 9 deliberately-red `DerivedJitterStats` invariant tests --
  untouched, unrelated to this branch's scope, and that branch was not modified.
