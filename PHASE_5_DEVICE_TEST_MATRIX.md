# PHASE 5: DEVICE TEST MATRIX

Author: AI 6. Date written: 2026-09-21.
Base: `phase-4-cross-cutting-decisions` @ `8a56dc70b19b170d9e334e797b132a65ea3be612`.
Companion documents: `PHASE_5_MEASUREMENT_VALIDATION_HARNESS.md` (strategy, scenarios NC-A to NC-M) and `PHASE_5_TEST_DATA_AND_EVIDENCE_SCHEMA.md` (fixtures and evidence).

This document is a plan and a status board. Nothing in it has been executed on any device. No device result exists and none is claimed.

## 1. Status vocabulary

Every row carries one status from this closed set. Rows never carry a result.

| Status | Meaning |
|---|---|
| EXPECTED | A prediction from documentation or design, stated so it can be checked later. Not an observation. |
| NOT YET TESTED | No run has happened at the named layer. This is the status of every row today unless a cell says otherwise. |
| REQUIRES PHYSICAL DEVICE | The condition cannot be judged without real hardware (real radio, real OEM software, real Wi-Fi stack). |
| NOT REPRESENTABLE IN EMULATOR | The emulator cannot produce the condition, or produces something that is not the condition. |

Layer names (L2 to L7) are defined in `PHASE_5_MEASUREMENT_VALIDATION_HARNESS.md`, section 3.

Basis labels used in this document: VERIFIED FACT (read from official documentation in this session, sources in section 7 of the harness document), REPO FACT (read from code or configuration), PRIOR-DOC FACT (stated in an earlier AERIVA document, not re-verified here), ASSUMPTION (unverified).

## 2. Category matrix

Representability is judged for the emulator layer (L3) and for the physical layers (L4 to L7). "Expected observation" is an EXPECTED statement, not a result.

