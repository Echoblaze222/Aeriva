# Network monitor: resync design note

Author: Agent 1. Design only. No code, no production file changed.
Follows NETWORK_MONITOR_RELIABILITY_SEAM_REPORT.md, section 4, step 2.
Labels as in that report: CONFIRMED, LIKELY, UNCONFIRMED.

## Finding that changes the open item

The report asked the Team Leader whether resynchronization needs a reducer contract change. Reading `NetworkEventReducer.reduce` (lines 112 to 181 on `main` @ `9cff60c`) answers it: NO, not needed. CONFIRMED by code reading:

- `RawNetworkEvent.Available(network, snapshot)` replaces the whole tracked state unconditionally, for any network (lines 135 to 153).
- `RawNetworkEvent.Unavailable` always resets to offline (lines 114 to 120).

So a recovery after a dropped event can be expressed with events that already exist: `Available(current, snapshot)` when a default network exists, `Unavailable` when none does. The reducer, `foldNetworkStateFlow` and `MonitorState` stay unchanged. The decision shrinks from "change the reducer contract" to "add one recovery step in the Android adapter layer".

## Design

1. Trigger. `PlatformEventSource.platformEventFlow` already reports a rejected send through `onSendFailed`. Record that an overflow happened (a flag, not a count).
2. When to resync. Only after the callback channel has been drained, so every older event already accepted is applied first. Reason: the accepted events are older than the dropped one. A resync applied BEFORE them would be overwritten by stale data when they arrive. This ordering hazard is the main design risk. UNCONFIRMED how best to guarantee it; two candidates, prototype both with the fake registrar before choosing:
   - (a) Inside the flow: after each receive, if the flag is set and the channel is empty, perform the resync then. Needs the flow to own its consumer loop.
   - (b) Outside the flow: a conflated signal merged into the event stream. Simpler, but the signal can be processed before queued older events, which is the hazard above.
3. Must not depend on a later event. A dropped `Lost` can be the LAST event for a long time. A resync that only runs when the next event arrives would never run in that case. The trigger therefore has to wake the consumer itself.
4. Lookup, outside the callback body. Read the platform's current default network and its capabilities on the consumer side, never inside a `NetworkCallback` method (the platform documents synchronous lookups inside callbacks as race prone; `AndroidNetworkMonitor.kt` already cites this). Emit `Available(current, snapshot)`, or `Unavailable` when there is no default network.
5. Existing guards stay as they are: strict network match for `Lost`, `CapabilitiesChanged` and `BlockedStatusChanged`, and both `distinctUntilChanged` stages around `debounce`.

## Open risks

- `blocked` cannot be read back. `Available` resets `blocked` to false (reducer line 151), and the platform's real value normally arrives as a separate `BlockedStatusChanged` right after `onAvailable`. A resync has no such follow-up, so a network that is actually blocked by device policy could be shown as unblocked until the next real `BlockedStatusChanged`. UNCONFIRMED whether a synchronous API for this exists at minSdk 26. Do not claim the resync restores `blocked`.
- The app's default network and the network this callback tracks are assumed to be the same. LIKELY, not verified. VPN and per-app network rules could make them differ.
- A resync is only as fresh as the instant of the lookup. A change right after it arrives as a normal event and is handled normally.
- Resync adds a synchronous platform call. It runs only after an overflow, which is UNCONFIRMED to happen on any real device (report risk R4).

## Cost and ownership

- Needs detection first: `audit/network-monitor-seam-impl` already counts rejected sends. The flag in step 1 is a small addition on top of it.
- Touches `AndroidNetworkMonitor.kt` and `PlatformEventSource.kt`, not the reducer. Outside Agent 1's original ownership; needs the Team Leader's go.
- Testable on the JVM with the fake registrar from `PlatformEventFlowTest`: fill the buffer, drop a `Lost`, assert the monitor ends offline after the resync, and assert an older queued event cannot overwrite it. The second assertion is the one that decides between candidates (a) and (b).
- Not worth building until detection runs on real devices and shows overflow is real. If it never fires, this stays a documented contingency.

## Recommendation

Do not build resync now. Merge detection, ship, and read `droppedEventCount` from real devices. If it is ever non-zero, implement candidate (a) with the ordering test above. No reducer change is needed in either case.
