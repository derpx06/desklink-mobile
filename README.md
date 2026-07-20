# DeskLink for Android

DeskLink is a phone-to-Linux connectivity application. The Android app
discovers DeskLink peers on the same local network and communicates directly
over encrypted links.

## Current transport

KDE Connect-compatible local-network protocol v8.

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
scheme and all `kdeconnect.*` packet identifiers are retained only for wire
compatibility; they are not public product branding.

## License and attribution

DeskLink includes code and protocol-compatible behavior derived from KDE
Connect. Required copyright, license, and contributor attribution remain in
the source. See the workspace documentation for the complete attribution and
compatibility record.
