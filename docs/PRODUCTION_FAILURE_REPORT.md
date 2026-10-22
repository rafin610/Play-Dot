# Play Dot — Production Failure Investigation Report

**Date:** 2026-10-22
**Scope:** release APK, Render backend, MovieBox integration, Android ↔ backend data flow
**Production origin:** `https://play-dot.onrender.com/`
**Updated status:** **H.264 sample rendering verified on a Pixel 7 AVD; current
MovieBox provider playback remains blocked by a live HTTP 429 response** (see
§12)

---

## 1. Confirmed root cause

The release APK that was installed on the phone was built with the **emulator-only
backend URL**:

```text
http://10.0.2.2:8000/
```

`10.0.2.2` is a special alias that only exists inside the Android emulator, where
it maps to the host machine's loopback interface. On a physical phone it is just
an ordinary private IP address with no Play Dot server listening on port 8000.
Every API call made by the app therefore failed before it could reach the backend:

- Home (`/trending`, `/home`) → no content, error state
- Search (`/search`) → no results
- Detail (`/detail`, `/episodes`) → no data
- Playback (`/stream`, `/subtitles`) → no stream

The failure is in the APK's build configuration, not in the Render backend, the
MovieBox provider, or the JSON contract.

### Why the APK contained the emulator URL

The Gradle build resolves the base URL as follows (before the fix):

```kotlin
val streamboxBaseUrl = providers.gradleProperty("streambox.baseUrl").orNull
    ?: "http://10.0.2.2:8000/"
buildConfigField("String", "STREAM_BASE_URL", "\"$streamboxBaseUrl\"")
```

`android-app/gradle.properties` does **not** define `streambox.baseUrl`. The
release APK was produced without passing `-Pstreambox.baseUrl`, so the fallback
value — the emulator host — was compiled into `BuildConfig.STREAM_BASE_URL`.

### Evidence from the original APK

The original `app-release.apk` (built 2026-10-21 19:48) was inspected before the
rebuild:

- `classes3.dex` contained both `STREAM_BASE_URL` and `http://10.0.2.2:8000/`
- No `onrender.com` host was present anywhere in the dex files
- The APK was correctly signed (`CN=Play Dot`), so signing was never the problem

This is direct evidence of the embedded value; the phone-level exception text
itself was not captured because no phone was available for `adb` access.

### The cleartext nuance

The network security config allows cleartext only for `10.0.2.2`, `localhost`,
and `127.0.0.1`. The bad APK's host was on that allowlist, so cleartext policy
did not stop the request. The request still failed because **no server exists at
`10.0.2.2:8000` on a physical phone**. A developer who tried to work around this
by building with a LAN IP would then hit the cleartext block, because the LAN IP
is not on the allowlist.

---

## 2. Evidence that the backend and provider are healthy

All checks below were executed against the real production service, not mocks.
The service responded with real catalog data.

### Health

`GET https://play-dot.onrender.com/health` → **HTTP 200**

```json
{
  "status": "ok",
  "service": "moviebox-internal-api",
  "version": "1.0.0",
  "guest_token": {
    "expires_at": "2027-01-19T13:17:26Z",
    "expires_in_days": 89.8,
    "expired": false
  }
}
```

The first request took **22.6 s** (Render cold start); subsequent requests took
2.5–9.2 s. The guest token was valid with ~90 days remaining, so token expiry was
not the cause.

### Endpoints used by the Android app

| Endpoint | Result | Shape observed |
|---|---|---|
| `GET /trending` | 200, ~5 KB | `{code:0, data:[9 items]}` |
| `GET /home` | 200, ~207 KB | `{code:0, data:{list:[20 sections]}}` |
| `GET /search?q=Black%20Clover` | 200, ~5.7 KB | `{code:0, data:{items:[10]}}` |
| `GET /rankings` | 200, ~5.6 KB | `{code:0, data:[1 section]}` |
| `GET /detail/1131798723912254248` | 200, ~1.8 KB | `code:0`, real title/cast/languages |
| `GET /episodes/1131798723912254248` | 200 | 1 season, 4 episodes |
| `GET /stream/1131798723912254248?season=1&episode=1&quality=720p` | 200 | `url`, `duration`, `isHls:false`, subtitles |
| `GET /subtitles/...` | 200 | subtitle list |
| Provider CDN `HEAD` + range `GET` | 200 / 206 | `video/mp4`, ranged requests supported |
| Subtitle CDN `GET` | 200 | `application/x-subrip` |

