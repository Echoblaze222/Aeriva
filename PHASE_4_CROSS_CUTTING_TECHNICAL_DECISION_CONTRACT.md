# PHASE 4: CROSS-CUTTING TECHNICAL DECISION CONTRACT

Role: technical decision-contract owner for Phase 4.
Date written: 2026-09-20.
Scope: decision documentation only. No production code, permission, dependency, `NetworkClient` implementation or endpoint is created or changed by this document. `main` is untouched.

Base branch: `phase-4-implementation-readiness` @ `e86f26a9ddb698ee6d2aa74879e168261ef9e2b9` (main plus the readiness document only).
Phase 3B code referenced below exists only on `phase-3b-measurement-engine` @ `727b2a91d2724735c3f22965ca72cf4311202370` and is not part of the base branch. Every code reference names that commit.

Source documents read for this contract:

| Document | Branch and commit | How it was read |
|---|---|---|
| `PHASE_4_IMPLEMENTATION_READINESS.md` | `phase-4-implementation-readiness` @ `e86f26a` | Fetched in full this session |
| `PHASE_4_NETWORK_MEASUREMENT_ARCHITECTURE.md` | `phase-4-measurement-architecture` @ `f279476` | Fetched in full this session |
| `PHASE_4_REAL_DEVICE_VALIDATION_PLAN.md` | `phase-4-real-device-validation` @ `3589da4` | Reviewed as authored earlier in this engagement. Not re-fetched. Branch tip confirmed unchanged via `list_branches` this session. |

## Label legend

| Label | Meaning |
|---|---|
| VERIFIED FACT | Read from official or primary documentation fetched in this session, with the source named in Section 17. |
| REPO FACT | Read directly from code in this repository at the commit named. |
| PRIOR-DOC FACT | Stated in an earlier AERIVA document or commit message and not re-verified here. |
| DECISION | A binding technical decision for Phase 4 implementation. May be revised only by a later, explicitly versioned revision of this contract. |
| OWNER DECISION | Requires the product owner. Not resolved here. Register in Section 12. |
| ASSUMPTION | Believed true, unverified. Must be verified or removed before it is relied on. |
| LIMITATION | A hard constraint on what can be claimed or built. |
| TEST REQUIREMENT | A test that must pass before the related claim is allowed. |

Nothing in this document has been compiled, run or executed. No CI result is claimed.

---

## 1. Executive summary

Five cross-cutting blockers were carried forward by the readiness document (B-1 to B-5). This contract resolves the technical content of all five and isolates the parts that only the owner can decide.

| # | Blocker | Technical status | Owner gate |
|---|---|---|---|
| D1 | `INTERNET` permission and manifest ownership | RESOLVED. Declared once, in the manifest of the module that owns the production client, in the same change as that client. Never in `:app` alone. | OD-1 must be recorded before the permission is added. |
| D2 | Production measurement endpoint strategy | RESOLVED at requirements level. The production endpoint (PME) must be first-party controlled for every MEASUREMENT-tier result, and must be a different system from the controlled reference server (CRS) and the in-process loopback fixture (LFS). | OD-2, OD-3, OD-4 gate production enablement. Implementation can start without them. |
| D3 | Raw sockets versus HTTP client | RESOLVED. HTTPS-based probes use OkHttp behind the existing `NetworkClient` seam. UDP probes use platform `DatagramSocket` behind a separate seam. Hand-rolled TLS sockets and `HttpsURLConnection` are rejected. | OD-7 (dependency admission) gates adding OkHttp. |
| D4 | Domain-model extensions | RESOLVED. Jitter is a derived value over ordered latency samples, not a new probe type. Packet loss is modeled as an unanswered-probe train and is not named "packet loss". Throughput gets its own sealed family. A shared `ProbeEvidence` type is added. | OD-8 for user-facing naming only. |
| D5 | Failure taxonomy | RESOLVED. Four layers (decline, transport, domain, interpretation). New failure kinds, stage-aware timeouts, a distinct suspected-captive-portal case, and a defect-flagged `Unclassified` case. Client never throws. | None. |

Five findings that changed earlier recommendations (Section 2.3):

1. Raw `SSLSocket` does not verify hostnames on Android. Apps must do it themselves (VERIFIED FACT). This is the decisive security argument against hand-rolled TLS probes.
2. `HttpsURLConnection` cannot report whether a pooled connection was reused, and has no phase events. A latency number whose cold or warm state is unknown cannot be labeled. It is rejected for measurement.
3. OkHttp exposes DNS, connect, TLS and request/response events and fires no connect events on pooled connections, so cold and warm are observable (VERIFIED FACT).
4. The architecture document overstates Android 17's cleartext change. Official text says the platform plans to deprecate `usesCleartextTraffic` and steer apps to Network Security Configuration. It does not state a new default (VERIFIED FACT).
5. A captive portal must be a distinct, evidence-based case (`CaptivePortalSuspected`), and `NetworkState` has no field for it. The Phase 2 type's own KDoc says a feature that needs a capability must get a named field. Two are added.

What can start now without any owner decision: the JVM-only migration of the failure taxonomy, `ProbeEvidence`, the new `NetworkState` fields and the method registry (Section 10, slice S1).

What cannot ship without the owner: outbound network access (OD-1), a production endpoint (OD-2 to OD-4), any data-budgeted feature (OD-6), and any third-party endpoint use (OD-5).

---

## 2. Repository and source-document baseline

### 2.1 Repository facts (REPO FACT unless stated)

| Item | Value |
|---|---|
| `main` | `e3f70a4a13e63b782c61601abadc2c36f65dbd73` (Phase 3A only). Not touched. |
| Readiness branch | `e86f26a`, contains only the readiness document on top of main |
| Phase 3B engine branch | `727b2a9`. Contains `MeasurementCapabilityClassifier`, `NetworkClient`, `LatencyMeasurementEngine`, `DerivedLatencyStats.from`. CI status is PRIOR-DOC FACT (readiness document says all four jobs green). Not re-run here. |
| SDK | `minSdk = 26`, `targetSdk = 36`, `compileSdk = 36` |
| Toolchain | AGP 8.13.2, Kotlin 2.3.21, coroutines 1.11.0, KSP 2.3.11, Gradle wrapper 8.14 per `libs.versions.toml` comments |
| Dependencies | No HTTP or socket library. JUnit4, androidx.test, coroutines-test. No mocking library. |
| Manifests | `app`: no permissions, no launcher activity. `network:monitor`: `ACCESS_NETWORK_STATE` only. No `networkSecurityConfig`, no `usesCleartextTraffic` anywhere. |
| Module graph | `:app` does not depend on `:network:monitor`. `core:model` is a pure JVM module. |
| `NetworkClient` | Interface only: `suspend fun probe(target: String): NetworkClientOutcome`. Outcomes: `Success(payload)`, `ConnectionRefused`, `TlsHandshakeFailed`, `NetworkChangedMidCall`. |
| `MeasurementFailure` | Sealed: `Timeout`, `Cancelled`, `EndpointFailure`, `TlsFailure`, `InvalidResponse`, `NetworkChangedDuringMeasurement`. KDoc says deliberately closed with no unknown case. |
| `NetworkState` | `transport`, `available`, `validated`, `metered`, `capabilities` (diagnostic only, per its KDoc), `estimatedQuality`, `diagnosticsStatus`, `lastChangedAt`. No captive-portal or VPN field. |
| `NetworkStateMapper` | `available = true` whenever a snapshot exists. VPN takes priority as the reported transport and hides the underlying transport. |
| `LatencyMeasurementEngine` | Declines with `CapabilityUnavailable` or `NoNetwork`. Otherwise times `withContext(io) { withTimeout(5000 default) { probe } }` with `elapsedNanos` sampled before the dispatcher hop. Catches only `TimeoutCancellationException`. Does not catch other exceptions. Payload must be exactly 8 bytes (placeholder). `method` is a free-text string defaulting to `"tcp-round-trip"`. `measureSeries` is sequential, has no interval and no sample cap. |
| `LatencyMeasurement` | Sealed `Succeeded(id, context, measuredAt, method, valueMillis, sampleCount)` or `Failed(..., failure)`. No sequence index. |

### 2.2 Registers this contract closes or changes

Readiness B-1 to B-5, C-1, C-2, C-3, C-5, C-6; validation plan D-10, D-17, D-24, D-25, D-29; architecture open decisions 2, 3, 4, 5, 12. Detail in Section 13.

### 2.3 Corrections and discrepancies found in the source documents

| # | Source | Statement | Finding | Effect |
|---|---|---|---|---|
| X-1 | Architecture 3.5, 5.1 | Engine branch fails CI | Stale. Readiness document already records the engine branch moved to `727b2a9` and is green. | None here. |
| X-2 | Architecture 13 | Android 17 moves cleartext toward blocked by default, gated on target SDK | Official Android 17 text says the platform plans to deprecate the `usesCleartextTraffic` element and steer apps to Network Security Configuration, which is supported on API 24 and higher. No default change is stated. (VERIFIED FACT) | AERIVA is HTTPS-only regardless (D1, D2). |
| X-3 | Architecture 16 | No dedicated captive-portal failure needed | Overridden. The validation plan requires a distinct CAPTIVE PORTAL state that is never collapsed into generic failure. | D5 |
| X-4 | Architecture 10.2 | Raw sockets for latency, jitter, packet loss | Overridden for TCP and TLS. Retained for UDP. | D3 |
| X-5 | Architecture 10.6 | Fresh connection per probe, no reuse | Overridden. Jitter needs many cheap samples on one connection. Cold and warm are separate, labeled methods. | D3, D4 |
| X-6 | Phase 3A `MeasurementFailure` KDoc | Closed set, no unknown case | Overridden narrowly. A real transport can raise arbitrary exceptions. The engine must never throw. A defect-flagged `Unclassified` case is required. | D5 |
| X-7 | Phase 2 `NetworkState` KDoc | Capabilities set is diagnostic only | Honored. Captive portal and VPN presence get named fields instead of set parsing. | D4, D5 |
| X-8 | Phase 3B `NetworkClient` KDoc | Timeout is the caller's concern | Revised. Stage-attributed timeouts require the client to know the deadline. The engine keeps an outer backstop. | D3, D5 |
| X-9 | Validation plan 12.1 | Seven HTTPS states as if a stored enum | Reframed as a pure derived view over measurement plus failure, not a second stored taxonomy. | D4, D5 |
| X-10 | Readiness 4.2 C-5 | Wire protocol has no independent content until B-2 and B-3 resolve | Now resolvable at requirements level. | D2, D3 |

