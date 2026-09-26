PHASE 4 JITTER INDEPENDENT AUDIT

Task: Independent Phase 4 Jitter Verification (audit only, no assumed trust in the prior report)
Auditor: AI 4, same role that produced the prior fix and report, deliberately re-verifying that work from the repository's actual current state rather than from memory of having done it
Scope: DerivedJitterStats.from and its tests, as they actually exist right now, on the actual current branch tips. Not a duplicate of AI 3's broader integration-readiness audit.

---

## 1. Executive summary

**VERIFIED WITH OPEN DECISION.**

The `DerivedJitterStats.from` implementation on `phase-4-test-gate` (current tip `4adf30c`, fix commit `a20dc4c`) was independently re-verified from the repository's actual current state, not from the prior report's claims. Every specific claim the prior report made was checked against fresh reads of the source, fresh CI data, and two additional independent oracles that did not exist when that report was written. All of it held up. No new defect was found in the arithmetic itself.

The specific concern AI 6 flagged from Phase 5 (`FIX-LAT-009`, `FIX-LAT-010`: "the first sample of the next successful run may get dropped") is **CONFIRMED real** against the pre-fix code and **CONFIRMED absent** from the current `phase-4-test-gate` implementation, by actually executing AI 6's own fixture data (transcribed by hand, not copied from any test I had already written) against the real compiled Kotlin fix. This closes AI 6's flag with an executed result, upgrading it from `SUSPECTED_FROM_CODE_READING` to confirmed-and-fixed.

Two decision points the prior report already left open (reported method for counted pairs spanning more than one method; null-handle bridging a real transport change) remain open, unaffected by this audit. **This audit found one further, previously unflagged gap**: neither Decision D4-3 nor the Phase 3 numerical-edge-case rule states whether a non-finite or negative sample should break pairing adjacency for its neighbors the same way a `Failed` sample does, or simply be excised while leaving its numeric neighbors free to pair with each other. The current implementation picks the first behavior (treats it exactly like a `Failed` sample), which is defensible and conservative, but this choice was made silently during the prior fix and was never written down as a decision or pinned by any test. It is recorded here rather than left implicit.

**The more consequential finding is not about the arithmetic.** The fix lives only on `phase-4-test-gate`. The actual integration lineage moving toward Phase 4 completion -- `phase-4-engine-foundation-integration`, currently at Stage 4, CI-green -- still carries the exact pre-fix, buggy `DerivedJitterStats.kt`, because it was built from `phase-4-measurement-foundation` (a sibling of `phase-4-test-gate`, not a descendant), and it never merged the fix. That branch's CI is green only because `DerivedJitterStatsInvariantTest.kt` -- the test that catches the bug -- was never carried onto it either. **"Safe to carry into the eventual Phase 4 integration base" therefore has two different answers depending on what is meant**: the function itself is correct and safe; its presence in the actual current integration base is not yet true and needs an explicit merge action this audit does not take.

## 2. Actual repository state

Verified fresh this session via `list_branches`, `list_commits`, and direct file fetches -- not carried over from the prior report.

