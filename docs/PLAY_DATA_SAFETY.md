# Play Console answers

Answers for the Play Console forms for DeskLink 2.0.0 (`org.desklink.mobile`).
They follow `PRIVACY.md`; if the app or the relay changes, update both.

**Before submitting, the owner must confirm one fact:** that the deployed relay keeps
IP addresses and certificate fingerprints only in memory for the life of a
connection (no logs, no database). The answers below assume it does. If the relay
logs them, change "Processed ephemerally" to No.

## Data safety

### Overview

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | Yes (device IDs and IP address, through the relay; see below) |
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

Everything else: not collected.

- Location: not collected (the Wi-Fi name for trusted networks stays on the phone).
- Personal info, financial info, health, messages, photos/videos, audio, files and
  docs, calendar, contacts, app activity, web browsing, app info and performance:
  not collected (files, messages in notifications and screen contents are sent
  end-to-end encrypted to the user's own device only).

## Permission declarations

### Accessibility API (`MouseReceiverService`)

Play requires a declaration and an in-app prominent disclosure. Text for the
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

Video: for `connectedDevice`, show the computer receiving a notification while the
app is in the background; for `mediaProjection`, start and stop a screen share.

### All files access (`MANAGE_EXTERNAL_STORAGE`)

Declared use: **file management / sync with a connected device**.

> Users browse, open, copy and save files on their phone from their own paired
> computer (the "Files" feature, as a network file system). This needs access to
> the whole shared storage, like a file manager, because the computer side lists
> arbitrary folders; the Storage Access Framework cannot serve an arbitrary
> directory tree to the computer. Access is requested only when the user turns on
> the Files feature, and nothing leaves the phone except to the user's own paired
> computer, end-to-end encrypted.

If Play rejects this, the fallback is to drop the permission and serve SAF-picked
folders on Android 11+ as is already done on Android 10 and below
(`SftpSettingsFragment`); that is a code change, not done yet.

### Other sensitive permissions

- `READ_PHONE_STATE`: mobile network type and signal strength for the computer's
  status view (connectivity report). No declaration form.
- `ACCESS_FINE_LOCATION` (foreground only): reading the Wi-Fi name for trusted
  networks. No background location.
- `CAMERA`: QR pairing and the find-my-phone flashlight.
- `RECORD_AUDIO`: terminal dictation.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: Play allows it for apps whose core
  function needs a persistent connection to a companion device; be ready to justify
  it with the `connectedDevice` text above.
- Removed in 2.0.0: SMS, MMS, call log, contacts, `QUERY_ALL_PACKAGES`,
  `REQUEST_INSTALL_PACKAGES`, `USE_FULL_SCREEN_INTENT`, background location.
  None of the SMS / Call Log policy declarations apply any more.

## App content

- Privacy policy: https://github.com/derpx06/desklink-mobile/blob/master/PRIVACY.md
- Ads: no. Target audience: 18+ (developer tool). Content rating: utility, no
  user-generated content shared with others.
- App access: the reviewer needs the DeskLink desktop app; provide instructions or
  a demo video of pairing.
