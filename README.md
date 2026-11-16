# DeskLink for Android

DeskLink connects your Android phone and your Linux computer: shared terminals
(with one-tap Claude Code and Codex), control of the computer from the phone,
the phone's screen on the computer, notifications with replies, clipboard, and
files. It works on the same Wi-Fi. There is no account.

Install: GitHub Releases (signed APK), Google Play, or F-Droid. Release and
signing notes are in `docs/RELEASE.md`; the privacy policy is `PRIVACY.md`.

## Current transport

DeskLink Protocol v9 over the local network.

Discovery, identity, pairing, and signed signaling use TLS with pinned
certificates. Paired features travel only over authenticated WebRTC data
channels; until the handover completes they are queued, never sent over the
bootstrap link. File bytes only ever travel on the bounded WebRTC file-data
channel.

**Off in 1.3** (a LAN-only release): `RELAY_ENABLED` in `build.gradle.kts` is
`false`. That constant is the only control: there is no setting to turn the
relay or TURN on or off, and the relay address comes from the build
(`DESKLINK_RELAY_URL`). When it is on: away from home, the phone and computer
meet at the DeskLink relay and run an
end-to-end encrypted bootstrap link through it (the relay sees IP addresses,
certificate fingerprints and timing, never content); media goes direct or
through the relay's TURN. Spec: `docs/RELAY.md` in the
workspace, relay server in the desktop repository's `relay/`.

The inactive compatibility module documents the legacy KDE Connect-compatible
protocol v8; it is not advertised or transmitted by the active path.

## Current support

- Same-LAN discovery and QR pairing (the relay for other networks is off in 1.3).
- Shared terminals with one-tap Claude Code/Codex, dictation and full screen;
  an optional per-computer session password.
- Remote control of the computer (view and control its screen) and the phone's
  screen on the computer.
- Ping, find-my-phone, battery, clipboard, files and links, notification
  mirroring with reply and action (both ways), media control, and lock and unlock.
- The Bucket (a folder kept in step with the computer), notes and snippets, contacts and
  calls on the computer, the phone's microphone or sound on the computer, the computer's
  pointer and keyboard carrying on onto the phone, and inserting a photo, scan or QR text
  into whatever you are doing on the computer.

The exact set exchanged with the desktop client is `WebRtcFeatureProfile`. It
must stay byte-identical to `DESKTOP_INCOMING` ∪ `DESKTOP_OUTGOING` in the
desktop's `features/registry.rs`, because the handover compares each peer's
filtered capability list for exact equality. Tests on both sides pin the list.

### Control, typing and the accessibility service

Control of the phone from the computer (taps, swipes, Glide) and typing into it
go through DeskLink's accessibility service. On Android 13+ it is also an input
method for the focused field (`flagInputMethodEditor`), so typed text is
committed and keys are sent the way a keyboard sends them, whatever keyboard is
selected; older phones, and an editor whose connection Android left stale
(Chrome's address bar after its first key), edit the focused field directly. A key that reaches no
field is never dropped in silence: the phone logs why and tells the computer's
Phone Screen window ("no text field is selected", "turn on DeskLink in
Accessibility"), which says so in its strip.

**A force stop switches an app's accessibility service off** (it is removed
from the enabled services: seen on stock Android 16 and on ColorOS), and
ColorOS (realme, OPPO, OnePlus) also does it on every app update, which stock
Android does not (an `install -r` keeps it on there). Nothing tells the
person, and control and typing stop. DeskLink remembers that the service
was running; when it is not running again after a restart and was not switched
off by the person, the phone posts one notification ("Android turned off
DeskLink's accessibility service", one tap to the setup) and the computer's
Phone Screen window says the same when it asks for control. Some builds leave it
shown as on in Settings while it is not running; then it has to be turned off
and on again, which the setup screen says.

## Not yet implemented

Wi-Fi Direct and a shared virtual library.

Browsing the phone's files, the presentation remote, system volume, run-command and
Bluetooth were removed in 1.3.

## Compatibility

The published Android package identity remains `org.desklink.mobile` so
existing installations can update in place. The legacy `kdeconnect` deep-link
scheme remains a stable external identifier. Active packet identifiers use
`desklink.*`; legacy `kdeconnect.*` values are kept only in the inactive
compatibility module and are not public product branding.

## License and attribution

DeskLink includes code and protocol-compatible behavior derived from KDE
Connect. Required copyright, license, and contributor attribution remain in
the source. See the workspace documentation for the complete attribution and
compatibility record.

Bundled assets:

- JetBrains Mono (`src/main/res/font/jetbrains_mono_*.ttf`), copyright 2020 The
  JetBrains Mono Project Authors, under the SIL Open Font License 1.1
  (`LICENSES/OFL-1.1.txt`). Used for the terminal and technical text.
- Material Symbols icons (`src/main/res/drawable/sym_*.xml`, `ic_device_*_32dp.xml`)
  by Google, under the Apache License 2.0 (`LICENSES/Apache-2.0.txt`).