| Branch | Tip (verified this session) | Relevant to jitter |
|---|---|---|
| `main` | `e3f70a4a13e63b782c61601abadc2c36f65dbd73` | Phase 3A only. No `DerivedJitterStats` exists on `main` at all. Untouched by this or the prior task. |
| `phase-4-cross-cutting-decisions` | `8a56dc70b19b170d9e334e797b132a65ea3be612` | Unchanged since 2026-09-20, before either jitter task. The decision text quoted in Section 3 was re-fetched from this exact commit this session, not reused from memory. |
| `phase-4-measurement-foundation` | `fdf54408d06ed568f1e2202eb75da46f6024fa7b` | One commit past the `545a961` the prior report used as its base (a `NetworkState` defaults change, unrelated to jitter). Still carries the original, unfixed `DerivedJitterStats.kt` -- the jitter fix was never applied here, only on `phase-4-test-gate`. |
| `phase-4-engine-foundation-integration` | `7731aa4e2504bf946c37e3d631fa35b56ecd87cc` | A mechanical merge of `phase-4-engine-hardening` onto `phase-4-measurement-foundation` (Stages 1-4, dated 2026-09-22 to 2026-09-23), **not built from `phase-4-test-gate`**. Confirmed by direct fetch: its `DerivedJitterStats.kt` (blob `e07477ca`) is byte-identical to the pre-fix version. Its test tree has `DerivedJitterStatsTest.kt` only -- no `DerivedJitterStatsInvariantTest.kt`. CI on its tip is green (verified via CircleCI, run `29d80918`, `succeeded`). Not modified by this audit. |
| `phase-4-test-gate` | `4adf30c911bb8189fd2ff6ad8529492523312da8` | Has not moved since the prior report's own push. `a20dc4c` (the fix) is its direct parent-of-parent; ancestry confirmed by `list_commits`. Not modified by this audit until the report commit below. |
| `phase-5-measurement-validation-harness` | `53ed0e05e373f0953fd0417bd3dc8a6d7a3a8699` | AI 6's branch. Contains `validation/fixtures/latency-series.json` (`FIX-LAT-001` through `FIX-LAT-013`) and `validation/tools/validate_validation_assets.py`. Not modified by this audit. |

No branch listed above had moved between the start and end of this audit session, confirmed by re-listing branches before writing this report.

## 3. Governing definition, re-read fresh (not reused from the prior report)

Fetched in full this session from `phase-4-cross-cutting-decisions` @ `8a56dc70` (the file has not changed since that commit, so this is the same text the prior report read, now independently re-confirmed rather than assumed).

**Decision D4-3, quoted exactly:**

> Rules for `from`: the input is the full ordered series in send order, including failures. A pair counts only if both samples are `Succeeded`, share the same method id, are both warm-connection samples, and have equal network handles (both null counts as equal). A failed sample, a network change, or a method change breaks adjacency and is never bridged. Input not in non-decreasing `measuredAt` order is rejected as unordered evidence. Returns null when there are zero valid pairs. The signed mean is not stored because the mean IPDV is typically zero (VERIFIED FACT, RFC 5481). PDV percentiles are deferred until the validation pilots supply a sample count that makes them meaningful (validation plan D-11).

**Decision D4-4, quoted exactly:**

> jitter uses its own confidence function over pair count. Reusing the latency function unchanged is prohibited. The existing 3 and 10 cutoffs and the 0.5 spread ratio are illustrative in the code's own words. They may be carried over as explicitly provisional starting points and must be replaced by pilot-derived values before release.

**D4.8 testing implications, quoted exactly:**

> Pure JVM arithmetic tests for each derived function including empty input, single sample, all-failed, non-finite, unordered input, mixed methods, and network change mid-series.

Note precisely what D4-3 does and does not say: it names four pairing conditions (Succeeded, same method, warm, equal handle) and says a failure, network change, or method change "breaks adjacency." It does **not** mention non-finite or negative values at all -- that requirement comes from a separate document (`PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md` Section 2, "Numerical edge cases," stated generally for derived scoring and aggregation), and neither document says whether an invalid value should be treated as a fifth adjacency-breaking condition or simply excised. This gap is addressed in Section 9.

This is the specification. It was read and quoted before Section 4 traces the implementation, in the order the task asked for.

## 4. Independent algorithm reconstruction

Read `DerivedJitterStats.kt` directly at `phase-4-test-gate` (blob `7d1d41a1`, re-fetched this session) and reconstructed the algorithm in plain terms, independent of the Kotlin syntax:

1. If the series is empty, or if any timestamp is earlier than the one before it, return no result.
2. Walk the series once, left to right. Keep a `previous` pointer to the last sample seen that was both (a) a `Succeeded` measurement and (b) numerically a real reading (finite, not negative).
3. For every sample in the walk: if it is not `Succeeded`, or is `Succeeded` but not a real reading, clear the `previous` pointer and move on -- this sample can never be a pair member and it also erases the memory of whatever came before it.
4. Otherwise, if there is a `previous` pointer and the current sample forms a valid pair with it (same method, both warm, equal handle), record the absolute difference between their values, and record **both** samples in a running map keyed by measurement id (their value is stored the first time each id is seen; a later re-store of the same id with the same value is harmless). Track the method of the first pair ever recorded.
5. Whether or not a pair formed, the current sample becomes the new `previous` pointer (as long as it was a real `Succeeded` reading).
6. After the walk, if no pair was ever recorded, return no result. Otherwise return: the recorded differences' mean as `meanAbsIpdvMillis`; the max minus the min of every value in the map as `pdvRangeMillis`; the map's size as `sampleCount`; the map's keys, in the order first inserted, as `sourceMeasurementIds`; the number of recorded differences as `pairCount`; the first pair's method as `method`; and a confidence tier from the pair count.

This reconstruction was written from reading the code once, top to bottom, before re-reading the prior report's own description of it, specifically to avoid anchoring on that description. They agree. The one detail the prior report's KDoc addition states but its prose summary underemphasizes is step 3's second half: an invalid reading does not just fail to become a pair member itself, it also **resets the adjacency chain**, exactly like a `Failed` sample. This is addressed as a finding in Section 9, not assumed to be correct simply because it is what the code does.

## 5. Pair eligibility table

| Condition | Valid pair? | Evidence |
|---|---|---|
| Two consecutive `Succeeded`, same method, both warm, same non-null handle | Yes | D4-3 direct statement; `isAdjacentPair` implements exactly these four checks and nothing else |
| Two consecutive `Succeeded`, both null handle | Yes ("both null counts as equal") | D4-3 direct statement; `a.evidence?.networkHandle == b.evidence?.networkHandle` is `null == null`, which is `true` in Kotlin |
| One null handle, one non-null handle | No | Same equality check; `null == 7L` is `false`. Verified against `FIX-LAT-011`-style handle data in Section 6. |
| Different methods | No | `if (a.method != b.method) return false`, first line of `isAdjacentPair` |
| Either sample cold | No | `if (!aWarm || !bWarm) return false` |
| A `Failed` sample sits between two `Succeeded` samples | The two `Succeeded` samples do not pair with each other; adjacency is broken, never bridged | D4-3: "a failed sample ... breaks adjacency and is never bridged"; confirmed by execution against `FIX-LAT-008`, `FIX-LAT-009`, `FIX-LAT-010` in Section 6 |
| A non-finite or negative value sits between two otherwise-pairable `Succeeded` samples | The two real samples do not pair with each other; adjacency is broken, same mechanism as a `Failed` sample | **Not stated by D4-3 or by the general numerical rule.** This is the implementation's own choice, first made during the prior fix, confirmed by trace in Section 9, and flagged there as an unrecorded decision, not a confirmed specification. |
| Out-of-order `measuredAt` anywhere in the series | The whole series is rejected (no result at all, not just that pair) | D4-3 direct statement; confirmed by execution against `FIX-LAT-013` |
| Equal `measuredAt` on consecutive samples | Accepted (non-decreasing, not strictly increasing) | D4-3 says "non-decreasing"; confirmed against the existing, unedited `DerivedJitterStatsInvariantTest.equalTimestamps_areAcceptedAsNonDecreasing` |
| A network change (differing non-null handles) mid-series | The pair spanning the change does not count; later pairs on the new handle count normally | D4-3 direct statement ("a network change ... breaks adjacency"); confirmed by execution against `FIX-LAT-011` |

## 6. Lineage verification, by direct execution against an oracle I did not author

The prior report's argument that the fix was correct rested on the prior report's own test file, `DerivedJitterStatsInvariantTest.kt`, and a hand trace. Both are still valid, re-checked here (Section 7), but this audit adds an oracle **written by a different session, from the same specification, with no visibility into the Kotlin implementation**: AI 6's `validation/fixtures/latency-series.json` on `phase-5-measurement-validation-harness`, and its companion `validate_validation_assets.py`, whose `jitter_oracle` function is a from-scratch Python re-implementation of D4-3.

