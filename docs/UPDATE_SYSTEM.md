# Play Dot — Android Update System & Provider Banner Audit

**Date:** 2026-10-22
**Scope:** signed release APK, backend update endpoint, emulator update flow, provider-served playback notice
**Release:** `com.streambox.mobile`, versionCode `3`, versionName `1.1.0`
**Verification device:** Pixel 7 Android emulator (no physical phone connected)

---

## 1. Provider banner: root cause and status

### Finding

The “This version will be discontinued soon” / “VERSION LEGACY” /
“SUPPORT ENDING” notice in the player is **video content**, not Play Dot UI,
signing state, a WebView, or an app string.

Evidence gathered from the signed release build:

- The string is absent from the APK resources/dex and from the backend source.
- No WebView exists in the app.
- The player rendered a 960x538 H.264/AAC stream on the emulator; the notice is
  visible inside the video frame, with MovieBox branding.
- `ffprobe` measured the stream served for the tested episode as
  **22.708333 seconds** (video) / **22.593031 seconds** (audio). The Media3
  player controls showed `0:03 / 0:22`, confirming that the *entire loaded
  stream* is the short notice, not a chapter inside the real episode.
- The provider's `play-info` response advertises normal episode metadata while
  returning this alternate/dummy media URL. The repository's
  `OFFICIAL_API_DOCUMENTATION.md` documents this as an anti-piracy/legacy-client
  trap.

### Conclusion

This is a **third-party provider control** for an unsupported/legacy client. It
cannot be legitimately removed by changing Play Dot's UI, signing key, version
number, or update metadata. An in-app update changes Play Dot; it does not
change the media the provider chooses to return to this client.

### Supported migration path

Use one of:

1. The **current official MovieBox app** from its official distribution channel,
   with an authorized account/subscription; or
2. A **licensed/authorized content source/API** for Play Dot.

If the provider is under your control, update the client identity/support
policy through the provider's official channels. Do not spoof a newer client
identity, decode hidden CDN prefixes, or bypass the provider's playback
controls.

### What this repository intentionally does **not** do

- No anti-piracy bypass is implemented or deployed.
- The pre-existing `signCookie`/`urlprefix` stream-override workaround was
  removed from the local backend so it cannot be included in a future deploy.
- The app does not claim the provider notice is fixed.
- The user's physical phone cannot be diagnosed without its APK/signing
  identity. If it runs the same unofficial build, updating Play Dot will not
  remove provider content. Update the official app instead.

The only honest in-app alternatives would be to detect the known dummy media and
show a clear “this title is unavailable from the provider” message, or to use a
licensed source. Neither restores content that the provider has not authorized.

---

## 2. Update system design

### Goals

- Asynchronous, non-blocking check after the first frame.
- Optional updates can be postponed and remembered; mandatory updates cannot be
  dismissed.
- Explicit user consent before download and before install.
- Android system installer only; no silent install, no Play In-App Updates, no
  signature-verification weakening.
- APK integrity, package identity, version, and signer continuity verified
  before the installer is opened.
- Same application ID, versionCode bump, and the existing release signing key.

### Flow

1. `MainActivity` creates `UpdateViewModel` and starts `checkForUpdates()` from a
   `LaunchedEffect` after the first composition.
2. The ViewModel waits 1.5 seconds, then requests `GET {BASE_URL}/app-update`
   with a 30-second call timeout.
3. The response is parsed and validated:
   - positive `latestVersionCode`;
   - non-blank, bounded `latestVersionName`;
   - `0 <= minimumSupportedVersionCode <= latestVersionCode`;
   - the package name equals the installed application ID;
   - an allowed URL (HTTPS in release; debug cleartext only);
   - optional SHA-256 is 64 hex characters;
   - optional size is at most 250 MiB.
4. If `installedVersionCode >= latestVersionCode`, nothing is shown.
   Otherwise severity is `MANDATORY` when the installed build is below
   `minimumSupportedVersionCode`, else `OPTIONAL`.
5. An optional version is suppressed if its code is at or below the locally
   stored skipped version (`DataStore`, `skipped_optional_version_code`).
6. The Compose dialog shows version, current version, release notes, download
   size, **Update now**, and (optional only) **Later**.
7. On **Update now**, the APK is streamed to `cacheDir/updates/app-update.apk`
   with a hard 250 MiB cap. Progress and errors are shown in the dialog.
8. After download:
   - exact byte count must match `sizeBytes` when provided;
   - SHA-256 must match when provided;
   - a parsed package must have the same application ID;
   - its versionCode must be at least the advertised `latestVersionCode`;
   - its signer certificate must be byte-identical to the installed app's
     current signer.
   Any failure deletes the downloaded file and shows **Retry**; the installer is
   never opened.
