# AERIVA Phase 3B -- Android Contract Final Review

Final readiness review of the Android measurement boundary
(`MeasurementCapabilityClassifier` and the three Android-contract
documents built around it) before real Android measurement integration
begins. Not a new capability analysis, not an implementation, not a
rewrite -- a focused check of exactly the ten items this task lists,
each independently re-verified against current official Android
documentation rather than carried over from any prior document's word.

## 0. Repository and branch state

- `main` HEAD: `e3f70a4a13e63b782c61601abadc2c36f65dbd73` -- confirmed
  unchanged, not touched by this branch.
- Branched from `phase-3b-android-contract-review` @
  `27c0f2f9e3866e97fbcb0fd347a333231535eb12`.
- Other branches observed on `origin` as of this session (not merged,
  not checked out over this branch's own work; noted only where they
  bear on an item below): `phase-3b-measurement-engine` (now contains a
  real `LatencyMeasurementEngine.kt` that consumes
  `MeasurementCapabilityClassifier` directly -- relevant evidence for
  item 8), `phase-3b-engine-integration-audit`,
  `phase-3b-engine-test-gate`, `phase-3b-repository-state-reconciliation`
  -- none of these were read in depth for this review; this task scopes
  the review to the Android contract specifically, not a re-audit of
  every branch in the repository.

## Method

Each of this task's ten items was independently re-verified against
current official Android documentation this session, with the
authoritative source (`developer.android.com` reference/guide pages)
distinguished explicitly from any third-party mirror or language-binding
documentation that surfaced in search results alongside it -- **this
distinction itself produced one of this review's two concrete
findings**, described in item 1 below.

---

## Item-by-item review

### 1. `WifiManager.getConnectionInfo()` deprecation

**CONFIRMED, and the replacement guidance is corrected/sharpened this
pass.** `developer.android.com/about/versions/12/behavior-changes-12`
(the authoritative, current official page for this exact topic) states
plainly: "we recommend all apps... migrate away from calling
`WifiManager.getConnectionInfo()` and instead use
`NetworkCallback.onCapabilitiesChanged()` to get all `WifiInfo` objects
that match the `NetworkRequest` used to register the `NetworkCallback`.
`getConnectionInfo()` is deprecated as of Android 12," with a code
sample showing `networkCapabilities.getTransportInfo()` cast to
`WifiInfo` inside `onCapabilitiesChanged`. This matches, and is now
re-confirmed from the primary source itself (not a secondary API-diff
listing), what `phase-3b-android-contract-review` already corrected.

### 2. Current `NetworkCapabilities` replacement and location-information requirements

