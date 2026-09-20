# Phase 4 Implementation Readiness — Canonical Contract

**Reconciliation document only.** No production code, permission, dependency, endpoint, or merge. main untouched. Neither source document (below) was modified.

This document independently compares two existing, unmerged Phase 4 documents, resolves their overlaps and one factual contradiction, and separates their combined 44 open decisions into what actually blocks implementation from starting versus what can be decided later. All branch/CI claims below were independently re-verified this session via the GitHub Project and CircleCI connectors, not taken on either source document's word.

## Source documents

| Document | Branch | Commit | Self-declared author | Scope |
|---|---|---|---|---|
| `PHASE_4_NETWORK_MEASUREMENT_ARCHITECTURE.md` | `phase-4-measurement-architecture` | `f279476c` (replaces an earlier placeholder-text commit, `921fabb3`, same branch) | not stated in-document | System design: components, data flow, endpoint options, permission/battery/security architecture. 14 open decisions (its own Section 21). |
| `PHASE_4_REAL_DEVICE_VALIDATION_PLAN.md` | `phase-4-real-device-validation` | `3589da4e` | "AI 6" (per the document's own title block) | Empirical validation methodology: what to test, how, against what ground truth, in what order. 30 open decisions, labeled D-01 through D-30 (its own Section 26). |

**Naming note:** this task refers to these as "AI 5's validation plan" and "AI 6's architecture." The validation plan's own title block instead says "Author: AI 6." Neither document names the other's author. This is a labeling inconsistency in the source material, not something this document resolves — the branch names above are used as the stable identifier throughout instead of an AI number.

Both documents were independently written against the repository and both explicitly refuse to add code, permissions, dependencies, or an endpoint. Both cite the reconciliation (`phase-3b-repository-state-reconciliation`) and engine-integration-audit (`phase-3b-engine-integration-audit`) branches as prior work they build on rather than duplicate.

---

## 1. Executive Summary

- The two documents do not actually conflict on any point of engineering substance. They cover different layers (architecture vs. validation methodology) and agree everywhere they overlap: `INTERNET` is undeclared, no production `NetworkClient` exists, the domain model covers latency only, packet loss cannot be directly measured on stock Android, and the endpoint decision is the single highest-leverage open item.
- **One factual contradiction was found, and it is a staleness problem in the architecture document, not a disagreement between the two authors.** The architecture doc's Section 2 branch table states `phase-3b-measurement-engine` is at `d1ea16f` and its CI `build` job fails. That was true when the engine-integration-audit examined it, but the engine branch moved to `727b2a91` on 2026-09-19 (three fix commits landing the missing classifier/`NetworkClient` files and a gradle dependency) and CI has been fully green (all 4 jobs) since — independently reconfirmed again this session. The validation plan, written after that fix landed, correctly cites `727b2a91` as its base and states the engine "compile-fix commits have landed." **Resolution: the architecture document's Section 5.1 "integration order" proposal, which frames landing the three Phase 3B branches in dependency order as the mechanism that resolves the CI failure, is moot as written — the failure it describes no longer exists, and it was actually resolved by AI 2 copying the dependency files directly onto the engine branch, not by a merge-order change.** The architecture document's broader point (land the three branches in dependency order before or alongside a real `NetworkClient` implementation) still holds as a merge-sequencing recommendation; only its causal claim about what fixes the CI failure is outdated.
- One real, unresolved overlap (not a contradiction) exists: both documents independently need a server. The architecture document's **endpoint strategy** (Section 11, its Open Decision #6) is about the production measurement target real users' probes run against. The validation plan's **"controlled reference server"** (cited throughout Section 7 and as a blocking prerequisite in its Section 24) and **loopback test server** (D-29) are about ground-truth infrastructure used only during validation. Neither document states whether these are meant to be the same server, related servers, or entirely separate. **This document treats them as separate by default** (a test/reference server has different requirements — known, injectable delay; sequence-numbered UDP support for loss calibration — than a production endpoint optimized for low latency and abuse resistance) and flags the relationship as an open question that should be answered explicitly, not assumed either way, when the endpoint decision is made.
- Of the combined 44 open decisions, this document identifies **5 as genuine cross-cutting prerequisites** that block any implementation work from starting, **6 as capability-specific prerequisites** that block only one capability's implementation, and **33 as properly deferred** — correctly left open by both source documents, not needed before implementation begins. See Section 4 for the full register.

---

## 2. Points of Full Agreement (no reconciliation needed)

Verified independently against the current repository state, not merely because both documents claim it:

| Fact | Confirmed how |
|---|---|
| `main` is `e3f70a4a13e6`, Phase 3A only, no Phase 3B/3C code | `get_commit` on `main` HEAD, this session |
| No `INTERNET` permission is declared anywhere in the repository, on any branch | Both documents independently read every `AndroidManifest.xml`; consistent with this session's own prior audits |
| No production `NetworkClient` implementation exists on any branch — only the test-only `FakeNetworkClient` | Confirmed independently in `phase-3b-engine-integration-audit` (this session's own prior work) and by both Phase 4 documents |
| The domain model (`core:model/measurement`) covers latency only — no jitter, packet-loss, throughput, or extended-DNS/HTTPS-state types exist | File-tree check, this session |
| Packet loss cannot be directly measured over TCP on stock Android without root/raw sockets | `MeasurementCapabilityClassifier`'s own `SupportedWithLimitations` classification and reasoning string, read directly from source |
| `PHASE_3C_ANDROID_REAL_DEVICE_VALIDATION_MATRIX.md`'s claim that `FLAG_INCLUDE_LOCATION_INFO` is being removed in favor of a `NetworkCallback.Builder` is wrong (the "removal" notice is from Microsoft's .NET binding docs, not the Android SDK) | Both documents independently researched and cited the same correction; this document does not re-verify the underlying Android API claim but notes both source documents agree and cite primary sources (`developer.android.com`) for it |
| The engine's timing starts before the dispatcher hop and does not decompose DNS/TCP/TLS phases | Both documents independently read `LatencyMeasurementEngine.kt`'s source and agree on this exact finding |

---

## 3. Where the Documents Complement Rather Than Duplicate

Not contradictions — noted so a reader of only one document knows the other covers this:

- **Domain-model extension**: the architecture doc specifies *what to build* (Section 5.2 — sealed `{Metric}Measurement` families for jitter/packet-loss/throughput only, following `LatencyMeasurement`'s exact shape; Wi-Fi/cellular extend `MeasurementNetworkContext` instead of getting their own type). The validation plan specifies *what evidence closes it* (Section 8's jitter definition choice D-10, Section 23's per-capability evidence table). Neither invents the other's half.
- **NetworkClient**: the architecture doc proposes the production design (Section 10 — raw sockets recommended for latency/jitter/packet-loss, OkHttp deferred as a real-dependency decision). The validation plan specifies how to prove that implementation is trustworthy once built (Section 7's timing-source and reference-hierarchy requirements, Section 9's packet-loss calibration ladder).
- **Packet loss**: the architecture doc classifies it once (`SupportedWithLimitations`, Section 8's table). The validation plan turns that into an operational four-stage honest-labeling ladder (Section 9.4, stages A–D) that the architecture doc does not attempt.
- **Android 17 / API 37**: both independently researched and cite the same release date (June 16, 2026) and the same two measurement-relevant changes (cleartext-traffic deprecation direction, cross-profile loopback restriction) — the architecture doc places this in its version matrix (Section 13); the validation plan does not restate it but does not contradict it either.

---

## 4. Consolidated Decision Register

All 44 open decisions (14 from the architecture doc, 30 from the validation plan), deduplicated where two decisions are actually the same question asked from two angles, classified by whether they block implementation from starting at all.

### 4.1 BLOCKING — cross-cutting (implementation cannot meaningfully begin without these)

| # | Decision | Source(s) | Why it blocks everything, not just one capability |
|---|---|---|---|
| B-1 | **`INTERNET` permission** — whether/how to add it | Arch §12 (permission matrix, explicit "not yet" gap); Validation §24 ("INTERNET permission decision: Open") | Every active-probe capability (latency, jitter, packet loss, throughput, DNS, HTTPS-reachability) classifies `NotReliablyAvailable` without it. Nothing in this document's scope, or either source document's scope, adds it — it is the first decision that must be made by someone with product/permission authority before any of the other blocking items matter. |
| B-2 | **Endpoint strategy** — production target *and* its relationship to validation/reference infrastructure (see Section 1's flagged overlap) | Arch §11 (Open Decision #6, its own "single highest-priority"); Validation §24 ("Controlled reference server and endpoint policy: Missing") | Every timing number is meaningless without a target; protocol (B-3), redirect/proxy handling, IPv4/IPv6, auth, and abuse-prevention design are all explicitly downstream of this one decision in the architecture doc itself. |
| B-3 | **Socket vs. HTTP-client approach for NetworkClient**, and whether OkHttp is added as a dependency | Arch §10.2 (Open Decision #2) | Determines whether a production `NetworkClient` can be written at all, and constrains what per-phase timing (DNS/TCP/TLS breakdown, Validation §7.3's TEST REQUIREMENT) is even possible to expose. |
| B-4 | **Domain-type extensions for jitter, packet loss, throughput** (sealed families matching `LatencyMeasurement`'s shape) | Arch §5.2 (proposed design); Validation §24 ("Domain types for jitter, loss, throughput, DNS: Missing") | No capability beyond latency can be implemented — not designed, not stubbed — without its result type existing. |
| B-5 | **Failure taxonomy extension** (DNS failure, captive-portal, blocked, unknown cases beyond the current four `MeasurementFailure` cases) | Validation §24 (explicit blocking item); implied but not separately decision-tracked in the architecture doc (§10.6 notes captive portal already maps to existing cases, but DNS-specific failure is not addressed) | A probe that fails for a reason the current sealed type cannot represent has nowhere honest to go — this is a compile-time-enforced gap, not a nice-to-have. |

### 4.2 BLOCKING — capability-specific (block one capability, not the others)

| # | Decision | Blocks | Source(s) |
|---|---|---|---|
| C-1 | Jitter definition — IPDV vs. PDV (D-10) | Jitter domain type design (part of B-4) and all jitter validation | Validation §8.2 |
| C-2 | Final HTTPS-reachability state model and its domain-type additions (D-17) | HTTPS-reachability capability specifically — the plan states not all seven proposed states are currently representable | Validation §23 (evidence table) |
| C-3 | Loopback test server approach (D-29) | The validation plan's own P2 stage (JVM loopback: TLS, redirect, malformed, timeout, refuse) cannot run without deciding this — and per Section 1's flagged ambiguity, this may or may not be the same infrastructure as B-2 | Validation §24, §26 |
| C-4 | Whether cellular characteristics are worth their permission cost (D-18) | Cellular-characteristics capability only — a product decision, not a technical blocker on anything else | Validation §26 |
| C-5 | Request/response wire protocol (downstream of B-2/B-3) | Any capability's actual probe implementation, but only once B-2/B-3 are already decided — listed here rather than in 4.1 because it has no independent content until its two prerequisites are resolved | Arch §10.3 (Open Decision #3) |
| C-6 | User-installed CA stance (D-25) | Production TLS-handling correctness for HTTPS-reachability and any TLS-based probe — a real security decision needed before, not after, a production `NetworkClient` ships, but does not block starting work on latency/jitter (which the architecture doc recommends implementing over raw sockets, not TLS, first) | Validation §26 |

### 4.3 DEFERRABLE — correctly left open by both documents, not needed before implementation begins

The remaining 33 decisions are, on independent review, genuinely fine to leave open: they gate *release* (device coverage, numeric budgets, repeatability thresholds), gate a *feature that is not required for a first implementation* (automatic scheduling/WorkManager — a user-triggered measurement needs no scheduler at all, per Arch §14's own reasoning), or gate a *later phase* (targetSdk 37 migration, generic `Measurement<T>` unification, community/peer infrastructure). Listed for completeness rather than re-argued, since neither source document disputes their own deferral and this review found no reason to override either:

Arch OD #1 (generic `Measurement<T>`), #4 (redirect/proxy — downstream of B-3), #5 (IPv4/IPv6 — downstream of B-2), #7 (targetSdk 37), #8 (battery/data numeric budgets), #9 (scheduler/WorkManager), #10 (backoff multiplier), #11 (endpoint auth/abuse-prevention — downstream of B-2), #12 (HTTP-failure granularity — downstream of B-3), #13 (network-condition test methodology — properly the validation plan's territory, see Section 3), #14 (doc-hygiene: reconstruct the missing implementation `.md`).

Validation D-01 through D-09, D-11 through D-16, D-19 through D-24, D-26 through D-28, D-30 (device tiers/counts/coverage, foreground-service, carrier-specific testing, network-stability definition, numeric agreement bounds, jitter sample count/repeatability, packet-loss/throughput calibration parameters, battery instrumentation specifics, data budgets, scheduler default cadence, endpoint auth freshness downstream of B-2, server logging/retention policy, per-condition sample counts, confidence calibration rate, physical lab approach).

---

## 5. Recommended Sequence

Derived directly from Section 4's classification, not a new design:

1. Resolve B-1 (INTERNET) and B-2 (endpoint, including its relationship to test infrastructure per Section 1) — both are product/authority decisions this document cannot make and neither source document attempted to.
2. Resolve B-3 (socket vs. HTTP-client) — an engineering decision with a stated recommendation already on record (Arch §10.2: raw sockets for latency/jitter/packet-loss).
3. With B-1–B-3 resolved: land the three existing Phase 3B branches (classifier, `NetworkClient`+tests, engine) per the architecture doc's Section 5.1 dependency order — noting, per Section 1 above, that the engine branch itself is already CI-green standalone, so this step is a normal merge, not a fix.
4. Add B-4 (domain types) and B-5 (failure taxonomy) alongside the capability being implemented — the architecture doc's own precedent (Phase 3A shipped one illustrative metric before generalizing) argues for extending one capability (jitter is the natural next one — same transport as latency, per Arch §8's table) rather than all three at once.
5. Resolve capability-specific items (C-1 through C-6) as each capability is reached, not all upfront.
6. Validation (the plan's own P1–P9 sequence, Validation §24) runs alongside implementation from P1 (JVM contract tests, needs only B-3 resolved) rather than waiting for the full sequence above to finish — P4 onward (physical device, impairment lab, multi-device) is genuinely gated on infrastructure (C-3, D-30) that can be procured in parallel with steps 1–4.

---

## 6. Explicit Non-Goals

Restated from this task's own instructions and both source documents' shared self-restriction: no production code, permission, dependency, or endpoint was added or chosen by this document. No branch was merged; `main` remains `e3f70a4a13e63b782c61601abadc2c36f65dbd73`. Neither `PHASE_4_NETWORK_MEASUREMENT_ARCHITECTURE.md` nor `PHASE_4_REAL_DEVICE_VALIDATION_PLAN.md` was edited — this document supersedes neither; it sits alongside both as the reconciliation layer a reader should consult first.

---

*End of readiness document.*