9. **Install update** opens the Android unknown-app-sources settings if needed,
   then the system package installer through a cache-only `FileProvider`.
   Android remains responsible for its own signature check and user
   confirmation.

### Failure behavior

| Condition | Behavior |
|---|---|
| Old backend / endpoint missing (`404`) | Silently treat as no update |
| Endpoint intentionally disabled / unconfigured (`503`) | Silently treat as no update |
| Offline, DNS, timeout, malformed JSON | Silent no-op; retry on next launch |
| Optional update dismissed | Persist version; hide on later launches until a newer version appears |
| Mandatory update | No Later button; back/tap-outside cannot dismiss |
| Download/checksum/size failure | Delete file; show error + Retry |
| Wrong package/version/signature | Delete file; show verification error; no installer |
| Unknown-app-sources denied | Remain in dialog; user can retry |
| Installer activity missing | Show non-fatal error |

Nothing in this path blocks the first frame or changes existing playback,
history, watchlist, or navigation code.

---

## 3. `GET /app-update` contract

The endpoint is session-independent and unauthenticated. It returns
`Cache-Control: no-store`.

Example:

```json
{
  "latestVersionCode": 3,
  "latestVersionName": "1.1.0",
  "minimumSupportedVersionCode": 1,
  "releaseNotes": "Playback stability fixes and the new in-app updater.",
  "apkUrl": "https://releases.example.com/play-dot-1.1.0.apk",
  "packageName": "com.streambox.mobile",
  "sha256": "166a92061a99af55345cb24b0b488be251298d880467a03bdc733539a619cbc8",
  "sizeBytes": 15964391
}
```

| Field | Required | Notes |
|---|---|---|
| `latestVersionCode` | Yes | Positive integer; compared numerically with the installed code |
| `latestVersionName` | Yes | Display only; bounded by the client |
| `minimumSupportedVersionCode` | Yes | Installed codes below this force a mandatory update |
| `releaseNotes` | Yes | Plain text; displayed in the dialog |
| `apkUrl` | Yes | Public HTTPS URL; debug builds also accept the local test origin |
| `packageName` | Yes | Must equal `com.streambox.mobile` |
| `sha256` | Recommended | Lowercase hex SHA-256; the client rejects a mismatch |
| `sizeBytes` | Recommended | Exact byte count; also bounds the download |

`sha256` and `sizeBytes` are **recommended**: the client still verifies the
package name, version, and signing certificate when they are absent. They should
be published for production so corruption/truncation is caught before the
installer.

API responses:

- `200` with metadata: update available or no update (client decides by code).
- `503`: metadata intentionally unconfigured/invalid. The release client
  silently ignores this.
- `404`: endpoint not deployed. The release client silently ignores this.

The old production backend currently returns `404` for this route; therefore the
release build's check is a no-op until the new backend is deployed.

### APK serving

Two supported hosting options:

1. **Backend-hosted artifact**
   - Set `APP_UPDATE_APK_PATH` to a readable local APK path.
   - The backend hashes/sizes it on request and serves it at
     `GET /app-release.apk`.
   - Set `APP_UPDATE_APK_URL` to the public HTTPS endpoint
     (`https://<host>/app-release.apk`), or omit it behind a trusted TLS proxy
     so the backend derives the request origin.
   - Render's filesystem is ephemeral; use a build step, persistent disk, or an
     external HTTPS artifact. Do not assume a local APK survives a redeploy.
2. **External HTTPS artifact (recommended for Render)**
   - Publish the signed APK to an object store, GitHub Releases asset, or other
     public HTTPS URL.
   - Set `APP_UPDATE_APK_URL` to that URL.
   - Set `APP_UPDATE_APK_SHA256` and `APP_UPDATE_APK_SIZE_BYTES` from the
     published artifact.
   - Do **not** use a URL that requires a browser login, cookie, or short-lived
     signed query; Android downloads it directly.

---

## 4. Configuration

| Variable | Example | Purpose |
|---|---|---|
| `APP_UPDATE_LATEST_VERSION_CODE` | `3` | Version code being advertised |
| `APP_UPDATE_LATEST_VERSION_NAME` | `1.1.0` | Display version |
| `APP_UPDATE_MINIMUM_VERSION_CODE` | `1` | Oldest code allowed without a mandatory prompt |
| `APP_UPDATE_RELEASE_NOTES` | `Playback fixes` | Dialog text; plain text |
| `APP_UPDATE_APK_URL` | `https://.../play-dot-1.1.0.apk` | Public APK URL; HTTPS required |
| `APP_UPDATE_APK_PATH` | `/srv/play-dot/app-release.apk` | Optional backend-local artifact |
| `APP_UPDATE_APK_SHA256` | 64 hex chars | Optional for local path; required for external hosting |
| `APP_UPDATE_APK_SIZE_BYTES` | `15964391` | Optional for local path; recommended for external hosting |
| `APP_UPDATE_PACKAGE_NAME` | `com.streambox.mobile` | Package guard |