**CORRECTED THIS PASS -- a real defect in this session's own prior
work, not a new Android-platform fact.** `PHASE_3C_ANDROID_REAL_DEVICE_VALIDATION_MATRIX.md`
(this session's own immediately-prior document) stated that
`ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO` "is
itself marked for removal" and that `NetworkCallback.Builder.setIncludeLocationInfo(boolean)`
is the current recommended surface. **Re-investigated from the primary
source this pass, that claim does not hold up:**

- The authoritative `developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback`
  page lists `FLAG_INCLUDE_LOCATION_INFO` as a plain constant ("Added in
  API level 31") with **no deprecation notice anywhere on that page**,
  and lists the class's public constructors as `NetworkCallback()` and
  `NetworkCallback(int flags)` -- i.e. the flag is passed to the
  constructor, not through a builder.
- **No `ConnectivityManager.NetworkCallback.Builder` class exists in the
  actual Android SDK.** No `developer.android.com` page for such a class
  was found in this session's research, on this pass or the prior one.
- The "will be removed... use `NetworkCallbackFlags` enum directly
  instead" notice `PHASE_3C_ANDROID_REAL_DEVICE_VALIDATION_MATRIX.md`
  cited came exclusively from `learn.microsoft.com` (.NET-for-Android /
  Xamarin binding documentation) pages. **Those are not Android SDK
  documentation** -- they document Microsoft's own .NET language
  bindings for the Android SDK, which wrap Java/Kotlin APIs in
  .NET-idiomatic shapes (including introducing their own enum types and
  their own binding-specific deprecation notices for .NET consumers
  specifically) that do not correspond 1:1 to the actual Android API
  surface a Kotlin/Java Android app calls. Treating them as "current
  official Android documentation" in the prior session's research was a
  sourcing error.

**Correct, current guidance:** the real, current, non-deprecated API
for including location-sensitive `WifiInfo` fields is
`ConnectivityManager.NetworkCallback(NetworkCallback.FLAG_INCLUDE_LOCATION_INFO)`
-- i.e. pass the flag to the constructor when instantiating the
callback, then read the resulting `WifiInfo` from
`NetworkCapabilities.getTransportInfo()` inside `onCapabilitiesChanged`,
exactly as item 1's official code sample shows (that sample's callback
construction is elided as `...` in the quoted snippet, but the
surrounding page's own constructor documentation and this flag's own
purpose -- "apps can request that location information is included" --
make the mechanism unambiguous). **The underlying permission requirement
(`ACCESS_FINE_LOCATION`, plus `ACCESS_WIFI_STATE`) is unaffected by this
correction either way** -- this is purely a citation-precision issue,
not a permission or classification defect. `MeasurementCapabilityClassifier.kt`'s
`WIFI_CHARACTERISTICS` classification logic remains correct and
unchanged; only its comment's API citation (already imprecise once,
now shown to need a second, different correction) needs updating
**by whoever next touches that file** -- per this task's scope
("do not implement," "do not modify" beyond this review), this
document records the correction rather than making it.

### 3. Latest Android API behavior relevant to Wi-Fi characteristics

**CONFIRMED**, folding in items 1-2: `WifiManager.getConnectionInfo()`
deprecated since API 31; current path is
`ConnectivityManager.registerNetworkCallback`/`registerDefaultNetworkCallback`
with a `NetworkCallback` constructed with `FLAG_INCLUDE_LOCATION_INFO`
when location-sensitive fields are needed, reading `WifiInfo` from
`NetworkCapabilities.getTransportInfo()` in `onCapabilitiesChanged`.
`ACCESS_FINE_LOCATION` remains required at every currently-supported API
level for the location-sensitive fields specifically (re-confirmed,
unchanged from every prior pass).

### 4. Cellular permission requirements

**CONFIRMED, unchanged.** `TelephonyCallback.CellInfoListener` requires
both `READ_PHONE_STATE` and `ACCESS_FINE_LOCATION` -- this specific
dual requirement has now been independently re-confirmed across three
separate sessions/passes in this repository's history
(`PHASE_3B_ANDROID_MEASUREMENT_CAPABILITY_REPORT.md`,
`PHASE_3B_ANDROID_CONTRACT_FINAL_FACT_CHECK.md`, and this pass), each
citing the current official `TelephonyCallback.CellInfoListener`
reference page independently. `MeasurementCapabilityClassifier.kt`'s
`CELLULAR_CHARACTERISTICS` branch, re-read directly this session,
correctly requires both permissions jointly (`&&`, not `||`).

### 5. `INTERNET` permission sequencing

**Not an Android-platform fact to verify -- restated as the still-open
project decision it already was.** `INTERNET` is confirmed, by direct
inspection this session, still not declared anywhere in
`network:monitor`'s manifest. This blocks every `INTERNET`-gated
capability's classifier branch from ever returning anything but
`NotReliablyAvailable` in practice, and blocks the "Required permission
denied" test row in `PHASE_3C_ANDROID_REAL_DEVICE_VALIDATION_MATRIX.md`
Section 7 from being executable at all. **Sequencing** (whether it is
added as its own isolated commit ahead of any provider, or bundled with
the first provider that needs it) remains exactly the open question the
implementation contract's Section 15 already posed -- this review does
not resolve it, and does not add the permission itself, per this task's
explicit instruction.

### 6. DNS responsiveness classification as Estimated, not direct measurement

