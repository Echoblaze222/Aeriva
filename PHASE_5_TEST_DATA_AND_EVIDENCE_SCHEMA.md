# PHASE 5: TEST DATA AND EVIDENCE SCHEMA

Author: AI 6. Date written: 2026-09-21.
Base: `phase-4-cross-cutting-decisions` @ `8a56dc70b19b170d9e334e797b132a65ea3be612`.
Companion documents: `PHASE_5_MEASUREMENT_VALIDATION_HARNESS.md` and `PHASE_5_DEVICE_TEST_MATRIX.md`.

Covers assignment sections 5 (golden test data), 6 (evidence model) and 13 (machine-readable result format). No production database is introduced. No production code is touched.

Basis labels: REPO FACT (read from code), PRIOR-DOC FACT (earlier AERIVA document, not re-verified), PROVISIONAL (a harness rule that a later specification must confirm or replace), PROPOSED TEST PARAMETER (an illustrative value, never a product threshold), OPEN DECISION.

## 1. Purpose and file inventory

| Path | What it is |
|---|---|
| `validation/schema/evidence-record.schema.json` | JSON Schema (draft 2020-12) for one evidence record |
| `validation/fixtures/latency-series.json` | Latency and jitter series (13 fixtures) |
| `validation/fixtures/udp-trains.json` | UDP probe train sequences (8 fixtures) |
| `validation/fixtures/throughput.json` | Throughput transfers (5 fixtures) |
| `validation/fixtures/dns-outcomes.json` | DNS outcomes (5 fixtures) |
| `validation/fixtures/https-outcomes.json` | HTTPS outcome mappings (19 fixtures) |
| `validation/fixtures/freshness-confidence.json` | Freshness (4) and latency confidence (6) |
| `validation/fixtures/evidence-examples/valid/` | Three synthetic evidence records that must validate |
| `validation/tools/validate_validation_assets.py` | Checker: schema, fixture oracles, 15 negative schema cases, document lint |

Equivalent Kotlin helpers were checked for before adding anything. A deterministic clock (`MutableClock`), a scripted fake network client (`FakeNetworkClient`) and the shared dispatcher fixtures already exist (REPO FACT and PRIOR-DOC FACT, listed in the harness document, section 2). None of them is duplicated. What did not exist, and is added here, is language-neutral golden data, an evidence schema, and a checker. No Kotlin was added because no Gradle build could be run in this environment.

## 2. Golden test data

Every fixture is SYNTHETIC. Fixture values are chosen so the arithmetic can be checked by hand. They are not measurements of any network and must never be presented as such. Each fixture file says so in a `note` field, and each names the code or contract text its semantics come from (`semantics_ref`).

### 2.1 Semantics sources

| Quantity | Source of the rule | Status |
|---|---|---|
| Latency aggregate (average, min, max, valid samples) | `DerivedLatencyStats.from` at `phase-3b-measurement-engine@727b2a91`. Negative and non-finite values are rejected. Null when nothing valid remains. | REPO FACT |
| Latency confidence | `Confidence.of` at the same commit. Fewer than 3 samples Insufficient. Fewer than 10 Low. From 10, Medium when inconsistent and High when consistent. Consistent means (max minus min) at most 0.5 times the average. The code calls these thresholds illustrative. | REPO FACT, illustrative |
| Freshness | `Freshness.isStaleAt` is true only when now is strictly after `validUntil`. A null `validUntil` is never stale. | REPO FACT |
| Jitter | Contract decision D4-3: mean absolute successive difference over adjacent pairs, plus range over the samples used. A pair needs two successful samples with the same method id, both warm, on the same network, in send order. A failure or change breaks adjacency. Samples used means members of at least one valid pair. Unordered input yields null. | Contract text. "Samples used" is interpreted here as stated. |
| Unanswered-probe train | Contract decision D4-6: counts of answered in window, late, duplicate, out of order. Unanswered is sent minus answered in window. Zero answered in window is a failure, never a 100 percent figure. | Contract text |
| Reply classification rules R-U1 to R-U7 | Defined in `udp-trains.json` | PROVISIONAL. The wire protocol specification must replace them. |
| Throughput | Contract decision D4-7. Bits per second is bytes times 8 divided by seconds, decimal units. Zero bytes by the time box is a timeout failure. | Contract text |
| DNS | Contract decisions D4-10, D5-4. DNS responsiveness is estimation tier. | Contract text |
| HTTPS mapping | Contract decisions D5-5 to D5-8. Portal requires an interception signal and the platform portal flag at probe start or end. | Contract text |

