# Independent Audit: PHASE_4_ANDROID_PERMISSION_PLATFORM_SPEC.md

Audit document only. No production code, manifest, dependency, or `PermissionAdapter` implementation was added or changed to produce this. No specification was modified — `PHASE_4_ANDROID_PERMISSION_PLATFORM_SPEC.md` itself is untouched. `main` is untouched (still `e3f70a4a13e63b782c61601abadc2c36f65dbd73`). Every finding below states specifically what was checked this session and against what source; nothing is carried over from the audited document's own claims without independent confirmation.

Audit target: `phase-4-android-platform-spec` @ `458e32ee499226c7c8a34be34110acf3a569acc6`, read in full this session.

---

## 0. Scope, method, and a tooling limitation stated up front

This session has the GitHub Project connector (used for every repository read below) and web search/fetch (used for every official-documentation check below). It does **not** have a CircleCI connector, and its `bash_tool` has no network egress. This matters for item I specifically: an exhaustive grep across all 29 branches' full tarballs (the technique `PHASE_4_S3_IMPLEMENTATION_AUTHORIZATION.md` used, per its own commit message, via `codeload.github.com` download) is not reproducible in this session. Where this audit relies on that document's own already-performed sweep rather than repeating it, it says so explicitly. Where this audit performed its own direct check (via `get_file_contents`/`get_commit` against a named branch and commit), it says that instead. This distinction is maintained throughout rather than blurred.

Documents read in full this session: `PHASE_4_ANDROID_PERMISSION_PLATFORM_SPEC.md` (audit target), `PHASE_4_CROSS_CUTTING_TECHNICAL_DECISION_CONTRACT.md`, `PHASE_4_IMPLEMENTATION_READINESS.md`, `PHASE_4_S3_IMPLEMENTATION_AUTHORIZATION.md`. `PHASE_4_NETWORK_MEASUREMENT_ARCHITECTURE.md` was not re-fetched this session (its branch tip, `f279476c`, is unchanged since it was read in full in an earlier session of this same engagement, and both the cross-cutting contract and the readiness document independently quote and correct it in ways this audit cross-checks instead of re-deriving).

Repository reads performed directly this session, each against a named commit: `app/src/main/AndroidManifest.xml`, `network/monitor/src/main/AndroidManifest.xml`, `app/build.gradle.kts`, `network/monitor/build.gradle.kts`, `gradle/libs.versions.toml`, `core/security/build.gradle.kts`, `MeasurementCapabilityClassifier.kt` (on three separate branches, compared by blob SHA), `list_branches` (fresh, 29 branches), and commit histories for `phase-4-measurement-foundation`, `phase-4-s3-implementation-authorization`, `phase-4-android-network-state`, `phase-4-engine-hardening`, `phase-4-engine-foundation-reconciliation`.

---

## A. Permission state model

**PASS**, with one clarification.

The four-state model (`Granted` / `Denied` / `GrantedApproximateOnly` / `CheckFailed`) is checked against the current official Android documentation for `ACCESS_FINE_LOCATION`'s runtime behavior on API 31+, fetched independently in the audited document's own session and re-confirmed in this one against the same current page (`developer.android.com/develop/sensors-and-location/location/permissions/runtime`): an app requesting `ACCESS_FINE_LOCATION` on API 31+ must request `ACCESS_COARSE_LOCATION` alongside it, and the user can choose Approximate even when the app requested Precise. This produces exactly the divergent-boolean situation (`checkSelfPermission(ACCESS_FINE_LOCATION)` denied, `checkSelfPermission(ACCESS_COARSE_LOCATION)` granted) the model's `GrantedApproximateOnly` case exists to name. No fifth real-world state was found that the model omits: normal-permission install-time grant collapses correctly into `Granted`/`Denied` (there is no "approximate" concept for a normal permission), and a `PackageManager` query failure is a real, distinct possibility `CheckFailed` correctly separates from a user decision.

**Clarification, not a correction**: the model is deliberately silent on *why* `check()` failed beyond a `reason: String`. This is consistent with this repository's own `MeasurementFailure.Unclassified(exceptionClass)` convention (exception class name only, never a raw message) — the spec's Section 6.7 states this explicitly, and it is accurate.