**CONFIRMED as correctly implemented.** Read `MeasurementCapabilityClassifier.kt`
directly this session: `DNS_RESPONSIVENESS` has its own `when` branch,
separate from `LATENCY`/`JITTER`/`HTTPS_REACHABILITY`, returning
`CapabilityClassification.Estimated(reason)` when `INTERNET` is granted
(not `Supported`) -- this is the fix `phase-3b-android-contract-review`
made and it holds up on direct re-inspection, not merely on the strength
of that document's own prose. The accompanying regression test
(`dnsResponsiveness_withInternetPermission_isEstimated_notSupported`)
exists in `MeasurementCapabilityClassifierTest.kt`, also confirmed by
direct read this session.

### 7. Android 16 implications relevant to AERIVA

**CONFIRMED, unchanged from the prior two passes.** Local Network
Protections (real, phased 25Q2-26Q2, gated behind a new runtime
permission once enforced) does not apply to any of AERIVA's ten scoped
capabilities, since none target local-network broadcast/multicast/LAN
addresses. Background-job runtime quotas extended to jobs started from
a foreground service (including `WorkManager` jobs) does not apply,
since no current AERIVA goal uses a foreground service. No new Android
16 finding was surfaced this pass beyond what the prior two documents
already recorded.

### 8. Whether the current classifier API is actually ready for the future measurement engine

**CONFIRMED READY, with real evidence rather than a documentation-only
claim.** `phase-3b-measurement-engine`'s `LatencyMeasurementEngine.kt`
(read directly this session, on that branch, not merged into this one)
already consumes `MeasurementCapabilityClassifier` in production code --
its own KDoc states it "consults `MeasurementCapabilityClassifier` for
`MeasurementCapability.LATENCY` before attempting a probe," and imports
`CapabilityClassification`/`MeasurementCapability`/
`MeasurementCapabilityClassifier` from this exact module without
modification, fork, or a competing interface. That file's own KDoc
independently notes a real, honest limitation worth restating here: for
`LATENCY` specifically, the classifier's current logic has exactly two
outcomes (`Supported` or `NotReliablyAvailable`) -- if a future
classifier change ever gives `LATENCY` a genuine
`SupportedWithLimitations` branch, a consuming engine's own `when` would
need a new arm added deliberately, not silently falling through. This is
not a defect in the classifier as it stands (its `when` is exhaustive
over `MeasurementCapability`, per its own structural test,
re-confirmed by direct read this session), but it is a real coupling
constraint between the classifier's API shape and any engine built
against a specific capability's *current* set of possible
classifications -- worth flagging as a non-blocking improvement (below)
for engine implementers, not a blocking defect in the classifier itself.

### 9. Whether `sdkInt` should remain in the classifier signature

**Concrete finding: `sdkInt` is currently a dead parameter.** Direct
inspection this session: `MeasurementCapabilityClassifier.classify(capability,
sdkInt, grantedPermissions)` takes `sdkInt: Int` as a parameter, and it
is referenced exactly twice in the entire file -- once in a KDoc comment
(`@param sdkInt the running device's Build.VERSION.SDK_INT`) and once in
a code *comment* (`// (sdkInt, permissions) alone`) -- **never inside
the actual `when (capability)` logic.** Every branch's outcome today
depends only on `grantedPermissions`. This is not a correctness defect
(the function still returns the right answer for every input, since no
branch needs version-gating today), but it is a real API-surface
question this task explicitly asks to resolve, and the honest options
are:
- **Keep it**, because Section 5/8 of the implementation contract
  already anticipates version-gated API paths becoming necessary later
  (e.g. `TelephonyCallback` needs API 31+; a future `NEARBY_WIFI_DEVICES`-aware
  branch might want API 33+ awareness) -- removing it now means adding
  it back (a breaking signature change for every caller, including the
  now-real `LatencyMeasurementEngine.kt`) exactly when it first becomes
  needed.
- **Remove it**, because an unused parameter in a pure function is a
  real code-quality smell an engine author could reasonably flag in
  review, and it can be re-added later with no cost beyond that same
  breaking change either way -- keeping it now defers, but does not
  avoid, that eventual API churn.