---

## 3. Three-system vocabulary (used throughout)

The readiness document flagged that endpoint strategy and validation infrastructure were never explicitly separated. This contract separates them.

| | PME: production measurement endpoint | CRS: controlled reference server | LFS: loopback fixture server |
|---|---|---|---|
| Purpose | What user devices probe in production | Ground truth for validation on real devices and networks | Deterministic fault fixtures for automated tests |
| Runs where | AERIVA-operated public infrastructure | Lab, under validation team control | In-process on JVM or emulator host loopback |
| Audience | Real users | Validation engineers | CI and developers |
| Reachable from | Public internet | Lab network only, or allow-listed | Loopback only |
| Behavior | Fast, abuse-resistant, stable | Configurable known delay, fault injection, sequence logs, packet capture | Scripted per-test responses |
| TLS certificate | Public CA, AERIVA-owned name | Lab CA or public CA on a lab-only name | Test CA generated per run |
| Logging | Governed by the owner's privacy policy (OD-3) | Full logging, lab data only | Test assertions |
| Hostnames in production build | Yes (the only ones) | Never | Never |
| Ground-truth role | Never. It is the thing under test. | Yes | Deterministic correctness only, never network truth |
| Shared with the others | The written wire-protocol specification and a conformance test suite, nothing else | Same | Same |

Binding rule (DECISION D2-1): PME, CRS and LFS are three separate systems. No shared hostname, credential or deployment. A validation result is never produced by measuring the PME against itself. The only artifacts shared across the three are the protocol specification and the conformance suite, so that a client proven against LFS and CRS behaves identically against PME.

---

## 4. Decision D1: INTERNET permission and manifest ownership

### D1.1 Problem

- No `INTERNET` permission is declared in any manifest on any branch. The classifier therefore returns `NotReliablyAvailable` for every active capability on a real device today (REPO FACT).
- `:app` does not depend on `:network:monitor`, so nothing shipped would carry the permission anyway (REPO FACT).
- Android permissions are app-wide, not module-scoped. "Which module owns it" is a governance and build question, not an enforcement one.
- The classifier takes `grantedPermissions` as input from the caller. If a caller supplies a static set, the classifier can say `Supported` while the merged manifest lacks the permission.

### D1.2 Options considered

| Option | Description |
|---|---|
| A | Declare in `:app` only |
| B | Declare in `:network:monitor` (module that owns the client, engine, classifier today) |
| C | Create a new measurement module and declare there |
| D | Declare in both `:app` and the network module |
| E | Do not declare yet; keep observation-only |

### D1.3 Authoritative evidence

- Gradle merges all manifest files, including those of imported libraries, into the single manifest packaged into the app (VERIFIED FACT, developer.android.com manifest merging page).
- When `INTERNET` is missing, platform DNS resolution fails with EACCES and libcore raises `SecurityException("Permission denied (missing INTERNET permission?)")` (VERIFIED FACT, AOSP libcore `Inet6AddressImpl` source). This is a runtime exception, not an `IOException`.
- `INTERNET` is a normal permission whose platform description is "Allows applications to open network sockets" (confirmed through secondary quotations of the platform manifest and the reference page. The official reference page itself was not fetched.)
- `ACCESS_NETWORK_STATE` declared in `network:monitor` already reaches the instrumented run of `AndroidNetworkMonitorInstrumentedTest` (PRIOR-DOC FACT: CI ran it on the API 30 emulator). This is in-repo precedent that a library-declared permission is usable in that module's instrumented tests. It is an inference and is listed as a test requirement below.

### D1.4 Compatibility with the existing architecture

- Precedent: the only existing permission is declared by the module that needs it, with a justification comment. Option B continues that pattern.
- Option A alone would leave the module that opens sockets without the permission in its own instrumented tests (TEST REQUIREMENT to confirm), forcing tests to depend on the app manifest.
- The classifier and engine stay Android-free. A thin Android adapter must supply real `grantedPermissions` at the edge (Section D1.10).

### D1.5 Data and battery implications

The permission itself has no cost. It removes the only OS-level barrier to all outbound traffic, so every data and battery safeguard must be enforced in code (Decision D3, D4 budgets, OD-6). The manifest is not a budget control.

### D1.6 Security implications

- The permission is invisible to users (normal permission, install-time). Its presence must therefore be governed in review, not by a user prompt.
- Manifest merging can add entries from dependencies. OkHttp on Android uses AndroidX Startup (VERIFIED FACT), which adds a provider element to the merged manifest. A governance check must cover providers and permissions, not permissions alone.
- Strip or block cleartext at the source: no `usesCleartextTraffic`, no cleartext network security configuration in the main source set. Defaults for target 28 and higher already block cleartext and trust only system CAs (VERIFIED FACT).

### D1.7 Offline and poor-network implications

Permission state is independent of connectivity. If the permission is absent at runtime the OS raises `SecurityException` at DNS or socket creation. That must map to a decline, never to a network failure (D5), so a build or config defect is not reported to users as "your network is bad".

### D1.8 Testing implications

| Test | Layer |
|---|---|
| Merged-manifest allowlist check for permissions and providers, generated from the first real merged manifest, not guessed | CI static check, later slice |
| Negative test: an instrumented run with `INTERNET` removed by a test-only manifest override, asserting `SecurityException` maps to a decline | Emulator |
| Confirm `checkSelfPermission(INTERNET)` reports denied when the permission is undeclared | Emulator. ASSUMPTION until run. |
| Confirm the library-declared permission is present in the library's instrumented test APK | Emulator. ASSUMPTION until run. |

### D1.9 Rejected alternatives and why

| Option | Reason rejected |
|---|---|
| A: `:app` only | Leaves the socket-owning module's instrumented tests dependent on the app manifest. Divorces the permission from the code that needs it. |
| C: new module now | A structural refactor with no evidence it is needed for Phase 4. If a split is ever done, the declaration moves with the client in the same commit. |
| D: both | Duplicated declaration, drift risk, no benefit since merging unions them. |
| E: keep observation-only | Only correct if the owner declines active probing (OD-1). In that case the existing classifier already yields the correct observation-only behavior. |

### D1.10 Final technical decision

- DECISION D1-1: `INTERNET` is declared exactly once, in the manifest of the module that contains the production `NetworkClient` implementation. Today that module is `:network:monitor`. It is added in the same change that introduces the first production client, not earlier and not separately.
- DECISION D1-2: The declaration carries a justification comment in the style of the existing `ACCESS_NETWORK_STATE` comment.
- DECISION D1-3: No other permission is added by this decision. Location, phone state and Wi-Fi state permissions stay under the Phase 2 rule that each needs its own written justification.
- DECISION D1-4: `grantedPermissions` passed to the classifier must come from an Android-edge adapter that calls `checkSelfPermission` at request time. It must never be a hardcoded set. The classifier and engine remain Android-import-free.
- DECISION D1-5: Adding `:app` to depend on the measurement-bearing module is a separate integration step. Until it happens, no shipped artifact carries `INTERNET`, and this is acceptable.
- DECISION D1-6: A merged-manifest allowlist check (permissions and providers) is added to CI in the first slice that introduces the permission. The allowlist is generated from the real merged manifest.
- DECISION D1-7: No `usesCleartextTraffic` and no cleartext network security configuration in any main source set. A test-only cleartext configuration for loopback fixtures, if needed, lives only in debug or androidTest source sets. (Android 17 plans to deprecate the attribute and points to Network Security Configuration, supported on API 24 and higher: VERIFIED FACT.)
- DECISION D1-8: The permission is added only after OD-1 is recorded (Section 12).

### D1.11 Consequences for Phase 4 implementation

- The first implementation slice that touches manifests is the client slice (S3, Section 10), not the domain slice.
- The Android edge adapter for permissions is a small new component owned by the same slice.
- If OD-1 is refused, Phase 4 becomes observation-only and every active capability keeps returning `NotReliablyAvailable`. No rework is needed.

### D1.12 Consequences for real-device validation

- The validation plan's special-condition row "INTERNET permission unavailable" stays as a negative test, now built from a test-only manifest override rather than from the production manifest.
- Validation plan Section 24 prerequisite "INTERNET permission decision: Open" is closed technically and gated on OD-1.
- Devices under test need a build that contains the permission. Builds without it remain part of the matrix as the negative case.

---

## 5. Decision D2: production measurement endpoint strategy

### D2.1 Problem

- No endpoint of any kind exists. Every timing number is meaningless without a target (readiness B-2).
- The architecture document analyzed hosting options, and the validation plan required a "controlled reference server". Neither stated whether they are the same system. Section 3 defines three separate systems. This section decides what the production one must be.
- Owner-level questions (cost, hosting, privacy, regions) are mixed with technical ones. They must be separated.

### D2.2 Options considered

| Option | Description |
|---|---|
| A | Third-party public endpoints as the endpoint of record (M-Lab NDT, Cloudflare speed endpoints, Google-style connectivity checks) |
| B | Single AERIVA-controlled origin |
| C | Multiple AERIVA-controlled regional endpoints |
| D | AERIVA-controlled logical endpoint fronted by a CDN |
| E | Hybrid: third party first, first-party later |
| F | Community or peer measurement nodes |
| G | Use the same server for production and validation |

### D2.3 Authoritative evidence

