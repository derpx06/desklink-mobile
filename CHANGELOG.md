# Changelog

## 1.3.0

- Needs Android 7.0 (API 24) or newer; Android 6.0 is no longer supported.
- Connection: comes back by itself after sleep, a network change or a restart; says clearly when it cannot.
- The Bucket: one folder kept in step with the computer, two folders deep, with progress and resume; a failed
  listing is never read as "everything was deleted"; names that differ only in capitals are not synced.
- Files: transfers that resume after a lost link and never arrive twice; a shared file keeps working after the share
  screen closes; "Send again" when Android no longer lets DeskLink read a file.
- Notes, snippets, contacts and calls on the computer (off until turned on); a call the computer asks for needs your
  Allow on the phone, and USSD/MMI codes are refused.
- Sound: the phone's microphone or sound on the computer, and the computer's sound on the phone, when you allow it.
- Glide: the computer's pointer and keyboard continue onto the phone.
- Privacy: secret notifications are never sent, private ones go without text unless allowed; secret screens (notes,
  terminals, pairing code, session password) are excluded from screenshots and recents; accessibility has a plain
  explanation and consent first; the clipboard workaround that read the system log is debug-only; release builds
  log errors only.
- Security: other apps can no longer make the phone send to the computer unasked or read its private data; strangers on the
  Wi-Fi are limited like on the computer; unpairing forgets everything held for that computer.
- Removed: browsing the phone's files, the presentation remote, Bluetooth.