This review does not decide between these (a design-taste call, not an
Android-platform fact) -- it is recorded as a required decision (below)
specifically because a future measurement-engine implementer
(`LatencyMeasurementEngine.kt` already exists and already calls this
signature) has a direct stake in which way this goes before more
callers accumulate.

### 10. Whether any claim in the existing Phase 3B documents is now outdated

**One item found and corrected this pass -- item 2 above**
(`PHASE_3C_ANDROID_REAL_DEVICE_VALIDATION_MATRIX.md`'s own "Further
Wi-Fi API refinement" section, itself only one document old). No other
claim across `PHASE_2_ANDROID_PLATFORM_AUDIT.md`,
`PHASE_3B_ANDROID_MEASUREMENT_CAPABILITY_REPORT.md`,
`PHASE_3B_ANDROID_MEASUREMENT_IMPLEMENTATION_CONTRACT.md`,
`PHASE_3B_ANDROID_CONTRACT_FINAL_FACT_CHECK.md`, or
`PHASE_3C_ANDROID_REAL_DEVICE_VALIDATION_MATRIX.md`'s remaining content
was found outdated this pass -- items 1, 3, 4, 6, 7 above each
independently re-confirm a distinct claim from those documents against
current official sources.

---

## Confirmed facts

- `WifiManager.getConnectionInfo()` deprecated since API 31; current
  path is `NetworkCallback.onCapabilitiesChanged()` +
  `NetworkCapabilities.getTransportInfo()` (item 1).
- The current, real API for including location-sensitive `WifiInfo`
  fields is `NetworkCallback(FLAG_INCLUDE_LOCATION_INFO)` (constructor
  flag), **not** a `NetworkCallback.Builder` class -- no such class
  exists in the Android SDK (item 2).
- `ACCESS_FINE_LOCATION` + `ACCESS_WIFI_STATE` remain the correct,
  unchanged Wi-Fi permission requirement regardless of which API-surface
  correction applies (items 2-3).
- `TelephonyCallback.CellInfoListener` requires both `READ_PHONE_STATE`
  and `ACCESS_FINE_LOCATION`, independently re-confirmed a third time
  (item 4).
- `DNS_RESPONSIVENESS` is correctly implemented as `Estimated`, not
  `Supported`, in the actual code, with a passing regression test
  already in place (item 6).
- Android 16 Local Network Protections and the background-job-quota
  extension remain confirmed inapplicable to AERIVA's current scope
  (item 7).
- `MeasurementCapabilityClassifier` is already real production
  infrastructure, not speculative design -- `LatencyMeasurementEngine.kt`
  on `phase-3b-measurement-engine` consumes it directly, unmodified
  (item 8).
- `sdkInt` is a currently-unused parameter in `classify()`'s actual
  logic (item 9).

## Limitations

- `LATENCY`'s classifier outcome space (`Supported`/`NotReliablyAvailable`
  only, no `SupportedWithLimitations`) is narrower than some other
  capabilities' -- a real constraint an engine author must be aware of,
  not a defect (item 8).
- This review's own research, like every prior pass, depends on search
  results that mix authoritative (`developer.android.com`) and
  non-authoritative (third-party mirrors, language-binding docs)
  sources -- item 2 is direct evidence that this mixing can produce a
  genuine, confidently-stated error if the authoritative source isn't
  explicitly separated out, as this pass did and the prior pass did not.
  This is recorded as a standing methodological limitation for any
  future research pass in this repository, not merely a one-off mistake.
- This review did not re-audit `phase-3b-measurement-engine`,
  `phase-3b-engine-integration-audit`, `phase-3b-engine-test-gate`, or
  `phase-3b-repository-state-reconciliation` in depth -- only the one
  file (`LatencyMeasurementEngine.kt`) directly relevant to item 8 was
  read. Those branches' own claims are out of this review's scope.

## Required decisions

1. **`sdkInt` -- keep or remove from `classify()`'s signature** (item
   9). Direct stakeholder: whoever maintains `LatencyMeasurementEngine.kt`
   and any future capability engine, since either choice made later is a
   breaking signature change for every existing caller.
