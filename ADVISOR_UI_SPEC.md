# Network Advisor A1, Home card UI specification

Branch `design/advisor-ui-and-brand-signoff`. Owner: Agent 6 (design and UX).
Documentation and resource drafts only. No Kotlin, layout, manifest or Gradle file is changed.

## 1. Status (read this first)

| Item | Status |
|---|---|
| This document | DESIGN ONLY. Nothing here is wired to a screen. |
| `values/strings_advisor.xml`, `values/colors_advisor.xml` | DRAFT resources. No layout or code references them. Not built by CI in a way that proves anything beyond "the XML is valid and merges". |
| Contrast ratios in section 7 | Computed by this agent with the WCAG relative luminance formula from the hex values in `colors.xml`. Not measured on a device. |
| Behavior on real devices, TalkBack, font scaling, Battery Saver | NOT VERIFIED. No device or emulator was used. |
| Brand voice | Derived from the shipped strings and `.claude/06-ui-ux/SKILL.md`. The brand guideline documents named in the brief were not found (section 2), so conformance to them is UNCONFIRMED. |

Evidence labels used below: CONFIRMED (read in the repository or in current official documentation), LIKELY (reasoned, not directly confirmed), UNCONFIRMED (could not be checked).

## 2. Inputs read, and inputs missing

Read:
* `.claude/00-core-engineering`, `.claude/06-ui-ux`, `.claude/16-task-execution`, `.claude/17-documentation-standards` at `f53134c`.
* `NETWORK_ADVISOR_PHASE_A_SPEC.md` from branch `network-advisor-phase-a-spec`, especially sections 5.3 to 5.5.
* Existing `app/src/main/res/**` and `HomeUiText.kt` at `f53134c`, to match existing patterns.
* `AdvisorOutcome.kt` on branch `feature/advisor-a1-core` @ `6a2ad0e` (read only, not merged). The state mapping in section 3 depends on it.

Not found (CONFIRMED absent): `AERIVA_Brand_Assets_Production/Brand/AERIVA_Brand_Guidelines.md` and `AERIVA_BRAND_IDENTITY_AND_LOGO_DESIGN.md`. I searched `main` @ `9cff60c` and every remote branch for file names containing brand, logo or identity. Nothing exists. Those files are probably only in a local checkout. Consequence: every statement here about brand rules rests on the repository (strings, colors, UI skill, spec section 5.5), not on the guideline documents. The Team Leader should send them, or confirm that section 5 copy and section 9 colors are acceptable as they stand.