- M-Lab publishes all test data, including the IP address assigned to the user's device (VERIFIED FACT, M-Lab FAQ and NDT page). Its Acceptable Use Policy allows anyone to use its services if they follow the policy and obtain user consent. Its NDT test tries to transfer as much data as it can in ten seconds (VERIFIED FACT). Third parties may run private NDT servers that do not contribute to the M-Lab dataset (VERIFIED FACT, M-Lab developer page).
- The Cloudflare speed test module is a browser JavaScript module using `PerformanceResourceTiming`, its results are collected by Cloudflare for aggregated insights, and its endpoint URLs are configurable. Packet loss uses a TURN server (VERIFIED FACT, module README). No published term permitting production use of `speed.cloudflare.com` endpoints by a third-party native app was found in this session. Absence of evidence, not a finding that use is prohibited.
- The Android platform's own connectivity-check URLs are platform-internal configuration and not documented as an app-facing service contract. This was not confirmed by a primary source this session (ASSUMPTION).
- RFC 8762 (STAMP) defines sequence-numbered, timestamped UDP test packets for delay, delay variation and loss, states the offered load must be carefully estimated per RFC 8085, and is designed for operator-managed sessions (VERIFIED FACT, RFC 8762; the single-administrative-domain remark is from RFC 9503). It is a design reference for the UDP probe, not a claim of compliance.
- Android 17 targets: `ACCESS_LOCAL_NETWORK` applies only to apps targeting 37 and to LAN destinations. Cross-profile loopback traffic is blocked on Android 17 and higher for all apps (VERIFIED FACT).

### D2.4 Compatibility with the existing architecture

- The engine already treats `target` as opaque and refuses to hardcode a host (its KDoc). That seam is kept.
- The existing 8-byte payload check is an explicit placeholder awaiting a protocol decision. This section supplies the requirements from which that protocol is written.
- Classifier reasons already state that throughput must default to consented, infrequent and unmetered-aware.

### D2.5 Data and battery implications

- A first-party endpoint lets the server enforce hard byte and duration caps as defense in depth, independent of client bugs.
- Third-party endpoints designed for saturation tests (NDT) are data-heavy by design and unsuitable for automatic flows.
- Latency probes must stay tiny. Throughput and UDP trains are the cost drivers.

### D2.6 Security implications

- The client must be able to prove a response came from AERIVA's server. TLS to an AERIVA-owned name gives server authenticity. A per-request nonce echo defeats cached and replayed responses from intermediaries.
- A UDP reflector can be abused for amplification and reflection. It must never send more bytes than it received and must limit rates per source (RFC 8085 guidance).
- Third-party endpoints give AERIVA no abuse control and no say in logging.

### D2.7 Offline and poor-network implications

- On very poor networks an endpoint that answers in one small exchange keeps the cost of a failed attempt low.
- A CDN edge terminates TCP and TLS, so measured latency is client-to-edge. That is acceptable only if the method identifier says so (D4).
- Standard HTTP CDNs generally do not forward arbitrary UDP (ASSUMPTION, provider-specific). The packet-loss ladder therefore needs UDP-capable hosting.

### D2.8 Testing implications

- The client must be provable without the production endpoint existing: LFS for deterministic faults, CRS for calibration.
- A conformance suite derived from the protocol requirements runs against all three systems.
- A release-variant test asserts the compiled endpoint configuration contains only production hosts.

### D2.9 Rejected alternatives and why

| Option | Reason rejected |
|---|---|
| A as endpoint of record | Cannot satisfy R2, R4, R5, R6, R7, R8 (Section D2.10). Publishes user IPs (M-Lab) or has no contract (others). |
| E | The first phase would build measurement history against an endpoint AERIVA cannot control, then have to invalidate or re-baseline it. |
| F | No such infrastructure, no design, no privacy model. Not a Phase 4 concern. |
| G | A validation reference must be independent of the system under test. Sharing them makes ground truth circular. |
| Remote-configured endpoint list | Adds a network dependency and an injection vector for a measurement app. |
| Third-party NDT as CRS | A reference must be controlled. A self-hosted `ndt-server` instance may still serve as a throughput cross-check in the CRS. |

### D2.10 Final technical decision

- DECISION D2-1: PME, CRS and LFS are three separate systems (Section 3).
- DECISION D2-2: For every MEASUREMENT-tier capability (latency, jitter, unanswered-probe train, throughput), the endpoint of record must be first-party controlled (options B, C or D). Third-party endpoints are not used for any MEASUREMENT-tier result.
- DECISION D2-3: The PME must satisfy these requirements. They are requirements only. No server is designed or built here.

| ID | Requirement | Why |
|---|---|---|
| R1 | HTTPS only on the standard HTTPS port, public-CA certificate for an AERIVA-owned hostname, system trust store, no custom trust anchors, no cleartext | Server authenticity; Android defaults (VERIFIED FACT) |
| R2 | Ping resource returning a small fixed-size body containing: echoed client nonce, server processing time, server region or POP identifier, protocol version | Nonce defeats caches and replay; processing time lets validation subtract server time (validation plan 7.3); region makes results attributable |
| R3 | `no-store` caching semantics on all measurement resources | Prevent intermediary caches from producing fake fast responses |
| R4 | Versioned, published wire protocol owned by AERIVA | Payload validation is protocol-based, replacing the 8-byte placeholder |
| R5 | Separate UDP echo service with per-packet sequence number and timestamp fields (STAMP-inspired), reply never larger than the request, per-source rate limits | Enables the unanswered-probe train; RFC 8085 and RFC 8762 guidance |
| R6 | Throughput resources with server-enforced hard byte cap and duration cap independent of the client request | Data safety defense in depth |
| R7 | Never issues redirects on measurement resources | Any 3xx seen by the client is a portal or proxy signal (D5) |
| R8 | No cookies, no accounts, no client identifiers accepted in v1 | Privacy by design; logging policy is OD-3 |
| R9 | Dual-stack (A and AAAA) reachability | Avoid silently biasing results toward one address family |
| R10 | Availability, abuse handling and rate limiting under AERIVA policy | Avoid unilateral third-party termination or blocking |

- DECISION D2-4: Endpoint identity is compile-time configuration behind a `MeasurementEndpointConfig` seam (host, port, protocol version). It is not remote-configured in Phase 4. Changing where a hostname points is an infrastructure action, not an app release.
- DECISION D2-5: Multi-region and CDN choices are infrastructure concerns behind one hostname per protocol. The client records the server-reported region in results. Geo-steering by DNS can misplace users of public resolvers, which is a LIMITATION disclosed in results, not solved.
- DECISION D2-6: A new engine decline reason `NoEndpointConfigured` is defined. Release builds may not ship a production endpoint configuration until OD-2 and OD-3 are approved. With no configuration, the engine declines cleanly.
- DECISION D2-7: No app-layer response signature in v1. TLS plus nonce echo is the authenticity mechanism. Escalation path in OD-10.
- DECISION D2-8: No certificate pinning in v1. A pin mismatch would be indistinguishable from the interception outcomes AERIVA specifically wants to detect (portal, proxy, MITM). Revisit if OD-10 changes the threat model.
- DECISION D2-9: Third-party endpoints (M-Lab NDT, Cloudflare, Google-style checks) are excluded from Phase 4 defaults. Any use, even as a user-initiated opt-in test, requires OD-5.
- DECISION D2-10: CRS requirements (validation-facing, not user-facing): configurable added delay and jitter, fault injection (slow drip, wrong length, redirect, bad certificate variants, non-echoing nonce), sequence-logged UDP echo, byte and timestamp logs per connection, packet-capture capability, a way to set server processing time. A self-hosted `ndt-server` may be added for throughput cross-checks.
- DECISION D2-11: LFS covers deterministic client-fault tests only (Section D3.10). It is never a source of network truth.

### D2.11 Consequences for Phase 4 implementation

- Client development can start immediately against LFS and CRS. No production endpoint is required.
- The production endpoint blocks only production enablement, not implementation start (OD-2, OD-3).
- The protocol specification is a new artifact. It must be written before the client's payload validation, since the client's `InvalidResponse` decisions depend on it.

### D2.12 Consequences for real-device validation

- Validation plan 24 prerequisite "Controlled reference server and endpoint policy: Missing" splits into two: CRS build (validation team) and PME decision (owner).
- Every validation run against PME must be paired with the same run against CRS from the same vantage. PME is never used as ground truth.
- Validation plan D-24 is answered: TLS plus nonce, no signature (D2-7). D-26 (logging and retention) remains OD-3. D-15 (NDT reference) remains OD-5.

---

## 6. Decision D3: raw sockets versus HTTP client

### D3.1 Problem

- `NetworkClient` has no implementation. The transport must be chosen before any probe can be written.
- The choice constrains timing decomposition (DNS, TCP, TLS, request), connection reuse labeling, network pinning, proxy and redirect behavior, cancellation, byte accounting and security.
- Coroutine cancellation is cooperative. A blocking socket call does not stop just because `withTimeout` fired.

### D3.2 Options considered

| Option | Description |
|---|---|
| T1 | Platform `Socket` and `SSLSocket` with a hand-written protocol |
| T2 | Platform `HttpsURLConnection` |
| T3 | OkHttp |
| T4 | Chromium-based stacks (Cronet or platform `HttpEngine`) |
| T5 | Ktor client |
| T6 | Split by transport: OkHttp for HTTPS-based probes, platform `DatagramSocket` for UDP trains |

### D3.3 Authoritative evidence

- `SSLSocket` does not perform hostname verification. The app must do it, preferably with `getDefaultHostnameVerifier()`, and must check the boolean result (VERIFIED FACT, Android "Security with HTTPS and SSL"; also the `SSLCertificateSocketFactory` reference). Android's default trust manager verifies the hostname only if `SSLParameters.setEndpointIdentificationAlgorithm("HTTPS")` was called (VERIFIED FACT, `HttpsURLConnection` reference). `HttpsURLConnection` itself relies on the hostname verifier for the whole verification step (VERIFIED FACT).
- `Network` provides `bindSocket(Socket)`, `bindSocket(DatagramSocket)`, `getSocketFactory()`, `getAllByName(String)` (resolution on that network, API 21), `openConnection(URL)` and `getNetworkHandle()` (API 23) (VERIFIED FACT, reference).
- OkHttp supports Android 5.0 and higher (API 21) and Java 8 and higher, uses AndroidX Startup on Android, and is published as Kotlin Multiplatform with separate JVM and Android artifacts (VERIFIED FACT, README and 5.0.0 changelog). Its `EventListener` reports `dnsStart/dnsEnd`, `connectStart/connectEnd`, `secureConnectStart/secureConnectEnd`, `connectionAcquired`, request and response events, and `responseBodyEnd` with a byte count. A pooled connection fires no connect events, so reuse is observable (VERIFIED FACT, OkHttp events page). OkHttp 5.0 introduced fast fallback that races TCP handshakes (VERIFIED FACT, changelog text). Events carry no timestamps. The listener supplies its own clock (VERIFIED FACT, events page example).
- `runInterruptible` interrupts a blocking block when its coroutine is cancelled. Cancellation is otherwise cooperative (VERIFIED FACT, kotlinx.coroutines documentation). Whether interruption unblocks a classic `java.net.Socket` read on Android is not verified (ASSUMPTION: it does not, and the socket must be closed).
- `TrafficStats` supports per-thread tags, `tagSocket` and `tagDatagramSocket`, per-UID byte counters and an `UNSUPPORTED` return (VERIFIED FACT, reference).
- STAMP, RFC 5481 and RFC 8085 as in Section 5.3 and Section 7.3.