## B. Permission lifecycle

**PASS.**

Auto-reset behavior (Android 11 native; extended to Android 6.0+ via a Google Play services update rolling out from December 2021; runtime permissions only; default-on for apps targeting API 30+) was independently re-searched this session against Google's own announcement material and matches the audited document's Section 4.9 claim exactly, including the scope limit (normal permissions are not subject to auto-reset — there is no "unused normal permission" concept, since normal permissions carry no runtime grant state to reset in the first place).

**Re-read for every classification decision**: the spec's Section 6.2/6.5 design (`check()` always re-queries, `buildGrantedPermissionSet` is a stateless pure function called fresh each time, nothing is memoized) is the only design consistent with auto-reset's actual behavior — a grant can be revoked in the background between two calls to the same process, so any caching layer would need its own invalidation signal the platform does not provide (there is no broadcast for "your permission was just auto-reset" an app can listen for pre-emptively; the only reliable way to know is to ask again). The spec does not claim a broadcast exists and does not design around one — correct.

**Behavior after process restart**: not explicitly discussed as its own subsection in the audited spec, but its design already covers it correctly as a special case of the general re-check rule — since `PermissionAdapter.check()` is specified to always query live platform state rather than in-memory state, a process restart changes nothing about its correctness (there is no stale in-memory cache to survive or fail to survive a restart, because the design has no cache at all). **INFORMATIONAL**: the spec would be marginally clearer if it named process-restart as an explicit instance of the general rule rather than leaving it implicit, but this is a presentation note, not a correctness gap.

## C. INTERNET

**PASS**, independently re-confirmed against the actual repository this session, not against the spec's own prior claim.