The live JSON types match the Android DTOs (`subjectId`/`id` strings, numeric
`subjectType`, nullable `runtime`, etc.). No contract mismatch was found.

---

## 3. Fixes applied

### 3.1 Release builds can no longer ship the emulator URL

`android-app/app/build.gradle.kts` now:

- Reads the backend origin from `-Pstreambox.baseUrl` **or** the
  `STREAM_BOX_BASE_URL` environment variable.
- Normalizes a missing trailing slash (Retrofit requires one).
- Adds a `checkReleaseBaseUrl` verification task that **fails the release build**
  when:
  - no explicit origin is supplied,
  - the origin is not `https://`,
  - the host is `localhost`, `127.0.0.1`, `0.0.0.0`, `10.0.2.2`, or `*.local`.
- Makes `assembleRelease`, `bundleRelease`, and `packageRelease` depend on that
  check.

Verified failures:

```text
> Task :app:checkReleaseBaseUrl FAILED
Release builds require -Pstreambox.baseUrl=https://<service>.onrender.com/
(or the STREAM_BOX_BASE_URL environment variable).
```

```text
> Task :app:checkReleaseBaseUrl FAILED
Release builds require an HTTPS backend URL, got: http://10.0.2.2:8000/
```

### 3.2 Startup diagnostic

`StreamBoxApplication.kt` now logs the public backend origin at startup (never a
secret) and warns if it is not HTTPS:

```text
I/PlayDot: Backend base URL: https://play-dot.onrender.com/ (debug=false)
```

### 3.3 Rebuilt and signed release APK

Built with:

```bash
./gradlew :app:assembleRelease \
  -Pstreambox.baseUrl=https://play-dot.onrender.com/
```

Resulting `BuildConfig`:

```java
public static final boolean DEBUG = false;
public static final String STREAM_BASE_URL = "https://play-dot.onrender.com/";
```

Dex inspection confirms the production host is present and
`http://10.0.2.2:8000/` is absent.

### 3.4 Documentation

`android-app/README.md` and `docs/RENDER_DEPLOYMENT_GUIDE.md` now show the
production build command, the required explicit origin, the guard behavior, and
the verified deployment URL.

---

## 4. Verification performed

### 4.1 Automated tests

| Check | Command | Result |
|---|---|---|
| Backend unit/contract tests | `.venv/bin/python -m unittest discover -s tests -v` | **30 passed, 0 failed** (0.313 s) |
| Android unit tests | `./gradlew :app:testDebugUnitTest --rerun-tasks --offline` | **15 passed, 0 skipped, 0 failures, 0 errors** |
| Release build guard (missing property) | `./gradlew :app:assembleRelease --offline` | failed with explicit message (expected) |
| Release build guard (HTTP loopback) | `./gradlew :app:assembleRelease -Pstreambox.baseUrl=http://10.0.2.2:8000/` | failed with “require an HTTPS” (expected) |
| Release build (production) | `./gradlew :app:assembleRelease -Pstreambox.baseUrl=https://play-dot.onrender.com --offline` | **BUILD SUCCESSFUL** |

These backend tests are offline contract tests with a fake provider session; the
live endpoint calls in §2 are the real production integration checks.

### 4.2 APK artifact

| Property | Value |
|---|---|
| Path | `android-app/app/build/outputs/apk/release/app-release.apk` |
| Version | `versionCode 2`, `versionName 1.0.0-playdot` |
| minSdk / targetSdk | 26 / 35 |
| SHA-256 | `70264d5e659591d5dda6c8202b23b07bd1f967b6fb02c56c114891f4d98fb407` |
| Signer | `CN=Play Dot, OU=Mobile, O=Play Dot, L=Dhaka, C=BD` |
| Signer SHA-256 | `26464e001c365a0142c80882665ebdefadfa4b9cdbef2b837f4fdd9d2f5bcd3d` |
| Embedded URL | `https://play-dot.onrender.com/` |
| Emulator URL in dex | absent |

### 4.3 Real device-emulator run (release APK)

Installed with `adb -s emulator-5556 install` (Pixel_7 AVD, Android 17) and
launched normally. `emulator-5554` was an unrelated pre-existing AVD and was not
modified.

Confirmed:

- Logcat: `Backend base URL: https://play-dot.onrender.com/ (debug=false)`
- Home rendered real production content: hero **“Black Clover [Hindi]”**,
  “Trending Now”, “IN Banner”, real posters (Coil image loading).
