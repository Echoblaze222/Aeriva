# CircleCI Validation

Second CI provider, added because the GitHub Actions free-tier Actions
minutes for this account were exhausted (100% of 2,000 min/month used,
resets on a monthly billing cycle). This document describes the CircleCI
pipeline only. GitHub Actions remains the primary, previously-validated
pipeline -- see `PHASE_1_VALIDATION_REPORT.md` for that history. Nothing
in this document should be read as re-validating or superseding that
report. CircleCI has now completed a real, passing run on `main` -- see
"Status" below for the specific commit and job results, not just a
syntactically-valid config.

## What was actually wrong with the previous `.circleci/config.yml`

Two independent bugs, not one:

1. **Wrong project root.** Every `working_directory`, cache-checksum
   path, and `store_test_results`/`store_artifacts` path assumed the
   Gradle project lived under `android/`. It does not -- the Gradle
   project is the repository root (confirmed by inspecting the actual
   checked-out repository: `settings.gradle.kts`, `gradlew`, and
   `gradle/` all sit at the top level).

2. **Redundant, risky wrapper regeneration.** The old config ran
   `gradle wrapper --gradle-version 8.14` against the CI image's own
   pre-installed Gradle, before every job. But `gradlew`, `gradlew.bat`,
   and `gradle/wrapper/gradle-wrapper.jar` are already committed to this
   repository and were manually verified when added (see the commit that
   introduced them, and `.github/workflows/ci.yml`'s own comment on its
   equivalent `gradle/actions/setup-gradle@v4` step). Regenerating the
   wrapper on every CI run would silently overwrite that already-verified
   file with a freshly-generated one -- not simply "wrong path", but
   doing unnecessary and slightly risky work that wasn't needed. The fix
   removes this step entirely and uses the committed `./gradlew` directly,
   the same way the working GitHub Actions pipeline already does.

## Current repository facts this config is built against

(Verified by inspecting the repository directly, not assumed.)

| | |
|---|---|
| Gradle wrapper | 8.14.2 (`gradle/wrapper/gradle-wrapper.properties`) |
| AGP | 8.13.2 |
| Kotlin | 2.3.21 |
| KSP | 2.3.11 |
| compileSdk / targetSdk | 36 |
| minSdk | 26 |
| Java (compile target) | 17 |
| Modules | `app`, `core:common`, `core:model`, `core:result`, `core:logging`, `core:database`, `core:preferences`, `core:security`, `network:monitor` |

`gradle/libs.versions.toml` documents *why* each of these versions is
pinned (AGP 8.13 vs. the newer 9.x line, coreKtx 1.18.0 vs 1.19.0, KSP's
versioning scheme change at 2.3.0) -- this file does not repeat those
version numbers as an independent, driftable copy; it only adds the
platform/CI-facing detail those comments don't cover.

## Current CI config

Path: `.circleci/config.yml`

## Executor / environment

- **build, unit_tests, static_checks**: Docker executor, image
  `cimg/android:2026.07` (verified as a current, real tag against Docker
  Hub's own tag list for `cimg/android` -- not the placeholder/outdated
  tag the previous config also happened to already have right).
- **connected_android_test**: Android machine executor
  (`android/android_machine`, tag `default`, resource class `large`),
  via the `circleci/android@3.2.0` orb -- confirmed as the current latest
  orb release against `CircleCI-Public/android-orb`'s own GitHub releases
  page.

## Java version

17, via the Java already pre-installed in `cimg/android:2026.07` and the
Android machine image. Matches `JAVA_VERSION: "17"` in
`.github/workflows/ci.yml` and every module's `jvmTarget = "17"`.

## Android SDK configuration

