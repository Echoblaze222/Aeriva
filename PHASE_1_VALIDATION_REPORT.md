# PHASE 1 VALIDATION REPORT

## A. Validation date
2026-09-11

## B. Repository/commit validated
Echoblaze222/Aeriva, branch main, commit d7ba9dc (pre-validation HEAD at the
start of this pass) plus the fixes described in section L, committed at the
end of this pass (final hash in section 11 of the response, and at the
bottom of this file once committed).

## C. Build command executed
None succeeded. Attempted:

```
ls gradlew gradlew.bat gradle/wrapper        # no wrapper committed in this repo
curl -sS -D - -o /dev/null https://services.gradle.org/distributions/gradle-8.14-bin.zip
curl -sS -D - -o /dev/null https://repo1.maven.org/maven2/
curl -sS -D - -o /dev/null https://dl.google.com/dl/android/maven2/
apt-cache policy gradle
which gradle kotlinc
```

## D. Build result
**BLOCKED.**

Evidence:
- No `gradlew` / `gradle/wrapper/` is committed to this repository (by design --
  `.circleci/config.yml` bootstraps Gradle from the CI image's pre-installed
  version, which does not exist in this environment).
- `curl` to `services.gradle.org` (Gradle distribution) returned
  `HTTP/2 403`, header `x-deny-reason: host_not_allowed`.
- `curl` to `repo1.maven.org` (Maven Central, source of `kotlin-stdlib`,
  `kotlinx-coroutines`, JUnit, etc. for every module including the pure-Kotlin
  ones) returned `HTTP/2 403`, header `x-deny-reason: host_not_allowed`.
- `curl` to `dl.google.com` (Google's Maven, source of AGP, Room, DataStore,
  security-crypto, all AndroidX) returned the same `403 host_not_allowed`.
- `apt-cache policy gradle` shows only Gradle 4.4.1 available via the Ubuntu
  package mirror (`archive.ubuntu.com`, which is allowlisted) -- far below
  what this project's `libs.versions.toml` requires (Gradle 8.13+ for AGP
  8.13.2), and irrelevant regardless since Maven Central/Google Maven are
  unreachable for dependency resolution either way.
- `which gradle kotlinc` returned nothing -- neither tool is installed.

This container's network egress allowlist (visible in its own
configuration) permits only: api.anthropic.com, api.github.com,
archive.ubuntu.com, codeload.github.com, crates.io, files.pythonhosted.org,
github.com, index.crates.io, npmjs.com/npmjs.org and registry,
pythonhosted.org, raw.githubusercontent.com, registry.yarnpkg.com,
release-assets.githubusercontent.com, security.ubuntu.com,
static.crates.io, www.npmjs.com/org, yarnpkg.com. Neither Maven Central nor
Google's Maven repository is on that list. This makes a real Android/Gradle
build **structurally impossible in this environment**, independent of
effort -- not a transient failure and not something a retry or a different
command fixes.

## E. Unit tests executed
None. Running `./gradlew test` (or any equivalent) requires the same blocked
build pipeline as section D. There is no way to execute JUnit inside this
container without Gradle successfully resolving `kotlin-stdlib` and JUnit
from a Maven repository.

## F. Unit test results
**BLOCKED** for the same reason as D. 45 unit tests exist across
core:common (1), core:result (5), core:database (5), core:preferences (4),
core:security (6), network:monitor (existing suite from before this
validation pass). None have been executed. What follows is what I found by
close manual re-reading of every new source file and its matching test,
which is evidence of code review, not test execution, and I am not
reporting it as PASS:
- Verified by re-reading: brace balance across every new `.kt` file (script
  check, all matched), import correctness against each file's usage,
  `AerivaResult`/`AerivaError` construction matches the sealed type
  definitions, no unresolved symbol I could spot by inspection.
- Found and fixed one real issue: `FakeNetworkStateHistoryDao.observeRecent()`
  used an unnecessarily indirect nested-flow-builder construction. Logically
  it should have worked, but its correctness was harder to verify by
  inspection than it should be for test-support code, which is itself a
  quality problem in code whose only job is to make tests trustworthy.
  Rewritten using `flow { emitAll(...) }`, a standard, directly-verifiable
  pattern. See section L.

## G. Instrumented tests executed
None -- same blocked build.

## H. Physical-device tests executed
**HARDWARE TEST NOT EXECUTED.**

Reason: this environment has no Android device, no emulator, and (per
section D) no way to even produce a build artifact to install on one if a
device were attached. The Keystore-backed round-trip test
(`AerivaSecureStorageInstrumentedTest`) and the Room DAO instrumented test
(`NetworkStateHistoryDaoTest`) both require real Android Keystore /
SQLite-on-device behavior that cannot be simulated here. Marking these as
passed would be false. They remain unrun.

## I. Security validation results
**NOT EXECUTED** (requires the blocked build + a device, same as G/H).

What I did instead -- a manual logic walkthrough against each scenario the
validation gate asked about, which is review, not proof:

| Scenario | Code path reviewed | Notes |
|---|---|---|
| secure write | `EncryptedPreferencesSecureStorage.set` -> `SecureKeyValueStore.putString` -> `SharedPreferencesKeyValueStore` (commit=true) | Logic present; unverified without Keystore |
| secure read | `.get` -> `getString` | Logic present; unverified |
| update | `set` on existing key overwrites (same code path) | Logic present; unverified |
| delete | `remove` -> `SharedPreferences.Editor.remove` | Logic present; unverified |
| missing values | `get` returns `null` when absent (default `getString(key, null)`) | Logic present; unverified |
| application restart | Not applicable to unit-testable logic -- depends on the real encrypted file surviving process death, which only a device test proves | **Cannot be validated without a device** |
| encrypted file persistence | Same as above | **Cannot be validated without a device** |
| corrupted encrypted storage | No explicit corrupted-file-but-key-valid path exists in `AerivaSecureStorageFactory` today -- only "key unusable" is handled (see next row). If the file itself is corrupted independently of the key, `EncryptedSharedPreferences.create()`'s behavior was not something I could verify without running it. **This is a real gap, not a fix** -- flagged in section K/M rather than silently left out. |
| unavailable/lost Keystore key | `AerivaSecureStorageFactory.create()` catches `Exception` from `buildEncryptedPreferences`, deletes the file, rebuilds once | Logic present; matches the deliberate design in the validation request; unverified against a real Keystore |
| automatic encrypted-storage recovery after unrecoverable Keystore loss | Same as above -- **this behavior is unchanged**, as instructed. I did not make it symmetrical with the database/preferences manual-recovery pattern. | Confirmed unchanged by diff review |

No hardcoded secrets, no plaintext writes of sensitive values, and no
logging of the actual stored value were found anywhere in `core:security`
(logging calls only log the operation name and exceptions, never `value`
parameters) -- confirmed by direct reading of every log call in the module.

## J. Module/architecture validation
**PASS**, by static inspection (this section is genuinely inspectable
without a build, unlike compilation correctness):

- All seven Phase 1 modules exist and are included in `settings.gradle.kts`:
  `core:common`, `core:model`, `core:result`, `core:logging`,
  `core:database`, `core:preferences`, `core:security`. No module is
  commented out.
- Dependency graph (from every `build.gradle.kts`'s `project(":...")`
  declarations):
  - `network:monitor` -> `core:model`, `core:logging`
  - `core:database` -> `core:model`, `core:result`, `core:logging`
  - `core:preferences` -> `core:result`, `core:logging`
  - `core:security` -> `core:common`, `core:result`, `core:logging`
  - `core:common`, `core:model`, `core:result`, `core:logging` declare no
    `project()` dependencies of their own (leaf modules).
  - No cycle exists. No core module depends on `network:monitor` or `app`
    (correct direction -- core is depended on, not depending).