Two independent checks were run this session:

1. **AI 6's own Python oracle against AI 6's own fixture data**, executed unmodified (`python3 validation/tools/validate_validation_assets.py`, after `pip install jsonschema`). Result: `fixtures checked: 60; ... assertions: 474; OK: all assertions passed`. This confirms the fixture file's stated `expected` values for jitter are internally consistent with an oracle derived independently of both the Kotlin implementation and of anything either AI 4 session wrote.

2. **The real, compiled, current `phase-4-test-gate` Kotlin implementation against those same fixture values**, by hand-transcribing 11 of the 13 `FIX-LAT-*` fixtures (skipping only `003` and `004`, which test latency confidence tiers, not jitter, and add no new pairing case beyond what `001`/`002`/`006` already cover) into a new JUnit test, compiled with the real `kotlinc 2.3.21` and run with real `JUnit 4.13.2`, asserting directly against the fixture file's stated `pair_count`, `sample_count`, `mean_abs_ipdv` and `pdv_range`. Result: `OK (11 tests)`. Every value matched, including:

   - `FIX-LAT-009` (`10, 20, FAIL, 30, 40`): expected `pair_count 2, sample_count 4, mean_abs_ipdv 10.0, pdv_range 30.0`. Actual, from the real compiled fix: identical. The fixture's own `known_implementation_divergence` note predicts `sample_count 3` for the **pre-fix** code by hand trace against `9a28cc62`; the post-fix code does not reproduce that number.
   - `FIX-LAT-010` (`50, 60, FAIL, 10, 20`): expected `pair_count 2, sample_count 4, mean_abs_ipdv 10.0, pdv_range 50.0`. Actual: identical. The pre-fix hand-traced prediction was `sample_count 3, pdv_range 40`; not reproduced.
   - `FIX-LAT-011` (network change mid-series) and `FIX-LAT-012` (cold-then-warm) also matched exactly, independently confirming the handle-break and method/warm-break rules in Section 5.

This means `sourceMeasurementIds`, `sampleCount` and `pdvRangeMillis` are now confirmed correct by three separate routes that were derived independently of each other: the prior report's own oracle (`DerivedJitterStatsInvariantTest`, re-verified unedited in Section 7), AI 6's Python oracle (executed fresh this session), and direct execution of AI 6's fixture values against the real compiled implementation (also executed fresh this session, in a file this audit wrote and that did not exist before).

`method` (F-3 in the prior report) is not exercised by AI 6's fixture set -- none of the 13 `FIX-LAT-*` fixtures use more than one method across separate runs. It remains verified only by the prior report's own test and hand trace (re-confirmed unedited in Section 7), which is weaker corroboration than the lineage fields have, and is exactly why DP-1 (Section 9) stays open.

## 7. The nine invariant tests, re-verified unedited

`core/model/src/test/kotlin/com/aeriva/core/model/measurement/DerivedJitterStatsInvariantTest.kt` was re-fetched from the current `phase-4-test-gate` tip this session and diffed byte-for-byte against the version verified when the fix was written. **Identical.** No test was altered, loosened, or removed between the prior report and this audit.