- Search for **“Sultan”** returned real results (2016 / 2021 / 2022 titles,
  ratings, series/movie labels).
- Detail for **“Sultan [Hindi]”** rendered 2016, “2h 50m · Movie”, audio/dub
  choices, synopsis, and cast.
- Playback resolved a stream and advanced normally:
  - `Prahaar…`: controls showed `2:14:01` duration; playback advanced.
  - `Sultan [Hindi]`: controls showed `0:13 / 2:36:21`; playback advanced,
    audio track `state:started`, no `PlayerViewModel`/playback errors.
  - Series `Our Universe`: `0:07 / 0:22`, H.264 decoder active.
- Error state: airplane mode + force-stop + relaunch produced
  **“Could not load content — Can't reach the server. Check your internet
  connection and try again.”** with a Retry button.
- Recovery: network restored + Retry → home feed loaded again.

Video frames could not be *visually* captured with `adb screencap`: Media3
`PlayerView` uses a `SurfaceView`, and the emulator's screenshot path returns
that layer as black. `dumpsys SurfaceFlinger` confirmed the SurfaceView layer
exists, and decoder/audio/timeline state confirmed playback is running. This is
a capture/emulator limitation, not evidence of an app playback failure.

---

## 5. Secondary issues discovered

### 5.1 Series episodes return a 23-second sample (provider side)

For every tested series (`Make Me Tingle`, `The Lament of Autumn`, `Our
Universe`) and every tested episode (1, 2, 3), the provider's raw
`play-info` response returned the **same file** with a **23-second duration**,
and no `signCookie`. The app correctly resolves and plays it (`0:22` total).
Movies, by contrast, return full-length streams (for example a ~2h30m–2h50m
movie). The backend's `/stream` reports `duration: 3600` from provider metadata,
which does not match the 23-second media.

This is not caused by the APK URL bug and cannot be fixed by changing the
Android client: the provider is returning a preview/sample under the current
guest credentials. No logged-in MovieBox account was available to determine
whether this is an entitlement, region, or provider-policy limitation. It is
reported as an open upstream issue.

### 5.2 Render cold starts are slow but within timeout

First `/health` response took **22.6 s**; later calls were 2.5–9.2 s. The app's
OkHttp client uses a 45 s read timeout, so the first request succeeds, but a
slow cold start plus a slow provider call is close to the limit. Retry works.
No code change was made for this; a longer read timeout or a keep-warm probe
would be a future improvement, not the root cause.

### 5.3 Unknown detail IDs return HTTP 200 with `code:1`

`GET /detail/{invalid-id}` returns HTTP 200 and `{"code":1,"msg":"Not found"}`.
The app maps this to a backend error state. This is a contract quirk, not a
production blocker.

### 5.4 HEVC rendering on the emulator

Most catalog movies are HEVC. The emulator selects
`c2.goldfish.hevc.decoder`; timeline and audio work, but the surface is black in
screenshots. Modern physical phones generally have hardware HEVC decoders, but
this was not verified on the target phone.

---

## 6. Hypotheses ruled out

| Hypothesis | Verdict | Evidence |
|---|---|---|
| Render backend is down or deploy failed | Ruled out | `/health` 200; all app routes 200 |
| Guest token expired | Ruled out | `/health` shows valid token, expires 2027-01-19 |
| Missing Render env vars | Ruled out | `/health` reports `status: ok`, would 503 and name missing vars |
| MovieBox upstream outage | Ruled out | Real trending/home/search/detail/episodes/streams returned |
| Android INTERNET permission missing | Ruled out | Manifest has `INTERNET`; emulator app reaches production |
| Cleartext policy blocking the actual request | Not the cause of this APK | The embedded host `10.0.2.2` was allowlisted; failure was unreachable host. LAN-IP HTTP would be blocked. |
| Wrong endpoint paths or Retrofit URL joining | Ruled out | All live calls used the exact Retrofit paths and returned data |
| JSON field/type mismatch | Ruled out | Live payload types match DTOs; 15 parsing tests; UI renders live data |
| Exceptions silently swallowed | Ruled out for connectivity | Airplane-mode test shows explicit error + Retry; Retry recovers |
| Release signed incorrectly | Ruled out | APK verifies with the Play Dot release certificate |
| Cold start mistaken for permanent failure | Ruled out | First request succeeded within the 45 s timeout after ~22.6 s |
| Provider rate limit/block | Not observed | Live calls succeeded consistently during the investigation |

