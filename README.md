# DeskLink for Android

DeskLink is a phone-to-Linux connectivity application. The Android app
discovers DeskLink peers on the same local network and communicates directly
over encrypted links.

## Current transport

DeskLink Protocol v9 over the local network.

Discovery, identity, pairing, and signed signaling use TLS with pinned
certificates. Paired features move to authenticated WebRTC data channels once
the handover completes, and use the pinned-TLS link until then, so features keep
working on networks where ICE cannot establish. File bytes only ever travel on
the bounded WebRTC file-data channel.

The inactive compatibility module documents the legacy KDE Connect-compatible
protocol v8; it is not advertised or transmitted by the active path.

## Current support

- Same-LAN discovery and direct encrypted communication.
- Ping, find-my-phone, battery, clipboard, files and links, notification
  mirroring with reply and action, SFTP browsing, and remote input.

The exact set exchanged with the desktop client is `WebRtcFeatureProfile`. It
must stay byte-identical to `DESKTOP_INCOMING` ∪ `DESKTOP_OUTGOING` in the
desktop's `features/registry.rs`, because the handover compares each peer's
filtered capability list for exact equality. Tests on both sides pin the list.

## Not yet implemented

Internet relay, Wi-Fi Direct, remote shell, and a shared virtual library.

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