| ID | Category | Emulator (L3) | Physical (L4 to L7) | Expected observation (EXPECTED) | Status |
|---|---|---|---|---|---|
| DM-API | Android API levels 26 to 37 | Only API 30 x86_64 google_apis exists in CI (REPO FACT). Other images may be run locally. Local images are not evidence for a physical device. | At least one physical device per band in section 3. | Behavior differences are limited to the API-specific items listed in section 3. | NOT YET TESTED beyond one prior instrumented run on API 30 (PRIOR-DOC FACT) |
| DM-WIFI | Wi-Fi connection (band, RSSI, link) | The emulator Wi-Fi is virtual. RSSI, band and link rate are not real. | Required. Compare app-reported values with the access point and device diagnostics. | NetworkState reports WIFI transport, validated and metered per the real network. | REQUIRES PHYSICAL DEVICE, NOT REPRESENTABLE IN EMULATOR |
| DM-CELL | Cellular connection (type, signal, carrier, SIM) | The emulator telephony is simulated through console `gsm` commands (VERIFIED FACT that the commands exist). It has no real radio. | Required. Each carrier SIM under test. | NetworkState reports CELLULAR transport. Signal and carrier fields depend on permissions not yet requested. | REQUIRES PHYSICAL DEVICE, NOT REPRESENTABLE IN EMULATOR |
| DM-TRANS-WC | Wi-Fi to cellular transition | Simulated toggles only. Real handoff is not representable. | Required. Move out of range or disable Wi-Fi during a series. | In-flight probe ends as a network-change or timeout result. No success spans the change. Jitter pairs break at the change. | REQUIRES PHYSICAL DEVICE, NOT REPRESENTABLE IN EMULATOR |
| DM-TRANS-CW | Cellular to Wi-Fi transition | Same as above. | Required. Join Wi-Fi during a cellular series. | Same as above. | REQUIRES PHYSICAL DEVICE, NOT REPRESENTABLE IN EMULATOR |
| DM-VPN | VPN active | Practically untestable. | Required, with a real VPN application. | vpnPresent true. Results labeled as VPN-path. The reported transport is VPN and hides the underlying one (REPO FACT about NetworkStateMapper). | REQUIRES PHYSICAL DEVICE |
| DM-METERED | Metered network | Limited. Whether the emulator can present a metered network is unverified. | Required. Mark the Wi-Fi network metered in device settings, and test a metered cellular connection. | metered true. Heavy capabilities decline. Latency proceeds only within a data budget (owner decision OD-6 in the cross-cutting contract). | NOT YET TESTED |
| DM-UNVAL | Unvalidated network (connected, not validated) | Limited. Depends on cutting the host upstream. | Required. Cut the upstream of an access point. | validated false. Probes are attempted and fail at the stage reached. They are not declined. | NOT YET TESTED |
| DM-PORTAL | Captive portal | Practically untestable. | Required, with a real or lab portal. | captivePortalReported true (platform flag). Interception signal observed. Result is a suspected portal, never AVAILABLE. | REQUIRES PHYSICAL DEVICE |
| DM-POOR | Poor connectivity (slow, lossy) | Shaping through emulator startup options and console is a developer aid only (VERIFIED FACT that the options exist). | Required for real conditions. Lab shaping with tc netem needs its own calibration first. | Slow successes remain distinct from timeouts. Timeouts carry a stage. | NOT YET TESTED |
| DM-INTERMITTENT | Intermittent connectivity | Simulated toggles. | Required. Repeated Wi-Fi or airplane-mode toggles, plus a real weak-signal site. | Alternating success and failure samples. Nothing is averaged across a failure. | NOT YET TESTED |
| DM-DNS | DNS problems (failure, slow, Private DNS modes) | Limited. Reference-server fault injection works from any client, but Private DNS behavior on the emulator image is unverified. | Required for carrier resolvers and Private DNS modes. | DnsFailure with kind NOT_RESOLVED or CLIENT_TIMED_OUT. The device cannot separate a missing name from a resolver failure (LIMITATION). | NOT YET TESTED |
| DM-HILAT | High latency | Emulator console and startup delay settings exist (VERIFIED FACT). The console `network` command manages ethernet and cellular only per its help text (VERIFIED FACT), so Wi-Fi is not shaped that way. | Required for Wi-Fi. Lab shaping. | Measured latency rises by roughly the configured delay plus the baseline. Any tolerance is derived from pilots. | NOT YET TESTED |
| DM-VARLAT | Variable latency | Emulator delay presets use a minimum and maximum latency (secondary source, ASSUMPTION for the distribution). | Required. Lab shaping with a jitter distribution. | Mean absolute successive difference and range rise with configured jitter. | NOT YET TESTED |
| DM-INTERRUPT | Network interruption during a measurement | Simulated toggles. | Required. | Failure at the stage reached, or a network-change result. Cancellation never yields a value. | NOT YET TESTED |
| DM-DOZE | Doze | The documented adb commands to force Doze work on virtual devices (VERIFIED FACT). | Required for OEM behavior. | No probe starts from a deferred context. A probe interrupted by Doze is not reported as an endpoint failure. | NOT YET TESTED |
| DM-SAVER | Battery saver | Not verified for the emulator. | Required. | On-demand foreground measurement is unaffected. Deferred work is reported as deferred. | REQUIRES PHYSICAL DEVICE |
| DM-BGRESTRICT | Background restrictions and App Standby buckets | App Standby can be forced with the documented `am set-inactive` command (VERIFIED FACT). OEM restrictions are not in the emulator image. | Required. At least one aggressive OEM. | Restricted app cannot reach the network from the background. Foreground is unaffected. | REQUIRES PHYSICAL DEVICE |

## 3. Android API level matrix

CI emulator coverage is one image: API 30, `google_apis`, x86_64 (REPO FACT, `.circleci/config.yml`). The build is `minSdk 26`, `targetSdk 36`, `compileSdk 36` (REPO FACT).