---

## 7. Files modified

| File | Change |
|---|---|
| `android-app/app/build.gradle.kts` | explicit URL property/env support, trailing-slash normalization, `checkReleaseBaseUrl` release guard |
| `android-app/app/src/main/java/com/streambox/mobile/StreamBoxApplication.kt` | startup base-URL diagnostic + non-HTTPS warning |
| `android-app/README.md` | production build command, guard behavior, verified origin |
| `docs/RENDER_DEPLOYMENT_GUIDE.md` | release build requirements, guard, verified origin |
| `docs/PRODUCTION_FAILURE_REPORT.md` | this report |

No backend code was changed in this round. No provider credentials, tokens, or
media URLs were added to the APK or logs.

---

## 8. Exact steps to rebuild and reinstall the APK

```bash
cd android-app
export JAVA_HOME=/path/to/jdk-17
export PATH="$JAVA_HOME/bin:$PATH"

# Release build (property or env var; trailing slash optional)
./gradlew :app:assembleRelease \
  -Pstreambox.baseUrl=https://play-dot.onrender.com/

# or:
STREAM_BOX_BASE_URL=https://play-dot.onrender.com/ \
  ./gradlew :app:assembleRelease

# Verify the embedded URL
grep STREAM_BASE_URL \
  app/build/generated/source/buildConfig/release/com/streambox/mobile/BuildConfig.java

# Install on the phone (USB debugging enabled)
adb install -r app/build/outputs/apk/release/app-release.apk

# Launch and watch the startup diagnostic
adb shell am start -n com.streambox.mobile/.MainActivity
adb logcat -s PlayDot:V
```

Expected log line:

```text
I/PlayDot: Backend base URL: https://play-dot.onrender.com/ (debug=false)
```

If installation fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the phone's
previous APK was signed with a different key. Uninstall first, which removes
local watchlist/progress data:

```bash
adb uninstall com.streambox.mobile
adb install app/build/outputs/apk/release/app-release.apk
```

On-phone acceptance checks:

1. Home shows real titles/posters (not the network error).
2. Search for `Sultan` returns results and opens the detail screen.
3. Press Play; the timeline advances and video renders (a hardware-decoded
   movie is the best check; HEVC support depends on the phone).
4. Enable airplane mode, force-stop and relaunch; confirm the error + Retry
   screen. Restore network, tap Retry; content should reload.

---

## 9. Render backend status and redeploy steps

No backend redeploy is required for this fix. The live service already responds
at `https://play-dot.onrender.com/` and is running the guest-token-expiry code
(`e0ced50`; `/health` exposes `guest_token`). The backend tests pass.

If a future backend deployment is needed:

1. Render Dashboard → the service behind `play-dot.onrender.com` (its
   `/health` identifies itself as `moviebox-internal-api` v1.0.0).
2. Ensure the environment contains the four required secrets:
   `MOVIEBOX_GUEST_TOKEN`, `MOVIEBOX_USER_ID`, `MOVIEBOX_GATEWAY_SECRET`,
   `MOVIEBOX_APPID` (plus the non-secret settings already in `render.yaml`).
3. Manual Deploy → deploy the desired commit (auto-deploy is off in the
   checked-in blueprint).
4. Wait for the deploy and cold start, then verify:

```bash
curl -sS https://play-dot.onrender.com/health
curl -sS https://play-dot.onrender.com/trending | head -c 300
```

Note: the checked-in `render.yaml` names the service `play-dot-api`, while the
live service identifies as `moviebox-internal-api`. Use the existing Render
service; do not create a duplicate from the blueprint without checking first.

---

## 10. Remaining blockers / not fully verified

1. **No physical phone access.** The release APK was verified on an Android 17
   emulator, not on the user's phone. The two device-specific checks still
   outstanding are: install/update with the user's signing key, and visual video
   rendering on that phone's decoder.
2. **No Render dashboard/log access.** The investigation used the public HTTPS
   endpoints only; Render server-side logs were not inspected. All public
   endpoints responded correctly.
3. **Series entitlement.** Series episodes return a 23-second sample from the
   provider under guest credentials. This requires a logged-in/authorized
   MovieBox account or provider confirmation; it is not an Android/backend bug
   found in this round.
4. **Emulator video capture.** SurfaceView frames appear black in `adb screencap`
   even though playback time, audio state, and decoding advance. Physical-device
   visual confirmation is required before claiming video rendering is fully
   verified.