### 2.2 Latency and jitter fixtures

Units are milliseconds. "Pairs" is the jitter pair count. "Used" is the number of samples that belong to a valid pair.

| ID | Input (in send order) | Latency expected | Jitter expected |
|---|---|---|---|
| FIX-LAT-001 | 10, 20, 30, 40 | 4 valid, average 25, min 10, max 40, Low | 3 pairs, used 4, mean abs 10, range 30 |
| FIX-LAT-002 | 10, 100, 15, 200 | 4 valid, average 81.25, min 10, max 200, Low | 3 pairs, used 4, mean abs 120, range 190 |
| FIX-LAT-003 | ten samples alternating 20 and 21 | 10 valid, average 20.5, High | 9 pairs, used 10, mean abs 1, range 1 |
| FIX-LAT-004 | ten samples alternating 10 and 110 | 10 valid, average 60, Medium | 9 pairs, used 10, mean abs 100, range 100 |
| FIX-LAT-005 | 20 | 1 valid, average 20, Insufficient | null |
| FIX-LAT-006 | 20, 30 | 2 valid, average 25, Insufficient | 1 pair, used 2, mean abs 10, range 10 |
| FIX-LAT-007 | four timeouts at the response stage | insufficient evidence (no aggregate) | null |
| FIX-LAT-008 | timeout, 20, 22, 21 | 3 valid, average 21, Low | 2 pairs, used 3, mean abs 1.5, range 2 |
| FIX-LAT-009 | 10, 20, timeout, 30, 40 | 4 valid, average 25, Low | 2 pairs, used 4, mean abs 10, range 30 |
| FIX-LAT-010 | 50, 60, timeout, 10, 20 | 4 valid, average 35, min 10, max 60, Low | 2 pairs, used 4, mean abs 10, range 50 |
| FIX-LAT-011 | 10, 20 on network 1 then 30, 40 on network 2 | not asserted (rule H-AGG-1) | 2 pairs, used 4, mean abs 10, range 30 |
| FIX-LAT-012 | one cold sample then 12, 11, 13 warm | not asserted (rule H-AGG-1) | 2 pairs, used 3, mean abs 1.5, range 2 |
| FIX-LAT-013 | send offsets 0, 200, 100 with values 10, 30, 20 | 3 valid, average 20, Low | null (unordered evidence) |

FIX-LAT-009 and FIX-LAT-010 carry a `known_implementation_divergence` note. They are the reason for finding F-1 in the harness document: by code reading and a hand trace, the Phase 4 foundation `DerivedJitterStats.from` at `phase-4-measurement-foundation@9a28cc62` would drop the first sample of a later run of pairs. That is suspected, not confirmed, because the Kotlin was not executed.

The named sequences requested for this checkpoint are covered as follows: the healthy sequence 10, 20, 30, 40 is FIX-LAT-001, the variable sequence 10, 100, 15, 200 is FIX-LAT-002, and timeout sequences are FIX-LAT-007 and FIX-LAT-008.

### 2.3 UDP probe train fixtures (PROVISIONAL rules)

All trains use a 100 ms send interval. Probe seq s is sent at s times 100 ms.

| ID | Scenario | Probes | Window (ms) | Expected |
|---|---|---|---|---|
| FIX-UDP-001 | Clean train, all replies in order and in window | 8 | 1000 | answered 8, late 0, duplicates 0, out of order 0, unanswered 0 |
| FIX-UDP-002 | Missing sequence: seq 3 never answered | 8 | 1000 | answered 7, unanswered 1 |
| FIX-UDP-003 | Duplicate: seq 2 answered twice | 8 | 1000 | answered 8, duplicates 1, unanswered 0 |
| FIX-UDP-004 | Reordered: seq 1 arrives after seq 2 | 8 | 1000 | answered 8, out of order 1 |
| FIX-UDP-005 | Late: seq 5 answered 1500 ms after send | 8 | 1000 | answered 7, late 1, unanswered 1 |
| FIX-UDP-006 | No replies | 8 | 1000 | failed, no response, unanswered 8 |
| FIX-UDP-007 | Reply carries unknown seq 99 | 4 | 1000 | answered 4, ignored 1 |
| FIX-UDP-008 | All three replies arrive late | 3 | 500 | failed, no response, late 3, unanswered 3 |