| Test | Independent re-verification this session |
|---|---|
| `breakThenResume_countsBothRunsCompletely` | Re-traced by hand against the current implementation (Section 4's reconstruction, not the code); matches. Also now covered by the equivalent independent `FIX-LAT-009` case, executed in Section 6. |
| `coldBreakThenResume_countsResumedRunCompletely` | Re-traced by hand; matches. Equivalent independent coverage: `FIX-LAT-012` (different break trigger, same underlying rule), executed in Section 6. |
| `property_lineage_listsEveryMemberOfEveryCountedPair_inSendOrder` | This property test's own oracle (`oracle()` function inside the same file) was read again and is a from-scratch re-implementation of D4-3's pairing rule, not a call to `DerivedJitterStats.from`. Confirmed non-circular. Its 600-series property run was not re-executed this session (the full `core:model` suite was, see Section 12), but the file is confirmed unedited and CI already ran it. |
| `property_sampleCount_equalsDistinctLineageSize` | Internal-consistency check between two output fields, not pinned to an external value. Confirmed the current implementation derives both from the same `members` map by construction (Section 4, step 6), so they structurally cannot drift apart, independent of whether any specific test passes. |
| `property_pdvRange_isMaxMinusMinOverEveryCountedSample` | Independently re-confirmed via `FIX-LAT-009`/`010`/`011` in Section 6, which check this exact field against a differently-sourced expected value. |
| `reportedMethod_isThatOfTheCountedPairs_notOfTheFirstSuccessfulSample` | Re-traced by hand; matches. **Not** independently re-confirmed against AI 6's fixtures, which do not exercise this case (Section 6). This is the weakest-corroborated of the nine. |
| `property_reportedMethod_isTheMethodTheCountedPairsUsed` | Re-read the property's own skip condition (`if (expected != null && expected.methods.size > 1) return@repeat`). Confirmed this is a real skip, not a disguised weakening: it excludes exactly the series the type cannot label today (Section 9's DP-1), and does not touch what it does assert for every other series. Same "not independently cross-checked by a second fixture source" caveat as the row above. |
| `nonFiniteSamples_neverProduceNonFiniteStatistics` | Re-traced by hand for all three cases (NaN, +Infinity, -Infinity); matches. AI 6's fixture set has no non-finite case to cross-check against (Section 8 gap). |
| `negativeLatencySample_isNotTreatedAsARealReading` | Re-traced by hand; matches. Same fixture-set gap as above. |

No test was found to encode behavior absent from D4-3 as if it were specified, with one caveat already raised in Sections 5 and 9: the adjacency-break-on-invalid-value behavior is exercised by `nonFiniteSamples_...` and `negativeLatencySample_...` (both assert the bad sample is excluded from the lineage) but **neither test asserts whether the samples on either side of the bad one do or do not pair with each other**. So the tests are consistent with the implementation's choice without actually pinning it. This is the concrete evidence behind the Section 9 finding.

## 8. Phase 5 fixture verification: `FIX-LAT-009` / `FIX-LAT-010`

Located at `validation/fixtures/latency-series.json` on `phase-5-measurement-validation-harness` @ `53ed0e05`. Read directly, not summarized from the task prompt.

Both fixtures carry a `known_implementation_divergence` block with `"status": "SUSPECTED_FROM_CODE_READING"` and `"impl_ref": "phase-4-measurement-foundation@9a28cc62"` -- meaning AI 6 hand-traced the code at that specific commit and never executed anything, and was checking a different commit than the one this audit is about.

**Classification: (1) a real implementation defect, in the pre-fix code, confirmed by execution, and independently confirmed as already resolved in the current `phase-4-test-gate` implementation** -- not a specification ambiguity, not an incorrect fixture, not a test-runner limitation. Both fixtures' `expected` blocks pass AI 6's own Python oracle (Section 6, check 1) and pass direct execution against the real, current, fixed Kotlin implementation (Section 6, check 2). The fixtures themselves required no correction.

One piece of follow-up worth recording plainly: `impl_ref` in both fixtures still points at `phase-4-measurement-foundation@9a28cc62`, which was never the fixed commit and is not the same lineage as `phase-4-test-gate`. If AI 6 or anyone else re-runs this fixture set against `phase-4-measurement-foundation` or `phase-4-engine-foundation-integration` today, it will still fail, because the fix is not there (Section 2, Section 11). This is not a fixture defect; the fixture's own statement is scoped to a specific commit and is accurate about that commit.

## 9. Numerical edge cases