If no artifact is configured, `/app-update` returns `503`; the release client
treats that as “no update” rather than showing an error.

---

## 5. Build and deployment

### Build

```bash
cd android-app
export JAVA_HOME=/path/to/jdk-17
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew :app:testDebugUnitTest
./gradlew :app:assembleRelease \
  -Pstreambox.baseUrl=https://play-dot.onrender.com/
```

The release task rejects a missing URL, non-HTTPS URLs, and emulator/loopback
hosts. The build uses the existing `keystore.properties` and
`playdot-release.keystore`; never commit either file.

### Publish

1. Verify the APK's signer and record its SHA-256.
2. Publish the **signed** APK at a stable public HTTPS URL.
3. Set the update environment variables on the backend.
4. Deploy the updated backend.
5. Verify:

```bash
curl -sS -i https://<host>/app-update
curl -sS -o /tmp/latest.apk https://<host>/app-release.apk
sha256sum /tmp/latest.apk
```

The returned `sha256`/`sizeBytes` must match the published file. Never publish
a debug APK or an unsigned release APK.

### Rollout check

Install an older signed build (same key), launch it, wait for the dialog, and
exercise optional and mandatory paths. A downgrade/repackaged APK with a
different signer must be rejected.

### Rollback

- If metadata is wrong, fix the backend configuration/deploy immediately.
- Android will not install a lower versionCode over a higher one. To undo a bad
  published version, publish a higher versionCode with the fix; do not ask
  users to uninstall/erase their data.
- If an APK is bad, remove/disable the URL so clients cannot download it, then
  publish a replacement with a higher versionCode.

---

## 6. Actual verification results

All Android results below are from the Pixel 7 emulator; production hosting is
not claimed.

| Test | Result |
|---|---|
| Backend contract + full suite | **43 tests pass** |
| Android JVM unit tests | **26 tests pass** (10 update-policy tests) |
| Debug build | Pass |
| Signed release build | Pass |
| Release version / package | `1.1.0` / versionCode `3`, `com.streambox.mobile` |
| Release signer | `CN=Play Dot, OU=Mobile, O=Play Dot, L=Dhaka, C=BD` |
| Certificate SHA-256 | `26464e001c365a0142c80882665ebdefadfa4b9cdbef2b837f4fdd9d2f5bcd3d` |
| Release APK SHA-256 | `166a92061a99af55345cb24b0b488be251298d880467a03bdc733539a619cbc8` |
| Same-key `v2 -> v3` upgrade | Pass; signature unchanged, `firstInstallTime` preserved |
| Optional dialog / release notes / size | Pass |
| **Later** persistence | Pass; relaunch logged “Optional update 1.1.0 already skipped” and hid the dialog |
| Mandatory dialog | Pass; no Later button |
| Mandatory back button | Pass; dialog remained |
| Download / SHA / package / signer verification | Pass |
| Android installer / unknown-sources flow | Pass |
| Debug `v2 -> v3` update | Pass; `firstInstallTime` preserved |
| Wrong-certificate APK | Rejected with verification error; file removed; installer not opened |
| Offline backend | No dialog, no crash; `ConnectException` logged, treated as unavailable |
| Release has no emulator backend literal | Pass; `10.0.2.2` absent from release dex |
| Release embeds production URL | Pass; `https://play-dot.onrender.com/` |
| Production `/app-update` | Not deployed (old backend returns `404`) |
| Public production APK artifact | Not hosted |
| Physical-phone update | Not verified; device not connected |

The local update test used a debug build and
`http://10.0.2.2:8000/`; the release APK uses
`https://play-dot.onrender.com/`. No production deployment or public release
upload was performed as part of this work.

---

## 7. Remaining blockers

1. **No production update deployment.** The live Render service predates
   `/app-update`; no Render credentials/API access were available.
2. **No public APK artifact.** The signed APK exists locally only. The user must
   choose a hosting location and authorize publication.
3. **No physical-phone verification.** The phone's installed APK and signing
   certificate are unknown. If its signer differs from the Play Dot release key,
   an in-place update is impossible and a clean install would be required (which
   erases app data).
4. **Provider banner is not fixable in this codebase.** It is provider media
   content for an unsupported client. The supported path is the official
   current app or a licensed source.