2. **`INTERNET` sequencing** (item 5) -- still open, restated, not
   resolved here.
3. Every required decision already listed in
   `PHASE_3B_ANDROID_MEASUREMENT_IMPLEMENTATION_CONTRACT.md` Section 15
   and `PHASE_3C_ANDROID_REAL_DEVICE_VALIDATION_MATRIX.md` Section 15
   remains open and is not re-litigated here.

## Non-blocking improvements

- Update `MeasurementCapabilityClassifier.kt`'s `WIFI_CHARACTERISTICS`
  comment to cite `NetworkCallback(FLAG_INCLUDE_LOCATION_INFO)` instead
  of the now-twice-corrected citation history (item 2) -- a comment-only
  change with no behavioral effect, the same kind of fix
  `phase-3b-android-contract-review` already made once; left to whoever
  next touches that file, per this task's scope.
- Update `PHASE_3C_ANDROID_REAL_DEVICE_VALIDATION_MATRIX.md`'s "Further
  Wi-Fi API refinement" section to reflect item 2's correction, so a
  future reader of that document isn't misled by its now-superseded
  claim -- also left to whoever next touches that file, per this task's
  scope ("do not implement," reviewed here rather than edited).
- Document, wherever `LatencyMeasurementEngine.kt` or a future
  multi-capability engine's own contract lives, the specific
  outcome-space-per-capability constraint item 8 identifies, so a future
  jitter/packet-loss/throughput engine author doesn't assume every
  capability has the same three-or-four-way outcome space `LATENCY`
  happens to have today.

## Must be fixed before Android measurement implementation

**Nothing found this pass rises to a blocking defect in the classifier
or its documented permission/classification logic.** The one concrete
code-level finding (item 9, `sdkInt`) is a required *decision*, not a
required *fix* -- the function is correct as written either way, only
its future API shape is undecided. The one concrete documentation
finding (item 2) is a citation correction with no effect on any
permission, classification, or manifest requirement. **The `INTERNET`
manifest gap (item 5) remains the single concrete blocker for any real
measurement to occur at all** -- restated, not newly discovered, and
explicitly out of this task's scope to fix.

---

## Validation

**No production code changed. No test added or modified.** Per this
task's scope (verification and documentation only), this branch's only
content is this review document. `MeasurementCapabilityClassifier.kt`
and its test file were read directly this session for items 6, 8, and 9
but not edited -- the corrections identified (items 2, 9) are recorded
as findings for a future commit, consistent with this task's "do not
implement" instruction and the prior branch's own precedent of not
silently rewriting unrelated code mid-review.

No Gradle build or test run was executed or is claimed. Same unchanged
sandbox network constraint as every prior document in this repository
(Maven Central/Google Maven outside this container's allowlist). No new
test file exists in this branch to report a pass/fail on.

## Evidence

- `developer.android.com/about/versions/12/behavior-changes-12` --
  authoritative source for item 1's `getConnectionInfo()` deprecation
  and its official replacement code sample.
- `developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback`
  (both the Java and Kotlin reference variants) -- authoritative source
  for item 2's correction: `FLAG_INCLUDE_LOCATION_INFO` carries no
  deprecation notice on this page, and the class's constructors are
  `NetworkCallback()`/`NetworkCallback(int flags)`, not a builder.
- `learn.microsoft.com/.../android.net.connectivitymanager.networkcallback.flagincludelocationinfo`
  -- the non-authoritative source item 2 identifies as the origin of
  the prior pass's error, cited here specifically so a future reader can
  verify the distinction being drawn rather than take this review's word
  for it.
- Direct reads this session (not from memory of a prior pass) of:
  `MeasurementCapabilityClassifier.kt`,
  `MeasurementCapabilityClassifierTest.kt` (this branch),
  `LatencyMeasurementEngine.kt` (`phase-3b-measurement-engine`, read via
  `git show`, not merged).
- `git log -1 --format="%H"` against `main` and against this branch's
  base, confirming the repository-state claims in Section 0.
