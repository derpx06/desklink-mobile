# Changelog

## 0.3.0 (beta)

The first public release. It works and is still being shaped; please report problems. LAN only: the
away-from-home relay is built but switched off. The version number is independent of `versionCode` (20300),
which only ever goes up.

- Needs Android 7.0 (API 24) or newer; Android 6.0 is no longer supported.
- Update prompts. When you open DeskLink, a sheet tells you if a newer version is available, with what changed and the
  right way to update for how you installed it (Google Play, F-Droid, a GitHub download). Not now, skip a version, or turn
  it off in Settings > About. DeskLink checks once a day (on Wi-Fi by default) for one signed file and sends nothing about
  you; it never downloads or installs anything by itself. Beta builds follow the Beta channel.
- Connection: comes back by itself after sleep, a network change or a restart; says clearly when it cannot.
- The Bucket: one folder kept in step with the computer, two folders deep, with progress and resume; a failed
  listing is never read as "everything was deleted"; names that differ only in capitals are not synced.
- Files: transfers that resume after a lost link and never arrive twice; a shared file keeps working after the share
  screen closes; "Send again" when Android no longer lets DeskLink read a file.
- Notes, snippets, contacts and calls on the computer (off until turned on); a call the computer asks for needs your
  Allow on the phone, and USSD/MMI codes are refused.
- Sound: the phone's microphone or sound on the computer, and the computer's sound on the phone, when you allow it.
- Glide: the computer's pointer and keyboard continue onto the phone.
- Privacy: secret notifications are never sent, private ones go without text unless allowed; the pairing code, the
  session password and the computer's screen are excluded from screenshots and recents; accessibility has a plain
  explanation and consent first; the clipboard workaround that read the system log is debug-only; release builds
  log errors only.
- Security: other apps can no longer make the phone send to the computer unasked or read its private data; strangers on the
  Wi-Fi are limited like on the computer; unpairing forgets everything held for that computer.
- Home-screen widgets: computer status, quick actions (screen, files, clipboard, ring, lock), notes (titles hidden while the
  phone is locked), transfers and a new-terminal launcher (only where terminal access is on); they follow the wallpaper
  colours on Android 12 and newer and say plainly when the computer cannot be reached.
- Connection: tells the computer when it is about to leave (Wi-Fi off, airplane mode, service end, task removed,
  network lost) so the computer checks the link at once; the computer says "Checking connection…" and "Can't reach"
  within seconds.
- Settings and Terminals use a tablet / unfolded layout (the page list beside the page, sessions side by side).
- Typing into the phone through the editor's input connection, with a notice when no field is selected.
- Release builds are checked for the classes WebRTC looks up by name (the R8 guard).
- One phone / computer vocabulary in the app's words.
- Removed: browsing the phone's files, the presentation remote, Bluetooth, run commands, automations, the drawing
  pad, the TV remote.