| Input | Behavior, confirmed by trace against the current implementation | Matches `DerivedLatencyStats`? |
|---|---|---|
| `NaN` | Sample excluded from pairing; adjacency chain reset (Section 4 step 3) | `DerivedLatencyStats.from` filters with `it.valueMillis.isFinite()`; same exclusion outcome. `DerivedLatencyStats` has no adjacency concept, so the "does it also break its neighbors' relationship" question does not arise there -- this is jitter-specific territory with no latency precedent to match against. |
| `+Infinity` / `-Infinity` | Same as `NaN` | Same |
| Negative value | Same as `NaN` | `DerivedLatencyStats.from` filters with `it.valueMillis >= 0.0`; same exclusion outcome |
| Zero | Treated as a real reading (`0.0 >= 0.0` is true, `0.0.isFinite()` is true) | Consistent; not a special case in either function |
| Fractional milliseconds | No special handling needed; `Double` arithmetic throughout | Consistent |
| Very large values | `differences.sum()` and `members.values.max()/.min()` are ordinary `Double` operations with no overflow risk at any latency-scale magnitude; not separately stress-tested this session, low risk given the value domain | Consistent |
| Single pair | `Confidence.Insufficient` (pair count 1 or 2, below `MINIMUM_PAIRS = 3`); confirmed against `FIX-LAT-006` in Section 6 | N/A, latency's own thresholds are different per D4-4 |
| Empty result (zero pairs) | Returns `null`, not a zero-filled object | Confirmed by `FIX-LAT-005`, `FIX-LAT-007` in Section 6 |
| Division by zero | `differences.sum() / pairCount` -- `pairCount` is guaranteed `>= 1` by the `if (differences.isEmpty()) return null` guard immediately above it; not reachable | N/A |

**New finding, not in the prior report (Category 3, missing domain specification):** the current implementation treats a non-finite or negative sample as adjacency-breaking for its *neighbors*, identically to a `Failed` sample -- traced precisely in Section 4 step 3 and Section 5's table. Neither D4-3 nor the Phase 3 numerical-edge-case rule says this is required; both are silent on it. The alternative reading -- excise the bad sample and let its real neighbors pair with each other, since they are still "consecutive" once the invalid one is removed -- is not obviously wrong either. The current choice is the more conservative one (a corrupted or impossible reading is treated the same as "we don't actually know what happened here," matching the spirit of D5's rule against fabricating claims from ambiguous data), and no test in either the prior report's suite or AI 6's fixture set contradicts it, but **no test pins it either** -- both `nonFiniteSamples_...` and `negativeLatencySample_...` check only that the bad sample itself is excluded, not what happens to its neighbors. This was made as an implicit implementation choice during the prior fix, not flagged at the time. It is flagged here. Recommendation in Section 14: record it as a decision (keeping current behavior, since it requires no code change and is the safer of the two readings) rather than leaving it to be rediscovered by whoever writes the test that would have caught it.

## 10. Ordering behavior

- Where validated: the very first thing `from` does after the empty-check, a single forward pass comparing each `measuredAt` to the one before it (`series[i].measuredAt.isBefore(series[i - 1].measuredAt)`).
- What happens on invalid ordering: the entire function returns `null` immediately -- not a partial result, not an exception.
- Equal timestamps: permitted (`isBefore` is strict; two equal instants are not "before" each other). Confirmed against the unedited, existing `equalTimestamps_areAcceptedAsNonDecreasing` test and matches D4-3's literal wording, "non-decreasing."
- Test coverage: confirmed present and unedited (`outOfOrderFailedSample_stillRejectsTheWholeSeries` covers the case where a *failed* sample in the middle of the series is itself the out-of-order element, not just a `Succeeded` one, which is a slightly stronger check than the bare minimum). Additionally, `FIX-LAT-013` (Section 6) independently exercises the same rule against AI 6's oracle and the real compiled implementation, both confirming rejection.
- This was not assumed correct because the KDoc says so; it was traced in the actual control flow (Section 4, step 1) and confirmed by execution against an independently authored fixture (`FIX-LAT-013`).

