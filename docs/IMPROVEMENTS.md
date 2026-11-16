# Improvements (1.3, phase 3.5): phone audit

A per-module reliability and performance pass over the phone app after the
phase-3 seams (593ff94). Each module group was audited read-only by an
independent reviewer; every finding was checked against its callers. The
desk's list is `DeskLink/docs/IMPROVEMENTS.md` (same severities and status).

Severity: **S1** crash, hang, data loss or security; **S2** a feature breaks
or stays broken until a restart under realistic conditions; **S3** degraded,
rare, or a cheap performance or battery win; **S4** cosmetic or code quality.

Out of scope here: the phone-screen pipeline (phase 3.4) and keyboard,
control re-ask and accessibility-off detection (phase 3.6).

## Home: redrawn only when something it shows changed (row 3.5 item 1)

`MainActivity` re-read the devices once a second (they are not observable
state) and so redrew Home every second even when nothing changed.
`RefreshGate` keeps the one-second check but bumps the refresh only when a
stamp of what Home shows changed: the paired list, the computer's asks and
`HomeModel.build` (connection, transfer, live sessions, media, offers,
clipboard preview, ...), plus the two system queries every 5 s. Every other
screen keeps the plain one-second refresh. `SessionTransport` is untouched.

Measured on emulator-5554 (API 36), Home in front and idle, link up, 60 s
per run, interleaved with the 593ff94 build (`.tools/overnight/perf/homeidle.sh`):
see `.tools/overnight/perf-e2e.md` for the runs; frames per minute went from
about 65 to about 4, process CPU from about 3 % to about 1.3 %.

Making the device fields observable (StateFlow) is the larger follow-up the
audit mapped (`DeskLinkApplication.onDeviceListChanged` covers most changes;
gaps: `connectionPath`, `webRtcUnavailable`, `onPluginsChanged`, deletion and
plugin-internal state). The stamp gives the measured win without it.

## Core: Device, transport, coordinator (non-screen), file engine

No S1 or S2 that could be proven.