FIX-UDP-008 follows the contract's literal rule. Whether a train whose replies all arrive late deserves its own outcome instead of "no response" is an open question recorded as finding F-4 in the harness document.

### 2.4 Throughput fixtures

| ID | Scenario | Bytes | Transfer (ms) | Termination | Expected |
|---|---|---|---|---|---|
| FIX-THR-001 | Completed download | 1,000,000 | 800 | completed | 10,000,000 bit/s |
| FIX-THR-002 | Stopped at the byte cap | 250,000 | 2000 | stopped at byte cap | 1,000,000 bit/s, valid result |
| FIX-THR-003 | Slow upload stopped at the time box | 12,500 | 5000 | stopped at time box | 20,000 bit/s, valid result |
| FIX-THR-004 | Zero bytes by the time box | 0 | 5000 | stopped at time box | failure: timeout at the response stage, never a zero rate |
| FIX-THR-005 | Interval samples with a ramp | 1,000,000 | 800 | completed | 10,000,000 bit/s overall, 12,000,000 bit/s with the first 200 ms interval excluded (PROPOSED TEST PARAMETER, illustration only) |

FIX-THR-005 interval bytes are 100,000, 300,000, 300,000, 300,000 and must sum to the total.

### 2.5 DNS fixtures

DNS responsiveness is estimation tier (REPO FACT: the classifier returns Estimated). A successful timed lookup is an evidence sample, not a measurement of a resolver.

| ID | Private DNS | Lookup | Expected |
|---|---|---|---|
| FIX-DNS-001 | off | resolved, 12 ms | evidence sample, no failure |
| FIX-DNS-002 | off | not resolved | DNS failure, kind not resolved |
| FIX-DNS-003 | opportunistic | client timer expired | DNS failure, kind client timed out |
| FIX-DNS-004 | strict | not resolved (invalid provider host) | DNS failure, kind not resolved, mode recorded |
| FIX-DNS-005 | off | cache pair, miss 45 ms and hit 1 ms | hit faster than miss. Ground truth needs reference-server query logs. |

### 2.6 HTTPS outcome fixtures

L1 is the transport-level outcome. L2 is the domain failure. The state is the derived reachability state. "Flag" is the platform captive-portal flag at probe start or end.

| ID | L1 outcome | Flag | L2 | State |
|---|---|---|---|---|
| FIX-HTTPS-001 | success, nonce ok | no | succeeded | AVAILABLE |
| FIX-HTTPS-002 | DNS failed | no | DNS failure | DNS FAILURE |
| FIX-HTTPS-003 | connect refused | no | endpoint failure | UNAVAILABLE |
| FIX-HTTPS-004 | timed out at connect | no | timeout at connect | TIMEOUT |
| FIX-HTTPS-005 | TLS hostname mismatch | no | TLS failure | TLS FAILURE |
| FIX-HTTPS-006 | TLS hostname mismatch | start | captive portal suspected | CAPTIVE PORTAL |
| FIX-HTTPS-007 | unexpected 302 | no | unexpected redirect | UNAVAILABLE |
| FIX-HTTPS-008 | unexpected 302 | end | captive portal suspected | CAPTIVE PORTAL |
| FIX-HTTPS-009 | success status, nonce not echoed | no | invalid response, nonce mismatch | UNAVAILABLE |
| FIX-HTTPS-010 | portal-like HTML, nonce not echoed | both | captive portal suspected | CAPTIVE PORTAL |
| FIX-HTTPS-011 | connect refused | both | endpoint failure | UNAVAILABLE |
| FIX-HTTPS-012 | network changed mid call | no | network changed | UNKNOWN |
| FIX-HTTPS-013 | blocked by device policy | no | blocked by device policy | UNKNOWN |
| FIX-HTTPS-014 | unmapped exception | no | unclassified, defect signal | UNKNOWN |
| FIX-HTTPS-015 | response too large | no | invalid response, too large | UNAVAILABLE |
| FIX-HTTPS-016 | TLS certificate invalid | both | captive portal suspected | CAPTIVE PORTAL |
| FIX-HTTPS-017 | TLS protocol or cipher failure | both | TLS failure | TLS FAILURE |
| FIX-HTTPS-018 | HTTP 500, not a redirect | no | invalid response, unexpected status | UNAVAILABLE |
| FIX-HTTPS-019 | malformed body | end | captive portal suspected | CAPTIVE PORTAL |

