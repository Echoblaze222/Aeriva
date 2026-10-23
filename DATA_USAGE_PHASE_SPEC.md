# AERIVA Data Usage Phase Specification

Author: Agent 3 (Data usage pillar researcher)
Branch: `docs/data-usage-spec`
Status: proposal for Team Leader decision. Documentation only. No code, no manifest, no Gradle, no dependency change.
Repository facts inspected at: `f53134c193ee3511a2e4117f9210280b056ee745` (the base named in AERIVA_TEAM_BRIEFS.md).
Branch base actually used: `main` @ `9cff60cd0544c7586fc1aacacd9261a3f3ef824e` (see "Base deviation" below).

## Base deviation (reported, not hidden)

The briefs require a branch off `f53134c`. The GitHub connector available to this agent can only create a branch from a named branch, not from a SHA. `main` has since moved to `9cff60c` (merge of PR #4, two added test files under `network/monitor/src/test`). This branch therefore starts at `9cff60c`. The single commit on it adds one file, `DATA_USAGE_PHASE_SPEC.md`. All repository facts quoted below were read at `f53134c`, and PR #4 touches none of them. If the Leader wants the strict base, the one commit can be cherry-picked onto `f53134c` without conflict.

## 0. How to read this document

Every claim is labeled.

| Label | Meaning |
|---|---|
| CONFIRMED | Stated in an official Android, AOSP or Google Play page read during this task (source id in brackets), or observed in this repository at the SHA above. |
| LIKELY | Follows from CONFIRMED facts, or rests on a mirror or secondary source, or is standard practice the official pages imply but do not state. Needs device validation before it is relied on. |
| UNCONFIRMED | Not found in official documentation, or only a real device can answer it. Not to be relied on. |

Source ids are listed in section 10. Source quality is noted there. Where a statement rests only on a mirror of the official Javadoc, it is labeled LIKELY.

Nothing here was run. No Gradle build, no CircleCI run, no emulator, no real device was used. Every behavioral statement about Android is a documentation reading, not a test result. Section 6.5 is the plan that turns readings into evidence.

## 1. Summary and the smallest honest first version

What Android lets a normal third party app do:

1. Read device wide and per app byte counts, split by mobile and Wi-Fi, in buckets that are hours long, if and only if the user grants Usage access in system Settings. [S1, S2] CONFIRMED
2. Observe, but not control. No public API found that lets a normal app restrict another app's network access. AERIVA can open system screens where the user does that. [S5, S6] LIKELY
3. It cannot see per SIM usage, per domain usage, packets by content, carrier balance or carrier plan. [S1, S9, S10] CONFIRMED or LIKELY, see 2.4.

Recommended first version (v1), the smallest version that answers the product questions without claiming more than Android provides:

- Permission flow for Usage access, with an honest denied state. Unrelated AERIVA features stay usable.
- On demand sync (when the Data Usage screen opens or the app returns to the foreground). No background worker, no notification, no new dependency.
- Device totals for mobile and Wi-Fi, current cycle and recent days.
- Per app ranking for mobile only, with a foreground versus "not in foreground" split as Android classifies it.
- One user entered mobile budget with a monthly reset day. Observed percent used. Warning thresholds shown in the app only.
- One explainable pace forecast that says "not enough history yet" when that is the truth.
- Three explainable waste rules that report observed bytes and never a savings figure.
- Links to Android's own data usage and app settings screens. No claim that AERIVA blocked or limited anything.
- Storage: Room for daily aggregates (database version 1 to 2, tables only added), DataStore for budget settings.

The main product risk is not the byte counting. It is turning a UID into an app name and icon under package visibility filtering (2.5). That decides whether per app ranking is useful, and it needs a real device answer before the per app screen is built.

## 2. Platform capabilities (deliverable 1)

### 2.1 API inventory

| API | What it gives | Permission or access | Label | Source |
|---|---|---|---|---|
| `NetworkStatsManager.querySummaryForDevice(type, subscriberId, start, end)` | One bucket: total bytes for the whole device over the interval, per network type | `PACKAGE_USAGE_STATS` (user grants Usage access) | CONFIRMED | S1 |
| `NetworkStatsManager.querySummary(...)` | Buckets per UID over the interval. Aggregated over time, so one bucket per UID, state, metered and roaming combination. This is the only query that returns the foreground versus default state split | `PACKAGE_USAGE_STATS` for UIDs other than the caller | CONFIRMED | S1 |
| `NetworkStatsManager.queryDetails(...)` | History: multiple time buckets per UID. Aggregated over state, metered and roaming, so state is `STATE_ALL` | same | CONFIRMED | S1 |
| `NetworkStatsManager.queryDetailsForUid(...)` | Same as above for one UID | same | CONFIRMED | S1 |
| `NetworkStatsManager.registerUsageCallback(...)` | Callback when usage on a network passes a byte threshold. Exists since API 24 | not established | LIKELY exists, permission and process death behavior UNCONFIRMED | S13 (mirror) |
| `NetworkStats.Bucket` fields | uid, tag, state, metered, roaming, default network status, start and end timestamps, rx and tx bytes, rx and tx packets. Statistics are measured at the network layer, so TCP and UDP are both included | none beyond the query | CONFIRMED | S2 |
| `TrafficStats` (`getMobileRxBytes`, `getTotalRxBytes`, ...) | Device counters since boot. They reset to zero after every reboot. The class itself points to `NetworkStatsManager` for robust history | none | CONFIRMED | S3 |
| `ConnectivityManager.getRestrictBackgroundStatus()` | Data Saver status as it applies to the calling app (AERIVA itself): disabled, enabled, or allowlisted | none | CONFIRMED | S6 |
| `ConnectivityManager.isActiveNetworkMetered()` | Whether the active network is metered | `ACCESS_NETWORK_STATE`, already declared by `:network:monitor` | CONFIRMED (API) and CONFIRMED (repo manifest comment) | S6, repo |

Notes that change the design:

- Foreground versus background is not a literal platform state. The bucket state constants are `STATE_ALL`, `STATE_DEFAULT` ("usage not accounted for in any other state") and `STATE_FOREGROUND` ("foreground usage"). [S2] CONFIRMED. AERIVA must therefore say "while the app was not in the foreground, as Android classifies it", never "in the background". Whether `STATE_DEFAULT` contains anything other than background use is UNCONFIRMED.
- The state split exists only in summary queries. History queries return `STATE_ALL`. [S1] CONFIRMED. A per day foreground split therefore needs one summary query per day window (see 4.4).
- Bucket length is "in the order of hours". Queries include only buckets that occur atomically inside the requested range and do not interpolate across partial buckets. So the API cannot measure fine grained time. [S1] CONFIRMED for the details queries. The same no-interpolation behavior for summary queries is LIKELY, not stated in the text read.
- Queries may take several seconds and must run on a worker thread. [S1] CONFIRMED.
- A query can return null or throw when permission is insufficient or collection failed, and can throw `SecurityException` or `RemoteException`. [S1] CONFIRMED.
- The `networkType` argument takes `ConnectivityManager` constants such as `TYPE_MOBILE` and `TYPE_WIFI`. [S1] CONFIRMED.

### 2.2 Permissions and special access

| Item | Fact | Label | Source |
|---|---|---|---|
| `PACKAGE_USAGE_STATS` | System level permission, not grantable to third party apps by normal means. Declaring it signals intent, and the user grants access through Settings (Usage access) | CONFIRMED | S1 |
| Own app's data | An app can read data about itself without the permission from API 24. Before API 24 the permission was needed even for own data. AERIVA's minSdk is 26 | CONFIRMED | S1, repo `core/preferences/build.gradle.kts` |
| Device wide totals | `querySummaryForDevice` requires `PACKAGE_USAGE_STATS` | CONFIRMED | S1 |
| Other apps' data | Requires `PACKAGE_USAGE_STATS` | CONFIRMED | S1 |
| Profile owner apps | Automatically granted access for the profile they manage, except `querySummaryForDevice` | CONFIRMED | S1 |
| Special access behaves as an app-op permission | The platform says the security check for app-op permissions defaults to the permission grant state, and the app-op state is checked instead when it is `MODE_ALLOWED` or `MODE_IGNORED`. Practical consequence: a correct "is it granted" check must handle `MODE_DEFAULT` by falling back to the permission grant state | LIKELY (rule is stated, the usage access specific handling is inferred) | S4 |
| Access state check | `AppOpsManager.OPSTR_GET_USAGE_STATS` exists. `unsafeCheckOpNoThrow` is the non-tracking check | CONFIRMED (constants), exact call pattern LIKELY | S4 |
| Opening the grant screen | `Settings.ACTION_USAGE_ACCESS_SETTINGS` | CONFIRMED | S5 |
| Deep link to AERIVA's own row via a `package:` URI | Not found in the pages read | UNCONFIRMED | |
| Settings side explanation | An activity with category `INTENT_CATEGORY_USAGE_ACCESS_CONFIG` can declare `METADATA_USAGE_ACCESS_REASON` meta-data that Settings shows to the user. The platform calls this optional but recommended for apps using the permission | CONFIRMED text, from a mirror of the Javadoc, so LIKELY | S5 |
| Mobile `subscriberId` | From API 29 the identifier is guarded. Apps that do not meet the new requirements may pass null for the mobile type to get all mobile networks. From API 31 an app can pass a subscriberId with the Wi-Fi type, otherwise null for all Wi-Fi | CONFIRMED | S1 |
| Who can read `getSubscriberId` | Only apps with `READ_PRIVILEGED_PHONE_STATE`, carrier privileges, device or profile owner with `READ_PHONE_STATE`, default SMS role holder or `USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER`. Apps delivered through Google Play need carrier privileges | CONFIRMED | S9 |
| No `READ_PHONE_STATE` needed | Because AERIVA passes null, no phone permission is required | LIKELY | S1, S9 |

### 2.3 API level differences relevant to AERIVA (minSdk 26, compileSdk 36)

| API level | Behavior | Label | Source |
|---|---|---|---|
| 23 | `NetworkStatsManager` introduced. All calls needed the permission, even for own data | CONFIRMED | S1 |
| 24 | Own app data readable without the permission. Roaming and tag fields. Usage callbacks | CONFIRMED (own data), LIKELY (callbacks, mirror) | S1, S13 |
| 28 | Default network status field on buckets | CONFIRMED | S2 |
| 29 | `subscriberId` access restricted. Null subscriberId on the mobile type returns all mobile networks | CONFIRMED | S1, S9 |
| 30 | Package visibility filtering applies to apps targeting API 30 or higher | CONFIRMED | S7 |
| 31 | Wi-Fi queries may take a subscriberId | CONFIRMED | S1 |
| 33 and later | Any additional change affecting these APIs (for example notification permission affecting alerts, new restrictions on special access for sideloaded installs) | UNCONFIRMED. Not found in the pages read. Covered by the validation matrix | |
| Metered field added-in level | The field exists in the current reference. The added-in level was not captured | UNCONFIRMED | S2 |

Because minSdk is 26, every behavior listed from API 24 upward is present on every supported device. The only per version branching needed is the `subscriberId` handling (always null in v1) and the access check call (`unsafeCheckOpNoThrow` on API 29 and above, the older check below that, per the deprecation noted in secondary material, LIKELY).

### 2.4 What is impossible or not available (and what is unverified)

| Item | Statement | Label | Source |
|---|---|---|---|
| Other apps' usage without Usage access | Impossible. Only the caller's own data is readable by default | CONFIRMED | S1 |
| Per SIM split on a Play distributed app | Not available. The SIM identifier requires carrier privileges or system level access. AERIVA can only show "all mobile data" | LIKELY | S1, S9 |
| Sub hour granularity | Impossible through these APIs. Buckets are hours long | CONFIRMED | S1 |
| Per domain, per connection, per content breakdown | Not offered. The bucket has no such field. AERIVA must not claim it | LIKELY (absence read from the field list) | S2 |
| Reading the carrier plan, balance or billing cycle | `SubscriptionManager.getSubscriptionPlans` describes the carrier and subscriber billing relationship, but is a carrier managed surface. Whether a normal app can read it was not established | UNCONFIRMED (likely restricted) | S10 |
| Reading the Android system data warning and limit values | No public API found | UNCONFIRMED | |
| Platform history depth | No official statement of how far back history is kept was found | UNCONFIRMED. Must be measured (T14) | |
| Device totals equal the sum of per app rows | Not guaranteed. The platform says usage by tethering, removed apps, removed users and the system is included only for callers with higher levels of access. Special UIDs exist for removed apps and tethering | CONFIRMED (special UIDs), LIKELY (sum mismatch for a normal caller) | S1, S2 |
| Agreement with the carrier's own meter | Not guaranteed. The platform measures at the network layer on the device. The carrier counts on its side | LIKELY | S2 |
| Traffic through a VPN app: attribution and double counting | Not established | UNCONFIRMED. Test T10 | |
| Android's `TrafficStats` per UID for other apps | Not used here. The class documentation read describes counters since boot that reset on reboot. Whether other UIDs are readable on API 24 and above was not established | UNCONFIRMED | S3 |

### 2.5 Mapping a UID to an app name and icon (package visibility)

- When an app targets API 30 or higher, the system filters what it can learn about other installed apps. Some packages are visible automatically. Others need a `<queries>` declaration. `QUERY_ALL_PACKAGES` exists for rare cases and its use on Google Play is subject to approval. [S7, S8] CONFIRMED
- Google Play treats the installed app inventory as personal and sensitive information. `QUERY_ALL_PACKAGES` needs a core purpose justification and a declaration. [S8] CONFIRMED
- Per app rows are keyed by UID. Resolving a UID to a label goes through `PackageManager`, so it is subject to the same filtering. That a Usage access grant widens visibility was not found. That unresolved UIDs will exist for third party apps is LIKELY.
- A UID can map to more than one package (shared UID). UNCONFIRMED how common on the target devices.

Design consequence (decision DU-1, section 7):

- v1 resolves a name only when the system lets it, and otherwise shows a neutral "Another app" row with the UID kept internally. It never fabricates a name.
- v1 does not declare `QUERY_ALL_PACKAGES`.
- Whether a `<queries>` intent declaration (launcher apps) is enough, and whether Play accepts it without a declaration, is UNCONFIRMED and goes to the device test T6 before any per app UI is designed.

### 2.6 History retention and granularity

- Granularity: hours. CONFIRMED [S1]
- Retention: UNCONFIRMED. The design does not assume any depth. It measures the oldest bucket it can read, stores that value, and refuses to present a full cycle total as complete when the cycle start predates what Android still holds (4.4, 5.1).
- Because AERIVA sync re-queries the platform for the recent past on every sync, a gap in AERIVA visits does not by itself create a gap in AERIVA history, as long as the gap is shorter than platform retention. LIKELY

## 3. Monitoring versus control (deliverable 2)

### 3.1 What AERIVA can only observe, and what it can legitimately link to

| Capability | AERIVA can | Label | Source |
|---|---|---|---|
| Show how much data was used, by transport, by app, in the foreground or not | Observe | CONFIRMED | S1, S2 |
| Show Data Saver status for AERIVA itself | Observe | CONFIRMED | S6 |
| Notice that Data Saver status changed | Observe, by a dynamically registered receiver for `ACTION_RESTRICT_BACKGROUND_CHANGED`. The system does not deliver this to manifest declared receivers | CONFIRMED (from a localized copy of the guide) | S6 |
| Open Android's data usage and settings screen | Link, with `Settings.ACTION_DATA_USAGE_SETTINGS` | CONFIRMED | S5 |
| Open an app's details screen so the user can restrict it | Link, with `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` and a `package:` URI. Whether a data usage entry is reachable from there is OEM dependent | CONFIRMED (action), UNCONFIRMED (reachability) | S5 |
| Open the per app background data restriction screen | `ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS` is documented for an app asking about its own background data. Using it for another package is not documented | CONFIRMED for own app, UNCONFIRMED for others. Do not rely on it | S5, S6 |
| Restrict, block, throttle or cap another app's network access | Cannot, with the public APIs found. Only the user can, in Android settings | LIKELY | S5, S6 |
| Stop mobile data at a budget | Cannot. A carrier plan can behave that way, but that is the carrier and the system, not AERIVA | CONFIRMED for the plan concept (`LIMIT_BEHAVIOR_*` constants describe carrier behavior), CONFIRMED for AERIVA's inability by design | S10 |
| Route traffic through a local VPN to filter it | Out of scope for this pillar. It would be a separate product decision, a new permission and user consent flow, and L6 forbids it without a brief | not researched | |

Rules for settings links: a matching activity might not exist on a device, so every launch must catch the failure and show an instruction fallback instead of crashing. [S5] CONFIRMED (the reference says a matching activity may not exist for several actions).

### 3.2 Budget wording: a warning is not a limit

An AERIVA budget is a number the user typed. It is not the carrier's cap and does not stop data. The UI calls it a "budget" or "alert level", never a "limit", and states that it only alerts inside AERIVA.

### 3.3 Copy rules (input to Agent 6, not final copy)

| Allowed (when true) | Forbidden |
|---|---|
| "Android reports ..." | "Blocked", "stopped", "limited", "restricted" as something AERIVA did |
| "While the app was not in the foreground, as Android classifies it" | "Background data" as a literal fact |
| "This is an estimate based on your last N days" | "You will run out on <date>" without "around" and "estimate" |
| "AERIVA cannot block data. You can restrict this app in Android settings." | Any saved or recovered megabyte figure that was not measured before and after |
| "Usage access is off, so AERIVA cannot read app usage" | "Your carrier balance" or "remaining plan" |
| "Android's count can differ from your carrier's count" | Names for apps AERIVA could not resolve |

No em dash and no emoji in any of it (core engineering rules 11 and 12).

## 4. Data model (deliverable 3)

### 4.1 Principles

- Detailed personal usage stays on the device. The manifest has no `INTERNET` permission and `allowBackup="false"`. [repo `app/src/main/AndroidManifest.xml`] CONFIRMED. v1 adds no backend field of any kind.
- Package names and UIDs describe the user's installed apps, which Play treats as sensitive. [S8] CONFIRMED. They are stored locally only and are never written to logs.
- Room holds history. DataStore holds small settings. DataStore is not used for history. [repo `.claude/09-database-preferences/SKILL.md`] CONFIRMED
- No fake data. Absent data is represented as absent.
- Completed days are immutable unless an algorithm version change deliberately recomputes them.

### 4.2 Room (module `:core:database`)

Existing state at `f53134c`: `AerivaDatabase` version 1, `exportSchema = true`, one entity `NetworkStateHistoryEntity`, schema directory `core/database/schemas`, Room 2.8.4, `room-testing` already an androidTest dependency, repository pattern with `pruneOlderThan`, no destructive fallback. [repo] CONFIRMED

Proposed new tables (names indicative, final naming belongs to the implementer):

`data_usage_day` (one row per transport per local day, device wide totals and coverage)

| Column | Type | Notes |
|---|---|---|
| transport | text | `CELLULAR` or `WIFI`, same spelling as `core:model` `TransportType` |
| dayEpochDay | integer | days since 1970-01-01 in the zone recorded in `zoneId` |
| zoneId | text | zone used to cut the day, so a time zone change cannot silently shift history |
| rxBytes, txBytes | integer | from `querySummaryForDevice` over the day window |
| complete | integer (bool) | true only when the sync that covered the day ran at least `closeMargin` after the day ended |
| coverageStartEpochMillis, coverageEndEpochMillis | integer | the window actually queried |
| syncedAtEpochMillis | integer | |
| algorithmVersion | integer | lets a later release recompute without guessing |

Primary key: (transport, dayEpochDay).

`data_usage_app_day` (per UID rows, mobile in v1)

| Column | Type | Notes |
|---|---|---|
| transport | text | |
| dayEpochDay | integer | |
| uid | integer | includes the special values `UID_REMOVED` (-4) and `UID_TETHERING` (-5) as returned [S2]. Never ranked as apps |
| fgState | text | `FOREGROUND` or `NOT_FOREGROUND` (from `STATE_DEFAULT`) |
| rxBytes, txBytes | integer | |
| packageNameSnapshot | text, nullable | first package for the UID at sync time, null when the system did not resolve it. Local only |

Primary key: (transport, dayEpochDay, uid, fgState). Indexes: (transport, dayEpochDay) and (uid), justified by the two queries the rules need (all apps for a day range, one app over a baseline window). A day row and its app rows are written in one transaction so the UI never sees a complete day with missing app rows.

An absent app row on a day whose `data_usage_day.complete` is true means zero observed use that day. An absent app row on an incomplete or missing day means unknown. The rules in section 5 depend on that distinction.

`data_usage_sync_state` (one row per transport)

| Column | Type | Notes |
|---|---|---|
| transport | text, primary key | |
| lastAttemptEpochMillis, lastSuccessEpochMillis | integer, nullable | |
| lastFailureKind | text, nullable | an enum name only: `PERMISSION`, `UNAVAILABLE`, `NULL_RESULT`, `SECURITY`, `REMOTE`, `UNKNOWN`. No stack, no package names |
| oldestObservedBucketStartEpochMillis | integer, nullable | the measured platform retention floor for this device |
| retentionProbedAtEpochMillis | integer, nullable | |

Row volume formula, not a measured number: per app rows per day = apps with traffic that day x 2 states, per transport. The real count on target devices is UNCONFIRMED and is measured in T13 before retention defaults are fixed.

### 4.3 DataStore (module `:core:preferences`, Preferences DataStore 1.2.1 already in the catalog)

One store, for example `data_usage_settings`.

| Key | Type | Meaning | Default |
|---|---|---|---|
| `schema_version` | int | settings schema version, starts at 1 | 1 |
| `budget_enabled` | boolean | | false |
| `budget_bytes` | long | the user's mobile budget in bytes | unset |
| `budget_transport` | string | `CELLULAR` in v1 | `CELLULAR` |
| `cycle_anchor_day` | int | 1 to 31, local reset day | unset |
| `warn_percent` | int | 1 to 99, first alert level | 80 (placeholder) |
| `usage_access_prompt_state` | string | `NEVER_ASKED`, `EXPLAINED_DECLINED`, `SENT_TO_SETTINGS` | `NEVER_ASKED` |
| `usage_access_last_prompt_epoch_ms` | long | to avoid re-prompting on every launch | unset |

Rules for bad data (database skill: plan for invalid preferences, missing records, partial writes, process death; do not silently discard user data) [repo skill 09] CONFIRMED:

- A value outside its range is treated as unset by readers. The stored raw value is not overwritten until the user edits it. The UI shows "Budget needs attention" rather than quietly changing the number.
- A missing budget never produces a percent or a forecast. It produces the "no budget" state.
- Writes use a single edit transaction per user action so a half saved budget cannot exist.

### 4.4 Sync procedure (design only)

1. Preconditions: Usage access granted (6.2). Single flight: a second sync request while one is running joins it or is ignored. Runs off the main thread and respects cancellation. [S1] CONFIRMED
2. Window: newest first. First sync backfills a bounded number of days (placeholder 35, decision DU-7) so the current cycle appears early, then older days if time allows. A day window is cut in the device zone and recorded in `zoneId`.
3. Per day and transport: `querySummaryForDevice` for totals, and for mobile `querySummary` for per UID per state rows. Summary queries are used instead of one `queryDetails` call because only summaries carry the foreground split. [S1] CONFIRMED. Cost: this is up to two queries per day per transport, each of which may take several seconds. If T12 shows this is too slow on target devices, fall back to one `queryDetails` range call for totals and trends, and fetch the foreground split only for the most recent complete day and the current cycle. This fallback is decision DU-7.
4. Day completeness: a day is `complete` only if the covering sync ran at least `closeMargin` after the day ended. `closeMargin` is a placeholder (4 hours) because the platform only says buckets are hours long. It is tuned in T11.
5. Cycle total for budget purposes: do not sum daily rows. Take one `querySummaryForDevice` over [cycle start, now]. Reason: queries drop partial buckets at the edges of a range, so summing many day windows can lose a partial bucket at every midnight, while one cycle wide query loses at most the two edges. [S1] CONFIRMED (no interpolation for details queries), LIKELY for summaries.
6. Retention probe: occasionally read the earliest available bucket (open ended start is allowed per [S1]) and store `oldestObservedBucketStartEpochMillis`. If the cycle start predates it, the cycle total is presented as "at least", never as a full total. Probe cost is UNCONFIRMED and measured in T14.
7. Failure: a null result, `SecurityException` or `RemoteException` ends the sync with a typed failure recorded in `data_usage_sync_state`. Existing rows are untouched. Core engineering rule 7 applies: processing, success, failure, cancellation, offline, permission and unavailable states are all visible.
8. Idempotence: re-running a sync for the same window produces the same rows. Complete days are not overwritten.

### 4.5 Migration notes

- Database version goes from 1 to 2 by adding tables only. `network_state_history` is untouched.
- Room documents automated migrations for basic schema changes and manual `Migration` classes for the rest. [S12] CONFIRMED. Adding tables is LIKELY covered by an automated migration, but a manual migration is acceptable if the implementer prefers explicit SQL.
- The repository's own build file already records the rule that schema export must exist before any version 2 change ships. [repo `core/database/build.gradle.kts`] CONFIRMED. The `2.json` schema file must be committed.
- A migration test must run in `androidTest` using `MigrationTestHelper`, which Room documents as the migration test mechanism. [S12] CONFIRMED. `room-testing` is already declared for `androidTest` in `:core:database`. [repo] CONFIRMED
- No `fallbackToDestructiveMigration()`. The provider deliberately avoids it. [repo `AerivaDatabaseProvider.kt`] CONFIRMED
- Version note: the current Room migration page shows the newer `androidx.room3` artifact names. The repository pins `androidx.room` 2.8.4 in `gradle/libs.versions.toml`. The implementer must use the 2.x API surface that matches the pin, not copy the room3 samples. [S12, repo] CONFIRMED
- DataStore: adding keys needs no migration because absent keys read as defaults. A future change of meaning of an existing key bumps `schema_version` and ships a read time upgrade step. LIKELY

### 4.6 Retention and deletion

All numbers below are placeholders, not evidence, and are decision DU-7:

- `data_usage_app_day`: 90 days.
- `data_usage_day`: 400 days (enough for a year over year comparison later).
- `data_usage_sync_state`: kept.
- Pruning follows the existing `pruneOlderThan(retention)` repository pattern. [repo `NetworkHistoryRepository.kt`] CONFIRMED
- The user gets a "Delete my data usage history" action that clears the three tables. It is required by the privacy skill's minimized retention rule. [repo skill 07] CONFIRMED
- Uninstalling AERIVA removes its database. No cloud backup exists (`allowBackup="false"`). [repo manifest] CONFIRMED. After a reinstall, AERIVA history restarts, while platform history that Android still holds can be re-read.

### 4.7 Privacy and telemetry

v1 introduces no telemetry. If any is added later, the privacy skill requires documenting event, fields, purpose, retention, access, whether it leaves the device, and consent before it ships. [repo skill 07] CONFIRMED. Logs must never contain package names, UIDs paired with names, or byte counts tied to a named app.

## 5. Budget, forecast and waste rules (deliverable 4)

All rules are pure functions of stored rows, the configuration, an injected clock and an injected time zone. No randomness, no network, no model. Every output carries the inputs used so the screen can explain itself. All thresholds are injectable configuration with placeholder defaults, the same approach as leader decision L5. The defaults have no empirical basis yet and are tuned on real devices.

### 5.1 Cycle

- Cycle start is the most recent occurrence of `cycle_anchor_day` at 00:00 local time at or before now. Cycle end is the next occurrence.
- If a month is shorter than the anchor day, the last day of that month is used. Rule is fixed and tested.
- Time zone: the device zone at evaluation time. A zone change re-cuts the current cycle boundaries. Stored daily rows keep their own `zoneId`.
- Budget scope: mobile (cellular) total in v1.
- A budget change applies to the whole current cycle retroactively. History is not rewritten.

### 5.2 Observed usage and warnings

- `U` is the cycle total from one `querySummaryForDevice` call over [cycle start, now] (4.4 step 5). If the retention floor is later than the cycle start, `U` is labeled "at least".
- `B` is the budget.
- Observed state:
  - `NoBudget` when no valid budget exists.
  - `Ok` when `U < B * warn_percent / 100`.
  - `Warning` when `B * warn_percent / 100 <= U < B`.
  - `Reached` when `U >= B`. This is an observation, not a forecast.
- Warnings are in app only in v1. No notification, so no new permission (L6).

### 5.3 Forecast

Inputs: the trailing window of valid days.

- A valid day is a complete day with a `data_usage_day` row for the budget transport.
- Window: the last `K` valid days (default `K = 7`). Minimum `Kmin = 3` valid days. Valid days may precede the cycle start because the rate describes recent behavior.
- Today is excluded from the rate (it is partial) and included in `U`.

Calculation:

- `rateMean = sum(window) / count(window)` bytes per day.
- `rateHigh = max(window)`. This is the "if your heaviest recent days continue" scenario.
- `R = (cycleEnd - now) in milliseconds / 86_400_000`. This is fractional days and does not assume every day has 24 hours.
- `projectedMid = U + rateMean * R`
- `projectedHigh = U + rateHigh * R`

Outcomes (each is an estimate and is labeled one in the UI):

| Condition | Outcome |
|---|---|
| no budget | `NoBudget` |
| `U >= B` | `Reached` (observation) |
| fewer than `Kmin` valid days | `InsufficientHistory(daysHave, daysNeed)` |
| `rateMean == 0` | `NoRecentUse`, no exhaustion date |
| `projectedHigh <= B` | `LikelyWithinBudget` |
| `projectedMid <= B < projectedHigh` | `MayReachBudget` (only if heavy days continue) |
| `projectedMid > B` | `LikelyToReachBudget(aroundDate)` where `aroundDate = now + ((B - U) / rateMean) days`, shown as a date only |

### 5.4 Insufficient history and failure states (explicit, never silent)

| State | When | What the screen says (intent, not final copy) |
|---|---|---|
| `NoPermission` | Usage access not granted | Cannot read usage. Explain once. Offer the Settings link |
| `NoBudget` | no valid budget | Show usage, invite the user to set a budget |
| `NoDataYet` | permission granted but no successful sync | Syncing, or why it failed |
| `InsufficientHistory` | fewer than `Kmin` valid days | "Not enough days yet to estimate. Have N, need M." |
| `PartialCycle` | retention floor later than cycle start | Show "at least" total |
| `Stale` | last successful sync older than a threshold (placeholder 24 hours) | Show last updated time |
| `Unavailable` | the query returned null or threw | Say usage cannot be read right now |

### 5.5 Waste detection (explainable rules only)

Scope: per app, mobile, complete days only, apps with a resolved or unresolved UID, but never `UID_REMOVED`, `UID_TETHERING` or other special rows. Findings state observed bytes and the rule that fired. They never state a savings figure. [repo skill 02: "Do not invent savings figures"] CONFIRMED

| Id | Rule | Placeholder parameters |
|---|---|---|
| W1 | Not in foreground heavy day. On the most recent complete day, the app's `NOT_FOREGROUND` bytes are at least `floorBytes` and at least `notFgShare` of its total that day | `floorBytes` 10 MB, `notFgShare` 0.8 |
| W2 | Unusual day against the app's own baseline. The most recent complete day's total is at least `floorBytes` and at least `multiplier` times the median of the app's daily totals over the baseline window. Requires at least `minBaselineDays` valid days in that window. Zero days count as zero only on complete days (4.2) | `baselineWindowDays` 28, `minBaselineDays` 7, `multiplier` 3.0 |
| W3 | Persistent not in foreground use. `NOT_FOREGROUND` bytes at least `floorBytes` on at least `persistDays` of the last `persistWindow` complete days | `persistDays` 4, `persistWindow` 7 |

Determinism and noise control:

- Findings are sorted by observed bytes descending, then by UID ascending, and capped at `maxFindings` (placeholder 3).
- When the baseline is too short, W2 returns an internal `NotEnoughBaseline(daysHave, daysNeed)` so the screen can say so, instead of showing nothing or guessing.
- A finding links to the Android settings for that app (3.1) and offers no AERIVA action that pretends to fix it.
- Dismissing a finding is not in v1.
- A usage ranking ("top apps by mobile data") is a separate, plain sort by observed bytes and is not called waste.

### 5.6 Required tests when implemented (for the later implementer)

Each rule gets positive and negative JVM tests. Required cases: anchor day 31 in a 30 day month and in February (leap and non leap), DST transitions and time zone change inside a cycle, a cycle that starts before the retention floor, fewer than `Kmin` days, exactly `Kmin` days, a missing day inside the window, an incomplete day excluded from the rate, `rateMean == 0`, `U` exactly equal to `B`, and the threshold edges of W1 to W3. Room tests: DAO queries, transaction atomicity of day plus app rows, migration 1 to 2, pruning. DataStore tests: defaults, out of range values, process death mid write. This matches the database skill's test list. [repo skill 09] CONFIRMED

## 6. Permission flow, denial behavior and device validation (deliverable 5)

### 6.1 Manifest and policy implications (all need Leader approval, L6)

| Change | Needed in v1 | Notes |
|---|---|---|
| `uses-permission PACKAGE_USAGE_STATS` | Yes | Declaring signals intent. The user grants through Settings. Lint will need a `ProtectedPermissions` suppression. [S1] CONFIRMED (permission model), suppression LIKELY |
| Activity with `INTENT_CATEGORY_USAGE_ACCESS_CONFIG` and `METADATA_USAGE_ACCESS_REASON` | Optional, recommended by the platform | LIKELY (mirror) [S5] |
| `<queries>` for label resolution | Decided by DU-1 | |
| `QUERY_ALL_PACKAGES` | No | Needs Play approval and a core purpose declaration [S8] CONFIRMED |
| `INTERNET`, `READ_PHONE_STATE`, `POST_NOTIFICATIONS`, any new dependency | No | Out of scope for v1 |
| Play Console declaration for `PACKAGE_USAGE_STATS` | UNCONFIRMED | The Play pages read describe declarations for other sensitive permissions. No page read stated the requirement for this one. The Leader should check the Play Console "sensitive permissions" declarations before release |
| Prominent in app explanation and privacy policy link | Required in practice | Play expects sensitive permission requests to make sense to users, to be requested in context, and to accommodate users who decline. [S8] CONFIRMED. The policy preview page read is dated and may be superseded. Recheck before release |

### 6.2 Is access granted? (check logic)

1. Read the app-op state for `OPSTR_GET_USAGE_STATS` for AERIVA's UID and package, with the non throwing, non tracking check. [S4] CONFIRMED (constants and check family)
2. If the mode is `MODE_ALLOWED`, access is granted.
3. If the mode is `MODE_DEFAULT`, fall back to the permission grant state. [S4] LIKELY
4. Anything else is denied.
5. Re-check on every return to the foreground. The user can change it in Settings at any time. A grant that fails at query time (null result or `SecurityException`) is treated as denied for that sync and recorded as a typed failure.

The exact mode values that real devices return in each state are verified in T1 to T3. Until then this section is LIKELY.

### 6.3 Flow states

`NeverAsked` -> `Explained` (in app screen: what AERIVA reads, that it stays on the device, that it cannot block anything) -> `SentToSettings` (Usage access screen opened) -> back in AERIVA: check -> `Granted` or `StillDenied`. From `Granted`, a later check can find `Revoked`.

- The Settings screen is opened with `ACTION_USAGE_ACCESS_SETTINGS`. If no activity handles it, show text instructions ("Open Android Settings and search for Usage access"). Do not crash. [S5] CONFIRMED
- The explanation screen offers "Not now". After "Not now" AERIVA does not re-prompt on each launch. The prompt returns only when the user taps a Data Usage entry point. `usage_access_prompt_state` and `usage_access_last_prompt_epoch_ms` hold this.
- Whether newer Android versions apply "restricted settings" style blocks to sideloaded installs for Usage access is UNCONFIRMED. T4 tests a sideloaded install.

### 6.4 Denial behavior

Principle: if denied, unrelated features stay usable. [repo skill 07] CONFIRMED

| Surface | When Usage access is off |
|---|---|
| Network status, Network Advisor, other pillars | Unaffected |
| Data Usage screen | Shows the denied state, one sentence on why, "Open Settings", "Not now". No numbers |
| Budget settings | The user can still save a budget. It shows no progress until access exists |
| Home card for data usage | Neutral "Usage access is off", no zeros and no placeholder numbers (core rule 9, never fake data) |
| Stored history | Kept. Shown with its last updated time and a "not updating" label |
| Degraded mode from `TrafficStats` since boot | Not in v1. Counters reset on reboot, cover only the time since boot, and AERIVA would have to sample continuously to turn them into history. Decision DU-4 |

### 6.5 Real device validation plan

Rule 4 of the briefs applies: emulator results are not real device results. The emulator may be used for permission flow UI logic and Room migration tests. It may not be used to claim data accuracy, retention, bucket size, OEM Settings behavior, query time or battery effect.

Device matrix (recommendation, the Leader chooses actual devices):

- At least one device per band: API 26 to 28, API 29 to 30, API 31 to 33, and the newest available release.
- At least one non Pixel OEM skin, one dual SIM device, and one low RAM device. The target market likely has many low cost devices with vendor skins. That is an assumption about devices, UNCONFIRMED, and the Leader should name the models.
- Each result records: device model, Android version and patch level, build SHA of the AERIVA test build, and the CircleCI run id that produced the build. A result without those is not accepted.

Tests (T1 to T3 permission, T4 to T8 platform behavior, T9 to T14 data):

| Id | Test | Evidence to capture | Resolves |
|---|---|---|---|
| T1 | Fresh install, access never granted: open Data Usage screen | screenshot, app-op mode value logged in a debug build | 6.2 mode values |
| T2 | Grant access in Settings, return to AERIVA | mode value, first successful query | 6.2 |
| T3 | Revoke while AERIVA is in memory, then return | mode value, UI state, no crash | 6.2, `Revoked` |
| T4 | Sideloaded install (not from Play) | whether Usage access can be granted | 6.3 restricted settings |
| T5 | Settings intent missing or blocked (managed device or OEM) | fallback text shown, no crash | 6.3 |
| T6 | UID to name resolution for a set of installed third party apps with and without a `<queries>` entry | which UIDs resolve, which do not | 2.5, DU-1 |
| T7 | Data Saver on: status and change receiver | `getRestrictBackgroundStatus` values, broadcast received only when registered dynamically | 3.1 |
| T8 | Settings links: data usage, app details | whether each opens, whether a data usage entry is reachable per app | 3.1 |
| T9 | Controlled traffic: download a file of known size on mobile and on Wi-Fi, after the bucket closes compare AERIVA's delta with the known size and with Android's Settings figure | signed difference per run | accuracy, DU-8 |
| T10 | Same download through an active VPN app | attribution, double counting | 2.4 |
| T11 | Bucket closing: poll after a transfer to see when the data appears | delay in minutes, bucket length observed | `closeMargin` |
| T12 | Query cost: time per summary query and per range query with a realistic number of apps, on a low RAM device | milliseconds per query, ANR free on worker thread | 4.4 step 3, DU-7 |
| T13 | Row volume: number of UIDs with traffic over 30 days of normal use | rows per day | 4.2, retention defaults |
| T14 | Retention probe: earliest queryable bucket on each device | oldest bucket date per device | 2.6 |
| T15 | Foreground split: start a download, switch away, compare `FOREGROUND` and `NOT_FOREGROUND` bytes | split observed | 2.1 note on `STATE_DEFAULT` |
| T16 | Day boundary, time zone change, DST change, reboot, SIM swap, airplane mode, process death in the middle of a sync | rows consistent, no double count | 4.4, 5.1 |
| T17 | Uninstall an app that has recorded usage, then re-sync | `UID_REMOVED` appears, complete day rows unchanged | 4.2 immutability |
| T18 | Battery and data cost of syncing: counted syncs per day and bytes read, no network traffic from AERIVA itself | log of sync count, confirmation of no outbound traffic | skill 02 "must not consume excessive battery or data" |
| T19 | Migration 1 to 2 with existing history | `androidTest` result and row counts before and after | 4.5 |

Acceptance for T9: this document does not set a tolerance. The first measurements will show the real spread, and the Leader sets the acceptance threshold (DU-8). Carrier meter comparison is informational only.

## 7. Decisions needed from the Team Leader

Owner only decisions are listed separately from recommendations.

| Id | Decision | Recommendation |
|---|---|---|
| DU-1 | Per app name strategy: resolve only visible apps (A), add a `<queries>` launcher intent (B), or request `QUERY_ALL_PACKAGES` (C) | Start with A plus a neutral "Another app" row. Decide B after T6. Do not choose C for v1 |
| DU-2 | v1 transport scope | Device totals for mobile and Wi-Fi, per app for mobile only |
| DU-3 | Approve adding `PACKAGE_USAGE_STATS` to the app manifest, and check Play Console declarations for it | Approve at implementation time, not now, and check Play first |
| DU-4 | Degraded mode without access using `TrafficStats` since boot | No for v1 |
| DU-5 | Background sync with WorkManager | No for v1. It is a new dependency (L6). Periodic work has a 15 minute minimum interval and is subject to battery optimizations, so it would not be real time anyway. [S11] CONFIRMED |
| DU-6 | Notifications at thresholds | No for v1. It needs a new permission on newer Android versions (not verified here) and L6 |
| DU-7 | Retention numbers, backfill size, `closeMargin`, and whether to use the range query fallback | Accept the placeholders in 4.4 and 4.6, revisit after T11, T12, T13 |
| DU-8 | Accuracy acceptance tolerance for T9 | Set after first device data |
| DU-9 | Where code will live (entities in `:core:database`, settings in `:core:preferences`, pure rules in a new package under `:core:model` or a dedicated module, platform adapter near `:network:monitor`) | Decide with Agent 2's module layout in mind |

## 8. Risks

| Id | Risk | Label |
|---|---|---|
| R1 | Per app names may not resolve for many apps, weakening the per app screen | LIKELY |
| R2 | Platform retention may be shorter than a billing cycle, making cycle totals partial | UNCONFIRMED |
| R3 | A Play policy requirement for `PACKAGE_USAGE_STATS` may exist that was not found | UNCONFIRMED |
| R4 | Hour sized buckets and dropped partial buckets make daily figures approximate | CONFIRMED |
| R5 | OEM variation in the Usage access screen and in the system data usage screen | UNCONFIRMED |
| R6 | Android's count will differ from the carrier's count and users may judge AERIVA wrong | LIKELY |
| R7 | Sync query cost on low end devices | UNCONFIRMED |
| R8 | Time zone and DST errors in day cutting | LIKELY (mitigated by recording `zoneId` and testing) |
| R9 | Room migration mistakes | LIKELY (mitigated by committed schema and migration test) |
| R10 | Package names and UIDs are sensitive local data | CONFIRMED |
| R11 | Placeholder thresholds read as facts if copied into UI | LIKELY. They must stay configuration and be labeled estimates |

## 9. Not verified

- Nothing in this document was executed. No Gradle, no CircleCI, no emulator, no device.
- No CircleCI run applies to this branch: it changes one Markdown file and no build input. No CircleCI connector was available to this agent, so no run id is claimed.
- Retention depth, bucket length, `STATE_DEFAULT` contents, app-op mode values per state, package visibility effect of a Usage access grant, behavior on Android versions after API 31, Play policy for `PACKAGE_USAGE_STATS`, and the usage callback behavior are all unverified (see the UNCONFIRMED labels).
- Several statements rest on mirrors of the official Javadoc (Microsoft Learn Android binding pages) or on localized copies of official pages. They are labeled LIKELY where that is the only source.
- The Play policy pages read include a "policy preview" page that is dated. Current policy must be rechecked in Play Console.
- `NETWORK_ADVISOR_PHASE_A_SPEC.md` (branch `network-advisor-phase-a-spec`) was named in the common rules but was not read, because nothing in the Data Usage scope depends on it. This is a gap against rule 1 of the briefs.

## 10. Sources

Official, read during this task:

- S1 NetworkStatsManager reference: https://developer.android.com/reference/android/app/usage/NetworkStatsManager
- S2 NetworkStats.Bucket reference: https://developer.android.com/reference/android/app/usage/NetworkStats.Bucket
- S3 TrafficStats reference: https://developer.android.com/reference/android/net/TrafficStats
- S4 AppOpsManager reference: https://developer.android.com/reference/android/app/AppOpsManager
- S5 Settings reference (data usage, app details, background data restriction actions): https://developer.android.com/reference/android/provider/Settings . Usage access category and reason meta-data were read from a mirror: https://learn.microsoft.com/en-us/dotnet/api/android.provider.settings.intentcategoryusageaccessconfig?view=net-android-37.0 and https://learn.microsoft.com/en-us/dotnet/api/android.provider.settings.metadatausageaccessreason?view=net-android-37.0
- S6 Optimize network data usage (Data Saver), read through localized copies of: https://developer.android.com/training/basics/network-ops/data-saver
- S7 Package visibility filtering: https://developer.android.com/training/package-visibility
- S8 Google Play policy: QUERY_ALL_PACKAGES https://support.google.com/googleplay/android-developer/answer/10158779 ; permissions and sensitive APIs (policy preview) https://support.google.com/googleplay/android-developer/answer/13986130 ; prominent disclosure https://support.google.com/googleplay/android-developer/answer/11150561
- S9 Device identifiers (AOSP): https://source.android.com/docs/core/connect/device-identifiers . `getSubscriberId` text read from a mirror: https://learn.microsoft.com/en-us/dotnet/api/android.telephony.telephonymanager.subscriberid?view=net-android-37.0
- S10 SubscriptionManager and SubscriptionPlan references: https://developer.android.com/reference/android/telephony/SubscriptionManager and https://developer.android.com/reference/android/telephony/SubscriptionPlan
- S11 WorkManager PeriodicWorkRequest reference: https://developer.android.com/reference/androidx/work/PeriodicWorkRequest
- S12 Migrate your Room database: https://developer.android.com/training/data-storage/room/migrating-db-versions
- S13 `registerUsageCallback` (mirror only): https://learn.microsoft.com/tr-tr/dotnet/api/android.app.usage.networkstatsmanager.registerusagecallback?view=net-android-37.0

Repository files read at `f53134c193ee`:

- `.claude/00-core-engineering/SKILL.md`, `.claude/02-data-usage/SKILL.md`, `.claude/07-security-privacy/SKILL.md`, `.claude/09-database-preferences/SKILL.md`, `.claude/16-task-execution/SKILL.md`
- `gradle/libs.versions.toml`, `settings.gradle.kts`, `app/src/main/AndroidManifest.xml`
- `core/database/build.gradle.kts`, `core/database/src/main/kotlin/com/aeriva/core/database/AerivaDatabase.kt`, `AerivaDatabaseProvider.kt`, `NetworkStateHistoryEntity.kt`, `NetworkHistoryRepository.kt`
- `core/preferences/build.gradle.kts`
- `core/model/src/main/kotlin/com/aeriva/core/model/TransportType.kt`