- **Manifest ownership**: re-read `network/monitor/src/main/AndroidManifest.xml` at `phase-4-measurement-foundation`'s current tip (`fdf54408`) this session — still exactly one entry, `ACCESS_NETWORK_STATE`, byte-identical (same blob SHA, `ee17e31117d6...`) to what the spec's own session read at the pinned commit. `app/src/main/AndroidManifest.xml` — re-read, still zero permissions. Ownership placement (`network:monitor`, in the same change as the first production `NetworkClient`) matches Decision D1-1 in the cross-cutting contract exactly, re-read in full this session.
- **Manifest merge behavior**: the general claim (library manifests are merged into the app's manifest automatically) is standard, well-established AGP behavior and is also independently asserted, with its own citation, in the cross-cutting contract's Section 4/D1.3 (which this audit read in full this session): "Gradle merges all manifest files, including those of imported libraries, into the single manifest packaged into the app (VERIFIED FACT, developer.android.com manifest merging page)." Two independently-authored documents in this repository now converge on the same claim from the same class of source.
- **Whether `:network:monitor` currently reaches the installable `:app`**: re-read `app/build.gradle.kts` at `phase-4-measurement-foundation`'s current tip this session — byte-identical (same blob SHA, `f9533b267707...`) to what the audited spec's own session read. The dependencies block is still empty. **No dependency on `:network:monitor` or `:core:model` exists.** This is independently corroborated a third time: `PHASE_4_S3_IMPLEMENTATION_AUTHORIZATION.md` (read in full this session, itself dated after the audited spec) performed the identical check independently and reached the identical conclusion, citing it as a load-bearing finding for its own implementation-order Section 10, step 5.
- **Whether adding INTERNET now would affect the APK**: correctly answered "no" by the spec's Section 8.2, and independently re-derived here from the same evidence rather than merely re-quoted: a permission declared in a manifest that manifest-merging never pulls into an installable artifact has no effect on that artifact, precisely because there is nothing to pull it *into* — `:app` does not consume `:network:monitor` at all today.

## D. ContextCompat

**CORRECTION REQUIRED.** This is the most significant finding in this audit.

The audited spec's Section 2 states, labeled REPO FACT: *"`libs.versions.toml` — No AndroidX permission-helper library (no `androidx.core` permission extensions beyond whatever ships transitively), no Play Services Location."* Its Section 7 then states, labeled ASSUMPTION: *"whether `androidx.core` (or a version providing `ContextCompat`) is already a transitive dependency of this repository's existing `androidx.test`/`kotlinx.coroutines.android` dependencies, or would need to be added explicitly ... not resolved by this spec."*

**Both statements are wrong in the same direction, and the correct fact was checkable by reading a file already in scope.** `gradle/libs.versions.toml`, re-read in full this session at `phase-4-measurement-foundation`'s current tip, contains:

```toml
coreKtx = "1.18.0"
...
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
```

with an extensive existing comment explaining *why* 1.18.0 specifically was pinned (1.19.0's AAR metadata requires `compileSdk 37`/AGP 9.1.0+, which this repository does not have — a real, previously-hit CI failure, per the comment's own account). This entry is not unused scaffolding: `core/security/build.gradle.kts`, also read in full this session, declares `implementation(libs.androidx.core.ktx)` in its main dependencies block. **`androidx.core` is already a project dependency, already resolved, already version-pinned with a documented compatibility rationale, and already proven to build successfully in at least one module (`core:security`).**

`androidx.core:core-ktx` is a thin Kotlin-extensions-only artifact with a transitive dependency on `androidx.core:core` (the base Java artifact) of the same version — this is `core-ktx`'s well-established, longstanding packaging convention across every version researched this session (official setup documentation consistently lists both artifacts together at the same version number for exactly this reason). `ContextCompat` lives in `androidx.core:core`. **Admitting `androidx.core` transitively via `core-ktx` into `network:monitor` therefore already brings `ContextCompat` along, and the version (1.18.0) is already chosen and already compileSdk-36-compatible** — this is not independently re-confirmed against a dependency-graph tool this session (no such tool is available), but is consistent with (a) `core-ktx`'s documented artifact structure across every version this session's search surfaced, and (b) the absence of any contrary signal in this repository's own build history.

**What this changes, precisely**: `network:monitor`'s `build.gradle.kts` (re-read this session, unchanged) does **not** currently list `androidx.core.ktx` as a dependency of its own — so admitting it into that specific module is still a real, one-line build-file change that has not happened yet. But it is a fundamentally lower-friction change than the spec's Section 7 framed it as: this is not a new library entering the project (which is the OD-7/OkHttp situation — an unpinned version, no prior compatibility research, no proof any module builds with it), it is an **already-vetted, already-pinned, already-in-use-elsewhere dependency being added to one more module**. The spec's own analogy — "a dependency-admission question in the same family as `OD-7`'s OkHttp admission" (Section 7, repeated in Section 10 item 1) — **overstates the friction and should be withdrawn**. There is no plausible owner-decision gate for adding a same-version, already-approved AndroidX artifact to a second module; this is normal build-file work, not a product/policy decision.

**Exact correction needed in the spec**: Section 2's REPO FACT row should read that `androidx.core` (via `core-ktx` 1.18.0) is present and in use elsewhere in the project (`core:security`), not absent. Section 7's ASSUMPTION and its `OD-7`-family framing should be replaced with a statement that admitting it into `network:monitor` is a normal, low-friction build-file addition, not an open owner decision — and Section 10's open-decisions list, item 1, should be removed or downgraded accordingly.

## E. Classifier boundary

**PASS** on substance; **one citation-precision note, not a substantive error**.

Re-read `MeasurementCapabilityClassifier.kt` on three separate branches this session — `phase-4-measurement-foundation` (`fdf54408`), `phase-4-android-network-state` (`fc6aafa2`), and (by blob SHA comparison rather than a second full fetch) confirmed identical to the copy read during the audited spec's own session at the earlier pinned commit. All three share the identical blob SHA `7fd8a841b798...` — **byte-for-byte unchanged across every branch checked**, confirming the spec's Section 3's claim that nothing has modified the classifier since it wrote its analysis.

The spec's Section 6.3 design (`PermissionAdapter.check(permission: String)`, never `check(MeasurementCapability)`) is checked directly against the classifier's actual current signature (`classify(capability: MeasurementCapability, sdkInt: Int, grantedPermissions: Set<String>)`) — the adapter design's output composes cleanly into `grantedPermissions` via the spec's own `buildGrantedPermissionSet` function (Section 6.5) without requiring any change to `classify()`'s signature or per-capability logic, confirmed by direct inspection rather than assumed. **The correct layer to translate permission state into capability availability is `MeasurementCapabilityClassifier.classify()` itself, unchanged** — this is exactly what the spec concludes, and it is correct: `PermissionAdapter` answers "is this Android permission held," `classify()` answers "is this AERIVA capability usable," and no third layer is invented or needed.

**Citation-precision note**: Section 9 of the spec (rejected alternatives) cites "Decision D1-9's own already-stated rejection of a speculative new module for the permission concern generally" as grounds for not placing `PermissionAdapter` in a new `core:permissions` module. Re-reading Decision D1.9 directly this session: it rejects "Option C: new module now" specifically **for the `INTERNET` manifest declaration**, with the reasoning "a structural refactor with no evidence it is needed for Phase 4. If a split is ever done, the declaration moves with the client in the same commit" — it does not discuss `PermissionAdapter`'s placement at all, since `PermissionAdapter` had not been designed yet at the time the contract was written. The spec's conclusion (don't create a new module) is still reasonable and is not contradicted by anything, but citing D1-9 as if it already covered this specific question overstates what that decision actually settled. This is a precision issue in attribution, not an error in the design itself.

## F. Approximate location

**OPEN**, correctly left open by the audited spec, and confirmed still open by every other document checked this session.

Per this task's own instruction ("If the existing contracts don't decide it, mark it OPEN"): no document in this repository — the cross-cutting decision contract (read in full this session), the architecture document, the implementation readiness document, or the S3 authorization document — contains any Wi-Fi or cellular *implementation* code, or any explicit design decision about what `WIFI_CHARACTERISTICS`/`CELLULAR_CHARACTERISTICS` should return when location is granted only approximately, beyond what `MeasurementCapabilityClassifier`'s existing binary permission check already implies as an arithmetic consequence (`ACCESS_FINE_LOCATION` absent → `NotReliablyAvailable`, whether absent because denied or because only coarse was granted — Section 4.10 of the audited spec states this correctly).

Traced this session, directly: `phase-4-android-network-state` (the one branch that touches `NetworkStateMapper.kt`/`AndroidNetworkMonitor.kt`, checked in full this session) adds a `NetworkEventReducer.kt` and fixes a generic-type-inference test bug — it does not add any Wi-Fi- or cellular-signal-reading code, does not touch `WifiInfo`, `TelephonyManager`, or any location API. **No branch in this repository, as of this session, contains any code that actually calls a Wi-Fi- or cellular-characteristics API.** The classifier's classification of these two capabilities remains permission-arithmetic only, not backed by any real data-reading implementation this audit could trace behavior against.

Consequently, the four states this item asks about (allowed / unavailable / reduced capability / another explicit state) resolve as follows, and no further than this:
- **Fine location granted** → `Supported`-with-limitations classification already exists (per Section 3.2 of the spec, unchanged) — effectively "allowed."
- **Approximate-only or denied** → `NotReliablyAvailable` — "unavailable." There is no "reduced capability" state anywhere in this repository's design for either capability; the classifier's `CapabilityClassification` type has no such case for these two capabilities, and nothing in this spec or any other document proposes adding one.
- **Whether a future "reduced capability" state (e.g., a coarser Wi-Fi read that only needs `ACCESS_COARSE_LOCATION`) should exist at all** is genuinely undecided, since no coarse-location-satisfiable Wi-Fi/cellular API has been identified as existing (the spec's Section 4.11/4.12 findings, re-confirmed this session as unchanged, both gate on fine location specifically, with no documented coarse-location alternative). **Marked OPEN, not resolved by this audit, since inventing one would be exactly the "do not invent behavior" this task prohibits.**

## G. Manifest validation

**CORRECTION REQUIRED.**

The audited spec's Section 8.3 proposes reading AGP's merged-manifest output from a guessed file-system path (`build/intermediates/merged_manifests/<variant>/AndroidManifest.xml`), explicitly labeled ASSUMPTION and explicitly flagged as not independently verified against this repository's pinned AGP version (8.13.2) "since this sandbox cannot run Gradle to inspect it directly."

**A better-grounded, version-independent mechanism exists and was found this session**: AGP's public, stable Variant API (available since AGP 7.0, per official documentation fetched this session — well before this repository's pinned 8.13.2) exposes the merged manifest as `SingleArtifact.MERGED_MANIFEST`, described in AGP's own current reference documentation as "Merged manifest file that will be used in the APK, Bundle and InstantApp packages." This is accessed via the `androidComponents` extension's `onVariants` callback (`variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)`), which returns a lazy `Provider<RegularFile>` a Gradle task can consume without needing to know or guess AGP's internal intermediate-directory layout for the version in use. This is the officially documented, forward-compatible way to read this artifact — precisely the "real, checkable mechanism" both the audited spec's own stated intent (Section 8.3's opening line: "never a hand-maintained expectation of what merging *should* produce") and Decision D1-6 in the cross-cutting contract ("generated from the real merged manifest") already call for, but neither document names it.

**Exact correction needed**: Section 8.3, point 1 of the spec should replace the guessed intermediate-path approach with the `SingleArtifact.MERGED_MANIFEST` Variant API reference, and Section 10's open-decision item 4 ("the exact AGP 8.13.2 merged-manifest output path/task name") should be resolved, not left open — the Variant API is stable and documented independently of the specific AGP patch version, so no further version-specific verification is actually needed to specify the mechanism (implementing and running it is separate, later work this audit does not perform either).

**Additional gap found, connecting back to item H below**: Decision D1-6 in the cross-cutting contract specifies the check must cover "permissions **and providers**" (its own words), citing OkHttp's AndroidX Startup content-provider addition as the specific reason. The audited spec's Section 8.3, point 2 only specifies checking `<uses-permission>` entries — it does not extend the check to `<provider>` elements, even though its own Section 8.3 point 2 names the exact same AndroidX Startup provider risk in the same sentence. This is an internal inconsistency: the spec correctly identifies the risk but does not design the check to catch it.

## H. Cross-document consistency

Compared against all four documents named in this task, each read in full or re-confirmed unchanged this session.

**No contradictions found** between the audited spec and the cross-cutting decision contract, the architecture document, or the implementation readiness document on any point of substance — Decision D1-1, D1-4, D1-5, D1-6 (partially — see the provider gap above), and D1-9's actual scope (see item E's precision note) are all consistent with what the spec says about them, once the one citation-precision note in item E is accounted for.

**`PHASE_4_S3_IMPLEMENTATION_AUTHORIZATION.md`** (dated after the audited spec, read in full this session) independently re-verified two of the spec's own load-bearing findings against a fresh repository read (its own Section 1.2) and found them accurate: `INTERNET` placement in `network:monitor`, and the `:app`-has-no-dependency-on-`:network:monitor` gap. This is a second, independent document arriving at the same conclusions from its own fresh evidence — a genuine corroboration, not a repetition of an unverified claim.

**Stale branch references**: the audited spec's own base-commit note (pinned to `9a28cc6`, branch tip moved to `89337fc0` by the time it was committed) is handled correctly and transparently within the document itself — this is not a defect. Checked this session: the branch has since moved twice more (`545a9612`, then `fdf54408`), for reasons (missed `NetworkState` constructor call sites in test fixtures, unrelated to permissions or manifests) confirmed this session by reading both intervening commit messages directly. **None of these three intervening commits touch anything the spec's findings depend on** — re-confirmed by this session's own direct reads of the classifier, both manifests, and `app/build.gradle.kts` at the current tip, all byte-identical to what the spec's own session read. The spec's findings remain current as of this audit.

**Duplicated decisions**: none found. The spec does not restate any Decision D-numbered item from the cross-cutting contract as if newly deciding it — where it references D1-1/D1-4/D1-5/D1-6, it explicitly frames itself as "restating... not revising" (its own Section 8.1), which this audit confirms is an accurate characterization of what it actually does.

**Decisions that have silently become resolved**: none found. OD-1 and OD-7 remain open in every document checked this session, including the most recent (`PHASE_4_S3_IMPLEMENTATION_AUTHORIZATION.md`, which explicitly re-confirms both as "OWNER ACTION REQUIRED" this session, independently of the audited spec). The one genuinely new fact this audit surfaces — the `androidx.core` dependency already existing (item D) — is not a "decision silently resolved" in the OD-numbered sense; no document ever claimed it required owner approval in the first place except the audited spec's own Section 7, which is exactly the passage this audit corrects.

## I. Full repository sweep

**INFORMATIONAL / LIMITATION** — partial, not exhaustive, for the reason stated in Section 0.

Direct checks performed this session, each against a named branch and commit:

| Term | Where checked | Result |
|---|---|---|
| `PermissionAdapter` | `phase-4-measurement-foundation`, `phase-4-android-network-state`, `phase-4-s3-implementation-authorization` (as prose, not code) | No implementation found anywhere. The S3 authorization document's own full-tarball sweep (its Section 12, covering five branches by that method) independently reports the same absence for the related `ProbeRequest` symbol; this audit did not repeat that specific sweep itself but the two findings are consistent. |
| `checkSelfPermission` | `AndroidNetworkMonitor.kt` (read in full, twice, across two branches) | Absent. Confirmed directly — the file has no such call anywhere in its ~150 lines. |
| `ACCESS_FINE_LOCATION` / `READ_PHONE_STATE` | `MeasurementCapabilityClassifier.kt` (three branches, byte-identical) | Present only as string constants in the classifier, unused by any manifest or any calling code that supplies a real value — consistent across every branch checked. |
| `ACCESS_NETWORK_STATE` | Both manifests, on `phase-4-measurement-foundation`'s current tip and cross-checked by blob SHA against the audited spec's pinned commit | Present, unchanged, in `network:monitor`'s manifest only. |
| `ACCESS_WIFI_STATE` | `MeasurementCapabilityClassifier.kt`, both manifests | Absent everywhere checked. |
| `INTERNET` | Both manifests, `libs.versions.toml`, classifier's own permission-constant list (present there only as a string constant, `PERMISSION_INTERNET`) | No `<uses-permission>` declaration anywhere checked. |
| `ContextCompat` | `network:monitor/build.gradle.kts`, `core/security/build.gradle.kts`, `libs.versions.toml` | Not directly named anywhere (it is a class inside the `androidx.core:core` artifact, not a Gradle coordinate), but its enabling dependency (`androidx.core:core-ktx`) is present in the catalog and in use in `core:security` — see item D. |
| `MeasurementCapabilityClassifier` | Three branches, direct fetch, compared by blob SHA | Present, byte-identical everywhere checked. |

**What this audit did not independently verify**: a full-tarball grep for these same terms across all 29 branches (only a representative subset — the branches most likely to be relevant, per their own commit messages and per the S3 authorization document's own already-performed sweep — were checked directly). In particular, `phase-3b-validation`, `phase-3c-android-validation-matrix`, `phase-4-real-device-validation`, `phase-4-network-state-audit`, and `phase-5-measurement-validation-harness` were not individually re-checked for these specific terms this session; their commit messages and stated scope (real-device validation methodology, a network-state-branch audit, a test-data schema) make it unlikely any of them introduces a `PermissionAdapter` implementation or a manifest change, but this is an inference from stated scope, not a direct grep result, and this audit does not claim otherwise.

---

## Consolidated findings

| # | Item | Classification |
|---|---|---|
| 1 | Permission state model (four-state `PermissionState`) | PASS |
| 2 | Permission lifecycle (re-check every time, no caching, process-restart-safe by design) | PASS |
| 3 | INTERNET manifest ownership, merge behavior, `:app` gap, no-effect-yet finding | PASS |
| 4 | `androidx.core`/`ContextCompat` availability | **CORRECTION REQUIRED** — already present and in use elsewhere in the project; not a new-dependency/owner-decision question |
| 5 | Classifier boundary (no duplication, correct translation layer) | PASS |
| 6 | D1-9 cited for `PermissionAdapter`'s module placement | Minor **CORRECTION REQUIRED** (citation precision only — conclusion unaffected) |
| 7 | Approximate location → Wi-Fi/cellular capability state | OPEN OWNER DECISION (correctly left open; no implementation exists anywhere to trace against) |
| 8 | Manifest-merge verification mechanism (guessed path vs. Variant API) | **CORRECTION REQUIRED** — use `SingleArtifact.MERGED_MANIFEST`, not a guessed intermediate path |
| 9 | Manifest-merge check scope (permissions only vs. permissions and providers) | **CORRECTION REQUIRED** — must also cover providers per D1-6 |
| 10 | Cross-document consistency (contract, architecture, readiness, S3 authorization) | PASS, with items 6 and 9 as the only specific corrections found |
| 11 | Full repository sweep for the ten named terms | INFORMATIONAL — partial, tool-constrained; everything directly checked is consistent with the spec's claims |

---

## Exact corrections required

1. **Section 2 (Repository state table)**: change the `libs.versions.toml` row from "No AndroidX permission-helper library... no `androidx.core` permission extensions beyond whatever ships transitively" to: present, as `androidx.core:core-ktx` 1.18.0, already in use in `core:security/build.gradle.kts`; not yet a dependency of `network:monitor`.
2. **Section 7**: remove or rewrite the ASSUMPTION and its "same family as OD-7" framing. Replace with: `androidx.core` (via `core-ktx`, transitively bringing in `androidx.core:core`, which contains `ContextCompat`) is already a version-pinned, in-use project dependency; adding it to `network:monitor` specifically is a normal one-line build-file change, not an owner-gated dependency admission.
3. **Section 9 (rejected alternatives table)** and **Section 6.3**: narrow the D1-9 citation to what it actually says (rejects a new module for the `INTERNET` manifest declaration specifically) rather than implying it already covered `PermissionAdapter`'s placement.
4. **Section 8.3, point 1**: replace the guessed `build/intermediates/merged_manifests/<variant>/AndroidManifest.xml` path with a reference to AGP's stable Variant API, `SingleArtifact.MERGED_MANIFEST` (available since AGP 7.0), accessed via `androidComponents.onVariants { variant -> variant.artifacts.get(SingleArtifact.MERGED_MANIFEST) }`.
5. **Section 8.3, point 2**: extend the described check's scope from "`<uses-permission>` entries" to "`<uses-permission>` and `<provider>` entries," matching Decision D1-6's actual stated scope.
6. **Section 10 (open decisions)**: remove item 1 (`androidx.core`/`ContextCompat` admission) as resolved by correction 1-2 above; remove or resolve item 4 (AGP merged-manifest output path) as resolved by correction 4 above.

None of these six corrections change the spec's `PermissionAdapter` interface design (Section 6), its INTERNET placement conclusion (Section 8.1), or its classifier-boundary conclusion (Section 6.3/E above) — all of which this audit confirms as sound. They tighten evidentiary claims and remove two items from the open-decisions list that were never actually owner-gated.

## Owner decisions remaining

Unchanged by this audit, and confirmed still open in every document checked this session, most recently `PHASE_4_S3_IMPLEMENTATION_AUTHORIZATION.md`:

- **OD-1** (INTERNET/outbound network access authorization) — not recorded as approved anywhere in this repository.
- **OD-7** (OkHttp admission) — not recorded as approved anywhere; the version-compatibility research underneath it (5.4.0 for compileSdk 36) is independently verified but is not itself an approval.
- Whether `ACCESS_FINE_LOCATION`/`READ_PHONE_STATE` are ever individually justified and requested, per `PHASE_2_ANDROID_PLATFORM_AUDIT.md`'s own exit criteria — untouched by this audit, still open.
- Item F above (approximate-location handling for Wi-Fi/cellular capabilities) is not an owner-gated decision in the OD-numbered sense — it is an unfilled design gap with no implementation to decide it against yet, correctly left open rather than invented.

## Implementation blockers

1. OD-1 and OD-7 remain the two hard blockers for any code that would actually declare `INTERNET` or add OkHttp — unaffected by this audit's findings.
2. The `:app` → `:network:monitor` dependency gap (item C) remains a real precondition for `INTERNET`'s declaration to have any effect, independent of OD-1's outcome.
3. **This audit removes one blocker that did not need to exist**: `androidx.core`/`ContextCompat` admission (item D) is not a blocker at all — it is a same-session, low-friction build-file change once someone is implementing `PermissionAdapter`, requiring no owner sign-off.
4. The manifest-merge verification check (item G) cannot be implemented as originally specified (an assumed path) without risking version-specific breakage; the corrected mechanism (item G's correction) removes this risk but the check itself still has no purpose until the `:app` dependency gap (blocker 2) closes, exactly as the audited spec's own Section 8.3, point 3 already states.

## Is the platform specification ready to become an implementation contract?

**Yes, conditional on the six corrections above being applied.** No finding in this audit changes any structural decision in the specification — the `PermissionAdapter` interface shape, its placement in `network:monitor`, its capability-agnostic design, the `INTERNET` manifest placement, and the classifier-boundary conclusion all survive this audit unchanged. The corrections are entirely evidentiary: one incorrect REPO FACT claim and its downstream ASSUMPTION (item D, the most consequential fix — it removes a phantom owner-decision item), one citation-precision issue (item E), and two design-completeness gaps in the manifest-verification mechanism (items G and the provider-scope gap) that would have caused rework if implemented as originally written. Item F is correctly left open by the original spec and remains correctly open after this audit — it is not a defect to fix, since no implementation exists anywhere in the repository to decide it against, and inventing an answer would violate this task's own instruction.

---

## Explicit non-goals and what this audit did not do

The audited specification was not modified. No `PermissionAdapter` was implemented. No `INTERNET` permission was added. No dependency was added, including `androidx.core` into `network:monitor` (this audit identifies that the *existing* catalog entry makes this low-friction — it does not add the line itself). No manifest was modified. No production code was changed. `main` was not touched; it remains `e3f70a4a13e63b782c61601abadc2c36f65dbd73`. No branch was merged. No build, test, or CI run was performed or claimed.

---

## Sources

**Fetched fresh this session**: Android's approximate-vs-precise location runtime documentation (re-confirmed, same page as the audited spec's own session), Google's permission auto-reset announcement material (re-confirmed), AGP's `com.android.build.api.artifact` package reference and `Artifact`/`SingleArtifact` reference pages (new this session — the audited spec did not fetch these), general `androidx.core`/`core-ktx` release documentation (new this session, to confirm the core-ktx-to-core artifact relationship).

**Read in full this session, from this repository**: `PHASE_4_ANDROID_PERMISSION_PLATFORM_SPEC.md`, `PHASE_4_CROSS_CUTTING_TECHNICAL_DECISION_CONTRACT.md`, `PHASE_4_IMPLEMENTATION_READINESS.md`, `PHASE_4_S3_IMPLEMENTATION_AUTHORIZATION.md`, `MeasurementCapabilityClassifier.kt` (three branches), both `AndroidManifest.xml` files, `app/build.gradle.kts`, `network/monitor/build.gradle.kts`, `gradle/libs.versions.toml`, `core/security/build.gradle.kts`, `AndroidNetworkMonitor.kt`, commit histories for `phase-4-measurement-foundation`, `phase-4-s3-implementation-authorization`, `phase-4-android-network-state`, `phase-4-engine-hardening`, `phase-4-engine-foundation-reconciliation`.

**Not independently re-verified this session, relied on as already-corroborated**: the full-tarball, all-branch grep methodology and its specific findings for `NetworkClient`/`NetworkClientOutcome`/`ProbeRequest`/`MeasurementFailure.Timeout`/`NetworkState` call sites, performed by `PHASE_4_S3_IMPLEMENTATION_AUTHORIZATION.md`'s own session (attributed explicitly wherever referenced above, per Section 0's stated method).