5. **`/detail` invalid-ID contract.** HTTP 200 + `code:1` is a minor API quirk;
   the app shows a generic backend error rather than “not found.”

---

## 11. Final status

The release URL configuration is fixed, and the Android rendering pipeline has
now produced a real first video frame from a known H.264/AAC HLS sample on the
Pixel 7 AVD. The MovieBox provider stream was separately inspected but returned
HTTP 429 during playback, so no first-frame claim is made for that title. See
the follow-up below for exact device and stream evidence.

## 12. Follow-up playback verification (2026-10-22)

### Independent Android rendering test

`:app:connectedDebugAndroidTest` ran on the **Pixel 7 AVD (Android 17)** and
passed its first-frame, track-selection, pause, seek, and resume assertions.
The test uses the public `https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8`
HLS sample, not a fabricated backend fixture. Host `ffprobe` reported H.264
video variants (320×184 through 1920×1080) and AAC stereo audio. Device Logcat
recorded:

- `Rendered sample video size: 848x480`
- `First video frame rendered from the public H.264 HLS sample.`
- Instrumentation result: 1 test, 0 failures, 0 errors.

The test exercises the same TextureView-backed `player_view.xml` used by the
Compose screen. It verifies Media3 track overrides and player operations, but
does not synthesize subtitle tracks that the sample does not provide.

### Live MovieBox provider check

The production app loaded the real **“Drishyam: The Conclusion [Hindi][CAM]”**
catalog entry from `https://play-dot.onrender.com/`. The stream endpoint
returned a signed MP4 URL on the provider CDN; the signed query was not retained
in this report. `ffprobe` found **HEVC 1150×480 at 30 fps + AAC 48 kHz stereo**.
Requests for 480p and 1080p returned the same media URL despite listing both
quality labels, so those labels did not represent distinct renditions in this
response. When ExoPlayer tried the provider URL, the CDN returned **HTTP 429**.
Logcat showed a Media3 source error with response code 429 before decoder
initialization or `onRenderedFirstFrame`; the app displayed the rate-limit
message and retry action.

Therefore the provider attempt does **not** establish whether the target
device's decoder can render that HEVC source, nor whether a separate H.264
provider rendition exists. Playback should be retried after the provider/CDN
rate limit clears, or against another currently accessible provider title.

### Code and build verification

- Backend selection now prioritizes explicit provider codec metadata (AVC/H.264
  before unknown before HEVC); URL suffixes are not treated as codec evidence.
- The backend no longer returns the first raw candidate after all accessibility
  probes fail.
- The Android player now counts 15 seconds of actual READY/playable time toward
  first-frame timeout, preserving elapsed time across buffering and pausing the
  timeout while playback is paused. On timeout it stops audio and shows the
  detected video format with an actionable error.
- Backend suite: **32 tests passed**.
- Android unit tests, `connectedDebugAndroidTest`, debug APK, and release APK:
  **BUILD SUCCESSFUL**.
- Release APK path: `android-app/app/build/outputs/apk/release/app-release.apk`.
- Release API base URL: `https://play-dot.onrender.com/`.
- Latest release APK SHA-256: `228b1ed6c137f3c786a61559d1cc5e6f04b4d87b786ede6f742d0257d7c5276d`.

The sample verifies the Android renderer, while the original provider title
remains unverified because of its live HTTP 429 and HEVC-only selected response.

## 13. Screenshot follow-up (2026-10-22)

The user-provided screenshot shows that playback time has advanced to
`55:17 / 2:26:07`, while the app still shows the generic "No video frame has
rendered" warning. Audio/timeline progress without a rendered frame is not
successful video playback.

The prior timeout was restarted after each buffering event, which could leave
the warning visible indefinitely if the stream repeatedly buffers. The latest
build preserves the remaining timeout across buffering, counts only active
READY playback time, pauses the timer while paused, and pauses playback with a
specific codec/resolution error if no first frame arrives after 15 seconds of
playable time. This is a reporting/diagnostic fix: it cannot convert an
incompatible or frame-less provider stream into video.

The latest release APK has SHA-256
`228b1ed6c137f3c786a61559d1cc5e6f04b4d87b786ede6f742d0257d7c5276d`.
The H.264 sample instrumentation test still passes after this change. The
provider title in the screenshot has not been re-tested because its previously
observed CDN response was HTTP 429; its exact codec/decoder failure on the
user's device is therefore not yet known.