| API | Release | Behavior relevant to network validation | Basis | Layer coverage today | Status |
|---|---|---|---|---|---|
| 26, 27 | 8.0, 8.1 | Floor of the supported range. Doze and App Standby exist since API 23. | REPO FACT, VERIFIED FACT | None | NOT YET TESTED, REQUIRES PHYSICAL DEVICE |
| 28 | 9 | Private DNS accessors on link properties are available. | VERIFIED FACT | None | NOT YET TESTED |
| 29 | 10 | Cell information freshness may differ. | PRIOR-DOC FACT | None | NOT YET TESTED |
| 30 | 11 | The only CI emulator image. | REPO FACT | L2 in CI (PRIOR-DOC FACT that it passed at `727b2a91`) | NOT YET TESTED for measurement behavior |
| 31, 32, 33 | 12, 12L, 13 | Network callback location-info flag exists and location info is redacted by default. | VERIFIED FACT | None | NOT YET TESTED, REQUIRES PHYSICAL DEVICE |
| 34 | 14 | Foreground service type rules. Relevant only if a foreground service is ever added. | PRIOR-DOC FACT | None | NOT YET TESTED |
| 35 | 15 | Foreground service limits for some types. | PRIOR-DOC FACT | None | NOT YET TESTED |
| 36 | 16 | Job runtime quotas adjusted by standby bucket and top state. Relevant to any future scheduler. | VERIFIED FACT | None | NOT YET TESTED |
| 37 | 17 | Local network permission and Encrypted Client Hello apply only to apps targeting 37. Not applicable while the target is 36. | VERIFIED FACT | None | NOT REQUIRED until the target changes |

Coverage floor (from `PHASE_4_REAL_DEVICE_VALIDATION_PLAN.md`, section 4): one physical device in each band 26 to 29, 30, 31 to 33, 34, 35, 36. Exact device counts are still an open owner decision (validation plan D-03). The CI emulator gives no evidence for API 31 and higher.

## 4. Capability by condition grid

X means the capability is relevant to that scenario and must be exercised when the scenario is run. A dash means it is not relevant. Every cell is NOT YET TESTED. Scenario definitions are in `PHASE_5_MEASUREMENT_VALIDATION_HARNESS.md`, section 4.

| Capability | NC-A | NC-B | NC-C | NC-D | NC-E | NC-F | NC-G | NC-H | NC-I | NC-J | NC-K | NC-L | NC-M |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Latency | X | X | X | X | - | X | X | X | X | X | X | X | X |
| Jitter | X | X | X | X | - | - | - | - | X | X | - | X | X |
| Unanswered probes | X | X | X | X | - | X | - | X | X | X | - | X | X |
| Throughput | X | X | - | - | - | - | - | X | X | - | - | X | - |
| DNS responsiveness | X | - | - | - | X | - | X | - | X | - | X | - | - |
| HTTPS reachability | X | X | - | X | X | X | X | X | X | X | X | X | X |
| Network state | X | - | - | - | X | - | X | X | X | X | X | X | X |

## 5. Device attribute coverage

The attribute rules (performance tier, OEM skin, modem family, Wi-Fi capability, SIM configuration) are defined in `PHASE_4_REAL_DEVICE_VALIDATION_PLAN.md`, section 3. This document adds no device names and no device results. A device inventory is a lab decision that depends on the physical lab approach (validation plan D-30, still open). Until an inventory exists, every physical-device row above stays NOT YET TESTED.

Rules that apply to every row:

1. A result from one device is evidence about that device only.
2. An emulator result never fills a physical-device row.
3. A row may move to a result only through an evidence record that validates against the schema in `PHASE_5_TEST_DATA_AND_EVIDENCE_SCHEMA.md`.

## 6. Row to scenario mapping

| Matrix row | Scenario |
|---|---|
| DM-WIFI | NC-A |
| DM-HILAT | NC-B |
| DM-VARLAT | NC-C |
| DM-INTERMITTENT | NC-D |
| DM-DNS | NC-E |
| DM-POOR | NC-F, NC-L |
| DM-PORTAL, DM-UNVAL | NC-G |
| DM-METERED | NC-H |
| DM-VPN | NC-I |
| DM-TRANS-WC, DM-TRANS-CW, DM-INTERRUPT | NC-J |
| DM-CELL | NC-L, NC-M |

## 7. Sources

Emulator startup options and console commands are from the official emulator command-line page and the emulator console page on developer.android.com (see the harness document, section 16, for the URLs). Doze and App Standby commands are from the official Doze and App Standby page. Items marked PRIOR-DOC FACT come from `PHASE_2_ANDROID_PLATFORM_AUDIT.md` and `PHASE_4_REAL_DEVICE_VALIDATION_PLAN.md` and were not re-verified.