## 11. Integration compatibility

This is the section with the most consequential finding of this audit.

- **`phase-4-engine-foundation-integration` does not have the fix.** Confirmed by direct fetch: its `DerivedJitterStats.kt` (blob `e07477ca`) is byte-for-byte the pre-fix version, with the `delaysInValidPairs.isEmpty()` bug, the `series.first { it is Succeeded }.method` bug, and no `isRealReading` filter. This is not a guess from ancestry alone (though ancestry alone would already predict it, since the branch was built from `phase-4-measurement-foundation`, a sibling of `phase-4-test-gate`, not a descendant) -- the file content itself was fetched and read.
- **Its test tree does not have the catching test either.** `core/model/src/test/kotlin/com/aeriva/core/model/measurement/` on that branch has `DerivedJitterStatsTest.kt` (the original 12 tests, none of which exercise a resumed run after a break) and no `DerivedJitterStatsInvariantTest.kt` at all.
- **Its CI is green regardless** (CircleCI run `29d80918` on commit `7731aa4`, outcome `succeeded`) -- confirming precisely why "CI green" cannot be read as "jitter is correct" for that branch: the green result reflects that no test on that branch exercises the bug, not that the bug is absent.
- **The fix would apply cleanly if someone chose to port it.** `LatencyMeasurement.kt` and `ProbeEvidence.kt` on `phase-4-engine-foundation-integration` were fetched and diffed against the same files on `phase-4-test-gate`: identical, field for field, KDoc for KDoc. `Confidence`, `ConnectionState`, `AddressFamily` and `MeasurementStage` are all dependencies the unfixed file on that branch already compiles against successfully today, so they are necessarily compatible with the fixed file too, which adds no new external dependency. Porting the fix would be a same-file replacement, not a structural change -- but this audit does not perform that port, per its own instructions not to modify AI 3's branch.
- No engine, `NetworkState`, or permission-adapter changes on either branch affect `DerivedJitterStats.kt`'s inputs in any way that would change this analysis; jitter consumes `LatencyMeasurement` and `ProbeEvidence` only, and both are unchanged between the two branches.

## 12. CI evidence, only what was actually verified

Re-checked this session via CircleCI, not carried over from the prior report's claims.

| Commit | Branch | CircleCI run | Outcome |
|---|---|---|---|
| `d760df1` (9 tests added, deliberately red) | `phase-4-test-gate` | `c6e91414` | `failed` (expected -- this is what "deliberately red" means) |
| `dfe8fa2` (spec doc added, no code change) | `phase-4-test-gate` | `db6c2aa3` | `failed` (still red, fix not yet applied) |
| `a20dc4c` (the fix) | `phase-4-test-gate` | `75047455` | `succeeded` |
| `4adf30c` (report doc added, no code change; current tip) | `phase-4-test-gate` | `08951cae` | `succeeded` |
| `7731aa4` (current tip) | `phase-4-engine-foundation-integration` | `29d80918` | `succeeded` (does not include the fix or its test, see Section 11) |