`sdkmanager "platforms;android-36" "build-tools;36.0.0"` is run
explicitly in the two Docker-executor jobs that need to compile
(`build`, `unit_tests`) and in `static_checks` (Lint needs the same SDK
platform), rather than assuming the image's pre-installed SDK platform
list already includes API 36. `connected_android_test` uses the Android
machine image's own pre-installed SDK plus the emulator's own system
image (`system-images;android-30;google_apis;x86_64`), matching
`.github/workflows/ci.yml`'s `api-level: 30, target: google_apis, arch:
x86_64` exactly, so this pipeline validates the same emulator target as
the already-working GitHub Actions one.

## Build / test / lint commands

| Job | Command |
|---|---|
| build | `./gradlew assembleDebug --stacktrace` |
| unit_tests | `./gradlew test testDebugUnitTest --stacktrace` |
| static_checks | `./gradlew lint --stacktrace` |
| connected_android_test | `./gradlew connectedAndroidTest --stacktrace` (via the orb's `start_emulator_and_run_tests`) |

The `test` / `testDebugUnitTest` split for `unit_tests` mirrors
`.github/workflows/ci.yml`'s own comment on why both are needed: `test`
covers the pure-Kotlin modules (`core:common`, `core:model`,
`core:result`), each with their own `test` task via the `kotlin.jvm`
plugin; `testDebugUnitTest` covers the Android library modules
(`core:logging`, `core:database`, `core:preferences`, `core:security`,
`network:monitor`), whose unit tests run against the debug variant.

## Caching strategy

Gradle **dependency** cache only (`~/.gradle/caches`, `~/.gradle/wrapper`),
keyed on `gradle/libs.versions.toml`'s checksum
(`gradle-deps-v2-{{ checksum "gradle/libs.versions.toml" }}` -- `v2` to
avoid colliding with any stale `gradle-deps-{{ checksum
"android/gradle/libs.versions.toml" }}` cache entries the old, broken
key may have left behind on the CircleCI project). This deliberately does
**not** attempt to cache Gradle build outputs/task-avoidance state across
jobs: each job in this workflow runs in its own fresh container, and
CircleCI jobs don't share a filesystem the way sequential steps within
one job do, so caching build outputs would need `persist_to_workspace` /
`attach_workspace` of full build directories -- meaningfully more
complexity for a correctness-vs-speed tradeoff this task's own priorities
(“prioritize correctness and reproducibility over aggressive caching”)
argue against taking on right now. `connected_android_test` uses the
orb's own separate `restore_gradle_cache_prefix` / `save_gradle_cache`
parameters instead, since it uses a different (machine, not Docker)
executor.

## Timeout strategy

- `assembleDebug`, `test`/`testDebugUnitTest`, and `lint`: 15-minute
  `no_output_timeout` (CircleCI's per-step silence timeout -- a step is
  killed if it produces no output for this long, not "must finish in this
  long total"). Fifteen minutes is deliberately generous rather than
  padded to hide a real hang: a genuinely stuck Gradle daemon on this
  project's module count would go silent well before that.
- `connected_android_test`: 20-minute `no_output_timeout` on the emulator
  step, since AVD creation, boot, and test execution together are
  legitimately slower than a plain Gradle command and the previous CI
  history for this exact job included real, non-hypothetical slowness
  (see `.github/workflows/ci.yml`'s own comment on the emulator/host
  issues chased on macOS runners, and the coroutine `runTest` virtual-time
  hang described below).
- Beyond per-step timeouts, CircleCI's own default job-level ceiling (5
  hours) is the hard backstop; nothing in this config raises that.

## Known limitations

- **No macOS/iOS equivalent.** This config only validates the Android
  build; it says nothing about the (separate, not-yet-built)
  iOS/macOS/Windows/Linux targets mentioned in the project's longer-term
  plans.
- **Instrumented tests remain emulator-only here too.** Same limitation
  `.github/workflows/ci.yml` already documents: this proves behavior
  against a virtual Keystore and virtual `ConnectivityManager`, not
  OEM-specific Keystore behavior, real battery/Doze/background-restriction
  behavior, or physical sensor/radio behavior. CircleCI's Android machine
  image does not change that.
- **`gradle-deps-v2-` cache key will start cold.** The very first run on
  a newly-connected CircleCI project has no prior cache to restore
  regardless of key correctness; this is expected, one-time, and not a
  config defect.
- **Coroutine `runTest`/real-time regression risk.** Per this task's own
  history note: a previous instrumented-test hang was traced to a test
  using Kotlin coroutine `runTest` virtual time while waiting for a real
  Android `ConnectivityManager` callback, and was fixed by moving that
  test to real-time execution. This CircleCI config does not change or
  re-touch that test; it is called out here only so a future regression
  of the same kind is recognized quickly against this pipeline too, not
  because this task altered it.

## Status

CircleCI project: `gh/Echoblaze222/Aeriva`, connected via the CircleCI
MCP connector (this environment's own network egress is not configured
for `circleci.com` directly; the connector is what makes fetching real
run/job/log data from here possible at all).

**Passing run:** commit `cd681d8` on `main` (merge of the
`circleci-recovery` branch into `main` -- see below for why a merge was
needed). Workflow `build_test_and_validate`, all four jobs succeeded:

| Job | Outcome | Duration |
|---|---|---|
| build | succeeded | ~2m |
| unit_tests | succeeded | ~2m |
| static_checks | succeeded | ~2m |
| connected_android_test | succeeded | ~7.5m |

`connected_android_test` is the job that matters most here: it's the one
proving `AerivaSecureStorageCorruptionInstrumentedTest` actually gets a
`DataCorrupted` failure from the real emulator, real Keystore, and real
Tink decrypt path -- not a syntactically-valid config that happens to
compile.

**Why this took three attempts, not one** (kept here rather than
smoothed over, since each attempt found a real, distinct bug):

1. The repaired `.circleci/config.yml` was first pushed only to a
   `circleci-recovery` branch, per that task's own instruction not to
   push CI changes directly to `main` without lead-engineer review.
   `main` itself still had the *original* broken config.
2. A later, unrelated commit (the `IllegalArgumentException` mapping fix
   to `EncryptedPreferencesSecureStorage`) was pushed straight to `main`
   without first merging `circleci-recovery` -- so the CircleCI run
   triggered on that push re-hit the *original* config's `android/`-path
   and wrapper-regeneration bugs, not a new problem. This is on the
   process that produced it, not a second config defect.
3. Merging `circleci-recovery` into `main` (commit `cd681d8`, per
   explicit instruction to proceed without waiting for further review)
   is what actually put the repaired config in front of a real run on
   `main`, and that run passed.

## Do not read this as Phase 1 (re-)validation

Per this task's own instructions: this document does not modify or
supersede `PHASE_1_VALIDATION_REPORT.md`. CircleCI itself has now
completed a real, passing run (see "Status" above) -- that is a claim
about CircleCI, on this document's own terms, not a re-validation of
Phase 1 or of the GitHub Actions pipeline that report describes.
