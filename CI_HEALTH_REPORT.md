# CI health report

Author: Agent 4 (CI, quality and repository hygiene). Date of evidence: 2026-10-25.
Labels follow leader decision L7: CONFIRMED, LIKELY, UNCONFIRMED.

Base note: the brief names `f53134c` as the base. `main` has since moved to `9cff60c` (PR #4, test-only: 2 new test files). This branch was created off `main` @ `9cff60c` because the branch tool cannot target an arbitrary SHA, and every other agent branch in the repository already sits on `9cff60c`. The change set is two new Markdown files, so the base difference has no effect on the content.

## 1. Root cause of the GitHub Actions failure at "Set up Android SDK"

### Verdict

UNCONFIRMED. The failing run log could not be read in this session, so no line of the actual error is available. The best supported explanation is LIKELY: an incompatibility between `android-actions/setup-android@v3` and the current `ubuntu-latest` image, not minutes exhaustion.

### What blocked confirmation

- The GitHub REST API from the agent sandbox is unauthenticated and rate limited (HTTP 403, `core` remaining 0 of 60). CONFIRMED.
- No Actions run or log tool is available to this agent. CONFIRMED.
- No credential was looked for or used (brief rule 8).

### Evidence for and against each candidate

| Candidate | Evidence | Label |
|---|---|---|
| Minutes exhaustion | `CIRCLECI_VALIDATION.md` in the repo states the free tier was at 100% of 2,000 minutes on 2026-09-15 and that this is why CircleCI was added. That is a month old and resets monthly, so it says nothing about October. A billing block normally stops a job before any step runs. The brief says the failure is at a named step, in seconds. A step level failure does not fit a billing block. This last point is reasoning, not something verified against GitHub documentation in this session. | UNLIKELY, not ruled out |
| Runner image change of 2026-10-19 | The `ubuntu-latest` label begins moving to Ubuntu 26.04 on 2026-10-19, rolling out over several weeks and due to finish by 2026-11-19 (actions/runner-images issue 14748). Every job in `.github/workflows/ci.yml` uses `runs-on: ubuntu-latest`. Because the rollout is gradual, some runs would land on the new image and some on the old one. | LIKELY as a contributing factor |
| `setup-android@v3` incompatibility with the current image | Independent reports: beeman/pocket-address-pwa PR 4 (2026-10-13) removed the step because it fails on the current `ubuntu-latest` image when `sdkmanager` cannot find the legacy `tools` package. android-actions/setup-android issue 546 (2026-09-18) reports a missing build-tools package on `ubuntu-latest`. The runner image already ships Android SDK platform 36 (runner-images Ubuntu 24.04 release 20260810 lists android-36 rev 2). These are third party reports on other repositories, not this repository's log. | LIKELY |
| Something else (network, action version, permissions) | No evidence either way. | UNCONFIRMED |

Note on dates: the third party failure report predates 2026-10-19, so the image change on that date is not the only way to reach the failure. The brief says main fails too, which fits a runner side cause rather than a code cause. CONFIRMED that CircleCI builds the identical code green (section 3).

### One step that settles it

Open the failed `build` job in the Actions tab, or run `gh run view <run-id> --log-failed`, and read the first error under "Set up Android SDK". Three outcomes:

1. Error mentions `sdkmanager`, a missing `tools` package or a license step: runner image incompatibility (matches LIKELY).
2. Job never started, annotation about payments or spending limit: minutes exhaustion.
3. Anything else: new finding, report back.

## 2. Recommendation

Recommended: keep CircleCI as the only pipeline of record, and park the GitHub Actions workflow (do not delete it).

Reasons:

- CircleCI is already green for `main` and covers the same four categories (build, unit tests, lint, emulator tests), CONFIRMED in section 3.
- Repeating a red Actions check on every PR hides real signals and burns minutes. The repository is private, so minutes are a limited resource (stated in `.github/workflows/ci.yml` and `CIRCLECI_VALIDATION.md`).
- Fixing Actions is possible but costs effort for no new coverage. The emulator job on `ubuntu-latest` is also the most minute hungry job.

Three options considered:

| Option | Effort | Risk | Verdict |
|---|---|---|---|
| Fix Actions | Pin `ubuntu-24.04`, remove the setup-android step, re-validate 4 jobs | Not verified to work, minutes still limited | Not recommended now |
| Retire Actions (delete file) | One commit | Loses a second provider and its history of comments | Not recommended |
| CircleCI only, Actions parked as manual | One small diff | Reversible | Recommended |

### Proposed diff (text only, not applied)

Parks the workflow: it stays in the repository, runs only on demand.

```diff
--- a/.github/workflows/ci.yml
+++ b/.github/workflows/ci.yml
@@
 on:
-  push:
-    branches: [main]
-  pull_request:
   workflow_dispatch:
```

If the leader prefers to fix Actions instead, the smallest candidate change is below. It is UNCONFIRMED until run once with `workflow_dispatch`.

```diff
-    runs-on: ubuntu-latest
+    runs-on: ubuntu-24.04
@@ in every job
-      - name: Set up Android SDK
-        uses: android-actions/setup-android@v3
+      - name: Accept Android SDK licenses
+        run: yes | sdkmanager --licenses || true
```

Second change rests on the third party report that the runner ships the SDK with licenses accepted. Not verified against this repository.

If Actions is parked, the leader should also remove any required status check that names an Actions job from branch protection. Not verified, branch protection settings were not readable by this agent.

## 3. CircleCI result for `main` @ `f53134c`

Source: CircleCI API through the project tool, project `gh/Echoblaze222/Aeriva`.

- Run id: `87a63cbb-0b2d-458b-b57b-724ff9948f33`, trigger push to `main`, revision `f53134c193ee`, outcome succeeded. CONFIRMED.
- Workflow `build_test_and_validate`, id `31a89f50-eaec-4e66-a598-f04d537f432e`, started 2026-10-22 11:20:13Z, ended 11:28:24Z, wall time 8 min 11 s. CONFIRMED.
- Later run on `main` @ `9cff60cd0544` (PR #4): run id `e41c8a6e-c7b2-49da-af4d-c58c12122f95`, outcome succeeded. Its jobs were not itemized.

| Job | Job id | Outcome | Duration | Tests |
|---|---|---|---|---|
| build | `823c8cbb-ae07-4022-9264-1bbde4255111` | succeeded | 2 min 21 s | n/a |
| unit_tests | `c828a00a-7a85-4e8a-a9a1-96988e64ae47` | succeeded | 2 min 41 s | 311 tests, 0 failing, 0 skipped |
| static_checks | `d083a598-3e31-4f30-b12d-4b68b85d0172` | succeeded | 2 min 46 s | n/a |
| connected_android_test | `43362ff8-8958-4214-9a6d-0896ebe4609e` | succeeded | 5 min 42 s | 14 tests, 0 failing (skipped count not queried) |

Durations are computed from job start and end timestamps. `build` runs first, then three jobs in parallel, so the critical path is build plus connected_android_test.

Slow job: `connected_android_test` is the longest and sets the workflow time. CONFIRMED. Resource usage and per step timing were not collected.

### Flakiness

One confirmed same code divergence:

- Run `5dd90895-46c0-4185-be7e-595a8b9f8df6`, branch `feature/advisor-a1-core`, revision `9cff60cd0544` (identical to `main`), job `connected_android_test` id `1c69c67c-31b6-434a-83fe-1a664bf2cc4d` failed. `build`, `unit_tests`, `static_checks` passed.
- Failed step: `./gradlew assembleDebugAndroidTest`, task `:network:monitor:packageDebugAndroidTest`, error `A failure occurred while executing com.android.build.gradle.tasks.PackageAndroidArtifact$IncrementalSplitterRunnable`, build time 1 min 57 s, 293 of 293 tasks executed (no task was up to date).
- The same revision passed on `main` (run `e41c8a6e`). CONFIRMED divergent outcome on identical code.
- Root cause: UNCONFIRMED. No stack trace was captured because that step ran without `--stacktrace`. It ran inside the orb's `start_emulator_and_run_tests` before the test command. Whether it is a one time packaging glitch or a recurring cache or disk problem is not known from one sample.
- Observation, LIKELY relevant: the log shows Android SDK Platform 36 being downloaded and installed at job time, and no up to date tasks, so this job runs cold. The `connected_android_test` job has no `ensure_sdk_platform` or explicit cache restore step, unlike the other three jobs. The orb is given `restore_gradle_cache_prefix: v2-aeriva`, and whether it hit is not known.

Other failed runs in the project list (7 failed in total as returned by the tool), logs not read, causes UNCONFIRMED:

| Run id | Branch | Commit subject (short) | Date |
|---|---|---|---|
| `33da36e9-0a30-4881-b18f-b9207e6351cd` | feature/advisor-a1-core | feat(advisor): Network Advisor A1 pure core | 2026-10-23 |
| `5dd90895-46c0-4185-be7e-595a8b9f8df6` | feature/advisor-a1-core | branch push at `9cff60c` (flake above) | 2026-10-23 |
| `b46d758e-74ea-45f4-b251-94685fa3f233` | phase-4-reducer-stale-callback-fix | VPN mapper tests and offline recovery UI test | 2026-10-22 |
| `a17d6242-9dab-476b-a9e6-a3085aef7f14` | phase-4-reducer-stale-callback-fix | report VPN underlying transport | 2026-10-22 |
| `b149eff2-1ffb-46f4-8c03-95417dfba9b6` | phase-4-reducer-stale-callback-fix | complete state reducer contract tests | 2026-10-18 |
| `d2f09992-64b8-4388-9ddf-f12f42a78ae4` | phase-4-reducer-stale-callback-fix | regression tests, message says expected to fail before fix | 2026-10-18 |
| `9609a9ea-31a7-4230-a692-749eb3ef062a` | phase-4-test-hardening | clock driven and Unavailable pipeline tests | 2026-10-15 |

The `phase-4-reducer-stale-callback-fix` branch later went green: run `b571ccbf-74e1-4fed-8086-68009632a407` at `37096676d31a` succeeded (2026-10-25). The head of `feature/advisor-a1-core` at `6a2ad0e` was not checked here.

## 4. Suggestions for the leader (text only, nothing applied)

1. Decide Actions versus CircleCI only using section 2. Then update `CIRCLECI_VALIDATION.md`, which still calls Actions the primary pipeline.
2. Before treating `connected_android_test` red as a code failure, rerun the failed job once. A pass on the same SHA means flake, matching the sample in section 3. Record both run ids.
3. If the flake recurs, capture the stack trace by running `./gradlew assembleDebugAndroidTest --stacktrace` as its own step before the orb. Verify the orb's cache behavior against the orb documentation first. Not verified in this session.
4. CircleCI automatic rerun of failed jobs may exist as a workflow setting. UNCONFIRMED, check CircleCI documentation before adopting.

## 5. Method and limits

- Read only. No CI setting, secret or branch was changed. Nothing was deleted.
- Repository files read at `f53134c`: `.github/workflows/ci.yml`, `.circleci/config.yml`, `CIRCLECI_VALIDATION.md`, `gradle/libs.versions.toml`.
- External sources: actions/runner-images issue 14748 (ubuntu-latest to 26.04), actions/runner-images release notes for Ubuntu 24.04 20260810, android-actions/setup-android issue 546, beeman/pocket-address-pwa PR 4.
- Not available: GitHub Actions logs, branch protection settings, billing state.
