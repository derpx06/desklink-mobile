# DeskLink privacy policy

Last updated: 2026-10-25. Applies to the DeskLink Android app (`org.desklink.mobile`)
from version 1.3.0.

DeskLink connects your phone to your own computer running DeskLink. It has no
account, no ads and no advertising or tracking SDKs. We do not collect, sell or share
your data: what the app reads goes to the computer you paired, and nowhere else.
(The one component that talks to Google on its own, ML Kit text recognition, is
described under "Other network requests".)

## What the app accesses, and why

Everything below is sent only to a computer you have paired, over an encrypted
connection, and only while the matching feature is turned on. Features that read
sensitive data (contacts, calls, the microphone, notifications, the accessibility
service) are off until you turn them on and Android asks you.

| Data | Used for | Permission |
|---|---|---|
| Notifications (app name, title, text; not the icon) | Showing your phone's notifications on the computer and replying to them. Notifications Android marks secret are never sent; private ones go without their text unless you allow it | Notification access |
| Clipboard (text and images) | Copying and pasting between the devices, while clipboard sync is on | none |
| Files and links you share; the Bucket folder you pick | Sending files and links, and keeping one folder in step with the computer | none (you pick the folder with Android's file picker) |
| Contacts (names, numbers, photos) | Showing your contacts on the computer and naming callers, only if you turn on "Share contacts" | Contacts |
| Call state, number and name | Showing, answering and ending calls on the computer, and dialling from it: a call the computer asks for is placed only after you tap Allow on the phone | Phone state, Answer calls, Make calls |
| Screen contents | Showing the phone screen on the computer, only while you are sharing it | Screen capture (asked each time) |
| Touch, pointer and key input from the computer | Controlling the phone from the computer, typing into the focused field, and the pointer crossing from the computer's screen to the phone, if you enable the accessibility service | Accessibility service (explained and agreed to first) |
| Microphone and the phone's sound | Streaming them to the computer when it asks and you allow it (you can have the choice remembered per computer) | Microphone |
| Camera | Scanning the pairing QR code, the flashlight for "find my phone", and photos or scans you take to insert on the computer | Camera |
| Notes and snippets | Syncing the notes and pinned snippets you write, kept encrypted on both devices | none |
| Battery level, charging state; the title, artist and cover of the media playing | Shown and controlled on the computer | none (media needs Notification access) |
| Terminal text you type | Typed into terminals that run on your computer | none |
| Wi-Fi network name | Recognising your "trusted networks" | Location |
| Device name, model, certificate | Identifying the phone to the computer during pairing | none |

The phone also keeps, for 30 days, a list of the files you sent and received (names and
where they are stored) so you can open them again. Pairing keys, the list of paired
computers, your settings and notes are stored only on the phone and are removed with
the pairing or by uninstalling the app. App data is excluded from Android backups.

What the phone does **not** do: it does not let the computer browse its storage, read
your call log or messages, or see your location.

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
- **ML Kit text recognition** (Google) reads text out of photos you take to insert on
  the computer. The recognition runs on the phone, but Google's ML Kit library may send
  usage and performance metrics and an installation identifier to Google. Nothing you
  photograph is sent.

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
