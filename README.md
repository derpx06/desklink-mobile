# DeskLink for Android

DeskLink connects your Android phone and your Linux computer: shared terminals
(with one-tap Claude Code and Codex), control of the computer from the phone,
the phone's screen on the computer, notifications with replies, clipboard, and
files. It works on the same Wi-Fi and, through the DeskLink relay, away from
home. There is no account.

Install: GitHub Releases (signed APK), Google Play, or F-Droid. Release and
signing notes are in `docs/RELEASE.md`; the privacy policy is `PRIVACY.md`.

## Current transport

DeskLink Protocol v9 over the local network.

Discovery, identity, pairing, and signed signaling use TLS with pinned
certificates. Paired features travel only over authenticated WebRTC data
channels; until the handover completes they are queued, never sent over the
bootstrap link. File bytes only ever travel on the bounded WebRTC file-data
channel.

Away from home, the phone and computer meet at the DeskLink relay and run an
end-to-end encrypted bootstrap link through it (the relay sees IP addresses,
certificate fingerprints and timing, never content); media goes direct or
through TURN when the "Use TURN" setting allows. Spec: `docs/RELAY.md` in the
workspace, relay server in the desktop repository's `relay/`.

The inactive compatibility module documents the legacy KDE Connect-compatible
protocol v8; it is not advertised or transmitted by the active path.

## Current support

- Same-LAN discovery, QR pairing, and the relay for other networks.
- Shared terminals with one-tap Claude Code/Codex, dictation and full screen;
  an optional per-computer session password.
- Remote control of the computer (view and control its screen) and the phone's
  screen on the computer.
- Ping, find-my-phone, battery, clipboard, files and links, notification
  mirroring with reply and action, and file browsing.

The exact set exchanged with the desktop client is `WebRtcFeatureProfile`. It
must stay byte-identical to `DESKTOP_INCOMING` ∪ `DESKTOP_OUTGOING` in the
desktop's `features/registry.rs`, because the handover compares each peer's
filtered capability list for exact equality. Tests on both sides pin the list.

## Not yet implemented

Wi-Fi Direct and a shared virtual library.

Media control, system volume, and run-command are implemented here in the
phone-controls-desktop direction only. They stay outside the shared profile
until the desktop client grows the backends to answer them.

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