The pairs FIX-HTTPS-005 with FIX-HTTPS-006, and FIX-HTTPS-011 and FIX-HTTPS-017 with the portal-flagged rows, encode the two-signal rule: neither the flag alone nor an interception signal alone yields a suspected portal.

### 2.7 Freshness and confidence fixtures

Timestamps use the year 2000 so they cannot be mistaken for real observations.

| ID | Case | Expected |
|---|---|---|
| FIX-FRESH-001 | now 1 s before expiry | not stale, age 59000 ms |
| FIX-FRESH-002 | now exactly at expiry | not stale (strictly after is required), age 60000 ms |
| FIX-FRESH-003 | now 1 ms after expiry | stale, age 60001 ms |
| FIX-FRESH-004 | no expiry, one day old | not stale, age 86400000 ms |
| FIX-CONF-001 | 1 sample | Insufficient |
| FIX-CONF-002 | 2 samples | Insufficient |
| FIX-CONF-003 | 3 samples, inconsistent | Low |
| FIX-CONF-004 | 9 samples, consistent | Low |
| FIX-CONF-005 | 10 samples, inconsistent | Medium |
| FIX-CONF-006 | 10 samples, consistent | High |

Jitter confidence is not asserted by any fixture. The foundation branch defines its own provisional function over pair count, and those thresholds are marked provisional in its own comments. Asserting them here would freeze provisional numbers.

## 3. Evidence model (minimum evidence for a physical-device test)

Each item requested for the minimum evidence set maps to a schema property. The privacy class column states how sensitive the field is and what the schema does to limit it.

| Required evidence | Schema property | Privacy handling |
|---|---|---|
| Test ID | `test_id`, `test_case_ref` | Opaque IDs. Synthetic records must start with FIXTURE-. |
| Device model | `device.manufacturer`, `device.model`, `device.device_class`, `device.oem_skin` | Model is not personal. `device_ref` is a lab inventory label. Serial numbers, IMEI and accounts have no field. |
| Android API | `device.android_api`, `device.android_release`, `device.build_fingerprint` | Not personal. |
| App build | `build.build_type`, `build.app_version_name` | Not personal. |
| Git commit | `build.git_commit` (40 hex), `build.branch`, `build.dirty_tree` | Not personal. |
| Network type | `network.transport`, `network.underlying_transport` | Not personal. |
| Carrier if relevant | `network.carrier_label` | Label only, 32 characters at most. No ICCID, IMSI or phone number field. |
| Wi-Fi state if relevant | `network.wifi_band`, `network.ap_ref` | Band and a lab access point label. There is no SSID or BSSID field. |
| VPN state | `network.vpn_present` | Not personal. |
| Metered state | `network.metered` | Not personal. |
| Validation state | `network.validated`, `network.captive_portal_reported`, `network.blocked_by_policy` | Not personal. |
| Measurement output | `measurement.*` (capability, method id, outcome kind, failure, samples, derived values) | Values only. No addresses. Failure kinds come from a closed list. |
| Timestamp | `execution.started_at`, `execution.ended_at` | RFC 3339. Coarse `environment.time_bucket` is available where the exact time is unnecessary. |
| Logs | `logs[]` (kind, relative path, sha256, redaction reviewed, contains bug report) | Only filtered artifacts. The bug report flag is fixed to false. |
| Expected result | `expected.status_label`, `expected.classification`, `expected.pass_criteria`, `expected.tolerances` | Tolerance provenance is mandatory so no threshold is passed off as validated. |
| Observed result | `observed.classification`, `observed.summary` | Free text, 500 characters at most. Reviewer must check for personal data. |
| Pass or fail | `verdict.result`, `verdict.reason`, `verdict.investigation_branch` | Branch names match the failure decision tree in the harness document. |
| Environmental limitations | `limitations[]`, `environment.*`, `condition.impairment` | Site is a coarse label, never coordinates. Impairment records configured values, not results. |

Endpoint identity is a lab label (`endpoint.role` is LFS, CRS or NONE). The production endpoint role is deliberately absent, and the label pattern forbids dots, colons and slashes, so a hostname or URL cannot be stored.

## 4. Machine-readable structure

### 4.1 Schema rules that encode validation policy