### D3.4 Compatibility with the existing architecture

- The seam already exists and is CI-proven with a fake. A production implementation slots in behind it.
- `core:model` and the engine stay Android-free. All Android and OkHttp types live in the module that owns the client.
- OkHttp is a runtime dependency in one module only. It must never appear in `core:model`.
- The repo's own history shows unverified version pins cause CI failures (`libs.versions.toml` comments on KSP and `coreKtx`). Dependency admission must therefore be verified, not assumed (Section D3.11).

### D3.5 Data and battery implications

- Reusing one connection for many exchanges makes jitter sampling cheap in bytes and radio wakeups. A new connection per sample multiplies handshakes.
- Silent client retries would double bytes and corrupt timing. They must be disabled.
- Byte accounting needs two views: application-level from the client, and OS-level from `TrafficStats` with tagged sockets, reconciled in validation.

### D3.6 Security implications

- T1 pushes security-critical code (hostname verification, HTTP parsing of untrusted portal responses) into AERIVA. The mistake is easy and silent.
- OkHttp performs hostname verification by default. Overriding the verifier to accept everything is a known critical defect class and is prohibited.
- Redirect following and automatic retries must be off so an interception signal is not hidden.
- ECH applies to apps targeting 37 only when the library supports it. OkHttp is named as a candidate (VERIFIED FACT, Android 17 page). No effect at target 36.

### D3.7 Offline and poor-network implications

- Stage-aware timeouts are only possible if the client knows the deadline and its own current stage (D5).
- On a network with a portal or proxy, a raw socket ignores system proxy settings. OkHttp's default proxy selection honors them (ASSUMPTION: verify at admission). The measurement should reflect what the user's traffic experiences, with the proxy flag recorded.

### D3.8 Testing implications

- Phase timing, reuse labeling and cancellation are each provable in JVM tests against LFS with real loopback sockets, without Android.
- Cancellation must be tested for bounded-time unblocking of the underlying call (TEST REQUIREMENT).
- A client contract suite runs against the fake and against every production client.

### D3.9 Rejected alternatives and why

| Option | Reason rejected |
|---|---|
| T1 hand-rolled TLS socket | Hostname verification is the app's job and easy to get wrong (VERIFIED FACT). A custom protocol on the standard HTTPS port cannot sit behind a CDN or load balancer, which would constrain endpoint hosting (D2). No proxy support. Hand-parsed responses from portals are attack surface. |
| T2 `HttpsURLConnection` | Cannot tell whether a pooled connection was reused and has no phase events. A latency value with unknown cold or warm state cannot be labeled honestly. |
| T4 Cronet or `HttpEngine` | `HttpEngine` and Chromium stacks are named as candidates in Android 17 text, but API-level availability for `HttpEngine` was not verified this session. With `minSdk 26` a second stack would be needed anyway, doubling validation. Revisit later. |
| T5 Ktor | Adds a layer with no measurement benefit. On Android it sits on OkHttp or another engine. |
| One stack for everything including UDP | HTTP clients do not do UDP. |

### D3.10 Final technical decision

- DECISION D3-1: All HTTPS-based probes (latency, jitter, HTTPS reachability, throughput) use OkHttp behind the existing `NetworkClient` seam. Exact version pin is not set by this document (Section D3.11).
- DECISION D3-2: UDP probe trains use platform `DatagramSocket`, bound to the target network with `Network.bindSocket(DatagramSocket)`, behind a separate seam (working name `DatagramProbeClient`). They are not forced through `NetworkClient`, whose outcome shape is single request and response.
- DECISION D3-3: Required OkHttp configuration for measurement probes (each item is a requirement, verified against the chosen version at admission):
  - Redirects not followed (plain and TLS-scheme redirects).
  - Automatic connection-failure retries disabled.
  - No response cache.
  - HTTP/1.1 only for latency, jitter and reachability probes so multiplexing cannot make samples queue behind each other. Negotiated protocol is recorded. HTTP/2 may be measured later as its own method.
  - DNS resolution through `Network.getAllByName` on the pinned network, and connections through that network's socket factory.
  - Fast fallback behavior verified and its setting recorded in the method identifier.
  - System proxy honored, with `proxyUsed` recorded.
- DECISION D3-4: The client owns the deadline. `probe` receives a deadline, enforces it with stage tracking, and returns a stage-attributed timeout. The engine's own `withTimeout` remains as an outer backstop set slightly above the client deadline. The backstop firing is a defect signal, not a normal outcome.
- DECISION D3-5: Cancellation is bridged so coroutine cancellation cancels the underlying OkHttp call (or closes the datagram socket) within bounded time. Reliance on `runInterruptible` alone for socket I/O is prohibited (ASSUMPTION about classic socket interruption).
- DECISION D3-6: The measured interval is defined per method identifier and computed inside the client from monotonic timestamps taken by the client's event listener with an injected `() -> Long` clock, following the engine's existing seam. The engine's own wrapper time is kept as a diagnostic (`engineElapsed`) so engine overhead is separately measurable (validation plan 7.2).
- DECISION D3-7: Controlled method vocabulary, registered as constants (the free-text `"tcp-round-trip"` default is retired):

| Method id | Meaning |
|---|---|
| `https-h1-warm-exchange` | One request and response on an already established HTTPS/1.1 connection. Excludes DNS, TCP, TLS. |
| `https-h1-cold-total` | DNS, TCP, TLS, request and response on a new connection. Phases also recorded. |
| `https-reachability` | Full-stack reachability judgment against the PME |
| `udp-echo-train` | Sequenced UDP echo probes with a fixed window |
| `https-download-stream` | Timed, byte-capped download |
| `https-upload-stream` | Timed, byte-capped upload |

- DECISION D3-8: Sockets used for measurement are tagged for `TrafficStats` accounting with a dedicated tag so their OS-level bytes can be reconciled with application-level counts.
- DECISION D3-9: No raw TLS sockets in Phase 4 v1. Fallback if OkHttp admission is denied (OD-7): a hand-written `SSLSocket` client is allowed only with `setEndpointIdentificationAlgorithm("HTTPS")` set, an explicit hostname-verifier check with the boolean result asserted, a bounded HTTP/1.1 parser, and a dedicated security test set. That fallback carries additional validation obligations and is not the recommended path.
- DECISION D3-10: Loopback fixture approach (validation plan D-29): if OkHttp is admitted, LFS for HTTPS faults uses the same project's test server artifact at test scope. Otherwise the JDK's built-in HTTP server. UDP fixtures use `DatagramSocket` on loopback in-process. Test-scoped dependency admission is separate from runtime admission.

### D3.11 Dependency admission checklist for OkHttp (a gate, not an approval)

| Check | Reason |
|---|---|
| Choose and verify an exact release on Maven Central (README snapshots seen this session name 5.3.0 and 5.5.0; do not copy either) | The repo failed CI before on unverified versions |
| AAR metadata `minCompileSdk` and AGP requirement compatible with `compileSdk 36` and AGP 8.13.2 | The repo hit exactly this with `coreKtx 1.19.0` |
| Kotlin 2.3.21 compiles against it | Forward compatibility expected, not proven |
| Licence check | Not verified this session |
| Consumer R8 rules present | Release builds |
| Merged-manifest delta (AndroidX Startup provider) reviewed and allow-listed | D1-6 |
| APK size delta recorded | Product impact |
| Confirm the builder options in D3-3 exist in the chosen version | Requirements above were not verified against a specific version |

### D3.12 Consequences for Phase 4 implementation

- `NetworkClient` contract evolves (Section D5.11 sketch): target abstraction, pinned network handle, deadline, byte budget, returns evidence, never throws except cancellation.
- The engine's timing changes: latency value comes from the client's method-defined interval.
- A second seam for datagram trains is created.
- The single largest slice (S3) depends on OD-7 for OkHttp and on D1 for the permission.

### D3.13 Consequences for real-device validation

- Validation plan 7.2 and 7.3 (timing offset, component separation) become directly implementable: the client reports phases and reuse state, and the engine reports its own overhead.
- Validation plan 9.1 T-9.2 (unprivileged ICMP) is no longer on the critical path. UDP echo replaces it for Stage B.
- Cold versus warm separation (validation plan 7.4) is enforced by method id, not by convention.
- Proxy and IPv4 or IPv6 variations are recorded in results and become matrix dimensions.

---

## 7. Decision D4: domain-model extensions for jitter, packet loss and throughput

Kotlin fragments in this section are contract sketches. They are not compiled and not committed to any source set. Names are working names.

### D4.1 Problem

- Only latency is modeled (average, min, max). No jitter, unanswered-probe, throughput or evidence type exists (REPO FACT).
- `LatencyMeasurement` has no sequence index, so consecutive-sample logic has nothing to order by.
- The failure and evidence needs of validation (phases, reuse state, network attribution, byte accounting) have no home.
- The Phase 3A tier boundary must remain type-enforced: measurement, derived value, estimation, prediction, recommendation.

### D4.2 Options considered

