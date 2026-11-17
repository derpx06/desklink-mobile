# Update-system shared fixtures v2 (TEST ONLY)

Master copy: `desklink-updates/fixtures/` = `phone_connect/docs/update/fixtures/`. Byte-identical copies live in
`DeskLink/testdata/update/` and `desklink-mobile/src/test/resources/shared/update/`; `protocol/check-fixtures.sh`
fails when one differs. Never edit a copy: change `desklink-updates/scripts/make-fixtures.py`, run it, then
`desklink-updates/scripts/sync-fixtures.sh`. The generator is deterministic (`git diff` is empty on a re-run).

**Everything here is a throwaway test artefact. `test1.private.pem` / `test2.private.pem` are published on purpose and
are NOT the project's signing keys.** Their kids are `test1`/`test2`, never `k1`/`k2`, so a real key set cannot collide.
**Rule for the clients:** a RELEASE build embeds ONLY the real keys; a unit test must fail if any kid starting with
`test` (or any key of `keys.json` here) is present in a release key configuration. Debug builds may compile the test keys in.

| File | What |
|---|---|
| `keys.json` | `{"keys":[{"kid","alg":"ed25519","public":<base64url, 32 bytes>}]}`: `test1` current, `test2` next |
| `vector.json` | one Ed25519 vector: public key, payload bytes, signature (hex and base64url) |
| `manifests/<id>.json` | the bytes a server would send (envelope, section 2.1 of docs/UPDATES.md) |
| `payloads/<id>.payload.json` | the unsigned payload of that manifest, pretty printed (humans only; apps never read it) |
| `cases.json` | the table: per manifest a list of runs, each with the expected verdict |
| `test*.private.pem` | the test private keys (so a debug build/test can sign its own manifests) |

## Envelope and the strict JSON profile (every client implements exactly this)

`{"v":1,"kid":"test1","payload":"<base64url, no padding>","sig":"<base64url, 64 bytes>"}`. The signature covers the
**decoded payload bytes as served**; nothing is re-serialised.

1. Body size <= 65536 bytes, else `oversized` (checked before anything is parsed; exactly 65536 is accepted).
2. Parse as JSON under the strict profile, else reason `parse`:
   - strict UTF-8 only (invalid bytes, UTF-16, a BOM U+FEFF at the start are errors);
   - nesting depth <= 32, checked while scanning, BEFORE or WHILE parsing (a 32000-deep body must not overflow the stack;
     Kotlin: pre-scan or catch `Throwable`, Rust: depth-limited parser);
   - no duplicate object keys, no `NaN`/`Infinity`, no lone surrogates (`\ud800`) in any key or string;
   - big numbers: integers only where integers are required (a float, string or bool there is an error), timestamps in 0..2^63-1.
3. The envelope object has EXACTLY the keys `v`, `kid`, `payload`, `sig` (an unknown or missing key is `parse`, not ignored);
   `v` is the integer 1 (not `true`).
4. `kid` is looked up exactly (case-sensitive) in the client's key set; unknown = `signature`.
5. `payload` and `sig` are CANONICAL base64url: alphabet `A-Za-z0-9_-`, no `=` padding, no length = 1 (mod 4), and
   re-encoding the decoded bytes must give the identical text (rejects non-zero trailing bits). Else `signature`.
6. `sig` is 64 bytes, Ed25519 strict: `S < L` (L = 2^252 + 27742317777372353535851937790883648493; a malleable S + L
   is rejected). Else `signature`.
7. The payload bytes are parsed under the same strict profile (else `parse`). `schemaVersion` must be an integer: `< 1` is `parse`,
   `> 1` is `schema` (nothing else is looked at). `issuedAt`/`expires` must be integers in 0..2^63-1 (else `parse`).
8. Time: `issuedAt <= now + 3600` (else `expired`: the documented code set has no clock code); `0 < expires - issuedAt <= 22 days`
   (else `expired`); `now <= expires` (else `expired`); `issuedAt >= lastSeenIssuedAt` (else `replay`; equal is fine).
9. **Ratchet:** after a manifest is fully accepted the client stores `lastSeenIssuedAt = min(issuedAt, now)`, never the raw
   `issuedAt`, so a manifest issued up to an hour "in the future" cannot block the legitimate ones that follow
   (`ratchetVectors`, case `legit-after-future-ish`).

## Components (a bad component is DROPPED; the other component and channel are unaffected)

