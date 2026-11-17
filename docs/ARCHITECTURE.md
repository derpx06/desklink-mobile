# DeskLink for Android: module map

The full product architecture (both apps, the wire, the invariants) is the
workspace `docs/ARCHITECTURE.md` and `CLAUDE.md`; this page is the phone app's
own map: where things live, which way dependencies point, how to add a feature,
and what is planned next for the module structure.

## Layers (one way): the target

This is the structure new code follows. The current code does not meet it yet
(see "Known exceptions" below).

```
ui/ (MainActivity, Compose screens)           plugins/<feature>/ (one package per feature)
        │  reads Device / DeviceManager state          │  Plugin subclasses, @LoadablePlugin
        ▼                                              ▼
Device.kt  ── per-device façade: certificate check, feature queue, plugin routing
        │
        ▼
transport/SessionTransport  (Java-compatible: no suspend, no Flow)
        │
        ▼
webrtc/  (WebRtcSessionCoordinator, WebRtcTransport, envelopes, file engine)   backends/ (LAN TLS, relay)
```

* The UI never talks to a transport: it reads `Device` / `session/DeviceManager`
  state and calls plugin methods.
* A plugin sends through `Device.sendPacket*`; it never reaches a link,
  `WebRtcTransport` or libwebrtc. Features never travel over LAN or Bluetooth.
* `transport/SessionTransport` stays Java-compatible (Java plugins use it).
* Plugins do not import each other's internals; code two or more features need
  goes into `helpers/` under a name that says what it is (no `utils2`).
* Wire constants live in `protocol/desklinkv9/DeskLinkProtocol.kt` and are
  mirrored byte-for-byte by the desk's `device_links/packet.rs`.

## Known exceptions (today)

Measured on 0.3 (phase 3); each is listed so it is fixed on purpose, not copied:

* `webrtc/` imports `plugins/` in 5 files (`WebRtcSessionCoordinator`,
  `WebRtcTransport`, `WebRtcFileProtocol`, `WebRtcFileTransferManager`,
  `TransferErrors`): transport code that knows features.
* 43 plugin-to-plugin import pairs (not counting the shared `Plugin` /
  `PluginFactory` base), among them `folders` <-> `share` and
  `desksettings` <-> `inputdevicesreceiver`.
* The UI reaches the link layer directly: `backends.lan.UnpairedLimits` and
  `backends.relay.RelayLinkProvider` (from `MainActivity` and `HomeModel`).

## Where things live

| Package | Owns |
|---|---|
| `DeskLinkApplication.kt`, `BackgroundService.kt` | process setup; the foreground service with link providers, network callbacks, locks |
| `Device.kt`, `PairingHandler.kt`, `QrPairing.kt` | per-device façade, pairing state machine, QR pairing |
| `session/` | `DeviceManager` (generation-based, mirrors the desk), `DeviceSession`, `SessionBinding` |
| `transport/` | `SessionTransport`, `LegacyLanTransport`, `TransportType` |
| `backends/` | `lan/` (TLS links, mDNS, link race), `relay/`, `loopback/` |
| `webrtc/` | `WebRtcSessionCoordinator` (signalling, recovery, watchdogs, remote session), `WebRtcTransport` (PeerConnection, media policy), `WebRtcEnvelope`/`WebRtcChannel`/`WebRtcHandover`, the file engine (`WebRtcFileTransferManager`, `FileSender`, `FileReceiver`, `FileRunner`, `TransferErrors`, `FileLane`), `terminal/` |
| `plugins/<feature>/` | one package per feature (23 `@PluginFactory.LoadablePlugin` classes, found by KSP `classindexksp`; no manual registry). `folders/` holds the Bucket: `BucketModel` (what the screens show), `BucketEngine` (the worker), `FolderSync` (the F8 rules), `BucketUi` |
| `ui/compose/screen/home/` | `HomeState` (state and actions, data only), `HomeScreen` and the section files beside it |
| `update/` | the update check (docs/UPDATES.md): strict-JSON signed-manifest verification (BouncyCastle Ed25519), the decision table, the fetch (own OkHttp client, https only, 64 KiB cap), its own `desklink_update` preferences, install-source detection and the show-on-open coordinator. A leaf: nothing in pairing, transport, WebRTC or the plugins calls it, and it calls none of them (a test scans for both) |
| `helpers/` | shared utilities with one job each (`RateLimiter`, `Sizes`, `InboundLimits`, `PerPeerState`, `security/`, …) |
| `org/webrtc/DeskLinkVideoEncoderFactories.java` | low-latency hardware encoder setup (screen latency contract) |