| Option | Description |
|---|---|
| M1 | A separate sealed `*Measurement` family for each of jitter, packet loss, throughput |
| M2 | Jitter as a derived value over ordered latency samples. Own families for loss and throughput. |
| M3 | Generic `Measurement<T>` supertype |
| M4 | Store jitter as a probed quantity with its own request type |
| M5 | Name the loss metric "PacketLoss" now |

### D4.3 Authoritative evidence

- RFC 5481 defines two delay-variation formulations. IPDV is defined against the previous packet in send sequence, and its mean is typically zero, so summarization needs another form. PDV references a single chosen sample and is summarized with percentiles or range (VERIFIED FACT). The range was avoided in ITU practice for large streams because the maximum is unstable. Measurement interval matters for validity (VERIFIED FACT).
- RFC 6673 and RFC 3393 are the IETF loss and IPDV references (VERIFIED in the validation plan research).
- TCP hides loss from applications. UDP probe non-response cannot separate loss from lateness without timing windows and cannot separate loss from filtering without a control probe (ASSUMPTION stated in the validation plan ladder, retained).
- `Network.getNetworkHandle()` provides an opaque process-local handle (VERIFIED FACT).
- `TrafficStats` and OkHttp `responseBodyEnd(byteCount)` support byte accounting (VERIFIED FACT).

### D4.4 Compatibility with the existing architecture

- Follows the `LatencyMeasurement` shape exactly: `Succeeded` versus `Failed` are structurally distinct.
- Uses the existing `MeasurementFailure`, `MeasurementNetworkContext`, `Confidence` and `Freshness` types.
- Derived values follow `DerivedLatencyStats.from`: a pure function, null only when nothing valid remains, and `Confidence.Insufficient` for thin evidence.
- New fields on existing Phase 3A types are added as trailing parameters with defaults so existing construction sites keep compiling where the default is truthful.
- `NetworkQuality.Measured` stays unpopulated. Nothing here creates a generic score.

### D4.5 Data and battery implications

- Every list-shaped field has a bounded size. Series length, train length and interval count are capped by named constants, whose values are tied to the owner's data budgets (OD-6).
- Byte counts are recorded per sample so any budget can be audited after the fact.
- Jitter costs no extra probing beyond a latency series.

### D4.6 Security implications

- No IP addresses, hostnames, URLs or raw exception messages in stored types. Only address family, negotiated protocol name, and a server-reported region identifier constrained to a short character set and length. Anything else from the server that fails validation is `InvalidResponse`.
- `networkHandle` is opaque and process-local. It does not identify a user or a network operator.

### D4.7 Offline and poor-network implications

- `Failed` samples break consecutive-difference chains. Jitter never bridges across a failure or a network change.
- A time-boxed throughput transfer on a very slow network is still a truthful measurement, marked by its termination reason. Zero bytes by the time box is a failure, not a value.
- A UDP train with no answers is never expressed as 100 percent loss.

### D4.8 Testing implications

- Exhaustive `when` tests over every new sealed hierarchy (compile-time boundary enforcement, matching Phase 3A precedent).
- Pure JVM arithmetic tests for each derived function including empty input, single sample, all-failed, non-finite, unordered input, mixed methods, and network change mid-series.
- A regression test that no code path constructs a "packet loss" type from an unanswered-probe train.

### D4.9 Rejected alternatives and why

| Option | Reason rejected |
|---|---|
| M1 for jitter | Jitter is arithmetic over latency samples (RFC 5481 defines it that way). A probe type for it would duplicate latency probing, double data cost and blur the measurement versus derived-value tier. |
| M3 generic `Measurement<T>` | Phase 3A flagged this as the route back to one generic score. Not needed to resolve any blocker. Stays deferred. |
| M4 | Same as M1. |
| M5 naming loss "PacketLoss" | The honesty ladder allows that name only after calibration against injected loss shows the estimate tracks it (validation plan 9.4 stage D). The type name enforces this at compile time. |
| Storing throughput as a rate | Rate is derivable from bytes and duration. Storing raw quantities avoids baked-in assumptions and supports later ramp-up exclusion. |
| Adding sequence index to every `LatencyMeasurement` | Not needed. A derived function taking the ordered full series (including failures) already encodes adjacency. |

### D4.10 Final technical decision

- DECISION D4-1 (shared evidence): a `ProbeEvidence` value is added to core:model and attached, optionally, as a trailing parameter to `LatencyMeasurement.Succeeded` and `.Failed` and to every new measurement type.

```kotlin
// contract sketch, not compiled
data class ProbeEvidence(
    val networkHandle: Long?,            // Network.getNetworkHandle at probe start
    val addressFamily: AddressFamily,    // IPv4, IPv6, Unknown
    val negotiatedProtocol: String?,     // "http/1.1", "udp", ... from a fixed vocabulary
    val connectionState: ConnectionState,// Cold, Warm, NotApplicable
    val proxyUsed: Boolean,
    val phases: PhaseTimings?,           // dns, connect, tls, firstByte, lastByte in millis
    val serverProcessingMillis: Double?, // server-reported, validated
    val serverRegionId: String?,         // short constrained charset, validated
    val bytesSent: Long,                 // application-level
    val bytesReceived: Long,
    val engineElapsedMillis: Double?     // engine wrapper time, diagnostic
)
```

- DECISION D4-2 (method registry): method identifiers are constants in one registry (Section D3-7). A JVM test fails if any measurement is produced with an unregistered method string.
- DECISION D4-3 (jitter): jitter is a derived value.

```kotlin
// contract sketch, not compiled
enum class JitterDefinition { MEAN_ABS_CONSECUTIVE_DIFFERENCE }  // v1 only

data class DerivedJitterStats(
    val definition: JitterDefinition,
    val method: String,                      // the latency method the samples share
    val meanAbsIpdvMillis: Double,           // mean of |d(i+1) - d(i)| over valid adjacent pairs
    val pdvRangeMillis: Double,              // max minus min over the samples used
    val sampleCount: Int,
    val pairCount: Int,
    val sourceMeasurementIds: List<Long>,    // in send order
    val calculatedAt: Instant,
    val confidence: Confidence
) { companion object { fun from(series: List<LatencyMeasurement>, calculatedAt: Instant): DerivedJitterStats? } }
```

  Rules for `from`: the input is the full ordered series in send order, including failures. A pair counts only if both samples are `Succeeded`, share the same method id, are both warm-connection samples, and have equal network handles (both null counts as equal). A failed sample, a network change, or a method change breaks adjacency and is never bridged. Input not in non-decreasing `measuredAt` order is rejected as unordered evidence. Returns null when there are zero valid pairs. The signed mean is not stored because the mean IPDV is typically zero (VERIFIED FACT, RFC 5481). PDV percentiles are deferred until the validation pilots supply a sample count that makes them meaningful (validation plan D-11). This resolves validation plan D-10 for v1, revisable after pilots.
- DECISION D4-4 (jitter confidence): jitter uses its own confidence function over pair count. Reusing the latency function unchanged is prohibited. The existing 3 and 10 cutoffs and the 0.5 spread ratio are illustrative in the code's own words. They may be carried over as explicitly provisional starting points and must be replaced by pilot-derived values before release.
- DECISION D4-5 (series control): `measureSeries` gains an inter-sample interval and a hard sample cap as constructor or plan parameters. The cap constant's value is set from the owner's data budgets (OD-6). A series exceeding the cap is refused, not truncated silently.
- DECISION D4-6 (unanswered-probe train, formerly packet loss):

```kotlin
// contract sketch, not compiled
sealed interface UdpProbeTrainMeasurement {
    val id: Long; val context: MeasurementNetworkContext; val measuredAt: Instant; val method: String
    data class Succeeded(
        override val id: Long, override val context: MeasurementNetworkContext,
        override val measuredAt: Instant, override val method: String,
        val probesSent: Int,
        val answeredInWindow: Int,   // must be at least 1 for Succeeded
        val lateAnswers: Int,        // arrived after the window closed
        val duplicateAnswers: Int,
        val outOfOrderAnswers: Int,
        val windowMillis: Long, val intervalMillis: Long, val payloadBytes: Int,
        val controlProbeSucceeded: Boolean?, // same-window HTTPS control to the same endpoint
        val evidence: ProbeEvidence?
    ) : UdpProbeTrainMeasurement
    data class Failed(/* same header fields */ val failure: MeasurementFailure) : UdpProbeTrainMeasurement
}
```

  Rules: unanswered count is `probesSent - answeredInWindow` and is called "unanswered" everywhere. Late answers are counted separately so lateness is not reported as loss. Zero answers is always `Failed(NoResponseFromEndpoint)`. It is never a `Succeeded` with a 100 percent figure. The capability enum entry stays `PACKET_LOSS`. The word "packet loss" is reserved for a later type that exists only after stage D calibration. A derived unanswered-fraction stat with an exact binomial interval is computed by a pure function.
- DECISION D4-7 (throughput):

```kotlin
// contract sketch, not compiled
enum class TransferDirection { DOWNLOAD, UPLOAD }
enum class TransferTermination { COMPLETED, STOPPED_AT_BYTE_CAP, STOPPED_AT_TIME_BOX }

sealed interface ThroughputMeasurement {
    val id: Long; val context: MeasurementNetworkContext; val measuredAt: Instant; val method: String
    data class Succeeded(
        override val id: Long, override val context: MeasurementNetworkContext,
        override val measuredAt: Instant, override val method: String,
        val direction: TransferDirection,
        val bytesTransferred: Long,       // application payload
        val transferMillis: Double,       // excludes connection setup
        val termination: TransferTermination,
        val byteCapBytes: Long,           // the cap that applied
        val intervals: List<TransferInterval>, // bounded; allows later ramp-up exclusion
        val evidence: ProbeEvidence?
    ) : ThroughputMeasurement
    data class Failed(/* same header fields */ val failure: MeasurementFailure) : ThroughputMeasurement
}
```

  Rate is computed by a pure function from bytes and duration, never stored. Zero bytes by the time box is `Failed(Timeout(stage))`. A stop at the byte cap or time box is a valid, explicitly labeled result. Interval count is bounded.
