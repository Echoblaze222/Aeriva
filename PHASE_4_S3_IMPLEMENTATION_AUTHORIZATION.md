# Phase 4 Slice S3 Implementation Authorization

**Gate document only.** No production Kotlin, Gradle file, or manifest was modified to produce this document. No endpoint, credential, permission, or dependency was added. main is untouched (still `e3f70a4a13e63b782c61601abadc2c36f65dbd73`). No branch was merged. Every repository claim below was read fresh this session, via the GitHub API and full-tarball greps across every relevant branch, not carried over from an earlier report.

---

## 1. Current repository state (read fresh this session)

| Branch | HEAD | Verified how |
|---|---|---|
| `phase-4-measurement-foundation` | `fdf54408d06ed568f1e2202eb75da46f6024fa7b` | `list_branches` + `list_commits`, this session |
| `phase-3b-measurement-engine` | `727b2a91d2724735c3f22965ca72cf4311202370` | same, unchanged since the foundation slice was approved from it |
| `phase-4-cross-cutting-decisions` | `8a56dc70b19b170d9e334e797b132a65ea3be612` | unchanged |
| `phase-4-implementation-readiness` | `e86f26a9ddb698ee6d2aa74879e168261ef9e2b9` | unchanged |
| `phase-4-measurement-architecture` | `f279476c6884fabe22b238e8f688263cc38283b6` | unchanged |
| `phase-4-s3-network-client-spec` | does not exist | full branch listing, this session |
| `phase-4-production-endpoint-security` | does not exist | same |
| `phase-4-s3-test-architecture` | does not exist under this exact name (see §1.1) | same |
| `phase-4-android-platform-spec` | **exists**, `458e32ee499226c7c8a34be34110acf3a569acc6` | read in full this session |
| `phase-4-real-device-execution` | does not exist | same |

### 1.1 Other branches found that this task's list did not name

A full listing (25 branches) surfaced four more Phase 4/5 branches, all unmerged, none named in this task's checklist. Read because an authoritative gate cannot ignore parallel work that exists:

| Branch | Base | What it contains | Touches NetworkClient/manifest/gradle/INTERNET/OkHttp? |
|---|---|---|---|
| `phase-4-android-network-state` (`c0ad893b`) | `phase-4-measurement-foundation` | Modifies `AndroidNetworkMonitor.kt`, `NetworkStateMapper.kt`, `NetworkState.kt`; adds `NetworkEventReducer.kt` + test | No |
| `phase-4-engine-hardening` (`158f1694`) | `phase-4-measurement-foundation` | Modifies `LatencyMeasurementEngine.kt`, `MeasurementFailure.kt`, `LatencyMeasurement.kt`, `NetworkState.kt`, `NetworkStateMapper.kt` + several test files; adds `PHASE_4_ENGINE_HARDENING_NOTES.md` | No |
| `phase-4-test-gate` (`dfe8fa20`) | `phase-4-measurement-foundation` @ `545a9612` | Doc (`PHASE_4_TEST_GATE_SPECIFICATION.md`) plus new test-only files (a `gates/` directory, matrix tests); labeled "Owner: AI 4 (testing layer)" in its own header -- functionally the closest thing to this task's item 8, but under a different branch name, so item 8 is correctly reported as not existing under the name asked for | No |
| `phase-5-measurement-validation-harness` (`c4900055`) | An older point in the lineage, predating the Phase 3B `core:common` dependency fixes (its `build.gradle.kts` files lack the `implementation(project(":core:common"))` line and the `java-test-fixtures` plugin the foundation branch has) | `PHASE_5_DEVICE_TEST_MATRIX.md`, `PHASE_5_TEST_DATA_AND_EVIDENCE_SCHEMA.md` | Its `build.gradle.kts` differs from the foundation branch's, but only by *lacking* the foundation's later fixes -- it adds no OkHttp, no INTERNET, no new dependency. Stale base, not a new gradle conflict. |