| ID | S | Where | Finding | Status |
|---|---|---|---|---|
| P1 | S3 | `webrtc/DeferredTaskExecutor.kt` `OffThreadObserver` | `onRequiredChannelOpen` was not forwarded, so the coordinator never heard it: capabilities that arrived while a channel was still opening left feature-ready unsent until the desk's 10 s rebuild. | **fixed** (`the required channels opening reaches the coordinator`) |
| P2 | S3 | `Device.kt` unpair | Unpair closed the outbound queue for good while the LAN link stayed: pairing again said "not reachable" until the link died (up to 5 min). | **fixed** (`OutboundQueue.drain`; `aDrainedQueueStillTakesNewSendsAfterAnUnpair`). With the queue alive, `sendPacketBlocking` refuses any non-bootstrap packet while unpaired, at the one choke point, reading the pairing state once, and no non-bootstrap packet ever reaches the LAN line (review 1+2): `anUnpairedDeviceSendsNoFeaturePacketOnTheLanLink`, `anUnpairDuringASendNeverPutsTheFeaturePacketOnTheLanLink`. The unpair drain keeps the queued pair:false (`anUnpairDrainKeepsThePairFalseItJustQueued`); features queued before a pairing are failed when it completes |
| P3 | S3 | `WebRtcSessionCoordinator` close | `fileTransfers.close` is posted, then the file runner is shut down at once: a send in progress can spin waiting for ever. | listed: needs a coordinator-order test with a real runner |
| P4 | S3 | `WebRtcTransport.requestConnectionStats` | `getStats` can race `dispose()` (a native use-after-free). | listed: proposed one peer lock with phase 3.4, which owns that file (same root as its O1/O2) |
| P5 | S3 | `BackgroundService` | The Wi-Fi and wake locks were created check-then-act from any thread: two at once leaked one Wi-Fi lock. | **fixed**: state changes are applied on the main looper (and a burst is one update); a change posted before onDestroy does nothing after it. No unit test: there is no service-lifecycle harness |
| P6 | S3 | `DeskLinkApplication` | Read-only UI queries create a coordinator (8 executors, disk I/O) on the main thread after it was removed. | listed |
| P7 | S3 | `FlowControlledSender.drain` | A send into a disposed channel throws: the message was lost silently. | **fixed** (reported CLOSED; `aSendThatThrowsIsReportedClosedNeverDropped`) |
| P8 | S3 battery | `WebRtcTransport` ICE config | `GATHER_CONTINUALLY` while the app rebuilds the peer on every network move anyway. | listed: a measurement first (idle wakeups are mostly libwebrtc's dcSCTP timer) |
| P9 | S4 | coordinator OFFER path | A late success reads the mutable `transport` field. | listed |
| P10 | S4 | `Device.kt` | The not-ready offer to the feature queue is outside `sendOrderLock`. | listed |
| P11 | S4 | `FileLane.kt` | `RealFileLanes.unregister` is never called. | listed |
| P12 | S4 | `Device.kt` | `webRtcRecoveryPending` is written but never read. | listed |
| P13 | S4 | coordinator | `WaitingForOffer` is published on every 10 s heartbeat (P5's coalescing absorbs the cost). | listed |
| P14 | S4 | `helpers/Guarded.kt` | An old executor's shutdown removed the replacement's "Tap to restart" (owners keyed by name). | **fixed** (`anOldExecutorShuttingDownKeepsItsReplacementsRestart`) |

## Plugins and UI

| ID | S | Where | Finding | Status |
|---|---|---|---|---|
| C1 | S2 | `plugins/clipboard/ClipboardListener.kt` | An image copy kept its key `image:<uri>` as the clipboard text: every reconnect sent it to the desk as text (replacing the desk's image copy) and Home previewed it. | **fixed** (the key is kept apart; `anImageCopyIsNeverSentAsTextOnConnectNorPreviewedAsText`) |
| C2 | S3 | `ReceiveNotificationsPlugin` | Nothing capped the desk's notifications in the shade; past Android's 50 per app the phone's own notices vanished. | **fixed**: a desk's newest 24 by its own time stay, an older one is not posted, so a resync changes nothing (`aDeskNeverFillsTheShadeItsOldestGoFirst`, `aResyncOfTheSameNotificationsChangesNothingInTheShade`) |
| C10 | S1 | `ClipboardListener.readNow` | Found while measuring on the live desk: every app start reads the clipboard and stamped the read time, so the connect packet said the phone's old copy was made now and the desk placed it over a copy made on the laptop since (15 placements on the owner's laptop from emulator restarts, 17:17-17:45). | **fixed** (the copy's own time, else 0 = unknown, which the desk never places; Android 7's first read is not auto-sent; `aCopyReadAtAppStartKeepsItsOwnCopyTime`, `theConnectPacketCarriesWhenTheCopyWasMadeNotWhenItWasRead`, `aFreshProcessWithNoCopyTimeNeverAutoSendsWhatWasAlreadyThere`) |
| C3 | S3 | `MprisReceiverPlugin` | Plain maps written from three threads (a ConcurrentModificationException crashed on the main thread). | **fixed** (all on the main looper; `featureReadyFromAWorkerRunsOnTheMainLooper`) |
| C4 | S3 | `NotificationsPlugin` | What the desk was sent for notifications dismissed while the link was down was kept for the process's life (with their text). | **fixed** (a snapshot forgets them; `aSnapshotForgetsWhatTheDeskWasSentForGoneNotifications`) |
| C5 | S3 | `LockDevicePlugin` | A `lock.request` carrying the report key `isLocked:true` locked the phone (a report read as a command). | **fixed** (`aRequestCarryingOnlyIsLockedNeverLocks`) |
| C6 | S3 | `MainActivity` Share / Open with | A file DeskLink can no longer read threw SecurityException on the main thread (crash). | **fixed** (`OpenElsewhere`; `OpenElsewhereTest`) |
| C7 | S3 | `TelephonyPlugin` | Desk requests changed the call tracker on the packet thread while the line callbacks did on main: a decline could be reported as missed. | **fixed** (`aDesksRequestIsAppliedOnTheMainLooper`) |
| C8 | S3 perf | `MainActivity` 1 s tick | See "Home" above. | **fixed** |
| C9 | S4 | `HomeModel` | The shade count and permission check run on the main thread every 5 s while Home is in front (now in the refresh loop instead of composition; still every 5 s). | listed |

### View-only desktops (row 3.5 item 2)

The amber strip in the desk-screen view said "Allow control on <computer>
first" with a button "Allow control" before and after the computer answered,
even on desktops that can only be watched (sway, Hyprland, COSMIC), where
there is nothing to allow and the button only asks again. Now: "Asking
<computer> for control…" while asking; once refused, "View only on
<computer>" above the computer's own reason, and the button (and the More
menu item) says "Ask again". Strings and the headline only; no wire change.
Tests: `aViewOnlyDesktopSaysViewOnlyAndOffersToAskAgain`,
`whileControlIsBeingAskedForTheStripSaysSo`.

## For other phases

- 3.4 (screen): O1 debug stats `TimerTask`s call `peer.getStats` with no
  closed check; O2 `pathExecutor.shutdownNow()` does not wait for a running
  `onPath` before `peer.dispose()`. Both native use-after-free risks, same
  root as P4.
