# CircleCI Validation

Second CI provider, added because the GitHub Actions free-tier Actions
minutes for this account were exhausted (100% of 2,000 min/month used,
resets on a monthly billing cycle). This document describes the CircleCI
pipeline only. GitHub Actions remains the primary, previously-validated
pipeline -- see `PHASE_1_VALIDATION_REPORT.md` for that history. Nothing
in this document should be read as re-validating or superseding that
report; CircleCI has not yet completed an actual run as of this writing
(see "Status" below).

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

- **CircleCI has not yet actually run this config.** See "Status" below
  -- this document describes what the config is intended to do, not a
  completed, observed result. Connecting the repository to a CircleCI
  project (via the CircleCI web app / GitHub App installation) is a
  one-time action that has to happen in CircleCI's own UI; it is not
  something a `git push` alone can do, and it was not performed as part
  of this task (see "Blocked step" below).
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

## Blocked step: triggering an actual CircleCI run

This task's environment (the sandbox used to inspect and edit this
repository) can reach `github.com` to clone, diff, and commit, but its
network egress is not configured for `circleci.com` / CircleCI's API --
so an actual pipeline run could not be triggered or observed from here.
Separately, and regardless of that: a brand-new CircleCI *project*
generally has to be connected once through CircleCI's own web app (or
GitHub App installation) before it will run pipelines for a repository at
all -- pushing a `.circleci/config.yml` to a repo CircleCI doesn't know
about yet does not, by itself, start builds. Both of these are the reason
"CIRCLECI STATUS" below is `BLOCKED` rather than `RUNNING`, `PASS`, or
`FAIL`: this is an honest "not executed", not a claimed pass.

## Do not read this as Phase 1 (re-)validation

Per this task's own instructions: this document does not modify or
supersede `PHASE_1_VALIDATION_REPORT.md`, and does not claim CircleCI
passed. It will need a real, completed CircleCI run before any pass/fail
claim can be made here.