- DECISION D4-8 (observation fields): `NetworkState` gains named fields `captivePortalReported: Boolean` and `vpnPresent: Boolean`, with the mapper populating them from the platform capability and transport bits. `RawCapabilitiesSnapshot` gains matching inputs. `vpnPresent` is separate from `transport` so a VPN over Wi-Fi is no longer invisible to logic that needs the underlying transport question answered honestly. A `blockedByDevicePolicy` observation is added from the platform blocked-status callback (VERIFIED FACT in the validation plan research that the callback exists).
- DECISION D4-9 (derived view for HTTPS reachability): the seven HTTPS states in validation plan 12.1 are a pure derived view over an HTTPS reachability measurement and its `MeasurementFailure`. They are not a second stored taxonomy (Section D5.10 mapping). The concrete `HttpsReachabilityMeasurement` type is added in its own slice.
- DECISION D4-10: DNS gets no measurement type in Phase 4. It stays ESTIMATION tier. A raw timed lookup is recorded as evidence with the DNS mode observed (Private DNS off, opportunistic or strict). Design of that evidence record is part of the DNS slice.
- DECISION D4-11: Nothing in this section modifies `NetworkQuality` or populates `Measured`.

### D4.11 Consequences for Phase 4 implementation

- Migration list for the JVM-only slice: `NetworkState` constructor sites and tests, `NetworkStateMapper` and `RawCapabilitiesSnapshot`, `LatencyMeasurement` constructors (trailing defaults), the method registry, exhaustive-`when` tests.
- Jitter needs no new engine. It needs series interval and cap controls on the latency engine.
- Unanswered-probe and throughput engines are new and follow the same shape as the latency engine.

### D4.12 Consequences for real-device validation

- Validation plan 8 (jitter) is now testable against a concrete definition: IPDV mean absolute plus PDV range, both computed also offline from raw samples so the pilots can decide on percentiles.
- Validation plan 9 (packet loss) uses the "unanswered" naming and the control-probe field. The ladder stages map to type changes: stage B is this type, stage D is a future type.
- Validation plan 10 (throughput) gets termination reason and interval samples as evidence, and byte counts to reconcile against server and OS counters.
- Validation plan 6.2 (VPN, captive portal, blocked rows) get observation fields to assert against.

---

## 8. Decision D5: measurement and network failure taxonomy

### D5.1 Problem

- Failures a real transport produces cannot be represented today: DNS failure, redirect, portal, blocked by policy, response too large, unknown exception.
- `Timeout` has no stage. A DNS timeout and a response-body timeout are different diagnoses.
- A real client that throws instead of returning an outcome would crash `measure`, since the engine catches only the timeout exception.
- The engine treats `available == true` as attemptable, and never looks at `validated`.

### D5.2 Options considered

| Option | Description |
|---|---|
| F1 | Keep the closed set. Fold everything into `EndpointFailure` and `InvalidResponse` with free-text reasons. |
| F2 | Add one case per kind at the domain level. |
| F3 | Layered: decline, transport, domain, interpretation. Add kinds as parameters where the case already exists. |
| F4 | Let the caller detect captive portals from `NetworkState` alone. |
| F5 | Decline every probe on an unvalidated network. |

### D5.3 Authoritative evidence

- A captive portal network has the internet capability and a captive-portal flag without the validated flag, and validated can coexist with poor signal (VERIFIED FACT, Android network state documentation, from the validation plan research).
- Android's validation is the platform's own check. A network can be unvalidated while the internet works for other destinations, so unvalidated does not mean unusable (ASSUMPTION grounded in the definition of the flag; the mechanism was not confirmed from a primary source this session).
- Missing `INTERNET` raises `SecurityException` at name resolution (VERIFIED FACT).
- `UnknownHostException` is what the platform resolver raises on lookup failure. The platform cannot tell NXDOMAIN from resolver failure at this API level (LIMITATION, consistent with the validation plan 11).
- With Network Security Configuration defaults for target 28 and higher, cleartext requests fail with an `IOException` stating cleartext is not permitted (VERIFIED FACT via multiple vendor documents quoting the platform message; not a primary Android source). That is a build or configuration defect, not a network condition.
- `NetworkCallback.onBlockedStatusChanged` exists (validation plan research).
- Kotlin: cancellation exceptions must not be swallowed. Only the timeout subclass is caught (REPO FACT: the engine already does this).

### D5.4 Compatibility with the existing architecture

- Keeps the Phase 3A boundary: "attempted and failed" (recorded) versus "declined, not attempted" (not recorded), already reflected by `LatencyMeasurementOutcome` (`Measured` versus `NoNetwork` and `CapabilityUnavailable`).
- Keeps `NetworkClientOutcome` narrower than `MeasurementFailure`. Transport reports what happened. The engine maps.
- Uses the existing default-parameter approach for additive change.

### D5.5 Data and battery implications

- No automatic retries anywhere. A failure is a result. Retrying is a scheduler decision under a budget (OD-6).
- Failure evidence records bytes already spent so failed attempts are counted against the budget.
- Declining early (no network, blocked, no endpoint, no capability) spends zero bytes.

### D5.6 Security implications

- `reason` strings must never contain URLs, hostnames, IPs or raw exception messages. They come from a fixed vocabulary or an exception class simple name.
- A TLS hostname or certificate failure on a network with no portal flag stays a TLS failure. It is not relabeled portal, because it may be interception. Relabeling to portal requires the platform flag as corroboration.
- Server-provided strings are validated before they enter any type.

### D5.7 Offline and poor-network implications

- No network, blocked, and no endpoint are declines, not measurements.
- On poor networks slow success versus timeout must stay distinct, and the timeout carries its stage.
- Unvalidated networks are attempted. Validation flags are recorded in the context and used only to interpret a failure.

### D5.8 Testing implications

- A pure `FailureMapper` (transport exception or outcome to domain failure) is unit-tested on the JVM with synthetic exceptions, including the no-throw property: any `Throwable` other than cancellation maps to some outcome.
- A pure interpreter for portal suspicion is tested with the full evidence truth table.
- Exhaustive `when` tests on every new sealed hierarchy.
- A client contract suite asserts no exception escapes except `CancellationException`.
- Validation tracks the `Unclassified` rate. Any nonzero rate in the validation harness is a defect to be closed by adding a specific case, not accepted as normal.

### D5.9 Rejected alternatives and why

| Option | Reason rejected |
|---|---|
| F1 | Free-text reasons cannot be tested or counted, and they collapse different diagnoses. The validation plan forbids "internet bad". |
| F2 | Explosion of top-level cases and churn across every `when`. Kinds as parameters on existing cases keep the set navigable. |
| F4 | `NetworkState` alone cannot distinguish a portal from an unrelated failure. A portal claim needs both the platform flag and an observed interception signal from the probe. |
| F5 | Unvalidated does not mean unreachable. Declining would hide real evidence and would prevent HTTPS reachability from ever reporting on such a network. |
| Storing `Throwable.message` | Leaks endpoint details and is brittle to platform wording. |

### D5.10 Final technical decision

- DECISION D5-1 (four layers): (L0) pre-flight decline, not recorded as a measurement; (L1) transport outcome from the client; (L2) domain `MeasurementFailure`, recorded; (L3) an interpretation step that may reclassify an L2 failure using observation evidence.
- DECISION D5-2 (L0 declines): the engine outcome set keeps `CapabilityUnavailable(reason)` and `NoNetwork` and adds `NoEndpointConfigured` and `BlockedByDevicePolicy`. A budget decline (`BudgetExhausted`) is reserved for the scheduler layer and is not built in Phase 4 slice S1. The engine attempts a probe when a network is available and unblocked, regardless of `validated`, and records the validation, portal and VPN flags.
- DECISION D5-3 (stage): a `MeasurementStage` value (`PreFlight`, `Dns`, `Connect`, `Tls`, `Request`, `Response`, `Unknown`) is added. `Timeout` becomes `Timeout(stage)`.
- DECISION D5-4 (L2 set): the domain failure set becomes:

| Case | Meaning | Change |
|---|---|---|
| `Timeout(stage)` | No progress within the deadline at the named stage | Existing case gains a parameter |
| `Cancelled` | Caller cancelled. Recorded by an orchestrator, never constructed by the engine. | Unchanged |
| `DnsFailure(kind)` | Name did not resolve. Kinds: `NOT_RESOLVED` (cannot separate NXDOMAIN from resolver failure), `CLIENT_TIMED_OUT`, `UNKNOWN` | New |
| `EndpointFailure(reason, kind = UNSPECIFIED)` | Connection-level failure. Kinds: `REFUSED`, `UNREACHABLE`, `RESET`, `UNSPECIFIED` | Additive parameter |
| `TlsFailure(reason, kind = UNSPECIFIED)` | Kinds: `CERTIFICATE_INVALID`, `HOSTNAME_MISMATCH`, `PROTOCOL_OR_CIPHER`, `UNSPECIFIED` | Additive parameter |
| `UnexpectedRedirect(statusCode)` | Any 3xx from a resource that must never redirect | New |
| `InvalidResponse(reason, kind = UNSPECIFIED)` | Kinds: `WRONG_LENGTH`, `NONCE_MISMATCH`, `MALFORMED`, `UNEXPECTED_STATUS`, `TOO_LARGE`, `UNSPECIFIED` | Additive parameter |
| `CaptivePortalSuspected(evidence)` | Interception signal plus the platform portal flag | New, produced by the interpreter only |
| `NoResponseFromEndpoint(protocol)` | A probe or train received nothing. Ambiguous between loss, filtering and endpoint down. | New |
| `BlockedByDevicePolicy` | Platform reported the network blocked for the app during the probe | New |
| `NetworkChangedDuringMeasurement` | Network identity changed during the probe | Unchanged |
| `Unclassified(exceptionClass)` | An exception no rule maps. Carries the exception class simple name only. Always a defect signal. | New |

- DECISION D5-5 (portal evidence): `CaptivePortalSuspected` is assigned only when both hold: an interception signal was observed (`UnexpectedRedirect`, or `TlsFailure` with `HOSTNAME_MISMATCH` or `CERTIFICATE_INVALID`, or `InvalidResponse` with `NONCE_MISMATCH` or `MALFORMED`), and `captivePortalReported` was true at the probe start or end. The evidence records which signals held. The word is "suspected", never "detected".
- DECISION D5-6 (L1 transport outcomes): the transport set is rebuilt around evidence and stage, as a single sealed family:

| L1 outcome | Maps to L2 |
|---|---|
| `Success(response)` carrying payload and `ProbeEvidence` | Validated by the protocol. Then `Succeeded`, or `InvalidResponse` if the payload fails validation. |
| `DnsFailed(kind)` | `DnsFailure` |
| `ConnectFailed(kind)` | `EndpointFailure` |
| `TlsFailed(kind)` | `TlsFailure` |
| `HttpUnexpected(statusCode, redirect)` | `UnexpectedRedirect` or `InvalidResponse(UNEXPECTED_STATUS)` |
| `ResponseTooLarge` | `InvalidResponse(TOO_LARGE)` |
| `TimedOut(stage)` | `Timeout(stage)` |
| `NoReply` (UDP) | `NoResponseFromEndpoint` |
| `NetworkChangedMidCall` | `NetworkChangedDuringMeasurement` |
| `Blocked` | `BlockedByDevicePolicy` |
| `Unclassified(exceptionClass)` | `Unclassified` |

- DECISION D5-7 (exception mapping responsibilities of the client): 

| Platform exception or condition | L1 result |
|---|---|
| `UnknownHostException` | `DnsFailed(NOT_RESOLVED)` |
| Client DNS-stage timer expires | `DnsFailed(CLIENT_TIMED_OUT)` |
| `ConnectException` | `ConnectFailed(...)`, kind only if a structured errno is reachable, otherwise `UNSPECIFIED` (message parsing is prohibited as brittle) |
| Socket timeout | `TimedOut(stage from the client's own event tracking)` |
| Peer-unverified or hostname failure | `TlsFailed(HOSTNAME_MISMATCH)` |
| Certificate-path failure | `TlsFailed(CERTIFICATE_INVALID)` |
| Other TLS failure | `TlsFailed(PROTOCOL_OR_CIPHER or UNSPECIFIED)` |
| `SecurityException` (missing permission) | Not a failure. The engine returns `CapabilityUnavailable` and logs a manifest or classifier inconsistency as a defect. |
| Cleartext-not-permitted `IOException` | `Unclassified("CleartextNotPermitted")`, a configuration defect covered by a build-time test that production endpoints are HTTPS |
| Coroutine cancellation | Rethrown, never mapped |
| Anything else | `Unclassified(exceptionClass)` |

- DECISION D5-8 (derived HTTPS states): the validation plan's seven states are a pure function of the result:

| State | Source |
|---|---|
| AVAILABLE | `Succeeded` |
| DNS FAILURE | `DnsFailure` |
| TIMEOUT | `Timeout(stage)` |
| TLS FAILURE | `TlsFailure` |
| CAPTIVE PORTAL | `CaptivePortalSuspected` |
| UNAVAILABLE | `EndpointFailure`, `InvalidResponse`, `UnexpectedRedirect` (no portal flag), `NoResponseFromEndpoint` |
| UNKNOWN | `Cancelled`, `NetworkChangedDuringMeasurement`, `BlockedByDevicePolicy`, `Unclassified`, and declines (never recorded) |

- DECISION D5-9 (never throws): `measure` never throws for any outcome, except genuine caller cancellation. A client contract test enforces it.
- DECISION D5-10 (timeout ownership): the client enforces stage-aware deadlines and returns `TimedOut(stage)`. The engine outer `withTimeout` stays as a backstop and, if it fires, yields `Timeout(Unknown)` and a defect log.

### D5.11 Consequences for Phase 4 implementation

- `NetworkClient` contract evolution (sketch, not compiled):

```kotlin
// contract sketch, not compiled
interface NetworkClient {
    suspend fun probe(request: ProbeRequest): NetworkClientOutcome
}
data class ProbeRequest(
    val target: ProbeTarget,          // from MeasurementEndpointConfig, never a raw user string
    val method: String,               // registered method id
    val networkHandle: Long?,         // pin to this network, or default if null
    val deadlineMillis: Long,
    val byteBudget: Long?             // hard stop
)
```

  Binding semantics: the client is pinned to the requested network. It compares that network before and after the exchange and reports `NetworkChangedMidCall` on mismatch. It closes every resource on every path. It never throws except cancellation.
- Migration list (JVM slice): `MeasurementFailure` and its `when` sites in the engine and tests, `NetworkClientOutcome` and `FakeNetworkClient`, `ReferenceLatencyProbeExecutor` and its tests, engine outcome sealed type.
- The engine timing change, series controls and new decline reasons are engine-slice work.

### D5.12 Consequences for real-device validation

- Validation plan 6.2 rows each have a named target case: DNS failure, captive portal, VPN, blocked, poor signal (slow success versus `Timeout(stage)`).
- Validation plan 12 matrix cases map one to one to L1 outcomes and derived states.
- Validation plan 19 (failure and cancellation) adds: no exception escapes, `Unclassified` rate is zero in the harness, backstop timeout never fires in normal runs.
- A new validation check: `CaptivePortalSuspected` requires both signals. Portal-only-flag and interception-only cases must yield the other classes.

---

## 9. Cross-decision consistency

| Dependency | Statement |
|---|---|
| D2 -> D3 | The endpoint requirements (HTTPS, nonce, no redirects, small fixed body) are what make HTTP-based probing workable through CDNs and proxies. A custom protocol would have forced D2 toward direct exposure. |
| D3 -> D4 | The client's event data (phases, reuse state, protocol, bytes) is exactly what `ProbeEvidence` records. Jitter's warm-only pair rule depends on the client labeling reuse. |
| D3 -> D5 | Stage-aware timeouts require client-owned deadlines and event tracking. Exception mapping is the client's responsibility. |
| D4 -> D5 | `ProbeEvidence` feeds the interpreter. New `NetworkState` fields feed portal and VPN logic. |
| D1 -> all | No probe can run without the permission. The permission is added with the first client and only after OD-1. |
| D2 -> D1 | Cleartext must never be configured because the PME is HTTPS-only. |

Conflicts found and resolved: none between the five decisions. Conflicts between this contract and the source documents are listed in Section 2.3.

---

## 10. Consolidated consequences for Phase 4 implementation (slice plan)

Each slice is an implementation task for later. Nothing here starts any of them.

| Slice | Content | Needs | Runs on |
|---|---|---|---|
| S1 | JVM-only migration: failure taxonomy, `ProbeEvidence`, method registry, `NetworkState` new fields and mapper, exhaustive-`when` tests, `FailureMapper` and interpreter pure functions | Nothing external | JVM |
| S2 | Protocol specification for the PME and CRS, endpoint config seam, `NoEndpointConfigured` decline, client contract test suite, LFS | S1. Protocol spec authored first. | JVM |
| S3 | Production HTTPS client (OkHttp), permission adapter, `INTERNET` declaration, allowlist CI check, `:app` dependency | OD-1, OD-7, D2 requirements. Not OD-2 to OD-4 (test against LFS and CRS). | JVM, emulator |
| S4 | Latency engine changes: client-owned deadline, method-defined interval, engine overhead diagnostic, series interval and cap, new declines | S3 | JVM, emulator |
| S5 | Jitter derived value and confidence function | S4 | JVM |
| S6 | HTTPS reachability measurement and derived states | S4, S1 | JVM, emulator |
| S7 | UDP train client and unanswered-probe type | CRS UDP echo, PME UDP service (OD-2), OD-6 | JVM, emulator, device |
| S8 | Throughput client and type with byte cap and time box | OD-6, PME resources | JVM, emulator, device |

Ordering rule: S1 and S2 can proceed now. Anything that adds a permission or dependency waits for its owner gate. This matches the readiness document's recommended sequence, with B-3, B-4 and B-5 now closed technically.

---

## 11. Consolidated consequences for the real-device validation plan

| Validation plan item | Effect |
|---|---|
| 2.5 findings (client throwing, engine timing, free-text method, no series cap) | Each has a decision that closes it (D5-9, D3-6, D3-7, D4-5). |
| 6.2 special conditions | Rows gain concrete target cases and observation fields (D4-8, D5-4). |
| 7 latency | Method ids, phase timings, reuse state, engine overhead diagnostic all become implementable. |
| 8 jitter | Concrete definition (D4-3) with offline PDV computation retained for pilots. |
| 9 packet loss | "Unanswered" naming, control probe, late-answer counting (D4-6). Stage B is the first implementable stage. |
| 10 throughput | Termination reason and interval evidence. Server-side caps on the PME (R6). |
| 11 DNS | Estimation tier retained. `DnsFailure` kinds documented with the LIMITATION that NXDOMAIN and resolver failure are not separable at this API level. |
| 12 HTTPS | Seven states are a derived view (D5-8). |
| 17 data usage | Tagged sockets and per-sample byte fields (D3-8, D4-1). |
| 20 failure and cancellation | Add: no escape, `Unclassified` rate zero, backstop never fires. |
| 21 security | PME requirements R1 to R10, no pinning, no signature in v1, sanitized strings. |
| 22 CI versus hardware | LFS adds a real deterministic JVM layer for client faults. CRS is lab-only. |
| 24 prerequisites | Table updated in Section 13. |

---

## 12. Owner decision register

These are not resolved here. Each states what is needed and what it blocks. Technical work that does not depend on it may proceed.