| Rule | Effect |
|---|---|
| Closed property sets everywhere | Any unlisted property, including ssid, gps, phone or account fields, fails validation. |
| Synthetic flag and identifier prefix | A synthetic record must have a FIXTURE- test ID. A non-synthetic record must not. |
| Verdict versus execution | PASS, FAIL and INCONCLUSIVE require `executed` true. NOT_RUN and BLOCKED require it false. Executed records require start and end times. |
| Emulator versus layer | An emulator record must be layer L2 or L3 and device class EMULATOR. Layers L4 to L7 require a physical device. Non-emulators may not claim class EMULATOR. |
| Cancellation | A cancelled measurement must carry no samples, no derived values and no failure. Cancellation never yields a value. |
| Declines | Declined and not-attempted outcomes carry no samples, derived values or failure. |
| Failed measurement | A failed measurement must carry a failure object. |
| Log attachments | Relative paths only, no parent directory segments, no URLs, and the bug report flag must be false. |
| Endpoint role | Only LFS, CRS or NONE. |

### 4.2 Versioning

`schema_version` is the constant `aeriva-evidence/1`. A change that removes or renames a property, or narrows an enumeration, creates version 2. A record never changes its version. The failure and capability enumerations mirror names in the Phase 4 cross-cutting contract and core:model. They are serializations for evidence, not a second domain model. An unknown value is rejected on purpose so drift from the canonical names is detected instead of silently accepted.

### 4.3 The checker

`validation/tools/validate_validation_assets.py` performs these checks. It needs python3 and the jsonschema package.

| Check | Detail |
|---|---|
| Schema validity | The schema is a valid draft 2020-12 schema. |
| Valid examples | The three synthetic records validate. |
| Negative cases | 15 mutations of valid records must each be rejected: extra SSID field, URL in the endpoint label, PASS without execution, emulator on a physical layer, bug report attached, cancelled with samples, fixture ID on a real record, failed measurement without a failure, short commit hash, location field, production endpoint role, absolute log path, executed without a start time, over-long carrier label, parent directory in a log path. |
| Fixture oracles | Each fixture's expected values are recomputed by a reference oracle written from the documented semantics and compared. |
| Document lint | No em dash or en dash. URL hosts must be on an allowlist (so a production endpoint cannot appear). Every fixture is described in a document. Every scenario and matrix row referenced exists. |

The oracles are not the Kotlin. They were written from the contract text and from reading the Kotlin. A difference between an oracle and the Kotlin output is a finding to investigate. Neither side is assumed correct.

## 5. Storage of real evidence, retention and privacy

| Topic | Rule |
|---|---|
| What is committed | Only synthetic fixtures and examples. Real evidence records from physical-device runs are not committed by this checkpoint. |
| Where real evidence lives | OPEN DECISION: a lab evidence store outside the git history. No database is introduced here. Records are plain JSON files that validate against the schema. |
| Attachments | Store outside git. Reference by relative path and sha256 only. |
| Packet captures | Treat as sensitive. Capture only on an isolated lab network where every client is a test device. A capture on a shared network holds other people's traffic. Encryption hides payloads but not addresses or plain DNS. |
| Retention | OPEN DECISION for the owner. No period is proposed here. |
| Free text | `observed.summary`, `verdict.reason`, `condition.impairment.notes` and `expected.pass_criteria` are the residual leak paths. A reviewer must check them before any record leaves the lab. |
| Diagnostic output | `dumpsys` output can contain network names, addresses and cell identifiers. Store excerpts of the fields needed and review them. Full bug reports are prohibited. |

The full list of data that must not be captured is in the harness document, section 12.

## 6. Known limitations

1. The fixtures and the checker have not been run against any Kotlin code. No Kotlin fixture runner exists yet.
2. The checker's oracles encode contract text. Where the contract is silent (all-late UDP train, whether jitter rejects negative input) the fixtures say so or avoid asserting.
3. Reply classification for UDP is provisional until the wire protocol specification exists.
4. The schema cannot stop personal data typed into free-text fields.
5. Synthetic examples show shape only. They are not evidence of any behavior.

## 7. Open items

| Item | Owner or dependency |
|---|---|
| Kotlin fixture runner that feeds these fixtures to the real classes | Needs a working Gradle environment and access to CI results. Next recommended task. |
| Replace provisional UDP rules R-U1 to R-U7 with the protocol specification | Phase 4B protocol specification |
| Decide the all-late train outcome | Contract owner and protocol specification |
| Evidence store location and retention period | Owner |
| Add the production endpoint role to the schema | Only after owner approval of production enablement (contract OD-2, OD-3) |
| Schema version 2 fields for cellular and Wi-Fi characteristics once permissions are decided | Depends on owner decisions about location and phone-state permissions |
