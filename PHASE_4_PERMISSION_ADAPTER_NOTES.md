PHASE 4 PERMISSION ADAPTER: IMPLEMENTATION NOTES

Branch: phase-4-permission-adapter
Base: phase-4-engine-foundation-integration @ 76651aa9dce89d98d9ac44808f7c49ac0f71a965

BASE DEVIATION FROM THE CORRECTED SPECIFICATION'S OWN PIN, EXPLAINED

PHASE_4_ANDROID_PERMISSION_PLATFORM_SPEC.md (as corrected) is pinned to
phase-4-measurement-foundation @ 9a28cc62. That branch's own tip moved
to fdf54408 before this task began (already accounted for by the
spec's own base-deviation note) and, since then, a separate
reconciliation effort merged phase-4-engine-hardening's validated
engine improvements on top of that, plus removed NetworkState's three
defaulted observation fields per Decision DD-5 (a repository-owner
decision recorded in PHASE_4_ENGINE_FOUNDATION_RECONCILIATION.md,
verified CI-green at every stage). The result is
phase-4-engine-foundation-integration @ 76651aa9 -- the current,
most-advanced, CI-verified state of core:model and network:monitor.
Basing this change on the older, now-superseded
phase-4-measurement-foundation tip would mean building on code a
separate, already-completed effort has since revised, guaranteeing an
unnecessary future merge conflict. Nothing this task's implementation
touches is affected by DD-5's change (NetworkState's constructor
defaults) -- PermissionAdapter is a new, standalone file that
constructs no NetworkState value -- so the base change has no design
consequence for this task, only a housekeeping one.

Confirmed unchanged between the two bases, for everything this task
actually reads: MeasurementCapabilityClassifier.kt (permission
constants, per-capability classification logic), network:monitor's
manifest (still only ACCESS_NETWORK_STATE), network:monitor's
build.gradle.kts dependencies before this change's own addition, and
libs.versions.toml's androidx-core-ktx entry.

WHAT WAS IMPLEMENTED

network/monitor/src/main/kotlin/com/aeriva/network/monitor/measurement/PermissionAdapter.kt
(new): PermissionState (Granted, Denied, GrantedApproximateOnly,
CheckFailed(reason)), the PermissionAdapter interface (check(permission:
String), currentSdkInt()), and buildGrantedPermissionSet(adapter,
permissionsToCheck) -- the pure composition function that maps the
adapter's richer states down to the plain Set<String>
MeasurementCapabilityClassifier.classify() already accepts, unchanged.
No Android import in this file.

network/monitor/src/main/kotlin/com/aeriva/network/monitor/measurement/AndroidPermissionAdapter.kt
(new): the real implementation. Calls
androidx.core.content.ContextCompat.checkSelfPermission via an
injectable checkPermission: (String) -> Int seam (default wired to the
real call), following this codebase's own established clock-seam
convention (LatencyMeasurementEngine's elapsedNanos: () -> Long =
System::nanoTime is the precedent) specifically so this class's own
decision logic -- including CheckFailed handling -- is unit-testable on
the plain JVM without Robolectric or a mocking library, neither of
which this repository depends on. context: Context? is nullable for
exactly this reason: a JVM test constructs this class with a fake
checkPermission and no real Context at all.

Fine-location handling (the approximate-only case): checks
ACCESS_FINE_LOCATION first: if granted, returns Granted without
checking ACCESS_COARSE_LOCATION at all (verified by a dedicated test
that COARSE is never queried in this path). If fine is denied, checks
COARSE; granted there produces GrantedApproximateOnly, denied produces
Denied. This logic is identical across every API level this module
supports (minSdk 26) -- FINE and COARSE have been independent,
separately-grantable runtime permissions since API 23; Android 12/API
31 changed only the request flow (both must be requested together), not
what checkSelfPermission reports for each independently. No
Build.VERSION.SDK_INT branch was added to this logic because none is
needed for correctness -- confirmed by a test that runs the identical
scenario at API 26, 30, 31, and 34 and asserts the identical result.
currentSdkInt() still exists on the adapter, unconditionally, because
MeasurementCapabilityClassifier's own classify() signature already
takes an sdkInt parameter this adapter is the natural source for.

network/monitor/build.gradle.kts (modified, one line): added
implementation(libs.androidx.core.ktx) -- the existing
androidx-core-ktx 1.18.0 catalog entry, already used by core:security,
reused rather than duplicated. No libs.versions.toml change was needed.
No compileSdk/targetSdk/AGP/Kotlin/Gradle version was touched.

CLASSIFIER BOUNDARY

MeasurementCapabilityClassifier.kt was read in full and not modified.
PermissionAdapter's check(permission: String) signature takes a plain
permission string, never a MeasurementCapability -- it cannot express
or duplicate the classifier's own per-capability when logic, by
construction. No minimal compile/integration change was required: this
new file compiles independently of the classifier and does not change
any type the classifier consumes (grantedPermissions is still a plain
Set<String>, unchanged). No STOP condition 3 situation was
encountered.

WHAT WAS NOT DONE (explicit scope confirmation)

No android.permission.INTERNET was added anywhere -- OD-1 remains an
owner decision, untouched. No manifest was modified. No OkHttp
dependency was added. The production NetworkClient interface and
OkHttpNetworkClient were not touched -- neither exists as a concern in
this change at all. No measurement endpoint was created or hardcoded.
No HTTPS/UDP transport was implemented. No Activity Result permission-
request flow, dialog, rationale screen, or any UI was added -- this
adapter only reports state, per the specification's own explicit
non-goal (Section 6.8). LatencyMeasurementEngine.kt, jitter, the UDP
train, and the Phase 5 validation harness were not touched. No other
branch was merged into this one.