- Android-framework imports (`grep "^import android"` across every
  module's `src/main`) are confined to platform-adapter files only:
  `AndroidLogcatLogger`, `NetworkHistoryRepository`/`AerivaDatabaseProvider`
  (Room/SQLite exception types), `AerivaPreferencesFactory`,
  `AerivaSecureStorageFactory`/`SharedPreferencesKeyValueStore`,
  `AndroidNetworkMonitor`/`TransportConstantMapper`. `core:common`,
  `core:model`, and `core:result` have zero `android.*` imports, confirmed
  by direct grep with no matches.
- No duplicated infrastructure found (one dispatcher abstraction, one
  result type, one logger interface, each used consistently rather than
  reimplemented per module).

## K. Problems discovered
1. `FakeNetworkStateHistoryDao.observeRecent()` was written in an
   unnecessarily indirect way (see F). Fixed.
2. **Real, unresolved gap:** `AerivaSecureStorageFactory` only handles the
   "Keystore key lost/invalid" failure mode. A distinct failure mode --
   the encrypted preferences *file* itself corrupted while the Keystore key
   is still valid -- is not explicitly handled or tested. Whether
   `EncryptedSharedPreferences.create()` throws in that case (and would
   therefore be caught by the existing `catch (e: Exception)`) or fails
   more subtly is something I could not determine without running it.
   I have not "fixed" this because I cannot verify a fix without execution,
   and the validation gate explicitly says not to make unverified fixes
   final. Flagged as a remaining risk (section M).
3. `core/database/schemas/` (the Room schema-export directory referenced by
   `ksp { arg("room.schemaLocation", ...) } }`) does not exist yet -- it can
   only be generated by a real KSP-processed build, which is blocked. Not a
   defect in the configuration itself, but it means the migration-testing
   infrastructure is configured, not yet populated.
4. The exact KSP plugin patch version for Kotlin 2.3.21 remains unverified
   (already flagged directly in `libs.versions.toml` before this pass).

No TODO/FIXME markers, no `println`/`printStackTrace`, no `GlobalScope`
usage, no empty catch blocks, and no hardcoded secret-shaped strings were
found anywhere in the codebase (all four checked by direct grep across every
`.kt` file, zero matches each).

## L. Fixes made
- `core/database/src/test/kotlin/com/aeriva/core/database/FakeNetworkStateHistoryDao.kt`:
  simplified `observeRecent()` from a nested nameshadowed flow-builder
  construction to `flow { ...; emitAll(state.map { ... }) }`. Same behavior,
  directly verifiable by reading instead of requiring careful tracing.

No production (`src/main`) code was changed in this pass -- everything else
found was a gap to flag (K.2, K.3), not something I could safely alter
without a build to verify the change against.

## M. Remaining risks
1. **Nothing in this repository has ever been compiled.** Every risk below
   is downstream of that one fact. A real `./gradlew build` may surface
   import errors, type mismatches, or KSP/Room annotation problems that
   code review cannot guarantee catching.
2. Corrupted-encrypted-file-with-valid-key handling in `core:security` is
   unverified (K.2).
3. Room schema export has never run, so the migration-testing
   infrastructure is unproven (K.3).
4. KSP plugin version is an unverified placeholder (K.4).
5. All "logic present, unverified" rows in section I remain genuinely
   unverified until a device runs them.
6. `DataStoreAerivaPreferencesTest`'s assumption -- that
   `datastore-preferences` is usable on a plain JVM test classpath without
   Robolectric -- was already flagged as unconfirmed when written, and
   still is.

## N. Tests that could not be executed and why
All of them (unit, instrumented, and device) -- see sections D through H
for the single root cause (no reachable Gradle distribution or Maven
repository in this environment's network allowlist) and section H for the
additional device-specific reason.

## O. Final Phase 1 status
**BLOCKED** -- not VALIDATED, not FAILED. No known defect was found that
would make the implementation wrong, but "no known defect from reading the
code" is explicitly not the bar this gate sets, and I'm not calling it met.
Phase 1 cannot be declared validated until sections D through H are actually
run, in an environment that can reach Gradle/Maven and, for H, real Android
hardware.

---

## P. CI status update (added after this report was first written)

A GitHub Actions workflow (`.github/workflows/ci.yml`) now exists and can
execute everything sections D-H could not, because GitHub-hosted runners
have normal, unrestricted internet access -- the block described in
sections D-H was specific to the sandboxed local development container,
not to Android/Gradle/Maven themselves.

**This section is being added at the same time the workflow is being
added. No CI run has completed yet as of this commit.** Nothing below is a
claim that Phase 1 is now validated.

### Execution-category matrix (going forward, keep this current)

| Category | Where it can run | Status as of this commit |
|---|---|---|
| Build / Android compilation | CI job `build` (`./gradlew assembleDebug`) | NOT YET RUN |
| Unit tests (JVM) | CI job `unit-tests` (`./gradlew test testDebugUnitTest`) | NOT YET RUN |
| Static checks (Android Lint) | CI job `static-checks` (`./gradlew lint`) | NOT YET RUN |
| Instrumented tests | CI job `instrumented-tests-emulator`, on a GitHub-hosted **emulator** (`./gradlew connectedDebugAndroidTest`) | NOT YET RUN |
| Physical-device tests | Real Android hardware only -- no CI job substitutes for this | NOT EXECUTED, still requires manual execution on a real device |
| Local sandbox execution | This project's chat-session container | Confirmed structurally BLOCKED (see sections D-H) -- will remain so regardless of CI's outcome, since the sandbox's network allowlist is unrelated to GitHub Actions' |

An **emulator** run passing is meaningfully different from a **physical
device** run passing -- an emulator does not exercise OEM-specific
Keystore implementations, real battery/Doze/background-restriction
behavior, or real radio/sensor hardware. Both rows are tracked separately
above and must stay separate in every future update to this file; do not
collapse "instrumented (emulator)" into "device tests" language.

### Gradle wrapper

`gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar` are now
committed (they were previously absent -- see the original section D).
The wrapper jar was fetched from the official `gradle/gradle` GitHub
repository at tag `v8.14.2` and its SHA-256 was verified against Gradle's
own published checksum list at gradle.org/release-checksums before being
committed -- it was not downloaded from an arbitrary or unverified source.
`gradle-wrapper.properties`'s `distributionSha256Sum` was set the same way,
from the same published list, not invented.

### Updated final status

**Phase 1 status remains BLOCKED**, not VALIDATED, per explicit instruction
-- a workflow file existing is infrastructure, not a passing run. This file
must be updated again once an actual CI run completes, with real per-job
outcomes (PASS/FAIL) replacing every "NOT YET RUN" above, before Phase 1
can be reconsidered.

## Q. First actual CI run result (real, not simulated)

The workflow ran on push (commit 947bb04). Result, from the real GitHub
Actions log the person pasted:

- **build**: FAIL. `Assemble (compiles every module, including Android
  sources)` failed after 40s with:
  ```
  FAILURE: Build failed with an exception.
  * What went wrong:
  Plugin [id: 'com.google.devtools.ksp', version: '2.3.21-2.0.2', apply: false]
  was not found in any of the following sources: ...
  ```
- **unit-tests**, **static-checks**, **instrumented-tests-emulator**: did
  not run (skipped -- all three `needs: build`, which failed).

### Root cause

`gradle/libs.versions.toml`'s `ksp` value, `"2.3.21-2.0.2"`, does not exist.
It was written earlier in this project following the old KSP versioning
scheme (`{kotlin-version}-{ksp-patch}`), and flagged at the time as an
explicitly **unverified placeholder** with an instruction to confirm it
before the first real build. That confirmation never happened before now
because no build could run until this commit. As of KSP 2.3.0, KSP
dropped that versioning scheme entirely -- current KSP releases are plain
semantic versions (2.3.11 is latest, per google/ksp's own release notes
and release list), independent of the Kotlin compiler version in use.

### Fix

`ksp` set to `"2.3.11"` in `gradle/libs.versions.toml`, with a comment
explaining the versioning scheme change and citing where this was
verified (google/ksp release notes, not assumed). This is a one-line,
targeted fix for the exact reported failure -- no other version was
touched, no architecture changed.

### What this does NOT yet confirm

This fix has not itself been run through CI yet as of writing this
section. It removes the specific, named cause of this failure; it does
not guarantee the next run succeeds -- there could be a next problem
behind it (another unverified assumption, a real compile error in one of
the modules that were never built before, etc.). Do not treat this
section as "Phase 1 validated" or "CI passing" -- it is a diagnosis and a
fix for one specific, evidenced failure, awaiting the next real run to
confirm.

**Phase 1 status: still BLOCKED.**

## R. Second actual CI run result (real, not simulated)

After the KSP fix (commit 4d5dab8), the workflow ran again and failed
again -- **at a different, unrelated step**, not the same problem
recurring. From the real GitHub Actions log the person pasted:

```
e: file:///home/runner/work/Aeriva/Aeriva/app/build.gradle.kts:26:9:
Using 'jvmTarget: String' is an error. Please migrate to the
compilerOptions DSL.
FAILURE: Build failed with an exception.
* Where: Build file '.../app/build.gradle.kts' line: 26
* What went wrong: Script compilation error: Using 'jvmTarget: String'
  is an error. Please migrate to the compilerOptions DSL.
```

### Root cause

The Kotlin Gradle Plugin has fully removed the old
`kotlinOptions { jvmTarget = "17" }` DSL (String-typed) -- it is now a
hard script-compilation error, not a deprecation warning, at the Kotlin
version in use. `app/build.gradle.kts` used that exact pattern. Once this
was found, the same pattern was checked for everywhere else rather than
fixed one file at a time across repeated CI runs: `grep -rln
"kotlinOptions"` found the identical pattern in five more files --
`network/monitor`, `core/database`, `core/logging`, `core/preferences`,
`core/security` -- every Android-library module in the repo. All six
would have failed the same way, sequentially, one CI run per file, if
fixed reactively instead of exhaustively.

### Fix

All six files migrated to the current `compilerOptions` DSL:

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
...
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
```

placed as a top-level block (outside `android { }`), which is how this
DSL is configured for both `kotlin.android` and `kotlin.jvm` modules
alike. The old `kotlinOptions { }` block was removed entirely, not left
empty. `core:model`, `core:common`, and `core:result` (the three
`kotlin.jvm` modules) never used the old DSL and were not touched --
confirmed by checking their build files directly, not assumed.

### What this does NOT yet confirm

Same caveat as section Q: this fix has not itself been run through CI as
of writing this section. Two real, distinct problems have now been found
and fixed by actually running this in CI -- exactly the kind of thing
that could not be found by inspection alone in the original Phase 1
validation gate. There may be more. Each fix in this file is being
recorded as a diagnosis-and-fix pair with an explicit "not yet confirmed"
note, not folded into a claim that the build now works.

**Phase 1 status: still BLOCKED.**

## S. Third actual CI run result (real, not simulated) -- and first sign of real progress

After the compilerOptions fix (commit 9050fa5), the workflow ran a third
time. This run got substantially further than either previous one --
dozens of real tasks executed and succeeded across every module
(`compileDebugKotlin`, `assembleDebug` for `:app`, KSP processing for
`:core:database`, etc., all visible in the real log), before failing at
a new, later stage: `:core:security:checkDebugAarMetadata`.

```
FAILURE: Build failed with an exception.
* What went wrong:
Execution failed for task ':core:security:checkDebugAarMetadata'.
> A failure occurred while executing CheckAarMetadataWorkAction
  > 4 issues were found when checking AAR metadata:
    1. Dependency 'androidx.core:core:1.19.0' requires ... compile
       against version 37 or later of the Android APIs.
       :core:security is currently compiled against android-36.
       Also, the maximum recommended compile SDK version for Android
       Gradle plugin 8.13.2 is 36.
    2. Dependency 'androidx.core:core:1.19.0' requires Android Gradle
       plugin 9.1.0 or higher. This build currently uses AGP 8.13.2.
    3/4. Same two issues for 'androidx.core:core-ktx:1.19.0'.
```

### Root cause

`core:security`'s direct dependency on `androidx.core:core-ktx` was
pinned to `1.19.0` when the module was written. `1.19.0`'s own AAR
metadata requires `compileSdk 37` and `AGP 9.1.0+` -- both newer than
this project's pinned `compileSdk 36` / AGP `8.13.2`. This was not
caught earlier because, same as every other version in this catalog,
nothing had actually built until CI existed.

### Fix

`coreKtx` set to `1.18.0` in `gradle/libs.versions.toml` -- confirmed (not
guessed) as the newest release still compatible with `compileSdk 36`/AGP
8.x, via androidx's own release notes (1.18.0's changelog: "compileSdk
changed from API 36 to API 36.1") and independent corroboration from
other projects that hit this identical AAR-metadata wall going from
1.18.0 to 1.19.0. One version value changed; nothing else.

### What this does NOT yet confirm

Same caveat as sections Q and R -- unconfirmed until the next real run.
Three distinct real problems have now been found this way (KSP version,
kotlinOptions DSL removal, core-ktx/compileSdk mismatch), each closer to
a working build than the last. This is what CI existing was for.

**Phase 1 status: still BLOCKED.**

## T. Fourth actual CI run result (real, not simulated) -- reached the source-code level

After the core-ktx fix (commit fdc719c), the workflow ran a fourth time.
Real progress again: `:app:assembleDebug` succeeded completely, and
`:core:logging` and `:core:preferences` both built and assembled in
full. This is the first run to get past dependency resolution/AAR
metadata entirely and fail on an actual Kotlin visibility rule inside
this project's own source:

```
e: .../core/security/src/main/kotlin/com/aeriva/core/security/
   EncryptedPreferencesSecureStorage.kt:15:5 'public' function exposes
   its 'internal' parameter type 'SecureKeyValueStore'.
> Task :core:security:compileDebugKotlin FAILED
```

### Root cause

`SecureKeyValueStore` was deliberately declared `internal` (an
implementation-detail seam, not part of `core:security`'s public API --
see its own doc comment). `EncryptedPreferencesSecureStorage`, which
takes it as a constructor parameter, was left at default (public)
visibility -- a real inconsistency between the two. Kotlin's compiler
correctly rejects a public class exposing a less-visible type in its
constructor signature. The same mismatch also existed in
`SharedPreferencesKeyValueStore` (public class implementing the internal
interface as a supertype) and the test-only `FakeSecureKeyValueStore` --
found by grepping for every reference to `SecureKeyValueStore` before
fixing, not discovered one CI run at a time.

### Fix

All three made `internal`, matching the type they depend on/implement:
`EncryptedPreferencesSecureStorage`, `SharedPreferencesKeyValueStore`,
`FakeSecureKeyValueStore`. `AerivaSecureStorageFactory.create()` (the
only actual public entry point into this module) was checked and
already declared its return type as the public `AerivaSecureStorage`
interface, not any of the now-internal concrete classes -- no change
needed there. This is a real design correction, not just a build fix:
`core:security`'s public surface is now exactly `AerivaSecureStorage` +
`AerivaSecureStorageFactory`, nothing else, which was the original
intent.

### What this does NOT yet confirm

Same caveat as sections Q/R/S. Four distinct real problems found and
fixed via actual CI runs now; each run has gotten further than the last
(dependency resolution -> DSL removal -> AAR metadata -> now
module-internal source code). Not claiming this fix is confirmed until
the next real run.

**Phase 1 status: still BLOCKED.**

## U. Fifth actual CI run result (real, not simulated) -- Build and Static checks both PASSED

After the visibility fix (commit ee4115e), the workflow ran a fifth
time, with the first genuinely good news:

- **build**: **PASS.** `:app:assembleDebug` and every module compiled
  successfully.
- **static-checks**: **PASS.** Android Lint ran clean.
- **unit-tests**: FAIL -- `:core:security:testDebugUnitTest`, all 5 tests
  in `EncryptedPreferencesSecureStorageTest` failed with
  `java.lang.IllegalStateException`.
- **instrumented-tests-emulator**: status not yet confirmed from what was
  shared this round -- follow up separately.

### Root cause

`EncryptedPreferencesSecureStorageTest`'s helper constructed
`StandardTestDispatcher()` with no arguments, which creates its own
independent `TestCoroutineScheduler` rather than sharing the one
`runTest` itself manages (`TestScope.testScheduler`).
`EncryptedPreferencesSecureStorage`'s methods call
`withContext(dispatchers.io) { ... }`; the moment that switches onto a
dispatcher backed by a *different* scheduler than the one `runTest` is
driving, kotlinx-coroutines-test throws `IllegalStateException` --
exactly what all 5 failures showed, at the exact line each test called
into `storage`. This is a real, common `kotlinx-coroutines-test` pitfall
that unit-test execution (not code review) is what actually catches.

### Fix

The test helper now takes the `TestCoroutineScheduler` explicitly and
constructs `StandardTestDispatcher(scheduler)` with it, and every test
passes `testScheduler` (the `TestScope` receiver's own scheduler,
available inside `runTest { }`) through. Same scheduler on both sides
now.

### Also fixed while here (a warning, not a failure)

`core:preferences`'s `DataStoreAerivaPreferencesTest` used
`UnconfinedTestDispatcher()` without the required
`@OptIn(ExperimentalCoroutinesApi::class)`, which compiled but emitted a
compiler warning on every run. Added the opt-in annotation. Not fixing
this would not have failed CI, but it was found in the same log and cost
nothing to fix alongside the real failure.

Deprecation warnings on `AerivaSecureStorageFactory.kt` (`MasterKey`,
`EncryptedSharedPreferences` "deprecated in Java") were also visible in
this run's log -- these come from `androidx.security.crypto` itself, not
from anything in this codebase, and are left as-is; not actioned in this
pass.

### What this does NOT yet confirm

Same caveat as sections Q/R/S/T. Five distinct real problems found and
fixed via actual CI runs now. This is the first run where two of the
four job categories (build, static-checks) are confirmed passing, not
just "further than last time" -- genuine progress, still not the whole
gate. `unit-tests` needs the next run to confirm this specific fix, and
`instrumented-tests-emulator`'s result from this run is still unknown as
of writing this section.

**Phase 1 status: still BLOCKED** (unit-tests unconfirmed after this fix;
instrumented-tests-emulator result unknown; physical-device tests never
attempted, as always).

## V. Continued diagnosis from the same/next run -- two more real, distinct problems

Further log detail from the same round surfaced two more real failures,
unrelated to each other and to section U's fix:

### 1. `:core:database:kspDebugKotlin` FAILED -- Room schema export

```
e: [ksp] kotlinx.serialization.json.internal.JsonDecodingException:
Expected start of the object '{', but had 'EOF' instead at path: $
JSON input:
...
at androidx.room.migration.bundle.SchemaBundle$Deserializer.deserialize
at androidx.room.compiler.processing.util.Database.exportSchema
```

**Root cause:** this project's `core:database/build.gradle.kts` configured
Room's schema export via the older, raw
`ksp { arg("room.schemaLocation", "$projectDir/schemas") }` mechanism.
Verified (not assumed) via web research that this exact mechanism has a
known, documented failure class in recent Room/KSP versions -- e.g.
DuckDuckGo's own Android repo hit and fixed this same category of bug
(PR #7712: "Several modules were using annotationProcessorOptions... but
this only works with kapt, not KSP... schemas weren't being exported" /
"absolute paths were being used which breaks Gradle caching"), and
Room's own documentation has promoted the dedicated `androidx.room`
Gradle plugin with a `room { schemaDirectory(...) }` DSL as the current
recommended replacement since Room 2.6.0.

**Fix:** added the `androidx.room` Gradle plugin (`libs.plugins.androidx.room`,
sharing the existing `room = "2.8.4"` version) to the root `build.gradle.kts`
and `core/database/build.gradle.kts`, replacing the raw `ksp { arg(...) }`
block with `room { schemaDirectory("$projectDir/schemas") }`. Also removed
the manual `sourceSets { getByName("androidTest").assets.srcDirs(...) }`
line -- the Room plugin wires this automatically per its own docs, so the
manual version was redundant (and possibly a contributing factor to the
original failure, since a plugin-managed and hand-managed path pointing
at the same directory could plausibly race).

### 2. `instrumented-tests-emulator` FAILED -- "Timeout waiting for emulator to boot"

Not a code problem. Verified (not assumed) via direct research:
`macos-latest` has been Apple Silicon (arm64) since macOS 14, confirmed
both by GitHub's own runner-images documentation and by a maintainer
comment on `reactivecircus/android-emulator-runner`'s own repo (issue
#392): *"macos-latest uses macOS 14 now which appears to have an issue
starting the Android emulator."* This job's `arch: x86_64` setting loses
hardware acceleration entirely on an Apple Silicon host -- exactly
"timeout waiting to boot," not a flake. `arm64-v8a` is not a safe
alternative either -- it has its own separate, actively open failure
reports on the same action's issue tracker on GitHub's macOS runners.

**Fix:** `runs-on: macos-15-intel` -- confirmed as GitHub's current,
still-supported Intel-architecture label (through 2027-08). The old
`macos-13` Intel label (what older guidance for this action assumes) is
fully deprecated as of 2025-12-04 and would fail outright, not just be
suboptimal -- checked and avoided. `arch: x86_64` unchanged, now paired
with a host that can actually accelerate it.

### What this does NOT yet confirm

Same standing caveat. Seven distinct real problems now found and fixed
via actual CI runs across this whole process (KSP version, kotlinOptions
removal, core-ktx/compileSdk, internal visibility, coroutine test
scheduler, Room schema export mechanism, emulator runner architecture).
None of today's fixes are confirmed until the next real run.

**Phase 1 status: still BLOCKED.**

## W. Real milestone: build, unit-tests, and static-checks all PASS for the first time

Run #10 (commit 2754aec) is the first run where three of four job
categories are **confirmed PASS**, not just "further than last time":

- **build**: PASS
- **unit-tests**: PASS -- confirms section U's coroutine-scheduler fix
  actually worked
- **static-checks**: PASS
- **instrumented-tests-emulator**: FAILED, new distinct cause (below).
  The Room schema-export fix from section V did work -- the log shows
  `core:database:copyRoomSchemasToAndroidTestAssetsDebugAndroidTest`
  running cleanly, and the emulator itself booted and ran (unlike the
  previous timeout) -- confirming both the architecture fix (V.2) and
  the Room plugin fix (V.1) that could not be confirmed as of section V.

### Remaining failure: `ClassNotFoundException` on the emulator

```
java.lang.RuntimeException: Unable to instantiate instrumentation
ComponentInfo{com.aeriva.core.database.test/androidx.test.runner.AndroidJUnitRunner}:
java.lang.ClassNotFoundException: Didn't find class
"androidx.test.runner.AndroidJUnitRunner" on path: ...
Task :core:database:connectedDebugAndroidTest FAILED
```

**Root cause:** every module's `testInstrumentationRunner` was set to
`androidx.test.runner.AndroidJUnitRunner`, but the dependency that
actually provides that class -- `androidx.test:runner` -- was never
declared. `androidx.test.ext:junit` (which every module did have) only
provides the `@RunWith(AndroidJUnit4::class)` JUnit4 runner annotation
class; it is a different artifact from the instrumentation bootstrap
class the emulator tries to load first. This is exactly the kind of gap
that inspection missed and only an actual device/emulator run surfaces.

**Fix:** added `androidx.test:runner:1.7.0` (same release line as
`androidx.test:core`, per Android's own dependency-setup docs) to
`network:monitor`, `core:database`, and `core:security` -- the three
modules with real `androidTest` source sets. Also removed
`core:preferences`'s `testInstrumentationRunner` declaration -- that
module has no `androidTest` source set at all, so the setting was dead,
slightly misleading configuration, not a functional problem; cleaned up
while already in this area, not scope creep.

### What this does NOT yet confirm

Eight distinct real problems now found and fixed via actual CI runs.
Three of four job categories are now genuinely confirmed passing, which
is real, verified progress -- not "further than last time" language.
`instrumented-tests-emulator` needs the next run to confirm this fix;
physical-device tests remain untouched, as always.

**Phase 1 status: still BLOCKED** (3/4 job categories confirmed PASS;
instrumented-tests-emulator fix unconfirmed; physical-device tests never
attempted).

## X. Ninth real problem: package service not ready at install time

Run #11 (after commit 8e2b230's androidx.test:runner fix) confirmed that
fix worked -- no more `ClassNotFoundException`. `build`, `unit-tests`,
and `static-checks` all stayed PASS. The emulator itself booted this
time (no repeat of the earlier "Timeout waiting for emulator to boot").
A new, later failure appeared instead:

```
[PropertyFetcher]: TimeoutException getting properties for device emulator-5554
...
Task :core:database:connectedDebugAndroidTest FAILED
Message: Failed to install split APK(s): [.../database-debug-androidTest.apk]
'package install-create -r -t -S 2817723' returns error
'Unknown failure: cmd: Can't find service: package'
```

### Root cause

`sys.boot_completed` (what the emulator-runner action waits on) can
report true before the Android `package` service is actually registered
and ready to accept installs -- a known gap in emulator boot-readiness
checking, not specific to this project's code. The `TimeoutException`
fetching device properties immediately before the install failure in
the same log is the same underlying readiness gap manifesting a second
way.

### Fix

Replaced the direct `./gradlew connectedDebugAndroidTest` script step
with an explicit poll loop that waits for `adb shell service check
package` to report `package: found` (capped at 180s) before running
Gradle. Deliberately not a fixed `sleep N` -- that would be guessing a
duration rather than waiting for the actual condition, and would either
waste time on fast boots or still race on slow ones.

Also observed in this run, not actioned: a non-fatal warning --
`Unable to strip the following libraries, packaging them as they are:
libdatastore_shared_counter.so` -- a known benign warning from
DataStore's bundled native library; did not fail the build and isn't
addressed here.

### What this does NOT yet confirm

Nine distinct real problems now found and fixed via actual CI runs.
build/unit-tests/static-checks continue to be confirmed PASS across
multiple runs, not a one-off. instrumented-tests-emulator's fix here is
unconfirmed until the next real run.

**Phase 1 status: still BLOCKED** (3/4 job categories repeatedly
confirmed PASS; instrumented-tests-emulator fix unconfirmed; physical-
device tests never attempted).

## Y. Tenth real problem: `timeout` command doesn't exist on macOS

Run #12 (after commit 34a2153) confirmed `build`/`unit-tests`/
`static-checks` all still PASS across yet another run. The emulator
step itself failed immediately this time, before even reaching the
package-service check:

```
/bin/sh -c 'timeout 180 bash -c ...'
/bin/sh: timeout: command not found
Error: The process '/bin/sh' failed with exit code 127
```

### Root cause

My own mistake in section X's fix, not a pre-existing project issue:
`timeout` is a GNU coreutils command. This job runs on `macos-15-intel`
(see the `runs-on` fix in section V.2) -- macOS's stock shell does not
have `timeout` available by default, unlike Linux. I wrote the fix
assuming a GNU/Linux environment.

### Fix

Replaced `timeout 180 bash -c '...'` with a manual attempt-counter loop
(90 attempts x 2s sleep = same 180s cap, no external dependency):

```bash
attempt=0
max_attempts=90
until adb shell service check package 2>/dev/null | grep -q "package: found"; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge "$max_attempts" ]; then
    echo "Timed out waiting for the package service." >&2
    exit 1
  fi
  sleep 2
done
```

Portable across macOS and Linux without installing anything extra.

### What this does NOT yet confirm

Ten distinct real problems now found and fixed via actual CI runs --
this one specifically was a mistake in a previous fix, caught the same
way every other problem in this report was: by actually running it, not
by review. build/unit-tests/static-checks remain confirmed PASS across
four consecutive runs now. instrumented-tests-emulator's fix here is
unconfirmed until the next real run.

**Phase 1 status: still BLOCKED.**

## Z. Eleventh real problem: this action splits multi-line `script:` per-line

Run #13 (after commit bc8a0f1) failed differently again -- the
`timeout`-command mistake was gone, but a new, structural failure
appeared:

```
/bin/sh -c echo "Waiting for the package service to be ready..."
/bin/sh -c attempt=0
/bin/sh -c max_attempts=90
/bin/sh -c until adb shell service check package 2>/dev/null | grep -q "package: found"; do
/bin/sh: -c: line 1: syntax error: unexpected end of file
Error: The process '/bin/sh' failed with exit code 2
```

### Root cause

`reactivecircus/android-emulator-runner`'s `script:` input, confirmed
from this exact log, executes **each line** of a multi-line YAML block
scalar as its **own separate** `/bin/sh -c` invocation -- not as one
continuous script. My `until ... do ... done` loop's `do` line ran as
its own isolated command with no matching `done` in the same shell
invocation, hence "unexpected end of file". This is a real, structural
property of this action's `script:` input that no amount of
shell-syntax fixing within that field would have solved -- confirmed
by seeing each line logged as its own separate `/bin/sh -c` call.

### Fix

Moved the wait/retry/run logic out of the workflow YAML entirely, into
a real script file: `.github/scripts/wait-for-package-service.sh`
(executable bit set). `script:` now calls it as a single line:
`bash .github/scripts/wait-for-package-service.sh`. A single line
cannot be split per-line into something broken, and the script file
itself can use normal multi-line bash control flow freely, since it
runs as one `bash` invocation, not through this action's line-by-line
splitting.

### What this does NOT yet confirm

Eleven distinct real problems now found and fixed via actual CI runs.
build/unit-tests/static-checks remain confirmed PASS across five
consecutive runs now. instrumented-tests-emulator's fix here is
unconfirmed until the next real run.

**Phase 1 status: still BLOCKED.**

## AA. Real milestone: instrumented tests actually ran and passed on the emulator

Run #14 (after commit c28477b's script-file fix) confirmed the
package-service wait logic worked: log shows `Package service ready.`
followed by real test execution:

```
> Task :core:database:connectedDebugAndroidTest
Starting 3 tests on test(AVD) - 11
test(AVD) - 11 Tests 2/3 completed. (0 skipped) (0 failed)
test(AVD) - 11 Tests 3/3 completed. (0 skipped) (0 failed)
Finished 3 tests on test(AVD) - 11
```

`core:database`'s instrumented tests -- the Room DAO tests written back
when `core:database` was first built -- ran against a real Android
runtime and **passed**. This is the first actual device-level (emulator)
test execution and pass in this entire validation process.

### New failure, further along: pre-existing test file, never compiled until now

```
Task :network:monitor:compileDebugAndroidTestKotlin FAILED
e: .../AndroidNetworkMonitorInstrumentedTest.kt:5:32 Unresolved reference 'AndroidAerivaLogger'
e: .../AndroidNetworkMonitorInstrumentedTest.kt:37:22 Unresolved reference 'AndroidAerivaLogger'
```

**Root cause:** `network:monitor`'s instrumented test (part of the
original pre-existing handoff code, from before this validation project
started, and never compiled until a run got this far) referenced
`com.aeriva.core.logging.AndroidAerivaLogger` -- a class that does not
exist. The real, actual logger implementation built in `core:logging`
is named `AndroidLogcatLogger`, and takes a required `debugBuild:
Boolean` constructor parameter that the old code didn't pass either.
This is a genuine naming/API mismatch between old handoff code and what
was actually later built, not something introduced by this validation
process -- surfaced only now because this is the first run to reach
compiling `network:monitor`'s androidTest sources at all.

### Fix

Updated the import and constructor call to the real class:
`AndroidLogcatLogger(debugBuild = true)`. Confirmed `core:logging` is
already a dependency of `network:monitor` (`implementation(project(":core:logging"))`,
already visible to androidTest by default) -- no dependency change
needed, only the reference itself.

### What this does NOT yet confirm

Twelve distinct real problems now found and fixed via actual CI/device
runs. This is qualitatively different from every fix before it in this
report: `core:database`'s instrumented tests didn't just compile, they
executed against a real Android runtime and passed. `network:monitor`'s
instrumented test has still never successfully compiled or run --
unconfirmed until the next real run.

**Phase 1 status: still BLOCKED** (build/unit-tests/static-checks
repeatedly confirmed PASS; core:database's instrumented tests confirmed
PASS on emulator for the first time; core:security's and
network:monitor's instrumented tests still unconfirmed; physical-device
tests never attempted).