| ID | Decision needed from the owner | Options | Blocks | Blocks implementation start |
|---|---|---|---|---|
| OD-1 | Authorize outbound network access (adding `INTERNET`) and the disclosures that come with it: privacy policy and Play data-safety declarations | Approve; approve with conditions; decline (observation-only product) | Slice S3 permission addition, every active capability | No. S1 and S2 proceed. |
| OD-2 | Build and operate a production endpoint: provider, hosting form (single origin, regional, CDN-fronted), budget, jurisdiction | Any of D2 options B, C, D. Technical requirements are fixed by R1 to R10. | Production enablement of every active capability | No |
| OD-3 | Endpoint logging and retention: what the server logs, how client IPs are handled, retention period, what the privacy statement says | Owner and legal | PME go-live | No |
| OD-4 | Geographic coverage target, including whether a West African presence is funded. This decides what "latency" means to the intended users. | Owner priority and budget | Meaningfulness of latency for target users, not correctness | No |
| OD-5 | Whether any third-party endpoint (M-Lab NDT, Cloudflare, Google-style) may ever be used, including as a user-initiated opt-in test. Needs legal review of terms and, for M-Lab, user consent to IP publication. | Never; opt-in only; automatic (not recommended) | Only that optional feature | No |
| OD-6 | Data budgets: maximum bytes per series and per day, metered and roaming behavior, throughput opt-in policy, retry policy | Owner sets values. Validation plan D-14, D-16, D-23 feed it. | Series cap constant, S7, S8, any scheduler | S4 needs the cap value |
| OD-7 | Dependency admission of OkHttp (and its test-scoped server artifact). This task cannot add dependencies. | Approve; approve with a version pin; refuse (fallback in D3-9 with extra obligations) | Slice S3 | No. S1 and S2 proceed. |
| OD-8 | User-facing naming and disclosure for the unanswered-probe metric. Technical rule fixed: never "packet loss" before stage D. | Copy and UX | Any UI, not the domain | No |
| OD-9 | Product commitment on `minSdk 26` for active measurement (validation plan D-04). OkHttp itself supports API 21 and higher. | Keep 26; raise floor | Device matrix scope | No |
| OD-10 | Security posture beyond TLS plus nonce: whether to add app-layer response signing or certificate pinning, based on the owner's threat model (for example, malicious CAs, hostile networks) | Keep default; add signing; add pinning | Revisit of D2-7 and D2-8 | No |

---

## 13. Effect on prior open-decision registers

### 13.1 Readiness register

| ID | Before | After this contract |
|---|---|---|
| B-1 INTERNET | Open, authority decision | Technically resolved (D1). Gated by OD-1. |
| B-2 Endpoint strategy | Open | Resolved at requirements level (D2). Production enablement gated by OD-2, OD-3, OD-4. |
| B-3 Socket vs HTTP client | Open | Resolved (D3). Dependency gated by OD-7. |
| B-4 Domain types | Open | Resolved (D4) |
| B-5 Failure taxonomy | Open | Resolved (D5) |
| C-1 Jitter definition | Open | Resolved for v1, revisable after pilots (D4-3) |
| C-2 HTTPS state model | Open | Resolved as derived view. Concrete type in slice S6 (D4-9, D5-8). |
| C-3 Loopback server | Open | Resolved conditional on OD-7 (D3-10) |
| C-5 Wire protocol | Blocked on B-2, B-3 | Requirements defined (D2-3). Full spec is slice S2. |
| C-6 User CA stance | Open | Resolved by platform default: no custom trust anchors, no user-CA trust for a target 28 or higher app (VERIFIED FACT). Debug-only overrides only in debug builds. OD-10 is the escalation path. |
| C-4 Cellular characteristics | Open | Not touched |

### 13.2 Validation plan register (affected items)

| ID | Effect |
|---|---|
| D-10 | Resolved for v1 (D4-3) |
| D-17 | Resolved as derived view (D5-8) |
| D-23 | Owner, folded into OD-6 |
| D-24 | Answered: TLS plus nonce (D2-7). Escalation OD-10. |
| D-25 | Answered by platform default |
| D-26 | Owner, OD-3 |
| D-29 | Resolved conditional on OD-7 |
| D-15 | Owner, OD-5 |

### 13.3 Architecture register (affected items)

Open decisions 2 (socket vs client), 3 (protocol), 4 (redirect and proxy: no redirects, system proxy honored and recorded), 5 (IPv4 versus IPv6: not forced, family recorded, dual-stack endpoints required) and 12 (HTTP failure granularity) are resolved by D3, D2, D3, D2 and D5 respectively.

---

## 14. Risks

| Risk | Mitigation |
|---|---|
| OkHttp version or AAR metadata incompatible with the pinned toolchain | Admission checklist (D3.11) before adding. The repo has hit this class of failure before. |
| OkHttp behavior options assumed rather than verified for the chosen version | The checklist requires confirming each D3-3 item against the version chosen. |
| Timing offset introduced by the HTTP stack itself | Engine overhead diagnostic and known-delay CRS calibration (validation plan 7.5) |
| Fast fallback changes which address is timed | Setting recorded in method identifier and evidence |
| PME never funded | Client and domain work still proceeds against LFS and CRS. Active capabilities stay off. |
| Owner refuses `INTERNET` | Observation-only product. No rework. |
| Server-supplied strings leaking into stored data | Validation of every server field. Unvalidated fields fail as `InvalidResponse`. |
| UDP reflector abuse | R5 limits and the never-larger-reply rule. UDP stays off until the PME service exists. |
| CDN masks origin latency | Method identifier and region recorded. Disclosed limitation. |
| Reclassifying failures as portal creates false alarms | Two-signal rule (D5-5) |
| `Unclassified` becomes a dumping ground | Defect-signal status, tracked rate, zero target in the validation harness |
| Source documents drift from this contract | Section 2.3 lists the discrepancies. Revisions to this contract must be versioned. |

---

## 15. Explicit non-goals and what this document did not do

- No production code, test code, permission, dependency, endpoint or server was created or modified.
- No `NetworkClient` was implemented. Kotlin fragments are sketches only and were not compiled.
- No product decision was made. Ten owner decisions are registered.
- `main` was not touched. Neither source document was edited. No branch was merged.
- No build, unit test, instrumented test or CI run was performed. No pass result is claimed.
- The exact OkHttp version, its licence, and its builder options for the chosen version were not verified.
- The official `Manifest.permission#INTERNET` reference page and the `HttpEngine` API level were not fetched.
- Data budgets, sample-count caps and timeouts have no numeric values here. They depend on OD-6 and validation pilots.

---

## 16. Documentation and static validation policy

Only documentation-appropriate checks are in scope. No Gradle task, unit test or emulator was run.

| Check | Purpose |
|---|---|
| File exists on the branch and is retrievable | Confirms the push |
| No em dash or en dash characters | Style requirement for this engagement |
| Every decision has the twelve required subsections D<n>.1 to D<n>.12 | Completeness of the required structure |
| Every OD and D identifier referenced is defined | Internal consistency |
| Markdown tables outside code fences have consistent column counts | Rendering correctness |
| Code fences are balanced | Rendering correctness |
| Commit touches only this one file | Scope control |
| `main` and the readiness branch tips unchanged | Confirms no collateral change |

The results of these checks are reported in the hand-off message for this commit. They are not embedded here so the document remains a fixed contract.

---

## 17. Sources

### 17.1 Fetched or searched and read in this session (VERIFIED FACT basis)

| Topic | Source |
|---|---|
| Manifest merging combines library manifests | https://developer.android.com/build/manage-manifests |
| Missing INTERNET raises `SecurityException("Permission denied (missing INTERNET permission?)")` from platform DNS lookup | AOSP libcore `Inet6AddressImpl` source (android-25 copy at chromium.googlesource.com/android_tools) |
| `INTERNET` normal permission description | Secondary quotations only; official reference page not fetched |
| Network Security Configuration defaults for target 28 and higher, and user-CA trust for target 23 and lower | https://developer.android.com/privacy-and-security/security-config |
| Android 17 plans to deprecate `usesCleartextTraffic`, NSC supported API 24+, cross-profile loopback blocked | https://developer.android.com/about/versions/17/behavior-changes-all |
| Android 17 `ACCESS_LOCAL_NETWORK`, ECH with library support (HttpEngine, WebView, OkHttp named) for target 37 | https://developer.android.com/about/versions/17/behavior-changes-17 |
| `SSLSocket` does not verify hostnames; app must | "Security with HTTPS and SSL" (Android developer docs; retrieved via mirror at emanual.github.io/Android-docs/training/articles/security-ssl.html) and https://developer.android.com/reference/kotlin/android/net/SSLCertificateSocketFactory |
| Default trust manager verifies hostname only when endpoint identification is set; `HttpsURLConnection` relies on the verifier | https://developer.android.com/reference/javax/net/ssl/HttpsURLConnection |
| `Network` API: `bindSocket`, `getSocketFactory`, `getAllByName`, `openConnection`, `getNetworkHandle` | https://developer.android.com/reference/android/net/Network |
| `TrafficStats` tagging and counters | https://developer.android.com/reference/android/net/TrafficStats |
| OkHttp platform support, Startup, KMP artifacts, changelog (5.0 packaging, fast fallback) | https://github.com/square/okhttp and https://square.github.io/okhttp/changelogs/changelog |
| OkHttp events and connection reuse observation | https://square.github.io/okhttp/features/events |
| `runInterruptible` and cooperative cancellation | https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/run-interruptible.html |
| M-Lab data policy, AUP, NDT behavior, private servers | https://www.measurementlab.net/frequently-asked-questions/ , https://www.measurementlab.net/aup/ , https://www.measurementlab.net/develop/ , https://www.measurementlab.net/privacy/ |
| Cloudflare speed test module design and data collection | https://github.com/cloudflare/speedtest |
| STAMP (sequence numbers, timestamps, load and RFC 8085 caution) | https://datatracker.ietf.org/doc/html/rfc8762 and https://datatracker.ietf.org/doc/html/rfc9503 |
| IPDV versus PDV | https://datatracker.ietf.org/doc/html/rfc5481 |

### 17.2 Verified during the earlier validation-plan research and reused here

NetworkCapabilities validated, captive-portal and metered semantics; `onBlockedStatusChanged`; RFC 2681, RFC 2680, RFC 3393, RFC 6673; Doze and battery documentation. See `PHASE_4_REAL_DEVICE_VALIDATION_PLAN.md` Section 28.

### 17.3 Not verified this session

The exact OkHttp release and licence; OkHttp builder options for retries, fast fallback and proxy behavior at a specific version; `HttpEngine` API level; whether thread interruption unblocks a classic `java.net.Socket` read on Android; whether library-declared permissions reach library instrumented-test APKs; `checkSelfPermission` behavior for an undeclared normal permission; AGP-added manifest entries; CDN UDP behavior; the platform connectivity-check URLs as an app-facing contract.