Base note: the brief names `f53134c` as base. The branch tool available to me could only branch from a named branch, so this branch was created from `main` @ `9cff60c`. CONFIRMED by diff: `f53134c..9cff60c` adds two test files under `network/monitor/src/test` (PR #4) and nothing else. No file I touched overlaps. The leader can cherry pick my commits onto `f53134c` if a strict base is required.

## 3. State model

The card has six states (spec 5.5). The spec's seventh item, "permission not required", is not a state: A1 asks for no permission, so there is nothing to show and no copy for it.

The mapping from the A1 core result to a card state is below. `AdvisorOutcome` and `NoRecommendationReason` are read from `feature/advisor-a1-core` @ `6a2ad0e` (CONFIRMED at that commit). If those names change, this table changes.

| Card state | Source in A1 core | Headline | Message |
|---|---|---|---|
| Recommendation shown | `Recommend` | `advisor_recommend_headline` | `advisor_recommend_message`, `advisor_recommend_basis`, action |
| Idle | `CURRENT_NETWORK_VALIDATED`, `NO_VALIDATED_ALTERNATIVE`, `CONDITION_NOT_STABLE_YET`, `RECENTLY_SHOWN` | `advisor_idle_headline` | `advisor_idle_message` |
| Single network | `ONLY_ONE_NETWORK` | `advisor_single_headline` | `advisor_single_message` |
| No network | `NO_NETWORK` | `advisor_none_headline` | `advisor_none_message` |
| No network (policy) | `ALL_BLOCKED_BY_DEVICE_POLICY` | `advisor_none_headline` | `advisor_none_blocked_message` |
| Evidence insufficient | `NOT_ENOUGH_EVIDENCE_TO_COMPARE` | `advisor_insufficient_headline` | `advisor_insufficient_message` |
| Evidence insufficient (current unknown) | `DEFAULT_NETWORK_NOT_REPORTED` | `advisor_insufficient_headline` | `advisor_insufficient_unknown_current_message` |
| Evidence insufficient (VPN) | `VPN_ACTIVE_ON_CURRENT` | `advisor_insufficient_headline` | `advisor_insufficient_vpn_message` |
| Stale | Not produced by the A1 core. Comes from the Android adapter. See O1. | `advisor_stale_headline` | `advisor_stale_message` |

Decisions behind the mapping:
1. The two anti flapping reasons (`CONDITION_NOT_STABLE_YET`, `RECENTLY_SHOWN`) show the idle card. The idle message is written to be true in every case where it can appear. It does not say "no other network is working", because while the dwell timer runs another network may in fact be validated.
2. Notes (`VPN_MAY_AFFECT_COMPARISON`, `METERED_STATUS_DIFFERS`) are appended as one extra line under the message using `advisor_note_vpn` and `advisor_note_metered`. They never change the headline and are never shown as a ranking.
3. The card never shows red. `aeriva_offline` already belongs to the connection status card directly above it. Two alarms for one condition would be noise.

## 4. Card anatomy

No layout is written here. This describes what the wiring task should build, reusing existing parts.

* Container: the existing `card_background` drawable (surface `#0E1114`, 1dp stroke, 20dp radius), same padding as the status card (20dp). In the recommendation state the stroke uses `advisor_card_stroke_recommend`. That needs a second drawable, which belongs to the wiring task.
* Eyebrow: `advisor_card_title`, 12sp, silver, `textAllCaps`, same style as the existing details title.
* Indicator dot: the existing `status_dot` at 14dp, tinted per state, `importantForAccessibility="no"` as in the status card.
* Headline: 20sp bold, white, wraps, no `maxLines`.
* Message: 15sp, silver, line spacing 1.1 as in the status card, wraps.
* Basis line (recommendation state only): `advisor_recommend_basis`, 13sp, silver, directly under the message.
* Note line (when present): 13sp, silver, under the message.
* Action (recommendation state only): a text button, `advisor_recommend_action`, 16sp bold, ice blue, minimum height 48dp.
* Inline failure line (only after a failed tap): `advisor_recommend_action_failed`, 13sp, warning color.

Placement recommendation: directly below the connection status card and above the details card, because it is the only actionable element on the screen. This is a layout decision for the leader (O2).

## 5. Copy per state

All strings are in `values/strings_advisor.xml`. Argument order is fixed: `%1$s` is the suggested network, `%2$s` is the current network.

### 5.1 Recommendation shown

| Element | Text |
|---|---|
| Headline | Try %1$s instead |
| Message | %2$s has no confirmed internet right now. %1$s does. |
| Basis | Based on Android's own connectivity check |
| Action | Open network settings |
| Example | "Try Mobile data instead" / "Wi-Fi has no confirmed internet right now. Mobile data does." |

The basis line is the exact wording fixed by leader decision L3. There is no confidence tier, no number, no "High".

Why "no confirmed internet" and not "no working internet" (the spec 5.5 example): the platform validation flag means Android's check did not pass. It does not mean the network is unusable. The existing screen already says "Not confirmed" for the same fact (`value_internet_not_validated`). One wording for one fact.

Why "Try ... instead" and not "Switch to ...": AERIVA cannot switch anything (spec section 2). The action opens system settings, so the headline invites, it does not instruct.

Known limit: the A1 core exposes a single `RecommendationCriteria` value. The card therefore cannot tell "current network has no validated internet" from "current network reported a sign-in page". Both use the same message. If the owner wants a sign-in specific line, the core needs to expose the difference (O3).

### 5.2 Idle

Headline: No suggestion right now.
Message: Network Advisor speaks up when the current network has no confirmed internet and another network does.

### 5.3 Single network

Headline: One network available.
Message: Android reports one network, so there is nothing to compare.

### 5.4 No network

Headline: No network to compare.
Message: Network Advisor needs at least two networks. None is available right now.
Policy variant message: A device policy blocks every available network, so there is nothing to suggest.

### 5.5 Evidence insufficient

Headline: Not enough information to compare.
Message (two or more validated): More than one network passed Android's check. AERIVA does not measure speed or delay in this version, so it cannot say which is better.
Message (current unknown): Android has not said which network is in use, so Network Advisor cannot compare.
Message (VPN): A VPN is active on the current network, so a comparison of networks may not reflect how the VPN performs.
Optional note, metered status differs: Some of these networks are metered and some are not.

The "does not measure ... in this version" sentence is true only while A2 does not exist. It must be reviewed when A2 ships (R-UI-1).

The metered note is generic because `AdvisorNote.METERED_STATUS_DIFFERS` does not say which network is metered. Naming them ("Wi-Fi is not metered, mobile data is metered", as in spec 5.3 rule 4) needs candidate details passed to the UI (O3).

### 5.6 Stale

Headline: Suggestions paused.
Message: The list of networks may be out of date, so Network Advisor is not suggesting anything.
Behavior: any recommendation is withdrawn immediately. No action button.

### 5.7 Naming candidates

Use the existing `transport_*` strings. When two candidates share a transport, use `advisor_network_label_numbered` ("Mobile data 1", "Mobile data 2", "Wi-Fi 2"), as spec 5.1 requires. Requirements for the wiring task:
* Numbers come from order of first appearance in the current session and stay fixed while that candidate exists. If numbers were reassigned when a candidate disappears, "Mobile data 1" could refer to a different SIM from one minute to the next and the card would mislead.
* Numbers are never persisted and never derived from carrier, SIM or SSID.
* A single candidate of a transport is never numbered.

## 6. Accessibility

State completeness (from the UI skill) for the one tappable element, the action:

| Condition | Behavior |
|---|---|
| Idle | Visible, ice blue text, ripple on press. |
| Pressed | Default platform ripple. |
| Processing | The tap starts a system activity and returns immediately. No spinner. Ignore taps while the launch call is in flight so a double tap cannot open settings twice. |
| Success | The system settings screen appears. The card does not change. |
| Failure (no activity can handle the intent) | Show `advisor_recommend_action_failed` under the button, warning color, polite live region. Clear it on the next card state change. |
| Unavailable / offline | Not applicable. Opening settings works offline. |
| Cancellation | The user returns with Back. The card shows whatever the current state is. |
| Permission required | None. |

Screen reader labels:
* Do not make the whole card one focus target. TalkBack then reads eyebrow, headline, message, basis and note in order, and the button as its own target.
* The eyebrow is stored in sentence case and uppercased by the layout, so TalkBack reads "Network Advisor", not letters.
* The indicator dot is hidden from accessibility (`importantForAccessibility="no"`), as the status card already does. State is carried by the headline text, never by color alone.
* Headline gets `accessibilityLiveRegion="polite"`, matching the status card. Risk: two live regions on one screen can announce at the same moment when a network changes. Recommend that only the advisor headline of the Recommendation state and the Stale state announce, and the other states update silently (O4). This needs a TalkBack check on a device.
* Action: the visible text is the label. Add `advisor_recommend_action_hint` as the hint so the user knows AERIVA does not change the connection. Tap target minimum 48dp by 48dp (Android accessibility guidance, LIKELY, not re fetched in this session).
* Text uses `sp` everywhere and must wrap. No fixed card height, no `maxLines`, so 200 percent font scale stays readable. NOT VERIFIED on a device.
* Focus order: status card, advisor card, details card, matching visual order.

## 7. Contrast checks

Method: WCAG relative luminance, ratio = (L1 + 0.05) / (L2 + 0.05). Thresholds: 4.5:1 for normal text (SC 1.4.3), 3:1 for large text and for user interface components and graphics (SC 1.4.11). Source: W3C Understanding SC 1.4.3 and 1.4.11 (w3.org/WAI/WCAG21/Understanding/contrast-minimum.html, w3c.github.io/wcag/understanding/contrast-minimum). Ratios below were computed by me. CONFIRMED arithmetic, not measured on a screen.

Card surface is `aeriva_surface` `#0E1114`.

| Use | Foreground | On surface | Needs | Result |
|---|---|---|---|---|
| Headline | white `#F4F6F8` | 17.48 | 3 (large) | Pass |
| Message, basis, note, eyebrow | silver `#B4BBC5` | 9.79 | 4.5 | Pass |
| Action text | ice blue `#8FD3FF` | 11.66 | 4.5 | Pass |
| Failure line | warning `#F2B84B` | 10.58 | 4.5 | Pass |
| Dot, recommendation | ice blue `#8FD3FF` | 11.66 | 3 | Pass |
| Dot, neutral states | unknown `#8A9099` | 5.89 | 3 | Pass |
| Dot, stale | warning `#F2B84B` | 10.58 | 3 | Pass |

Screen background black `#000000` gives higher ratios for every pairing above (silver 10.86, ice blue 12.93, unknown 6.53).

Finding, CONFIRMED arithmetic: the card stroke `#1E242B` against the surface `#0E1114` is 1.21:1. This is below 3:1. It is acceptable only because the stroke is decoration: the card is identified by its text, not by its outline. Do not rely on the outline alone to show the recommendation state. In that state the stroke is ice blue (11.66:1 on the surface), and the headline text changes too.

The UI skill palette (`#0A0A0A`, `#C0C6D1`, `#5EC8FF`) also passes every row (silver 11.04, ice blue 10.08 on `#0E1114`). The palette mismatch is therefore a brand decision, not an accessibility one. See `BRAND_SIGNOFF_ITEMS.md`.

## 8. Reduced motion

A1 needs no animation. Nothing is measured, so there is no spinner and no progress.

Rules:
1. Never animate the indicator dot, and never use a repeating or pulsing animation anywhere in this card.
2. Card content changes between states instantly. If the leader wants a short cross fade, it is optional and must be skipped when `ValueAnimator.areAnimatorsEnabled()` is false.
3. `ValueAnimator.areAnimatorsEnabled()` was added in API level 26, which equals the project minSdk, so no version guard is needed. CONFIRMED: developer.android.com/reference/android/animation/ValueAnimator and the API 26 diff at developer.android.com/sdk/api_diff/26/changes/android.animation.ValueAnimator. Per the same method's documented behavior, it returns false when the user sets the animator duration scale to 0 in developer options, and when Battery Saver disables animations. The Battery Saver statement is from a mirror of the Java documentation (learn.microsoft.com, Android bindings for the same method), CONFIRMED there, not re checked on developer.android.com.
4. No motion is used to signal urgency. A recommendation appearing is announced by the live region, not by movement.

NOT VERIFIED: behavior with animations off on a device.

## 9. Resource drafts

Files, both new, both with `tools:ignore="UnusedResources"` so lint stays quiet until wired:
* `app/src/main/res/values/strings_advisor.xml`: 23 strings, all prefixed `advisor_`. No name collides with `strings.xml`.
* `app/src/main/res/values/colors_advisor.xml`: 13 semantic aliases of existing colors. It introduces no new hex value. If the brand sign off changes a palette value, only `colors.xml` changes and these follow.

Existing files `strings.xml` and `colors.xml` are untouched, to avoid merge conflicts with other work.

Checks run by this agent on the files: both parse as well formed XML; neither contains an em dash, an en dash or any non ASCII character; no duplicate names. Not run: Android resource merge, Android Lint, any build. Those only count through CircleCI on the exact head SHA (see the report).

## 10. Open questions for the Team Leader

| Id | Question | Label |
|---|---|---|
| O1 | What makes the candidate list stale? Network callbacks are event driven, so a long silence on a stable connection is normal and is not staleness. I found no documented heartbeat. If "stale" is defined by the age of `observedAt`, the card would pause itself on healthy, unchanging Wi-Fi. Suggest defining stale only as a reported failure of the candidate monitor (registration failure or lost upstream), tied to the Agent 1 reliability audit. | LIKELY |
| O2 | Is the card always visible after the first report, or hidden when idle or single network? I recommend always visible, so the feature is discoverable and the "nothing to suggest" answer is honest. Hiding is cleaner if the leader prefers fewer cards. | Decision |
| O3 | The A1 core result does not tell the card whether the current network showed a sign-in page, and does not name which candidate is metered. Wording that uses those facts needs the core or the adapter to pass them. | Dependency |
| O4 | Live region policy when both the status card and advisor card change at once (section 6). Needs TalkBack on a device. | Needs device check |
| O5 | The deep link target. `Settings.Panel.ACTION_INTERNET_CONNECTIVITY` exists from API level 29 (CONFIRMED, developer.android.com/reference/android/provider/Settings.Panel). The fallback for API 26 to 28 was not researched here (UNCONFIRMED, same as spec 3.1 item 7). The failure string in section 5.1 covers the case where no activity resolves. | Partly UNCONFIRMED |

## 11. Real device validation list (design side)

Required before calling the card done, per the "real device" rule in spec section 7:
1. TalkBack: reading order, the live region behavior in O4, hint on the action.
2. Font scale 130 and 200 percent: no clipping of any state, in the longest message (insufficient, VPN).
3. Display size largest: card stays inside the 24dp screen padding.
4. Animations off in developer options, and Battery Saver on: no motion.
5. Each of the six states, plus the note line, on at least one small phone and one tablet width (`values-w600dp` already narrows the content column).
6. Tap the action with settings unavailable, if a test device can simulate it, to see the failure line.
7. Record device model, Android version and OEM for each result.

## 12. Risks

| Id | Risk | Label |
|---|---|---|
| R-UI-1 | The "does not measure" sentence becomes false when A2 ships and must be rewritten then. | CONFIRMED dependency |
| R-UI-2 | Numbered labels ("Mobile data 2") could swap meaning if the adapter reassigns numbers. | LIKELY |
| R-UI-3 | Brand guideline documents were not available, so brand voice conformance is unchecked. | CONFIRMED gap |
| R-UI-4 | `advisor_recommend_message` is built from two substituted names. Translation to languages with grammatical case would need review. Only English exists today. | LIKELY |

## 13. Sources

* Repository at `f53134c` and `9cff60c`: `app/src/main/res/**`, `HomeUiText.kt`, `.claude/06-ui-ux/SKILL.md`, `.claude/00-core-engineering/SKILL.md`, `.claude/17-documentation-standards/SKILL.md`.
* `NETWORK_ADVISOR_PHASE_A_SPEC.md`, branch `network-advisor-phase-a-spec`.
* `AdvisorOutcome.kt`, branch `feature/advisor-a1-core` @ `6a2ad0e`.
* developer.android.com/reference/android/animation/ValueAnimator (areAnimatorsEnabled, API 26).
* developer.android.com/reference/android/provider/Settings.Panel (ACTION_INTERNET_CONNECTIVITY, API 29).
* w3.org/WAI/WCAG21/Understanding/contrast-minimum.html and the SC 1.4.11 Understanding document.