`version` and `minSupported` are strict `MAJOR.MINOR.PATCH`: ASCII digits only, no leading zeros, no build metadata or
pre-release, exactly three parts, each part <= 2147483647. `minSupported` must be <= `version` (otherwise the component is
invalid: there is no "Required to reach a version that does not satisfy it"). `rollout`, when present, is an integer 0..100
(a float, string, bool or out-of-range value drops the component). Android `versionCode` is an integer 1..2100000000
(missing, string, negative, overflow = drop). The beta channel falls back to the stable entry only when that is newer.
Decision order: component absent -> UpToDate; **`rollout == 0` -> UpToDate (a PAUSE: nothing is shown, not even for a
manual check or Required)**; installed < minSupported -> `Required`; not newer (Android: `versionCode <= installed`) -> UpToDate;
`bucket >= rollout` (not manual) -> UpToDate; skipped / snoozed (not manual); else `Offer`. Rollouts 1..99 are ignored by
`Required` and by a manual "Check now".

## Cosmetic fields (invalid parts are dropped, never fatal)

- `notes`: not a list = none; each entry that is not an object, has a title that is not a string of 1..40 Unicode
  SCALARS (code points, not UTF-16 units or bytes), or a detail longer than 80 scalars, is skipped; then the first 8 kept.
  `glyph` not in the list (or missing) = `star`; empty `detail` = none.
- Allowed text (notes, `requiredReason` 1..160): no Cc (NUL, newline, tab), Cf (bidi controls U+202A-202E/U+2066-2069,
  zero width U+200B-200D, BOM), Cs, Zl, Zp, and none of `<` `>` `&`. A note failing this is skipped, a bad `requiredReason` is
  ignored (the Required state stays). **Client rule: all manifest text is rendered as PLAIN text (GTK `use_markup(false)`,
  Compose `Text`), never parsed as markup.**
- `released` must be a real `YYYY-MM-DD` date, else absent. Display only.
- URLs: exact pinned shapes (`url_ok` in `scripts/dl_update.py`; `urlChecks` has about 60 cases): scheme `https`; host
  `github.com` or `play.google.com` (case-insensitive after normalisation); no userinfo, no port except an explicit 443, no
  query/fragment (Play: exactly `?id=org.desklink.mobile`), no `%` at all, no backslash/whitespace/control/non-ASCII, no empty
  or `.`/`..` segments, no trailing-dot host. GitHub paths: `/derpx06/DeskLink/releases/tag/v<semver>`,
  `/derpx06/desklink-mobile/releases/tag/v<semver>`, `.../releases/download/v<semver>/desklink-<semver>-x86_64.tar.gz`
  (desktop only), `.../releases/download/v<semver>/desklink-<semver>.apk` (android only); the version in the URL must equal
  the component's `version`. Anything else is dropped (the field is absent); `downloads.play.id` is ignored unless it is
  `org.desklink.mobile`. Other hosts (`objects.githubusercontent.com`, `f-droid.org`, `flathub.org`, the update host) are
  NOT manifest URLs; the app builds those links locally.

## cases.json

`defaults` (merged under every run): `installed {desktop, android, androidCode}` (0.3.0 / 0.3.0 / 20300), `channel`
(`beta`), `now` (1795003600), `lastSeenIssuedAt` (1795000000), `installId` (32 hex = 16 bytes), `manual`, `prefs`
(`skipped`, `snoozedVersion`, `snoozedUntil`), `keys` (the client's key set: kids of `keys.json`).

A run's `expect` is either a verdict string for the whole manifest (`Ignore:<reason>`) or `{desktop, android}`:

| Verdict | Meaning |
|---|---|
| `UpToDate` | nothing to offer (absent/dropped component or channel, not newer, outside the rollout, paused) |
| `Offer:<v>` | `Available`: show the popup for `<v>` (Android compares `versionCode`, `<v>` is only the name) |
| `Required:<v>` | installed < `minSupported` |
| `Skipped:<v>` / `Snoozed:<v>` | would be `Offer` but for the stored preference |
| `Ignore:<reason>` | whole manifest rejected; reason in `oversized parse signature schema expired replay` |

Per-run checks on the desktop component: `expectNotes` (the exact notes after the rules above; `detail` null when absent),
`expectRequiredReason` (string or null), `expectReleased` (string or null), `expectUrls` (`"<component>.<field>": "kept" |
"dropped"`, field `notesUrl releaseUrl tarball apk`, or `playId` = `"ignored"`).
Other tables: `ratchetVectors` (`stored = min(issuedAt, now)`), `bucketVectors`
(`bucket = u32_be(sha256(installId_bytes || "|" || component || "|" || version)[0..4]) % 100`), `urlChecks` (`[url, allowed]`).

`oversized.json` is a real 95 KB file (valid signature, body > 65536). `body-exactly-65536.json` / `body-65537.json` pad a valid
envelope with trailing whitespace to exactly those sizes. The deep-nesting bodies are 32000 (envelope) and 20000 (payload)
levels deep, the largest that fit under 64 KiB.
