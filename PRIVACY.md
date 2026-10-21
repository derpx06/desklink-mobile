# DeskLink privacy policy

Last updated: 2026-10-20. Applies to the DeskLink Android app (`org.desklink.mobile`)
from version 2.0.0.

DeskLink connects your phone to your own computer running DeskLink. It has no
account, no ads, no analytics and no tracking SDKs. We do not collect, sell or share
your data: what the app reads goes to the computer you paired, and nowhere else.

## What the app accesses, and why

Everything below is sent only to a computer you have paired, over an encrypted
connection, and only while the matching feature is turned on.

| Data | Used for | Permission |
|---|---|---|
| Notifications (app name, title, text, icon) | Showing your phone's notifications on the computer and replying to them | Notification access |
| Clipboard | Copying and pasting between the devices | — |
| Files you share, and phone storage when you browse it from the computer | Sending files, and browsing the phone from the computer | All files access (Android 11+) / storage |
| Screen contents | Showing the phone screen on the computer, only while you are sharing it | Screen capture (asked each time) |
| Touch and key input from the computer | Controlling the phone from the computer, if you enable the accessibility service | Accessibility service |
| Battery level and charging state | Shown on the computer | — |
| Mobile network type and signal strength | Shown on the computer | Phone state |
| Camera | Scanning the pairing QR code, and the flashlight for "find my phone" | Camera |
| Microphone | Dictating into the terminal; speech goes to your phone's speech recognizer, which may be an online service from your phone's vendor | Microphone |
| Wi-Fi network name | Recognising your "trusted networks" | Location |
| Device name, model, certificate | Identifying the phone to the computer during pairing | — |

Pairing keys, the list of paired computers and your settings are stored only on
the phone. Uninstalling the app deletes them. App data is excluded from Android
backups.

## The DeskLink relay

When your phone and computer are not on the same network, they can meet through the
DeskLink relay (you can turn this off, or point the app at your own relay).

- The connection setup through the relay is **end-to-end encrypted** between your two
  devices. The relay cannot read it and cannot impersonate either device.
- The relay sees: the **IP addresses** of your devices, the **certificate
  fingerprints** that identify them and which devices are allowed to reach each
  other, and **timing** (when devices connect and how much they send). It does not
  see the content of anything you send.
- Your data itself (files, notifications, clipboard, screen, terminal) travels over an
  encrypted WebRTC connection, directly between your devices where possible.
- When no direct path works, that encrypted traffic may pass through a **TURN
  server provided by Cloudflare** (Cloudflare Realtime TURN). Cloudflare sees IP
  addresses and traffic volume, not content. You can turn TURN off in Settings.

## Other network requests

- The app downloads Google Play's public list of device models
  (`storage.googleapis.com`) to show a friendly name for your phone. No personal
  data is sent with this request.

## Crash reports

If the app crashes, it writes a report (app version, Android version, phone model and
the error's stack trace) to its private storage. On the next start it offers to let
you share it. Nothing is uploaded automatically; you choose whether and where to
send it.

## Children

DeskLink is not directed at children.

## Contact and changes

Questions and requests: open an issue at
https://github.com/derpx06/desklink-mobile/issues. Changes to this policy are
published in this file, with its history in the repository.
