# Releasing DeskLink for Android

One source tree, three channels: Google Play (AAB signed with the upload key),
GitHub Releases (APK + checksums, built by `.github/workflows/release.yml`) and
F-Droid (built and signed by F-Droid from a tag, unsigned on our side).

## 1. Create the upload key (once, outside the repository)

Never put a keystore in this repository, and never regenerate or touch
`debug.keystore` (historical compatibility material, see `docs/` in the workspace).

```sh
mkdir -p ~/keys && chmod 700 ~/keys
keytool -genkeypair -v \
  -keystore ~/keys/desklink-upload.jks -storetype PKCS12 \
  -alias desklink-upload -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=DeskLink, O=derpx06"
```

`keytool` asks for the store password. With PKCS12 the key password is the same as
the store password. Back the file and password up somewhere offline: losing the
upload key means asking Google for a reset, losing a self-managed app signing key
means a new package name.

## 2. Build a signed release locally

The build reads four values from Gradle properties (`~/.gradle/gradle.properties`,
never the project's `gradle.properties`) or from the environment:

```properties
DESKLINK_UPLOAD_KEYSTORE=/home/you/keys/desklink-upload.jks
DESKLINK_UPLOAD_KEYSTORE_PASSWORD=...
DESKLINK_UPLOAD_KEY_ALIAS=desklink-upload
DESKLINK_UPLOAD_KEY_PASSWORD=...
```

```sh
./gradlew bundleRelease     # build/outputs/bundle/release/*.aab, for Play
./gradlew assembleRelease   # build/outputs/apk/release/*.apk
```

Release builds must also set the off-LAN relay address (`docs/RELAY.md` in the
workspace), the same way:

```properties
DESKLINK_RELAY_URL=wss://relay.example.org/v1
```

Without it `BuildConfig.DEFAULT_RELAY_URL` is empty: LAN works, and there is no relay
until the user types an address in settings (nothing is ever looked up on its own). `release.yml` reads
it from the repository variable `DESKLINK_RELAY_URL` (Settings > Secrets and
variables > Actions > Variables).

When `DESKLINK_UPLOAD_KEYSTORE` is unset the release is built **unsigned**
(`*-release-unsigned.apk`). That is what F-Droid and CI pull requests use.

Check a signature: `apksigner verify --print-certs build/outputs/apk/release/*.apk`.

## 3. Google Play App Signing

Play requires App Signing for new apps: you upload with the upload key, Google signs
what users install with the **app signing key**.

- **Recommended:** at first upload choose "Use a key you provide" / "Export and
  upload a key from Java keystore" and give Play a separate app signing key you
  generated the same way as above (alias `desklink-app`, kept offline). Then
  GitHub APKs can be signed with that same key, so people can move between GitHub
  and Play builds without reinstalling.
- If you let Google generate the app signing key, Play builds and GitHub/F-Droid
  builds have different signatures, and switching between them needs an uninstall
  (which drops pairings).
- Debug builds (`org.desklink.mobile.debug`) are a separate package and are not
  affected.
- F-Droid signs with its own key unless the build is reproducible and you ask
  F-Droid to ship the developer-signed APK. Either way it is a different signature
  from Play's Google-generated key.

Keep `build/outputs/mapping/release/mapping.txt` for every release (CI attaches
it to the GitHub release) and upload it to Play to deobfuscate stack traces.
Code is not obfuscated (`-dontobfuscate`), but R8 still removes and inlines code.

## 4. Versioning

`versionName` is the product version: **0.3.0**, the first public beta (the "beta" lives in the release notes,
the GitHub and Play release type and the update channel, not in the version string). `versionCode` is
**independent of `versionName`** and must increase on every upload and never go down: the first build was numbered
2.0.0 / 20000, so 0.3.0 is **20300** and the next beta or stable upload continues upward (20301, 20400, ...).
Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (500 characters max, named for the
versionCode, not the version) for each release; Play, F-Droid and the GitHub release all read it.
`ReleaseHygieneTest` pins versionName, the changelog file's length and the `CHANGELOG.md` top heading together.

## 5. CI

- `ci.yml` runs unit tests, `lintDebug` and an unsigned `assembleRelease` on every
  push and pull request.
- `release.yml` runs on a `v*` tag (`git tag v0.3.0 && git push origin v0.3.0`; the tag must equal `versionName`).
  It checks the tag against `versionName`, runs the unit tests and the dex guard,
  builds the APK and AAB, signs them if the secrets below exist, writes `SHA256SUMS`, and
  publishes everything to a GitHub **prerelease** (0.x is a beta) whose notes are the version's
  changelog file. Without the upload-key secrets the APK is `-unsigned` (Android will not install
  it): the release is then a **draft**, with a warning in the job summary, and is never public.

Repository secrets (Settings > Secrets and variables > Actions):

| Secret | Value |
|---|---|
| `DESKLINK_UPLOAD_KEYSTORE_BASE64` | `base64 -w0 ~/keys/desklink-upload.jks` |
| `DESKLINK_UPLOAD_KEYSTORE_PASSWORD` | store password |
| `DESKLINK_UPLOAD_KEY_ALIAS` | `desklink-upload` |
| `DESKLINK_UPLOAD_KEY_PASSWORD` | key password |

The workflow decodes the keystore into the runner's temp directory, points
`DESKLINK_UPLOAD_KEYSTORE` at it, and deletes it after the build. Secrets are not
available to workflows from forks, so pull requests always build unsigned.

If you used a separate app signing key (section 3), store that one in these secrets
instead, so the GitHub APK matches Play; upload to Play with the upload key from
your machine.

## 6. Store listing

Text lives in `fastlane/metadata/android/en-US/` (`title.txt` ≤ 30 characters,
`short_description.txt` ≤ 80, `full_description.txt` ≤ 4000). F-Droid reads it from
the tag; for Play run `fastlane supply` as described in `fastlane/README`.

Images live under `fastlane/metadata/android/en-US/images/`. `icon.png` is in place
(512 × 512, rendered from `icon.svg`, which matches the launcher icon). Still to add:

| File | Size |
|---|---|
| `featureGraphic.png` | 1024 × 500 PNG/JPEG, no KDE artwork (`docs/BRANDING.md`) |
| `phoneScreenshots/1.png` … | 2–8 screenshots, 16:9 or 9:16, 320–3840 px per side |

Use screenshots of a test session: no real notifications, names or terminal output.

Play Console forms (data safety, permission declarations): see
`docs/PLAY_DATA_SAFETY.md`. Privacy policy URL:
https://github.com/derpx06/desklink-mobile/blob/master/PRIVACY.md

## 7. Checklist

1. Bump `versionCode`/`versionName` in `build.gradle.kts`, add the changelog file.
   Keep versionCode above every earlier upload. (`DESKLINK_RELAY_URL` is inert while `RELAY_ENABLED` is false.)
2. `./gradlew testDebugUnitTest lintDebug assembleRelease`. Every `assembleRelease` and `bundleRelease` then runs the dex guard (`scripts/check-release-dex.sh`): it fails the build when the shrunk dex lacks a class native code looks up by name (`org.jni_zero.JniInit`: R8 removed it once and every release build aborted at the first WebRTC use), a plugin canary, or has fewer than 23 plugins. When a native library starts looking up another class, add it to `release-required-classes.txt` (read the class-path literals of the `.so` files) and keep it in `proguard-rules.pro`.
3. Install the release APK on a real phone; pair and run a feature over LAN
   (`docs/testing/RELIABILITY.md` in the workspace).
4. Tag and push; check the GitHub release and its `SHA256SUMS`.
5. Upload the AAB and `mapping.txt` to Play: the beta goes to a closed or open **testing track** first, not to production.


## 8. Third-party facts (the owner's decisions; nothing here was changed)

What the release APK (`assembleRelease`, arm64-v8a + armeabi-v7a, 43.7 MB) bundles that is not free software
or needs a decision. Written down so the choice is made knowingly:

| Component | Where | Size (arm64 / arm32) | Licence | Notes |
|---|---|---|---|---|
| WebRTC (`libjingle_peerconnection_so.so`, `org.webrtc`) | `webrtc-sdk` AAR | 12.1 MB / 6.8 MB | BSD-3-Clause (WebRTC), with the AAR's own third-party notices | Needed for every feature |
| ML Kit text recognition (`libmlkit_google_ocr_pipeline.so`, `assets/mlkit-google-ocr-models/`) | `com.google.mlkit:text-recognition:16.0.1` | 11.1 MB / 6.8 MB plus the models | Google ML Kit terms (proprietary, not GPL-compatible) | Used only to read text out of photos taken for "insert on the computer". Bundles Google's telemetry transport (`com.google.android.datatransport`) and `MlKitInitProvider`; ML Kit documents sending usage and performance metrics and an installation id. `PRIVACY.md` and `docs/PLAY_DATA_SAFETY.md` say so. |
| AndroidX graphics path | AndroidX | 10 KB | Apache-2.0 | |

Decisions that are the owner's, with the facts:

- **ML Kit is a build option, off by default** (2026-11-05): `-Pdesklink.ocr=true` (or `desklink.ocr=true` in
  `gradle.properties`) adds the dependency and the `src/ocr` recogniser; the default build uses the `src/noocr` stub, has
  no ML Kit (no ~18 MB, no telemetry, no licence entry) and does not offer "Copy Text". The table row above and the next
  bullet describe the `-Pdesklink.ocr=true` build.
- **GPL and ML Kit.** The app is GPL-3.0-or-later with KDE Connect code that cannot be re-licensed. Shipping a
  closed library in the same APK is a recognised GPL conflict, and F-Droid will not accept it (non-free
  dependency). Options: keep it (Play and GitHub only); move text recognition to a separate optional download;
  drop it (the option removes about 18 MB per ABI and the telemetry).
- **ABIs.** Dropping `armeabi-v7a` removes about 13.6 MB (the 32-bit copies of WebRTC and ML Kit). With every
  ABI the release APK is 95.6 MB.
- **Removing the ML Kit telemetry** (the datatransport services and `MlKitInitProvider` via
  `tools:node="remove"`) was not done: it cannot be checked off-device that recognition still works without
  them. Real-phone check V14 in the workspace list covers it if you want to try.
- **Unused dependencies** (done in 0.3): rxjava, reactive-streams, swiperefreshlayout, gridlayout and lifecycle-extensions had no users
  and were removed; `lifecycle-process` (for `ProcessLifecycleOwner`) is declared directly.
- **minSdk 24 (decided for 0.3).** Android 7.0 is the minimum: it drops Android 6.0 (well under 1% of devices), removes the API-24
  `NewApi` lint errors and the guards they asked for, and Termux's terminal libraries already declared 24. The 20 moot entries
  were deleted from `lint-baseline.xml`; the build still fails on any new lint error.