For the `succeeded` `unit_tests` job on `a20dc4c`/`75047455` specifically: job-level green was confirmed (Gradle's default is to fail the build on any test failure, and the job outcome is `succeeded`), and the uploaded test-result artifact list for that job names 24 individual test classes, including `DerivedJitterStatsInvariantTest`. This audit did **not** re-fetch that job's individual per-test XML this session (CircleCI's log tool did not expose it beyond the artifact list already recorded in the prior report); the job-level result is treated as verified, the individual-test-level claim from the prior report is treated as corroborated-but-not-independently-re-executed by CI directly -- **this is why this audit separately built and ran the local `kotlinc`/JUnit execution in Sections 6, 7 and 12 below, which is fully independently verified, not merely re-read from a prior claim.**

Additional local re-execution this session (real `kotlinc 2.3.21`, real `JUnit 4.13.2`, not simulated):

```
Full core:model suite (9 pre-existing/prior-report test classes, unedited, plus
the new fixture-oracle test added by this audit): 84 tests, 0 failures.

New this session -- Phase5FixtureOracleAuditTest (11 tests transcribed from
AI 6's fixtures, independent of both prior sessions' own test files): 11 tests,
0 failures.

AI 6's own validate_validation_assets.py, executed unmodified: fixtures
checked 60, assertions 474, 0 failures.
```

## 13. Findings

**Confirmed defects (in the pre-fix code; none remaining in the current `phase-4-test-gate` implementation):**
- The three defects the prior report identified (F-2 lineage/sampleCount/range, F-3 method, F-4 numerical filter) are confirmed still fixed, by fresh execution against two independent oracles this session, not by trusting the prior report's own claim.

**Specification ambiguities (open, none resolved by this audit):**
- DP-1 (prior report): reported `method` for a series whose counted pairs span more than one method across separate runs. Confirmed still open; the type still has no field shape for it.
- DP-2 (prior report): null-handle pairing bridging a real transport change once a client actually populates handles. Confirmed still open; unrelated to any code this audit touched.
- **New this audit:** whether a non-finite or negative sample should break adjacency for its neighbors (current behavior) or simply be excised (the alternative). Neither is written down anywhere. See Section 9.

**Test gaps (informational, not confirmed defects):**
- AI 6's fixture set has no non-finite or negative-value case for jitter, so F-4's fix has independent corroboration only from the prior report's own test file, not from a second source the way F-2 does.
- `reportedMethod_isThatOfTheCountedPairs_...` and its property variant (F-3's tests) similarly have no independent fixture-based corroboration.
- `DerivedJitterStatsInvariantTest.kt` does not exist on `phase-4-engine-foundation-integration`, which is why that branch's CI cannot be read as evidence about jitter correctness at all.

**Informational observations:**
- `phase-4-measurement-foundation` and `phase-4-engine-foundation-integration` both still carry the pre-fix jitter code; this is a fact about branch state, not a new defect in the function itself.
- AI 6's `known_implementation_divergence` notes reference a different commit (`phase-4-measurement-foundation@9a28cc62`) than the one this and the prior report worked on (`phase-4-test-gate`); both are accurate about their own scope, and the apparent tension resolves once the branch topology in Section 2 is understood.

## 14. Required actions

**No production-code action is required by this audit.** The function itself is verified correct against everything checked.

**If an owner or engineering decision is wanted**, the exact decision needed is: for a series whose counted pairs span more than one method (DP-1), should the type reject it, split it, or add a `method: Set<String>`? This blocks nothing today (no test or fixture exercises it), so it does not need to be resolved before anything currently planned.

**If a production fix is wanted** (optional, not required): record the non-finite/negative adjacency-break behavior (Section 9) as an explicit decision, and add one test to `DerivedJitterStatsInvariantTest.kt` that pins whether the samples surrounding a bad one do or do not pair with each other -- today's two tests check only that the bad sample itself is excluded. This is a test-only addition; the current behavior needs no code change to satisfy it, since the behavior it would pin already matches what the code does.

**If an integration action is wanted** (the most consequential item, but explicitly out of this audit's own scope to perform): the fix on `phase-4-test-gate` needs to be ported to `phase-4-engine-foundation-integration` before that branch's jitter code can be relied on, since it is not there today and that branch's own CI cannot detect its absence. This audit does not perform that port -- it would mean modifying `phase-4-engine-foundation-integration`, which the task instructions this audit was given explicitly forbid ("do not modify AI 3's branch").

## 15. Final checkpoint

- Production code changed: **NO**
- Tests changed: **NO** (one new test file was created and executed locally in this sandbox for verification purposes, `Phase5FixtureOracleAuditTest.kt`; it was not committed to any branch, is not part of the permanent test suite, and no existing test file was edited)
- `main` touched: **NO**
- Other AI branches touched: **NO** (`phase-4-engine-foundation-integration` and `phase-5-measurement-validation-harness` were read from, never written to)
