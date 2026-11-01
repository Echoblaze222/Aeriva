# AERIVA release checklist

Status: DRAFT. It lists what stands between the current `main` and an upload to Google Play, in the order to do it. Each line says who must act and how to tell it is done. Nothing here has been done unless it says so.

State of `main` when written: `f5b42f7`, CircleCI green on all four jobs (build, static checks, unit tests, instrumented tests) on that commit.

## 1. Decisions only the owner can make

| Item | What is needed | Done when |
|---|---|---|
| Brand | Send `AERIVA_Brand_Guidelines.md` and the identity documents, or confirm none exist. Pick between the UI skill palette and `colors.xml` (see `BRAND_SIGNOFF_ITEMS.md`, branch `design/advisor-ui-and-brand-signoff`). | Brand items closed in writing. |
| Privacy contact and hosting | A contact email and a public URL for the privacy policy. | `PRIVACY_POLICY.md` filled in and hosted. |
| Release signing | Create the upload key and enroll in Play App Signing. This needs the owner's Google Play developer account. | A keystore the owner holds, never committed to the repository. |
| Version | Decide the first released `versionName` and `versionCode` (now 0.1.0 and 1 in `app/build.gradle.kts`). | Numbers agreed. |

## 2. Work that can be done without a decision

| Item | Who | Done when |
|---|---|---|
| Real device pass of Home, Settings, About, Share status and the welcome card | Owner on a phone, using the checklist in section 11 of `ADVISOR_UI_SPEC.md` | A written result per item, including phone model and Android version. |
| Advisor recommendation test: connect to a Wi-Fi with no internet with mobile data on, wait for the suggestion, tap Open network settings | Owner | The card shows the suggestion and the button opens settings. |
| TalkBack, large font size, animations off | Owner | Each checked on at least one phone. |
| Merge the NetworkState VPN fix (`phase-4-reducer-stale-callback-fix`) | Needs the conflict in `MainActivity.kt` resolved, see section 4 | Branch merged and CircleCI green on `main`. |

## 3. Release build steps (once signing exists)

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Build a signed release bundle. The release build type currently has `isMinifyEnabled = false`. Turning on code shrinking is a separate change that needs its own device test and is not recommended for a first upload.
3. Open the merged release manifest and confirm it declares only `ACCESS_NETWORK_STATE`, and that the debug probe activity, `PACKAGE_USAGE_STATS` and the package visibility entry are absent.
4. Install the release build on a real phone and run the section 2 checks again on that exact file.
5. Upload to an internal testing track first. CHECK the Console for the current review and testing requirements for new developer accounts, which this checklist did not verify.

## 4. Known open item: the NetworkState VPN fix

Branch `phase-4-reducer-stale-callback-fix` (head `37096676d31a`, CircleCI green on its own) adds the VPN underlying network to `NetworkState` and updates its tests. It conflicts with `main` in exactly one file, `app/src/main/kotlin/com/aeriva/app/MainActivity.kt`. Cause: the branch added a test hook, `app.uiStateOverride ?: app.container.homeState.uiState`, in `onStart`, and `main` has since added more jobs to the same method.

Resolution to apply by hand in `onStart`: use the override for the Home state only,

    val app = application as AerivaApplication
    val container = app.container
    val homeUiState = app.uiStateOverride ?: container.homeState.uiState
    collectJob = uiScope.launch { homeUiState.collect { render(it) } }

and keep the advisor, networks, history and welcome jobs as they are on `main`. After merging, run CircleCI on `main` again. Not done, and not tested on the merged result.

## 5. Not in the first release

- Data Usage (waits for real device probe results and a decision on the usage access permission and Play policy).
- Advisor A2 speed and delay measurement (needs the decisions in `MEASUREMENT_ENDPOINT_AND_CLIENT_RESEARCH.md`, branch `docs/measurement-endpoint-research`, including a new permission).
