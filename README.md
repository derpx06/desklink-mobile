# DeskLink for Android

DeskLink is a phone-to-Linux connectivity application. The Android app
discovers DeskLink peers on the same local network and communicates directly
over encrypted links.

## Current transport

DeskLink Protocol v9 over the local network.

The inactive compatibility module documents the legacy KDE Connect-compatible
protocol v8; it is not advertised or transmitted by the active path.

## Current support

- Same-LAN discovery and direct encrypted communication.
- Clipboard, files and links, notifications, media control, remote input, and
  the other plugins included in this repository.

## Not yet implemented

Internet relay, WebRTC, Wi-Fi Direct, remote shell, phone-to-desktop screen
capture, and a shared virtual library.

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