## How to add a feature (phone)

1. Wire constants first: the packet type in `DeskLinkProtocol.kt` **and** the
   desk's `packet.rs`, identical strings; a shared fixture under
   `src/test/resources/shared/` (copied from the workspace `protocol/testdata/`,
   checked by `protocol/check-fixtures.sh`) when the body is non-trivial.
2. A package `plugins/<feature>/` with a `Plugin` subclass annotated
   `@PluginFactory.LoadablePlugin`; declare `supportedPacketTypes` /
   `outgoingPacketTypes` (capabilities are derived from them).
3. Enable it in `WebRtcFeatureProfile.enabled` only when the desk has a working
   handler too (the profile is pinned against the desk's registry by
   `profileMatchesTheDesktopFeatureRegistry`; the handover intersects).
4. Dispatch on the packet type, never on which body keys happen to be present;
   a peer's state report is never a command.
5. Per-device state is cleared on unpair (`notifyPluginsOfDeviceUnpaired`).
6. UI: a Compose screen under `ui/compose/screen/<feature>/` reading plugin or
   device state; strings in `res/values/strings.xml`.
7. Run `./gradlew testDebugUnitTest lintDebug assembleRelease` and the desk's
   mirrored change and tests. (The overnight gate script,
   `gate-phone-batched.sh`, lives in the workspace's `.tools/`, not in this
   repo.)

## Module structure: done and next

Done in 0.3 (phase 3): seams that moved whole top-level declarations without
changing a class: the file engine's `FileRunner` and error helpers, the Bucket's
data model, Home's state types.

Proposed next, each its own reviewed change with tests first:

* **First, the known exceptions above**: move what `webrtc/` needs from
  plugins behind interfaces the plugins implement, break the plugin-to-plugin
  pairs through `helpers/` or events on `Device`, and give the UI what it
  reads from the link layer through `Device` / `DeviceManager` state.

* **Split `WebRtcSessionCoordinator` (≈2.9k lines) by collaborator, not by
  file.** It is one class whose lock guards the active binding, attempt and
  transport. Extract, behind the same lock owner: `RemoteSessionControl`
  (view/control requests, leases, heartbeat), `RecoveryScheduler` (watchdogs,
  ladder, liveness timers) and `ScreenShareControl`; the coordinator keeps
  signalling and the handover and delegates. Each collaborator gets the
  coordinator's state through a narrow interface, testable with fakes.
* **Split `Device.kt` (≈1.7k lines)** into `DevicePairing` (pairing callbacks
  and notification), `FeatureQueue` (queue, flush, expiry under
  `sendOrderLock`) and `PluginHost` (load, enable, route); `Device` stays the
  Java-facing façade so plugins and Java callers do not change.
* **`MainActivity.kt` (≈1.7k)**: move the polling tick's state building into a
  `HomeStateBuilder` and make device state observable (phase 3.5 item 1).
* **Gradle modules.** A follow-up, not part of 0.3: `:protocol` (packet
  constants, envelope, fixtures; pure Kotlin/JVM), `:transport` (the
  Java-compatible `SessionTransport` and `DeviceManager`), `:webrtc` (libwebrtc
  wrapper and file engine), `:feature-*` per plugin family, `:app` (UI,
  service, wiring). Benefits: enforced one-way dependencies, faster incremental
  builds, plugins tested without Android where possible. Costs: KSP plugin
  discovery must index across modules (classindex per module + one merged
  index), R8 keep rules per module, and the debug keystore / package ids stay
  in `:app` (frozen identifiers). Start with `:protocol`, which has no Android
  dependencies.

## Secure windows (screenshots, screen sharing, recents)

Only real secrets set `FLAG_SECURE`: the **pairing code** screen, the **session password** dialog
(`SecureFlagPolicy.SecureOn`) and the **mouse pad** (it shows the computer's own screen). **Terminals
(tab and screen), Notes (with Snippets and the Clipboard history tab) and Files are not secure**: the
owner decided (2026-11-17) that screenshots, screen recording, the desk's Phone Screen share and the
recents tile must work there, and accepted that their content can appear in Recents thumbnails and
captures. The terminal keyboard is never a password variation (ColorOS blanks every capture while one
has the keyboard). `SecureWindowTest` pins the list; there is no debug switch any more.
