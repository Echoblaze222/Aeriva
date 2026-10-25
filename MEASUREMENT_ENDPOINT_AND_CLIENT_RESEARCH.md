# Measurement endpoint and client research (A2 prerequisite)

Author: Agent 5. Date: 2026-10-25.
Branch: `docs/measurement-endpoint-research`. Documentation only. No code, manifest, Gradle, dependency, permission, endpoint or credential is changed.

## 0. Status and how to read this

| Item | Status |
|---|---|
| This document | RESEARCH ONLY. Nothing here is implemented. No build, test, CI run, device or emulator was used. |
| Label meaning | CONFIRMED: read in official or primary documentation or source in this session. LIKELY: consistent secondary evidence or protocol arithmetic, not run. UNCONFIRMED: not verified, must be checked before reliance. |
| Base deviation | The brief says base `f53134c`. The tooling available to this agent can only branch from a branch name, not a SHA. `main` has since moved to `9cff60c` (PR #4, two test files under `network/monitor/src/test`, per its merge message). This branch was cut from `main` @ `9cff60c`. The only file this branch adds is this document, so the diff against either base is identical. The Team Leader may rebase onto `f53134c` at merge time. |
| Prior decisions | `PHASE_4_CROSS_CUTTING_TECHNICAL_DECISION_CONTRACT.md` (branch `phase-4-cross-cutting-decisions` @ `8a56dc7`, referred to below as "the contract") already decides much of the architecture. This document does not reopen those decisions. It supplies facts the contract marked unverified, and flags where new evidence changes or complicates them. |

## 1. Summary for the Team Leader

### 1.1 Recommendations

| Decision | Recommendation | Label |
|---|---|---|
| OD-2 (endpoint) | A first-party echo service on one AERIVA-owned hostname, answered at a CDN edge that has a Lagos presence (Cloudflare Workers is the leading candidate, CloudFront is the alternative), with the origin or edge region id echoed in every reply. v1 is HTTPS only. UDP stays out of v1. No public third-party infrastructure for any MEASUREMENT-tier result. | LIKELY (fit), CONFIRMED (platform facts cited in section 3) |
| OD-3 (logging) | v1 logs nothing per request beyond what the platform cannot avoid. No cookies, no accounts, no client identifiers accepted. Aggregate counters only. Owner and legal to confirm what the chosen platform logs by default. | UNCONFIRMED (platform default logging not verified) |
| OD-4 (coverage) | Lagos edge presence is available through commodity CDNs. No funded in-country origin is required for v1. Because two candidate networks may reach different edges, `serverRegionId` must be recorded and a comparison between two networks must be suppressed or flagged when their regions differ. | CONFIRMED (Lagos PoPs exist), LIKELY (confound) |
| Client | OkHttp behind the existing `NetworkClient` seam, conditional on the admission checklist in section 4.5. Pin 5.4.0 or 5.5.0 only after a CircleCI scratch-branch build proves AAR metadata compatibility. Do not use 5.5.1 until its Maven Central publication date is verified. Fallback: platform sockets with the contract's security obligations. | LIKELY |
| Network binding | `Network.getSocketFactory()` plus a custom `Dns` using `Network.getAllByName`. Available on API 21 and higher, so fully available at minSdk 26. | CONFIRMED |
| Permissions | `INTERNET` is needed. NEW FINDING: `CHANGE_NETWORK_STATE` is also needed to use a non-default (background) network. L6 and the contract's D1-3 did not anticipate it. This is the single most important new fact in this document. | CONFIRMED (official guide) |
| Data cost | A cold HTTPS probe is roughly 5 to 9 KB on the wire. A warm exchange is roughly 0.6 to 1 KB. Money cost is negligible even at Nigerian bundle prices. The real costs are user trust, radio wake-ups and slow links. Metered probing must be opt-in with a byte budget. | LIKELY (arithmetic), UNCONFIRMED (device numbers) |

### 1.2 What changes in the contract or spec because of this research

1. Spec section 6 prerequisite 6 and the contract's D1-3 (`INTERNET` only) are incomplete. Probing a non-default candidate network also needs `CHANGE_NETWORK_STATE` (section 6).
2. Contract R2 asks the endpoint to report server processing time. A Cloudflare Worker cannot measure it meaningfully, because its timers only advance after I/O (section 3.3). `ProbeEvidence.serverProcessingMillis` is already nullable, so the type holds, but the requirement must be relaxed to "reported where the platform can measure it".
3. Contract R5 (UDP echo) cannot be served by a Worker. It needs separate UDP-capable hosting. Contract slice S7 is already late and gated, so this does not block v1.
4. Spec 3.1 item 6 (does a device keep a second network up) is partly answered by the official guide: it is true when "Mobile data always on" is enabled, and it is false after a delay when that setting is off. The default per device and OEM remains UNCONFIRMED.

## 2. Repository facts used (read at `f53134c`, unchanged by PR #4)

| Fact | Label |
|---|---|
| `libs.versions.toml`: AGP 8.13.2, Kotlin 2.3.21, coroutines 1.11.0, Room 2.8.4, DataStore 1.2.1, KSP 2.3.11, coreKtx 1.18.0 (1.19.0 rejected after a real CI failure caused by AAR metadata). compileSdk 36, minSdk 26 per the file comments and the contract. | CONFIRMED |
| `NetworkClient` is `suspend fun probe(target: String): NetworkClientOutcome` with `Success(payload)`, `ConnectionRefused`, `TlsHandshakeFailed`, `NetworkChangedMidCall`. No production implementation. | CONFIRMED |
| `MeasurementEndpointConfig.current` is `null`. `ProbeTarget(host, port, protocolVersion)` exists. | CONFIRMED |
| `LatencyMeasurement.Succeeded(id, context, measuredAt, method, valueMillis, sampleCount, evidence)` and `Failed(..., failure, evidence)`. | CONFIRMED |
| `ProbeEvidence(networkHandle, addressFamily, negotiatedProtocol, connectionState, proxyUsed, phases, serverProcessingMillis, serverRegionId, bytesSent, bytesReceived, engineElapsedMillis)` and `PhaseTimings(dns, connect, tls, firstByte, lastByte)`. | CONFIRMED |
| No `INTERNET`, no OkHttp, no network security config in the tree. `ACCESS_NETWORK_STATE` only. | CONFIRMED (spec 3.2 and contract 2.1) |
| Dependency governance skill requires: capability already in Android or Kotlin, maintained, licence, permissions, size and startup cost, sensitive data, simpler option. | CONFIRMED |
| Network optimization skill: no promise of impossible control, never inspect traffic beyond the documented model. | CONFIRMED |

## 3. Endpoint options (deliverable 1)

### 3.1 What the probe needs from an endpoint

From contract D2-3 R1 to R10 and spec section 6: HTTPS on 443 with a public-CA certificate on an AERIVA-owned name, nonce echo, `no-store`, no redirects, no cookies, dual-stack, tiny fixed-size body, region id in the reply, hard byte caps, abuse limits. For v1 only the latency and jitter path matters (warm exchange on one connection plus one cold probe). Throughput and UDP are later slices.

### 3.2 Options compared

| Option | Cost | Abuse risk | Privacy | Availability and control | Fit for poor networks and Nigeria | Verdict |
|---|---|---|---|---|---|---|
| A. Public third-party infrastructure (M-Lab NDT, Cloudflare speed endpoints, platform connectivity-check URLs) | Free | None for AERIVA, but no control either. NDT tries to move as much data as it can for ten seconds, so it is unsuitable for automatic probes. | M-Lab publishes test data including the device IP address. Other providers: no contract found. | None. Terms for native-app production use not found for Cloudflare speed endpoints. Platform check URLs are not documented as an app-facing contract. | Server locations are not under AERIVA control, so "latency" means whatever the provider's nearest server is. | REJECT for MEASUREMENT tier, matching contract D2-2 and D2-9. Needs OD-5 for any opt-in use. |
| B. Single self-hosted origin in a nearby cloud region (for example AWS Cape Town) | Small VPS or container. Provider prices not verified this session. | Fully controllable: rate limits, caps. | Full control of logs. | Single point of failure, single region. | No AWS, Google Cloud or Azure region exists in Nigeria as of September 2026 (secondary source). Nearest regions are in South Africa. Every Nigerian probe pays a long RTT, and the figure then mostly reflects distance. | ACCEPTABLE as the origin behind a CDN, poor as the only endpoint |
| C. Self-hosted echo answered at a CDN edge (Workers-style, one hostname) | Cloudflare Workers Free: 100,000 requests per day and a 1,000 per minute burst limit. Paid: $5 per month minimum, no daily request cap. Listed price $0.30 per million requests on the pricing page (included allotment not verified). | Edge platform absorbs volume. Per-IP rate limiting is possible. Free plan would be exhausted by abuse or growth. | The platform sees client IPs. AERIVA code can avoid storing them. Platform default logging is UNCONFIRMED. | High availability. Code is AERIVA's, hostname is AERIVA's. | Lagos edge exists for Cloudflare, CloudFront and EdgeNext (secondary directory, 179 days old). Client-to-edge RTT is short, so poor-link behavior is measured rather than distance. | RECOMMENDED for v1 |
| D. CDN-fronted origin (CDN passes through to B) | B plus CDN cost | Good | Same as C | Good, origin behind edge | Edge termination means measured latency is client-to-edge only unless the request misses to origin. With `no-store` and a dynamic path it will reach origin and add origin RTT. Decide which one is wanted. | ACCEPTABLE if a true end-to-end figure is needed. Not needed for network comparison. |
| E. Community or peer nodes | Unknown | High | Hard | None | None | REJECT (contract F) |
| F. Same server for production and validation | n/a | n/a | n/a | Circular ground truth | n/a | REJECT (contract D2-1) |

Evidence for the table:
* Cloudflare Workers limits and pricing: free plan 100,000 requests per day, burst 1,000 per minute, paid plan minimum $5 per month with no daily cap. CONFIRMED (official limits and pricing pages).
* Worker timers: for security, `performance.now()` and `Date.now()` only advance after I/O occurs, so a measured span with no I/O reads as zero. CONFIRMED (official performance and timers page). Consequence: a Worker cannot report an honest server processing time for CPU-only work. `serverProcessingMillis` would be `null`, or a value that is explicitly "platform-limited". The value is not needed for comparing two networks, because the same endpoint serves both.
* No AWS, Google Cloud or Azure region in Nigeria as of September 2026: one secondary source (zop.dev) and one older secondary source. LIKELY. AWS announced a CloudFront edge in Lagos in June 2023 and a Lagos Local Zone in January 2023 (several press reports of the AWS announcement). CONFIRMED that the announcement was made, LIKELY that the edge is still operating.
* CDN directory listing Cloudflare, CloudFront and EdgeNext with a Lagos PoP: secondary directory. LIKELY.
* M-Lab behavior (IP publication, ten second saturation test): already CONFIRMED in the contract, section 17.1. Not re-fetched.

### 3.3 Why the endpoint location matters more in Nigeria than the probe design

Reported latency figures for Nigeria differ by about an order of magnitude depending on where the test server is:
* A regulator and Ookla study of 739,294 tests (June 2025 to May 2026) is reported to show 31 ms urban and 39 ms rural, and operator averages from 36 ms (Airtel) to 77.5 ms (9mobile). The source I could read is a secondary news summary of the study. LIKELY.
* A browser-based public test site reports a 218 ms median for Nigeria in H1 2026. CONFIRMED that the site reports it. Its methodology and server placement are not verified, so the cause of the gap is UNCONFIRMED. The likely cause is test server distance.
* 2G still accounted for 36.1 percent of Nigerian mobile connections in May 2026 and 3G for 5.1 percent, per a secondary summary of regulator data. LIKELY. Any probe design must therefore tolerate 2G-class links.

Design consequences:
1. AERIVA's number means "client to this endpoint". It must never be presented as the network's latency in general. The method id and `serverRegionId` already carry that.
2. For the Network Advisor the useful quantity is the difference between two candidate networks reaching the same endpoint. That difference is meaningful when both reach the same edge and misleading when they do not. Wi-Fi from one ISP and mobile data from another can land on different PoPs (UNCONFIRMED, depends on anycast and peering). Rule: when two compared samples carry different `serverRegionId`, the advisor must show "not comparable" instead of ranking.
3. Single hostname, anycast edge, region id echoed, is the smallest design that satisfies 1 and 2.

### 3.4 Recommended v1 endpoint (requirements level, not a build)

* One hostname, `HTTPS` only, port 443, public-CA certificate, no pinning (contract D2-8).
* `GET /v1/ping?n=<nonce>` returns a fixed-size body of at most 64 bytes containing: protocol version, echoed nonce, region or colo id from a fixed charset and length, and processing time or an explicit null. Headers: `Cache-Control: no-store`, no `Set-Cookie`, no redirect ever (contract R3, R7, R8).
* `Connection: keep-alive` permitted, so the warm series can reuse one connection. The server idle timeout must exceed the client series duration (UNCONFIRMED for the chosen platform).
* Server-side hard limits: request size, response size, per-IP rate. No endpoint serves a variable-size body in v1.
* Throughput and UDP endpoints are explicitly out of scope for this decision. Contract slices S7 and S8 need their own requirements and OD-6.
* Cost shape at Workers pricing: for illustration, 10,000 daily users at 20 requests each is 200,000 requests per day, about 6 million per month, which is a few dollars of request charges at the listed rate plus the $5 minimum. LIKELY. Not a quote. The free plan would not carry this.

### 3.5 Abuse model

| Abuse | Mitigation |
|---|---|
| Third parties use the endpoint as a free echo or a DDoS reflector | Reply never larger than the request plus the fixed body. No UDP in v1. Per-IP rate limit at the edge. |
| Cost exhaustion | Paid plan with a billing cap or alert (platform feature, UNCONFIRMED). Hard rate limits. |
| Cache poisoning or replay | `no-store` and a nonce echo. Client rejects a reply whose nonce does not match (`InvalidResponse(NONCE_MISMATCH)` in the contract taxonomy). |
| Fake fast responses from an intermediary | Same nonce rule, plus TLS to an AERIVA-owned name. |
| Server-supplied strings poisoning stored data | Region id validated for length and charset at the client boundary (contract D4.6). |

## 4. Client options (deliverable 2)

### 4.1 Options

| Option | Notes | Verdict |
|---|---|---|
| T1 platform `Socket` and `SSLSocket` | App must do hostname verification itself (contract, CONFIRMED there). No phase events. Hand-parsed HTTP. | Fallback only, with the contract's D3-9 obligations |
| T2 `Network.openConnection(URL)` with `HttpsURLConnection` | Binds to a network on API 21 and higher. Cannot tell whether a pooled connection was reused and has no phase events, so cold and warm cannot be labeled. | REJECT (contract D3.9) |
| T3 OkHttp | Phase events, reuse observable, builder options for the required behavior, pluggable `SocketFactory` and `Dns`. Adds a dependency and one merged-manifest provider. | RECOMMENDED, gated on section 4.5 |
| T4 Cronet or `HttpEngine` | Availability by API level not verified. Second stack for minSdk 26. | Revisit later |

### 4.2 OkHttp facts verified in this session

| Fact | Label | Source |
|---|---|---|
| Supports Android 5.0 (API 21) and higher and Java 8 and higher. On Android it uses AndroidX Startup. | CONFIRMED | OkHttp repository README |
| 5.x is published as separate JVM and Android artifacts. Android users get an AAR from 5.0.0-alpha.15 onward. The Android variant depends on AndroidX Startup and AndroidX Annotation. | CONFIRMED | OkHttp changelog (the page cached by the fetch tool ended at 5.3.1) |
| Maven Central lists 5.3.2 (Nov 18, 2025), 5.4.0 (Jun 9, 2026) and 5.5.0 (Aug 17, 2026). The README now shows 5.5.1 as the example version. 5.5.1 was not found in a dated listing. | CONFIRMED for 5.3.2, 5.4.0, 5.5.0. UNCONFIRMED for 5.5.1. | mvnrepository listing, GitHub README (secondary listings for dates) |
| 5.4.0 release note: interceptors can now override anything settable on `OkHttpClient.Builder`, including socket factory and DNS. | LIKELY | Renovate release notes quoting the changelog. The official changelog page fetched was older. |
| Licence: Apache 2.0. | CONFIRMED | Maven Central POM and listings |
| The `okhttp-android` 5.5.0 POM declares `kotlin-stdlib 2.1.21` (compile), `okio-jvm 3.18.1` (compile), `androidx.annotation:annotation-jvm 1.10.0` (runtime) and `androidx.startup:startup-runtime 1.2.0` (runtime). | CONFIRMED for the cached POM shown | Sonatype Central page |
| 5.3.2 depends on okio 3.16.4 and `kotlin-stdlib 2.2.21`. | CONFIRMED | mvnrepository dependency page |
| Builder methods exist in 5.x: `dns`, `socketFactory`, `followRedirects`, `followSslRedirects`, `retryOnConnectionFailure`, `protocols`, `cache`, `proxy`, `proxySelector`, `fastFallback`. | CONFIRMED | OkHttp 5.x API documentation, third-party API mirror for the full list |
| `socketFactory` documentation: OkHttp only uses the parameterless `SocketFactory.createSocket` to create unconnected sockets. | CONFIRMED | OkHttp 5.x official Builder documentation |
| Fast fallback (Happy Eyeballs, RFC 8305) races IPv4 and IPv6 TCP handshakes, with a 250 ms head start for IPv6. It is on by default since 5.0.0-alpha.11 and can be disabled with `fastFallback(false)`. | CONFIRMED | OkHttp changelog |
| `EventListener` reports `dnsStart/End`, `connectStart/End`, `secureConnectStart/End`, `connectionAcquired`, request and response events, `requestBodyEnd(byteCount)` and `responseBodyEnd(byteCount)`. Pooled connections fire no connect events. Events carry no timestamp. | CONFIRMED in the contract (D3.3), not re-fetched | Contract section 6 |
| Header bytes are not reported by the body-end events. Accurate byte accounting therefore needs a counting wrapper or `TrafficStats`. | LIKELY | Inference from the event signatures above |
| 5.2.1 fixed a crash on `SSLSocket.shutdownOutput()` on API 21 to 23. | CONFIRMED | Changelog. Not relevant at minSdk 26. |
| 5.2.0 fixed worker thread interrupt handling in the fast fallback code. | CONFIRMED | Changelog. Matters if cancellation is by interrupt. |

### 4.3 Compatibility with the pinned toolchain

| Check | Result | Label |
|---|---|---|
| minSdk 26 vs OkHttp floor of API 21 | Compatible | CONFIRMED |
| Kotlin 2.3.21 vs OkHttp built with Kotlin 2.1.x or 2.2.x (stdlib 2.1.21 in the 5.5.0 POM, 2.2.21 for 5.3.2) | Kotlin keeps backward compatibility for libraries compiled with older compilers. Gradle will resolve stdlib to the project's version. | LIKELY. Not proven by a build. |
| AGP 8.13.2 and compileSdk 36 vs OkHttp AAR metadata (`minCompileSdk`, `minAgpVersion`) | NOT VERIFIED. The AAR metadata file is inside the binary AAR and was not readable with the tools available. The repo's own history (coreKtx 1.19.0 failed `CheckAarMetadata`) shows this exact check can fail. | UNCONFIRMED |
| `androidx.startup:startup-runtime 1.2.0` AAR metadata vs compileSdk 36 | Not verified. | UNCONFIRMED |
| Okio 3.16 to 3.18 and Kotlin 2.3.21 | Not verified by a build. | UNCONFIRMED |
| Supply chain: version age | 5.4.0 is about 4.5 months old and 5.5.0 about 10 weeks old at the date of this document. | CONFIRMED (dates above) |
| Vulnerabilities | The listing showed none for 5.4.0 and 5.5.0. Not an audit. | LIKELY |

How to close the UNCONFIRMED rows (for the leader to authorize, not done here): on a scratch branch that is never merged, add one `implementation` line for the candidate version to `network:monitor` and run on CircleCI the equivalents of `:network:monitor:dependencies`, `:network:monitor:checkDebugAarMetadata`, a debug assemble, the JVM tests, and a merged-manifest dump. Record the commit and run id. This is precisely the process that caught the coreKtx problem.

### 4.4 Binding probes to a specific candidate network

| Item | Detail | Label |
|---|---|---|
| Socket binding | `Network.getSocketFactory()` (API 21). Its parameterless `createSocket()` creates an unconnected `Socket` and calls `bindSocket` on it. `bindSocket(Socket)` is API 21, `bindSocket(DatagramSocket)` is API 22, `bindSocket(FileDescriptor)` is API 23. | CONFIRMED (API levels from the official reference). The factory body is from AOSP source for Android 9 and the Connectivity module. |
| Why it fits OkHttp | OkHttp only calls the parameterless `createSocket()`, which is exactly the method that binds. | CONFIRMED (both halves) |
| DNS on the same network | `Network.getAllByName(host)` (API 21) resolves on that network. Wire it as a custom `Dns { network.getAllByName(it).toList() }`. If this is skipped, the name is resolved on the default network and the probe may connect to an address chosen for a different network. | CONFIRMED (API doc). Interaction with Private DNS on a non-default network is UNCONFIRMED. |
| Network handle for evidence | `Network.getNetworkHandle()` is API 23. `fromNetworkHandle` is API 28. | CONFIRMED |
| Process-wide binding | `bindProcessToNetwork` is not suitable. It affects the whole process and does not support several networks of the same type (spec 3.1 item 1). Per-socket binding is the right tool. | CONFIRMED (spec, official doc) |
| Seeing a network is not permission to use it | The official guide says seeing a network in a callback does not mean the app can use it for data transfer. Some networks are restricted. Check `NET_CAPABILITY_INTERNET` and `NET_CAPABILITY_VALIDATED`. | CONFIRMED |
| Background network needs `CHANGE_NETWORK_STATE` | The guide states twice that using a background network requires `CHANGE_NETWORK_STATE`. | CONFIRMED (official guide, last updated 2026-10-23). The exact set of API levels where the check is enforced is UNCONFIRMED. |
| `registerNetworkCallback` does not keep a network up | Official `NetworkRequest` documentation. Only `requestNetwork` keeps a network up, and it also needs `CHANGE_NETWORK_STATE`. Therefore a passive advisor can only probe a second network while the system happens to keep it up. | CONFIRMED |
| A second network is kept up when "Mobile data always on" is on | The official sample sequence says the mobile network never disconnects in that case, and disconnects after a while if the setting is off. | CONFIRMED for the documented sample. Default setting per OEM is UNCONFIRMED. |
| VPN | A probe bound to the underlying network bypasses the VPN. It measures the underlying network, not the VPN path. The spec 5.3 rule 5 wording must stay. | LIKELY |
| Network disappears mid probe | A `Network` object stops being usable when the network disconnects (official guide). The client must compare the handle before and after and report `NetworkChangedMidCall`. | CONFIRMED (guide), design per contract D5.11 |

Recommended OkHttp configuration for probes (each item still needs confirmation against the pinned version at admission, per the contract D3.11):
* `socketFactory(network.socketFactory)` and `dns` via `network.getAllByName`.
* `followRedirects(false)`, `followSslRedirects(false)`, `retryOnConnectionFailure(false)`, `cache(null)`.
* `protocols(listOf(Protocol.HTTP_1_1))` for latency and jitter probes.
* `fastFallback(false)` for the warm series so that one address family is timed and recorded. This is a design recommendation. The alternative (leave it on and record) costs a possible bias in cold totals. Decision for the leader. Whichever is chosen, record it in the method id as the contract requires.
* A fresh client per candidate network, or `newBuilder()` from a shared base so the thread pools are shared but the connection pool is not, so a connection never leaks across networks. A shared connection pool across different bound networks is a correctness hazard. UNCONFIRMED how OkHttp keys pooled connections when only the socket factory differs, so use a separate pool per network.
* System proxy honored and `proxyUsed` recorded (contract D3-3).

### 4.5 Admission checklist status (contract D3.11)

| Check | Status |
|---|---|
| Exact release chosen and verified on Maven Central | Candidates 5.4.0 and 5.5.0 verified as published. Final pin pending CI. |
| AAR metadata vs compileSdk 36 and AGP 8.13.2 | OPEN (UNCONFIRMED) |
| Kotlin 2.3.21 compiles | OPEN (LIKELY) |
| Licence | CONFIRMED Apache 2.0 |
| Consumer R8 rules | The README links R8 rules. Not inspected. UNCONFIRMED. |
| Merged-manifest delta | See section 6. |
| APK size delta | OPEN. Measure in CI. |
| Builder options exist in the chosen version | CONFIRMED to exist in 5.x docs. Behavior per option not run. |
| Dependency governance questions (skill 18) | Capability not in Android or Kotlin stdlib at the needed level of event detail, maintained (releases through Aug 2026), permissions none of its own, startup cost is one lazy provider, no sensitive data, simpler option is T1 which moves security-critical code into AERIVA. |

Proposed catalog lines, text only, not applied:
```
[versions]
okhttp = "5.4.0"   # candidate, pending CircleCI proof of AAR metadata and Kotlin compatibility
[libraries]
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
```
Test-scoped fixture server artifact (`mockwebserver3`) needs its own admission (contract D3-10).

## 5. Data cost, consent and byte budget (deliverable 3)

### 5.1 Cost of one probe

Header sizes are protocol arithmetic. Totals are estimates to be replaced by `ProbeEvidence` and `TrafficStats` measurements on real devices.

| Component | Bytes (estimate) | Label |
|---|---|---|
| DNS query and reply (UDP, not part of app TCP bytes but billed) | about 100 to 250 | UNCONFIRMED |
| TCP handshake, three packets with IP and TCP headers and options | about 180 to 240 (IPv4), more for IPv6 | LIKELY |
| TLS 1.3 ClientHello | about 250 to 550 | LIKELY |
| TLS 1.3 server flight (certificates dominate; RFC 8879 says certificate chains often take up the majority of handshake bytes) | about 2,500 to 6,000 depending on chain and key type | LIKELY. One example in a vendor article shows 3,331 bytes for a particular chain. |
| Client Finished and TLS record overhead | about 100 to 200 | LIKELY |
| HTTP/1.1 request headers (small, no cookies) | about 150 to 300 | LIKELY |
| HTTP response headers plus a 64 byte body | about 200 to 400 | LIKELY |
| Connection close (FIN and ACK) | about 100 to 200 | LIKELY |
| Cold probe total | about 4 to 9 KB | LIKELY |
| Warm exchange total (request, response, two ACKs, TLS record overhead) | about 0.6 to 1 KB | LIKELY |
| One series: 1 cold plus 9 warm | about 10 to 18 KB | LIKELY |

Money: at the best published bundle price found (about N200 per GB for a 45 GB monthly bundle, MTN, checked 12 September 2026, secondary site quoting the operator) and at N500 for a 1.5 GB weekly bundle (N333 per GB), a 15 KB series costs about N0.003 to N0.005. At the higher figure of N500 per GB (a 2025 price for a 1 GB plan) it is about N0.0075. The money cost is negligible. What matters is trust, battery, and time on slow links.

Time on slow links: at 50 kbit/s a 6,000 byte handshake needs about 1 second of pure transfer (arithmetic), before round trips. With 2G still more than a third of mobile connections, a cold probe on such a link is slow and noisy. LIKELY.

### 5.2 Design: consent and byte budget

All numbers are placeholders for OD-6, in the same spirit as L5. They are not validated.

1. Consent states per user: Off (default), Unmetered only, Unmetered and metered with budget. First use shows the cost in plain terms before enabling ("A check uses about 15 KB").
2. Never probe: when the network is roaming, when the platform reports the app blocked on that network (`onBlockedStatusChanged`, contract D4-8), when Data Saver restricts the app (API details UNCONFIRMED, must be checked), or when the user has not consented for that network class.
3. Metered networks: probing only with explicit opt-in. Metered state comes from `NET_CAPABILITY_NOT_METERED` (official guide). Do not assume Wi-Fi is unmetered or mobile is metered (the guide says so).
4. Per-probe hard cap: client aborts and reports a failure if `bytesSent + bytesReceived` exceeds a cap (suggest 16 KiB per cold probe, 2 KiB per warm exchange). The server also caps response size (contract R6).
5. Per-day budget per network class (suggest 64 KiB per day on metered, a larger value on unmetered), counted from `ProbeEvidence.bytesSent + bytesReceived` plus an estimated fixed overhead term for headers and DNS that the client cannot see. Failed probes count against the budget (contract D5.5).
6. Series control: sample cap and inter-sample interval as in contract D4-5. A series over the cap is refused, not truncated.
7. No automatic retries. A retry is a scheduler decision under the budget.
8. User-initiated "check now" is allowed on metered networks after consent, and shows the bytes used afterwards from the evidence.
9. Link-quality gate: do not run a cold probe when `getLinkDownstreamBandwidthKbps()` (official guide) reports a very low value. The threshold is a design parameter for validation. The platform value is an estimate and may be wrong. UNCONFIRMED.
10. Foreground only for v1. No background scheduling. This also avoids Doze and background-network restrictions (not researched further here).
11. Accounting is double entry: application-level bytes from a counting socket wrapper (event bodies exclude headers) and OS-level bytes from tagged sockets (`TrafficStats`, contract D3-8), reconciled in validation. Whether `TrafficStats` counts IP-layer or payload bytes was not verified. UNCONFIRMED.

### 5.3 What the user is told

Copy rules stay with the brand voice and the no-unsupported-claims rule. Example factual line: "A check sends about 15 KB. It uses your mobile data." No em dash, no emoji.

## 6. Manifest and release implications (deliverable 4)

| Change | Why | Who needs it | Release implication | Label |
|---|---|---|---|---|
| `uses-permission android.permission.INTERNET` | Open sockets | Any probe | Normal permission, granted at install without a prompt (platform protection level normal). Declared once in the module that owns the client (contract D1-1). | CONFIRMED (protection level from a mirror of the platform reference). Official reference page itself was not fetched. |
| `uses-permission android.permission.CHANGE_NETWORK_STATE` | Use a background (non-default) network, and `requestNetwork` | Only probing of non-default candidates | Normal permission, no prompt. NEW relative to L6, spec section 6 and contract D1-3. Needs explicit owner approval. If refused, A2 can only probe the default network, which removes the main purpose of the advisor. | CONFIRMED (official guide). Protection level normal: LIKELY (mirror of platform docs). |
| `androidx.startup.InitializationProvider` merged from OkHttp 5.x | OkHttp uses AndroidX Startup | Only if OkHttp is admitted | A non-permission manifest entry that appears in the merged manifest. Another large app removes the provider in its test manifest and relies on OkHttp lazy initialization (comment in that manifest, secondary). Exact metadata entries must come from the real merged manifest. Allowlist per contract D1-6. | LIKELY. Exported flag of the provider UNCONFIRMED. |
| `networkSecurityConfig` | Not needed for production. The endpoint is HTTPS with a public CA, system trust store. Defaults for target 28 and higher block cleartext and ignore user CAs (contract, official docs). | n/a | No `usesCleartextTraffic`, no cleartext config in main (contract D1-7). A debug-only config for loopback fixtures belongs in debug or androidTest source sets only. | CONFIRMED (contract, official docs) |
| `ACCESS_NETWORK_STATE` | Already declared | n/a | None | CONFIRMED |
| Not needed | Location, phone state, Wi-Fi state | n/a | The spec's rule that A1 and A2 do not read SSID, BSSID or cell identity stands. | CONFIRMED |

Release and policy implications:
* Play Data safety form: "collected" is commonly described as data transmitted off the device, and an IP address is classed under device or other identifiers by several vendor guides. "Processed ephemerally" is a strict in-memory-only definition. The correct answers for AERIVA depend on what the endpoint logs (OD-3). The primary Play Console help pages were not fetched. UNCONFIRMED. The owner or legal must read them directly.
* Privacy policy must describe the probe: destination (AERIVA hostname), what is sent (a random nonce, nothing else), what is stored on the device, that nothing is uploaded in v1, and the consent states.
* A normal permission does not appear as a prompt, so consent has to be an in-app control, not an OS control.
* Merged-manifest allowlist check in CI (contract D1-6) is the right guard and must cover permissions and providers.

## 7. Mapping a probe result to `LatencyMeasurement` and `ProbeEvidence` (deliverable 5)

Method ids are from the contract registry (D3-7). `https-h1-warm-exchange` is the latency and jitter workhorse. `https-h1-cold-total` is a separate measurement and is never mixed into jitter pairs (contract D4-3).

### 7.1 Success

| Target field | Source | Notes |
|---|---|---|
| `LatencyMeasurement.Succeeded.method` | Registry constant | `https-h1-warm-exchange` or `https-h1-cold-total`. Fast fallback setting recorded here or in evidence (contract D3-3). |
| `valueMillis` | Client's own monotonic clock difference, taken in the event listener. Warm: from request start on an already acquired connection to response body end. Cold: from `callStart` (or `dnsStart`) to response body end. | Not wall clock. The engine's wrapper time goes into `engineElapsedMillis`, not here (contract D3-6). Server processing time is not subtracted. |
| `sampleCount` | 1 per exchange, or the number of samples aggregated by the series engine | Per contract |
| `measuredAt` | Injected clock at probe start | |
| `context` | `MeasurementNetworkContext` from the candidate network state (validated, metered, portal, VPN flags) | Existing type |
| `evidence.networkHandle` | `network.getNetworkHandle()` (API 23) | Opaque, process local. Never persisted beyond the process by default. |
| `evidence.addressFamily` | Remote address of the connected socket as seen at connect or acquire time | `Unknown` if not observable |
| `evidence.negotiatedProtocol` | Response protocol from OkHttp mapped to a fixed vocabulary (`http/1.1`) | Never a free string from the server |
| `evidence.connectionState` | `Cold` if connect events fired for this call, `Warm` if a pooled connection was acquired with no connect events, `NotApplicable` for UDP | Observable per the contract's event analysis |
| `evidence.proxyUsed` | Route proxy is not `DIRECT` | |
| `evidence.phases` | `dnsMillis`, `connectMillis`, `tlsMillis`, `firstByteMillis`, `lastByteMillis` from listener events. Null for any phase not separable. Warm samples have null dns, connect and tls. | Never a guessed split (contract) |
| `evidence.serverProcessingMillis` | Parsed from the reply body, validated and bounded, or null | Null with a Worker-based endpoint unless a measurable I/O span exists |
| `evidence.serverRegionId` | Parsed from the reply, length and charset validated | Used for the comparability rule in section 3.3 |
| `evidence.bytesSent`, `bytesReceived` | Counting socket wrapper at application level (headers included) | Body-end events alone would undercount (LIKELY). Cross-checked against `TrafficStats` in validation. |
| `evidence.engineElapsedMillis` | Engine wrapper time | Diagnostic |

### 7.2 Failure

Use the contract's D5 taxonomy unchanged. Examples for this client:

| Observation | `MeasurementFailure` |
|---|---|
| `UnknownHostException` | `DnsFailure(NOT_RESOLVED)` |
| Connect refused, reset, unreachable | `EndpointFailure(kind)` |
| Certificate or hostname failure | `TlsFailure(CERTIFICATE_INVALID or HOSTNAME_MISMATCH)` |
| 3xx from `/v1/ping` | `UnexpectedRedirect(code)` |
| Nonce mismatch or malformed body | `InvalidResponse(NONCE_MISMATCH or MALFORMED)` |
| Deadline reached at a stage | `Timeout(stage)` |
| Handle differs before and after | `NetworkChangedDuringMeasurement` |
| Blocked by the platform | `BlockedByDevicePolicy` |
| Missing `INTERNET` (`SecurityException`) | Not a failure. Decline `CapabilityUnavailable` and log a defect (contract D5-7). |
| Missing `CHANGE_NETWORK_STATE` when binding to a background network | NEW. Platform behavior (exception type, or silent failure) is UNCONFIRMED. Map to a decline with a dedicated reason, never to a network failure, and cover it with a negative test. |
| Anything else | `Unclassified(exceptionClass)` |

A failed probe always carries evidence with the bytes already spent (contract D5.5).

### 7.3 Jitter and the advisor

* Jitter uses `DerivedJitterStats.from` over warm samples on one network handle with equal method ids. The series is one cold exchange (kept as its own `https-h1-cold-total` result, excluded from pairs) followed by N warm exchanges on the same pooled connection.
* The advisor compares candidate networks only when: both have succeeded series, both carry the same method id, both carry the same `serverRegionId`, and both are fresh. Otherwise it reports "not comparable". It never subtracts across regions and never produces a score (`NetworkQuality.Measured` stays unused).

## 8. Open items, risks and next steps

### 8.1 Decisions the leader needs

| Id | Decision | Recommendation |
|---|---|---|
| OD-1 | `INTERNET` | Approve when the first client slice starts, as already planned |
| OD-1b (NEW) | `CHANGE_NETWORK_STATE` for non-default candidate probing | Approve together with OD-1, or accept that A2 probes only the default network |
| OD-2 | Endpoint form | Option C (edge-answered echo on one hostname) with a paid plan, origin optional |
| OD-3 | Logging | No per-request logs in v1. Confirm platform defaults before go-live |
| OD-4 | Coverage | Lagos edge via commodity CDN. No funded in-country origin for v1 |
| OD-6 | Budgets | Use the placeholders in section 5.2 until pilots exist |
| OD-7 | OkHttp admission | Approve a scratch-branch CircleCI proof, then pin |
| New | fast fallback on or off | Off for the warm series, recorded either way |

### 8.2 Risks

| Risk | Label | Mitigation |
|---|---|---|
| OkHttp 5.x AAR metadata or startup-runtime needs a higher compileSdk or AGP than 8.13.2 | UNCONFIRMED, history says it can happen | Scratch-branch CircleCI proof before any pin |
| `CHANGE_NETWORK_STATE` refused or its absence fails silently | UNCONFIRMED behavior | Negative test on device, explicit decline reason |
| Two candidate networks reach different edges, so the comparison is a routing artifact | LIKELY | Region id rule, "not comparable" state |
| Second network is often not kept up, so the advisor rarely has two candidates | UNCONFIRMED (spec R1) | Device matrix before any claim of usefulness |
| Worker free tier exhausted | LIKELY if adopted at scale | Paid plan, rate limits |
| Platform logging of IPs conflicts with OD-3 and the Data safety answers | UNCONFIRMED | Read the platform's data processing terms before choosing |
| 2G links make cold probes slow and noisy | LIKELY | Link-quality gate, longer stage timeouts, no cold probe below a threshold |
| Shared connection pool across networks leaks a connection to the wrong network | UNCONFIRMED how pooling keys work | Separate client and pool per candidate network |
| Private DNS on a non-default network may bypass `Network.getAllByName` semantics | UNCONFIRMED | Device test with Private DNS on and off |

### 8.3 Not verified in this document

* No build, test, CI run or device. No CircleCI run id exists for this change. It adds one Markdown file only. If CircleCI ran on the branch push, its result was not read by this agent.
* OkHttp AAR metadata, startup-runtime AAR metadata, consumer R8 rules, APK size delta, 5.5.1 publication date.
* Official `Manifest.permission#INTERNET` and `#CHANGE_NETWORK_STATE` reference pages (protection level taken from a platform-doc mirror).
* Platform default logging of Cloudflare Workers or CloudFront, billing caps, included request allotment.
* Primary Google Play Data safety definitions.
* Behavior on real devices for every item marked UNCONFIRMED.
* The Nigerian latency figures come from secondary summaries and a third-party test site. The NCC and Ookla PDF was found but its relevant pages were not read in full.

### 8.4 Suggested next steps (for the leader to authorize)

1. Decide OD-1b and OD-2. Everything else follows from those two.
2. Authorize an OkHttp scratch-branch CircleCI proof (section 4.3). Record commit and run id.
3. Write the wire-protocol specification for `/v1/ping` (contract slice S2) using section 3.4 as the requirements input.
4. Add `CHANGE_NETWORK_STATE` to the device validation matrix: with and without the permission, default network versus background network, Wi-Fi plus mobile with "Mobile data always on" on and off.
5. Plan the first real-device pass to answer spec 3.1 item 6 and to measure real probe bytes.

## 9. Sources

Official or primary (read or seen in this session):
* Android Developers, Read network state (last updated 2026-10-23), including additional networks, restrictions, `CHANGE_NETWORK_STATE`, sample callback sequence: https://developer.android.com/develop/connectivity/network-ops/reading-network-state
* Android reference, `android.net.Network` (API levels for `bindSocket`, `getSocketFactory`, `getAllByName`, `getNetworkHandle`, `fromNetworkHandle`): https://developer.android.com/reference/android/net/Network
* Android reference, `NetworkRequest` (registerNetworkCallback does not keep networks up): https://developer.android.com/reference/android/net/NetworkRequest
* Android reference, `ConnectivityManager.NetworkCallback`: https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback.html
* Android App Startup: https://developer.android.com/topic/libraries/app-startup
* AOSP `Network.java` (NetworkBoundSocketFactory): https://code.yawk.at/android/android-9.0.0_r35/android/net/Network.java and https://git.aicp-rom.com/packages_modules_Connectivity.git/plain/framework/src/android/net/Network.java
* OkHttp changelog: https://square.github.io/okhttp/changelogs/changelog
* OkHttp 5.x Builder documentation: https://square.github.io/okhttp/5.x/okhttp/okhttp3/-ok-http-client/-builder/index.html
* OkHttp repository README: https://github.com/lysine-dev/okhttp
* Maven Central okhttp-android POM: https://central.sonatype.com/artifact/com.squareup.okhttp3/okhttp-android
* Cloudflare Workers performance and timers: https://developers.cloudflare.com/workers/runtime-apis/performance/
* Cloudflare Workers limits: https://developers.cloudflare.com/workers/about/limits and https://developers.cloudflare.com/workers/platform/limits/index.md
* Cloudflare Workers pricing: https://developers.cloudflare.com/workers/platform/pricing/ and https://www.cloudflare.com/plans.md
* RFC 8879, TLS Certificate Compression: https://www.rfc-editor.org/info/rfc8879
* Repository files at `f53134c`: `gradle/libs.versions.toml`, `NetworkClient.kt`, `MeasurementEndpointConfig.kt`, `LatencyMeasurement.kt`, `ProbeEvidence.kt`, `.claude/05-network-optimization/SKILL.md`, `.claude/18-dependency-governance/SKILL.md`.
* `NETWORK_ADVISOR_PHASE_A_SPEC.md` (branch `network-advisor-phase-a-spec`) and `PHASE_4_CROSS_CUTTING_TECHNICAL_DECISION_CONTRACT.md` (branch `phase-4-cross-cutting-decisions`).

Secondary (used for LIKELY findings only):
* mvnrepository listings for okhttp 5.3.2, 5.4.0, 5.5.0 and dependencies: https://mvnrepository.com/artifact/com.squareup.okhttp3/okhttp
* Renovate and Dependabot pull requests quoting the OkHttp 5.4.0 release notes (2026-06-08).
* Mozilla Fenix test manifest comment on OkHttp 5.x and the startup provider: https://searchfox.org/firefox-main/source/mobile/android/fenix/app/src/androidTest/AndroidManifest.xml
* Platform permission descriptions mirrored at https://docwiki.embarcadero.com/RADStudio/en/Uses_Permissions
* Cloud regions in Nigeria: https://zop.dev/cloud-cost-optimization/nigeria and https://www.cdnplanet.com/geo/nigeria-cdn/
* AWS CloudFront Lagos edge announcements (June 2023): https://guardian.ng/news/aws-launches-first-cloudfront-edge-location-in-nigeria/ and https://www.thisdaylive.com/2023/06/16/aws-launches-first-cloudfront-edge-location-in-nigeria/
* NCC and Ookla summary: https://pro.edgex.exchange/en-US/news/article/nigeria-mobile-internet-report-urban-rural-divide
* NCC consumer insights PDF (not read in full): https://ncc.gov.ng/sites/default/files/2026-01/Consumer-Insights-%26-Industry-Trends.pdf
* Browser test site Nigeria median: https://speedof.me/internet-speed/nigeria
* Nigerian connection mix (2G, 3G, 5G, May 2026): https://intelpoint.co/?p=2469
* Data prices: https://awajis.com/?p=73738 and https://businessday.ng/?p=949775
* Google Play Data safety explainers from third-party vendors (not primary): https://help.fliplet.com/google-play-data-safety-for-fliplet-apps/ and https://support.singular.net/hc/en-us/articles/5755762951835-Submitting-the-Google-Play-Data-Safety-Section-Form
* Handshake size example: https://www.alibabacloud.com/blog/how-does-babassl-reduce-the-80%25-bandwidth-of-tls-handshake_598938