**None of these four branches touch `NetworkClient.kt`, any manifest, any `build.gradle.kts` dependency addition, or anything OkHttp-related.** They are real, unmerged, mutually-divergent forks of `core:model`/`network:monitor` files this S3 authorization also depends on (`NetworkState.kt`, `MeasurementFailure.kt`, `LatencyMeasurementEngine.kt` are each modified differently by two or more of these branches) -- a real integration-debt risk flagged in §15, but not a conflict with the S3 gate's own scope, since none of the four modify the files this authorization actually governs (`NetworkClient.kt`, manifests, dependency catalogs).

### 1.2 `phase-4-android-platform-spec` (AI 5's work, task item 9)

Exists and was read in full (376 lines). Pinned to `phase-4-measurement-foundation @ 9a28cc62` (an earlier point on the same branch, before the CI-driven call-site fixes); the document itself verifies and states that neither of the two fix commits between its pin and the branch tip it was actually committed from (`89337fc0`) touches anything permission-, manifest-, or classifier-related, so its findings are current. It is specification-only: no `INTERNET`, no manifest edit, no dependency, no `PermissionAdapter` implementation body. Its two load-bearing findings, independently spot-checked against this session's own repository reads and found accurate:

- **`INTERNET` placement is `network:monitor/src/main/AndroidManifest.xml`**, in the same change that adds the first production `NetworkClient` implementation -- restates Decision D1-1, does not revise it. Confirmed by this session: that manifest today (`phase-4-measurement-foundation` HEAD) declares only `ACCESS_NETWORK_STATE`; `app/src/main/AndroidManifest.xml` declares nothing.
- **`:app` does not yet depend on `:network:monitor`** (confirmed by this session: `app/build.gradle.kts`'s dependencies block has no `project(":network:monitor")` or `project(":core:model")` entry). This means declaring `INTERNET` in `network:monitor`'s manifest alone would not reach any manifest `:app` actually merges and ships -- Decision D1-5's `:app` dependency step is a real, separate precondition, not an optional follow-up. This is folded into §10's implementation order below.

`phase-4-s3-network-client-spec`, `phase-4-production-endpoint-security`, and a document with the exact scope of task item 8 under the exact name asked for do not exist. Item 10 (`phase-4-real-device-execution`) does not exist either.

---

## 2. Exact approved base commit

**`phase-3b-measurement-engine @ 727b2a91d2724735c3f22965ca72cf4311202370`** remains the correct base for any change that touches `network:monitor` production code (this is what the Phase 4 foundation slice was built from, and nothing on `main` yet carries the classifier, `NetworkClient` interface, or engine). `phase-4-measurement-foundation @ fdf54408d06ed568f1e2202eb75da46f6024fa7b` is the correct base specifically for the JVM-only domain types (`ProbeEvidence`, `MeasurementFailure` taxonomy, `MeasurementMethod`, `DerivedJitterStats`, `MeasurementStage`) that slice S3's `NetworkClient` migration and `OkHttpNetworkClient` implementation must build on -- S3 work should branch from `phase-4-measurement-foundation`, not from `phase-3b-measurement-engine` directly, or it will not have these types available.

---

## 3. OD-1 status: outbound network access authorization (`INTERNET`)

**OWNER ACTION REQUIRED. Not authorized by anything in this repository.**

Searched (this session): every manifest on `phase-4-measurement-foundation` and all four related unmerged branches in §1.1 -- zero `<uses-permission>` entries for `INTERNET` anywhere. Searched every document on every branch listed in §1 for an explicit "OD-1: approved" or equivalent record -- none exists. `PHASE_4_CROSS_CUTTING_TECHNICAL_DECISION_CONTRACT.md`'s own Section 12 owner-decision register lists OD-1 as open, not resolved, and `phase-4-android-platform-spec` (§1.2) independently restates it as still open in its own §10. No commit message, PR, or document anywhere in this repository records the repository owner (Anita) approving OD-1. This authorization document cannot manufacture that approval -- it can only confirm, as it does here, that the approval does not yet exist in any repository artifact.

---

## 4. OD-7 status: OkHttp as the production HTTPS client, and which version

**OWNER ACTION REQUIRED for the admission decision itself. The version-compatibility question underneath it is independently VERIFIED (research carried out and recorded in `PHASE_4_MEASUREMENT_FOUNDATION_NOTES.md`, re-confirmed this session, not re-litigated).**

No `okhttp` entry exists in `libs.versions.toml` or any `build.gradle.kts` on any branch checked in §1 (confirmed by grep across every fetched branch tarball this session). No document records an owner approval of OkHttp's admission. The following is ready evidence for whoever makes that decision, not a substitute for it:

- OkHttp 5.5.0's `okhttp-android` artifact declares `minCompileSdk = 37`. This repository is pinned to `compileSdk 36` (`network:monitor/build.gradle.kts`, confirmed unchanged this session). **5.5.0 is incompatible.**
- **OkHttp 5.4.0** (released 2026-06-09) is the newest release whose `okhttp-android` artifact is compatible with `compileSdk 36` -- this is the correct pin if OD-7 is approved without also raising `compileSdk` to 37 first.
- **minSdk compatibility**: OkHttp 5.x's stated minimum Android API level is 21 (Android 5.0); this repository's `minSdk = 26` is comfortably above that floor -- no conflict.
- **Required Gradle dependency**: `com.squareup.okhttp3:okhttp-android:5.4.0` (the Android-specific artifact, not the plain JVM `okhttp` artifact -- see next point), as an `implementation` dependency in `network:monitor/build.gradle.kts` only. It must not be added to `core:model` or `core:common`, which this repository's own existing convention (and Decision D3 in the cross-cutting contract) keeps Android-import-free.
- **`okhttp-android` vs. plain `okhttp`**: `okhttp-android` is the correct artifact -- it is OkHttp's own Android-specific packaging (introduced in the 5.x line), which is what carries the `minCompileSdk` AAR metadata checked above and includes Android-appropriate defaults (e.g. platform trust manager integration). The plain `okhttp` JVM artifact would also compile but is not the artifact OkHttp's own documentation recommends for an Android target as of the 5.x line.
- **Licensing**: OkHttp is Apache License 2.0 -- permissive, no copyleft obligation, compatible with a proprietary or open-source app either way. Not itself a blocker, but the owner decision should note it since no other dependency in this repository's current `libs.versions.toml` was checked against a license policy in this session (out of scope for this to re-audit every existing dependency; noted only for OkHttp as the new addition).
- **Transitive dependencies**: OkHttp 5.x's `okhttp-android` artifact pulls in `okio` (its I/O library) and, per the cross-cutting contract's own Decision D3 security note (restated, not re-verified independently this session against 5.4.0 specifically), an AndroidX Startup content-provider entry used for platform trust-manager initialization -- this is exactly the kind of transitive addition Decision D1-6's manifest allowlist check exists to catch (see §13).
- **Module placement**: `network:monitor` -- confirmed correct and the only reasonable placement, since that is the module `NetworkClient`/the classifier/the engine already live in, and neither `core:model` nor `core:common` may take an Android or OkHttp dependency without breaking their existing, load-bearing Android-import-free property (confirmed unchanged this session in both files).

---

## 5. Approved dependency/version

**Not yet approved (OD-7 open).** If and when OD-7 is approved: `com.squareup.okhttp3:okhttp-android:5.4.0`, `implementation` scope, `network:monitor/build.gradle.kts` only. This is a ready recommendation backed by verified evidence (§4), not an authorization -- this document does not add it.

## 6. Approved manifest location

**Design confirmed, not yet authorized to execute (OD-1 open).** `network:monitor/src/main/AndroidManifest.xml`, in the same change that adds the first production `NetworkClient` implementation (restates Decision D1-1; independently grounded by `phase-4-android-platform-spec` §8.1, §1.2 above). **A real precondition this document surfaces as newly load-bearing**: `:app`'s `build.gradle.kts` has no dependency on `:network:monitor` today, so declaring `INTERNET` there alone would not reach any manifest `:app` ships until that module dependency is also added (§1.2, §10).

## 7. Approved NetworkClient migration boundary

**Design authorized to proceed once OD-1/OD-7 land** (this is a technical boundary, not an owner gate -- nothing here requires further owner sign-off beyond OD-1/OD-7 themselves). Confirmed this session by reading the current interface directly (`network/monitor/src/main/kotlin/com/aeriva/network/monitor/measurement/NetworkClient.kt` on `phase-4-measurement-foundation`, unchanged since `phase-3b-measurement-engine`): still the original four-case `NetworkClientOutcome` (`Success`, `ConnectionRefused`, `TlsHandshakeFailed`, `NetworkChangedMidCall`) and a bare `probe(target: String)` signature. The cross-cutting contract's Decision D5-11 `ProbeRequest`/richer-outcome sketch remains the correct target shape -- confirmed via the full-repository call-site sweep in §12 that nothing on any branch has begun that migration yet (`ProbeRequest` does not exist as a symbol anywhere in this repository). The migration boundary is: `NetworkClient.kt`, `NetworkClientOutcome`, `FakeNetworkClient.kt`, `ReferenceLatencyProbeExecutor.kt` + its test, `LatencyMeasurementEngine.kt`'s `toMeasurement`/probe call site, and `LatencyMeasurementEngineTest.kt` -- exactly the six-file set the Phase 4 foundation notes already identified as the deferred S3 scope, re-confirmed current by this session's own sweep.

## 8. Approved endpoint boundary

**No endpoint authorized. `MeasurementEndpointConfig.current` must remain `null`** until OD-2/OD-3/OD-4 (endpoint identity/hosting/regional strategy -- all separate, still-open owner decisions per the cross-cutting contract, none touched or resolved by this document) are recorded. This document does not invent one, per its own instructions, and confirms (via §1's repository read) that nothing on any branch checked has invented one either.

## 9. Approved security boundary

No credentials, no TLS pinning beyond OkHttp's platform-default trust manager, no signing changes -- all explicitly out of scope for S3 per the cross-cutting contract and this task's own stop conditions, and confirmed not present anywhere in the repository this session. `phase-4-production-endpoint-security` (task item 7) does not exist, so no security-specific specification exists yet to layer onto this boundary -- that remains a gap, named here rather than filled.

---

## 10. Required implementation order

1. Owner records OD-1 (INTERNET) and OD-7 (OkHttp 5.4.0 admission, or a `compileSdk 37` migration decided first if 5.5.0 is preferred instead -- a separate, larger owner decision this document does not recommend).
2. `NetworkClient`/`ProbeRequest` migration (§7's six-file set), branched from `phase-4-measurement-foundation`, in one CI-verified change -- per this session's own prior finding (recorded in the foundation notes) that splitting this migration into smaller pushes on the same class of change already cost multiple failed CI rounds once; do not repeat that pattern for a change this size.
3. `OkHttpNetworkClient` production implementation, in the same change as step 2 (it cannot compile against the old interface -- see §7).
4. `INTERNET` declaration in `network:monitor`'s manifest, in the same change (Decision D1-1).
5. `:app`'s dependency on `:network:monitor` (§1.2, §6) -- without this, step 4 has no effect on any real, installable manifest. Confirm via the manifest-merge verification design in `phase-4-android-platform-spec` §8.3 (a CI check reading AGP's actual merged-manifest output, not an assumption of what merging should produce).
6. `PermissionAdapter` implementation, following `phase-4-android-platform-spec`'s design (its own §6 -- signatures, not bodies, are already specified; this document does not re-specify them).
7. Reconcile with the four unmerged branches in §1.1 that already modify files S3's own migration will touch (`NetworkState.kt`, `MeasurementFailure.kt`, `LatencyMeasurementEngine.kt`) -- not S3's job to merge them, but S3's implementer should check for fresh conflicts before starting, since this document's own repository read is only current as of this session.

## 11. Complete affected-file inventory (S3's own scope)

| File | Change |
|---|---|
| `network/monitor/src/main/kotlin/com/aeriva/network/monitor/measurement/NetworkClient.kt` | Interface evolution (D5-11) |
| `network/monitor/src/test/kotlin/com/aeriva/network/monitor/measurement/FakeNetworkClient.kt` | Migrate to new contract |
| `network/monitor/src/test/kotlin/com/aeriva/network/monitor/measurement/FakeNetworkClientTest.kt` | Migrate |
| `network/monitor/src/test/kotlin/com/aeriva/network/monitor/measurement/ReferenceLatencyProbeExecutor.kt` | Migrate |
| `network/monitor/src/test/kotlin/com/aeriva/network/monitor/measurement/ReferenceLatencyProbeExecutorTest.kt` | Migrate |
| `network/monitor/src/main/kotlin/com/aeriva/network/monitor/measurement/LatencyMeasurementEngine.kt` | Update probe call site |
| `network/monitor/src/test/kotlin/com/aeriva/network/monitor/measurement/LatencyMeasurementEngineTest.kt` | Update |
| `network/monitor/src/main/kotlin/com/aeriva/network/monitor/measurement/OkHttpNetworkClient.kt` | New, production |
| `network/monitor/src/main/AndroidManifest.xml` | Add `INTERNET` |
| `app/build.gradle.kts` | Add dependency on `:network:monitor` |
| `network/monitor/build.gradle.kts` | Add `okhttp-android` dependency |
| `gradle/libs.versions.toml` | Add `okhttp` version entry |
| A new `PermissionAdapter` implementation file (location per `phase-4-android-platform-spec` §6.1) | New |
| A new manifest-merge allowlist CI check (Decision D1-6, design in `phase-4-android-platform-spec` §8.3) | New, build/CI config |

## 12. Full-repository call-site inventory

Swept via full-tarball download (`codeload.github.com`) and grep across `phase-4-measurement-foundation` and all four branches in §1.1, this session, for `NetworkClient(`, `NetworkClientOutcome.`, `ProbeRequest(`, `MeasurementFailure.Timeout`, `MeasurementNetworkContext(`, and `NetworkState(`:

- **`ProbeRequest`**: zero occurrences anywhere. The migration has not started on any branch.
- **`MeasurementFailure.Timeout`**: every occurrence found (11, across `LatencyMeasurementEngine.kt`, `LatencyMeasurementEngineTest.kt`, `MeasurementBoundaryTest.kt`, `MeasurementFailureTaxonomyTest.kt`, `DerivedJitterStatsTest.kt`, `ReferenceLatencyProbeExecutor.kt`, `ReferenceLatencyProbeExecutorTest.kt`) is already correctly parameterized (`MeasurementFailure.Timeout(MeasurementStage.X)`) -- confirmed zero bare, un-parameterized occurrences remain anywhere on `phase-4-measurement-foundation`.
- **`NetworkState(`**: every construction site on `phase-4-measurement-foundation` (`NetworkStateMapper.kt`'s two branches, `AndroidNetworkMonitor.kt` indirectly via the mapper, `ReferenceLatencyProbeExecutorTest.kt`, `LatencyMeasurementEngineTest.kt`, `NetworkHistoryRepositoryTest.kt`, `NetworkState.kt`'s own `unknown()` factory) either supplies all three Phase 4 fields explicitly or relies on their now-defaulted (`= false`) values -- confirmed compiling, confirmed CI-green at `fdf54408` (§14).
- **`NetworkClientOutcome.`**: all usages match the current four-case type (§7) -- `Success`, `ConnectionRefused`, `TlsHandshakeFailed`, `NetworkChangedMidCall` -- across `FakeNetworkClient.kt`, `FakeNetworkClientTest.kt`, `ReferenceLatencyProbeExecutor.kt`, `ReferenceLatencyProbeExecutorTest.kt`, `LatencyMeasurementEngine.kt`, `LatencyMeasurementEngineTest.kt`. No fifth case, no divergent shape on any branch checked.
- **`MeasurementNetworkContext(`**: six construction sites, all on `phase-4-measurement-foundation`'s test files plus `NetworkStateMapper`'s own type reference -- none require migration for S3 itself (this type is unaffected by the `NetworkClient` boundary).
- **`NetworkClient(`** (direct instantiation of the interface): none -- it is an interface; `FakeNetworkClient` is the only implementation anywhere.

## 13. Test requirements

Per this task's own scope and `phase-4-test-gate`'s specification (§1.1, functionally the closest existing artifact to task item 8): the six-file migration in §7/§11 must keep every one of the fourteen existing `LatencyMeasurementEngineTest` cases and every `ReferenceLatencyProbeExecutorTest` case passing under the new contract (not deleted and replaced -- migrated), plus new cases for whatever `ProbeRequest`/richer-outcome cases Decision D5-6/D5-11 add that have no current equivalent (DNS failure as its own case rather than folded into `ConnectionRefused`, redirect, no-response, blocked). `OkHttpNetworkClient` itself needs a loopback-server-backed test suite (MockWebServer, per Decision D3-10 and `phase-4-android-platform-spec`'s own convention of specifying rather than assuming); this cannot be verified compiling in this sandbox (no Maven Central access, confirmed repeatedly across this whole engagement) and must be proven via real CircleCI, not claimed.

## 14. CI acceptance criteria

All 4 CircleCI jobs (`build`, `unit_tests`, `static_checks`, `connected_android_test`) green on the S3 implementation branch, verified via the CircleCI API by whoever implements it -- not assumed, not inferred from a clean local read. **This document's own CI claim, stated precisely so it is not overread**: `phase-4-measurement-foundation @ fdf54408` was independently re-checked this session (`list_run_workflows`/`list_workflow_jobs` against run `1c3da56b`) and is confirmed green on all four jobs as of this session. No CI claim is made anywhere in this document about any branch this document did not itself query this session.

## 15. Stop conditions

- If OD-1 is declined: S3 does not proceed past §10 step 1. The `NetworkClient`/`ProbeRequest` migration (steps 2-3) can still proceed independently -- it does not itself require `INTERNET` to compile or test (JVM tests use `FakeNetworkClient`/MockWebServer, neither of which needs a real device permission) -- but `OkHttpNetworkClient` would have no way to ever make a real connection on-device without it, so shipping it without OD-1 approved would be dead code, not a reason to block writing it.
- If OD-7 is declined or a different HTTP client is mandated instead: §7's `NetworkClient`/`ProbeRequest` interface evolution is unaffected (it is client-implementation-agnostic by design); only §4/§5/§11's OkHttp-specific rows change.
- If any of the four branches in §1.1 merge before S3 starts: re-run §12's call-site sweep against the new base before proceeding -- this document's sweep is current as of this session only.

## 16. Explicit list of things still forbidden

Production endpoint URL/host or hosting; endpoint authentication beyond whatever OD-1/OD-7 themselves authorize; data budget; third-party endpoint authorization; `minSdk` change; production telemetry/log retention policy; regional endpoint strategy; TLS pinning/signing beyond OkHttp's platform-default trust manager; UI naming for the unanswered-probe metric; Phase 3C; WorkManager/scheduler implementation; throughput or UDP implementation; Supabase/backend work; VPN-control functionality; merging any branch into any other, including this one into main.

## 17. Traceability to the Phase 4 decision contract

| This document's section | Decision contract reference |
|---|---|
| §3 (OD-1) | Section 12, OD-1 |
| §4 (OD-7) | Section 12, OD-7; D3.11 admission checklist |
| §6 (manifest) | D1-1, D1-5, D1-6 |
| §7 (NetworkClient boundary) | D5-11 |
| §8 (endpoint) | D2-4, D2-6, OD-2/OD-3/OD-4 |
| §9 (security) | D3 security note (AndroidX Startup transitive addition) |
| §10 step 6 (PermissionAdapter) | D1-4 |
| §15 (unmerged parallel branches) | Not contract-tracked -- a repository-state finding this document adds, since the contract predates these branches' existence |

---

*End of authorization document.*
