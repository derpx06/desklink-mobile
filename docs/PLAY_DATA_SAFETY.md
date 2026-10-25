# Play Console answers

Answers for the Play Console forms for DeskLink 1.3.0 (`org.desklink.mobile`).
They follow `PRIVACY.md`; if the app or the relay changes, update both.

**Before submitting, the owner must confirm one fact:** that the deployed relay keeps
IP addresses and certificate fingerprints only in memory for the life of a
connection (no logs, no database). The answers below assume it does. If the relay
logs them, change "Processed ephemerally" to No.

## Data safety

### Overview

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | Yes (device IDs and IP address through the relay; ML Kit metrics and an install id; see below) |
| Is all of the user data collected by your app encrypted in transit? | Yes |
| Do you provide a way for users to request that their data is deleted? | Yes: nothing is stored server-side; uninstalling or unpairing removes everything. Point to PRIVACY.md |
| Independent security review | No |
| UPI / Families | Not applicable; not directed at children |

### Why most data is not declared

Notifications, clipboard, files, screen contents, terminal I/O, battery and
network state go only to the user's own paired computer, over WebRTC with DTLS
between the two devices. Neither the developer nor the relay nor the TURN
provider holds the keys. Play's guidance exempts "data transferred off the device
using end-to-end encryption that is unreadable by anyone other than the sender
and recipient", so these are **not** declared as collected or shared.

Crash reports are written to app storage and leave the device only when the user
shares them through the Android share sheet (user-initiated), so they are not
declared.

Speech for terminal dictation goes to the phone's system speech recognizer, not to
DeskLink; nothing to declare.

### Declared data types

| Data type | Collected | Shared | Ephemeral | Required | Purpose |
|---|---|---|---|---|---|
| Device or other IDs (device certificate fingerprint, IP address) | Yes | No (Cloudflare acts as a service provider for TURN) | Yes | No: only when the relay is used; it can be turned off in Settings | App functionality |
| Device or other IDs (ML Kit installation identifier) | Yes (by Google's ML Kit library) | Yes, with Google | No | Yes while text recognition is in the app | Analytics / app functionality |
| App info and performance (ML Kit usage and performance metrics) | Yes (by Google's ML Kit library) | Yes, with Google | No | Yes while text recognition is in the app | Analytics |

Everything else: not collected by the developer or a third party.

- Location: not collected (the Wi-Fi name for trusted networks stays on the phone).
- Contacts, call state and numbers, notifications, clipboard, files, photos, audio (microphone and
  phone sound), screen contents, messages in notifications and terminal text go only to the user's own
  paired computer, end-to-end encrypted, and only for the feature the user turned on, so they are not
  declared as collected or shared (Play's exemption for end-to-end encrypted transfer between the
  user's own devices).
- The ML Kit rows are what the library does on its own; if the telemetry transport is ever removed
  from the app (see `docs/RELEASE.md`, "Third-party facts"), delete them and say "No analytics".

## Permission declarations

### Accessibility API (`MouseReceiverService`)

Play requires a declaration and an in-app prominent disclosure. The disclosure is built in:
`AccessibilitySetupActivity` shows what the service can see and do with an "Agree" and a
"No thanks" before anything sends the user to Accessibility settings. Text for the
declaration form:

> DeskLink is a companion app that connects the user's Android phone to their own
> Linux computer. When the user shares the phone's screen to the computer, the
> accessibility service lets them control the phone from the computer: it performs
> taps, swipes and global actions (back, home, recents, lock screen) and types text
> into the focused field, as instructed by the user's paired computer. It is turned
> on only by the user in system settings, and only acts on input the user sends
> from their own paired computer. The service does not collect, store or share
> any data; window content is read only to find the focused text field for typing.
> It is not an accessibility tool for users with disabilities, so
> `isAccessibilityTool` is false.

Video: show enabling the service, sharing the phone screen to the computer, and
controlling the phone from the computer.

### Foreground service types

| Service | Type | Declaration text |
|---|---|---|
| `BackgroundService` | `connectedDevice` | Keeps the encrypted connection to the user's paired computer while the app is in the background, so notifications, clipboard, files and remote input keep working. Started when the app starts and shown as a persistent notification the user can turn off. |
| `PhoneScreenCaptureService` | `mediaProjection` | Runs only while the user is sharing the phone's screen to their paired computer, after accepting the system screen-capture prompt; stops when sharing stops. |
| `PhoneAudioService` | `microphone` and `mediaProjection` | Runs only while the user's paired computer is receiving this phone's microphone or sound, started from a visible DeskLink window after the user allows it; stops when that ends. |

Video: for `connectedDevice`, show the computer receiving a notification while the
app is in the background; for `mediaProjection`, start and stop a screen share.

### All files access

Not declared. DeskLink 1.3 has no file browser for the phone's storage; received files go to the Downloads
folder or a folder the user picks, and the Bucket uses a folder the user picks with the Storage
Access Framework. `MANAGE_EXTERNAL_STORAGE` is not in the manifest.

### Other sensitive permissions

- `READ_PHONE_STATE`, `ANSWER_PHONE_CALLS`, `CALL_PHONE`: "Calls on the computer" (off by default):
  the call state, answering and ending calls, and dialling. A call the computer asks for is placed
  only after the user taps Allow on the phone, and numbers are digits only (no USSD/MMI codes).
- `READ_CONTACTS`: "Share contacts" and "Show caller" (both off by default).
- `ACCESS_FINE_LOCATION` (foreground only): reading the Wi-Fi name for trusted
  networks. No background location.
- `CAMERA`: QR pairing, the find-my-phone flashlight and photos or scans the user takes to insert on the computer.
- `RECORD_AUDIO`: streaming the microphone or the phone's sound to the computer when it asks and the user allows it
  (terminal dictation uses the system recognizer).
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: Play allows it for apps whose core
  function needs a persistent connection to a companion device; be ready to justify
  it with the `connectedDevice` text above.
- `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE`: up to Android 10 only (`maxSdkVersion` 29).
- Not in release builds: `READ_LOGS` and `SYSTEM_ALERT_WINDOW` (the "automatic clipboard sync" workaround is
  declared only by hand-installed debug builds), Bluetooth permissions (the Bluetooth link was removed).
- Removed: SMS, MMS, call log, `QUERY_ALL_PACKAGES`, `REQUEST_INSTALL_PACKAGES`,
  `USE_FULL_SCREEN_INTENT`, background location, all files access. None of the SMS / Call Log
  policy declarations apply.

## App content

- Privacy policy: https://github.com/derpx06/desklink-mobile/blob/master/PRIVACY.md
- Ads: no. Target audience: 18+ (developer tool). Content rating: utility, no
  user-generated content shared with others.
- App access: the reviewer needs the DeskLink desktop app; provide instructions or
  a demo video of pairing.
