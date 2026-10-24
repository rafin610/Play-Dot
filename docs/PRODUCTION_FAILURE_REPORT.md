# Play Dot — Production Failure Investigation Report

**Date:** 2026-10-22
**Scope:** signed release APK, Render backend, MovieBox integration, Android playback
**Production origin:** `https://play-dot.onrender.com/`
**Verification device:** Pixel 7 Android emulator (no physical phone available)
**Current status:** native production playback now renders a video frame in the
signed release APK on the emulator. Real-device verification is still
outstanding. A separate provider-side “VERSION LEGACY” placeholder video can
replace the requested episode; it is not an app rendering failure. See
[`UPDATE_SYSTEM.md`](UPDATE_SYSTEM.md) for the update system and provider
banner audit.

---

## 1. Summary

Two independent release defects were found.

1. An earlier release APK was built against the emulator-only backend
   `http://10.0.2.2:8000/`. That release could not reach the production API from
   a real phone. The build now requires an explicit public HTTPS origin and the
   current APK embeds `https://play-dot.onrender.com/`.
2. Black video on the emulator was **not** an HEVC/decoder failure. The
   `PlayerView` was never given the ExoPlayer instance after the player was
   created. `AndroidView`'s `update` block did not rerun because its captured
   inputs appeared unchanged, so `PlayerView.player` stayed `null`. ExoPlayer
   initialized its video/audio decoders and advanced the timeline, but no video
   surface was attached to the player.

The fix binds each player identity to a fresh `PlayerView` inside
`key(currentPlayer)`. Native HEVC and H.264 playback both render first frames
after this fix, and the signed production release renders the previously failing
HEVC title.

A later provider response can serve a 22.7-second “VERSION LEGACY” placeholder
instead of the requested episode. That is provider media content for an
unsupported/legacy client, not an Android rendering, signing, or UI defect.
Updating Play Dot cannot legitimately remove it; the supported path is the
official current provider app or a licensed source. No anti-piracy bypass is
implemented.

---

## 2. Exact failing media and failure layer

| Item | Value |
|---|---|
| Title used for the failing case | `Epic - First Semester` |
| Native video codec | HEVC Main, `hev1` / `hvc1.1.6.L90.90` |
| Native video | 850x480, yuv420p, 24 fps |
| Native audio | AAC HE-AAC 5.1, 48 kHz (`mp4a.40.2` in the selected track) |
| Container | MP4 |
| Emulator decoder | `c2.goldfish.hevc.decoder` |
| Failure layer | Android view/player binding: no `PlayerView` surface attached |
| Not the failure layer | Codec support, CDN, HTTP/auth, backend JSON, audio decoder, `SurfaceView` vs `TextureView` |

Evidence that the media and player were healthy even while the screen was black:

- ExoPlayer selected a supported HEVC video track.
- `Video decoder initialized: c2.goldfish.hevc.decoder` appeared.
- Video and audio input formats were logged.
- Playback position advanced and audio decoded.
- `PlayerView.player` was still null; the view had no player to attach to a
  surface. The `AndroidView` update callback had run only at first composition,
  when the player did not yet exist.

Both HEVC and H.264 titles were black before the binding fix. After the fix, both
codecs render. HEVC was therefore a red herring.

---

## 3. Android fix

### 3.1 Player/View binding

`PlayerScreen` now keys the Android view by the actual player identity and
captures that player in the view factory/update block:

```kotlin
key(currentPlayer) {
    AndroidView(
        factory = { context ->
            LayoutInflater.from(context)
                .inflate(R.layout.player_view, null, false) as PlayerView
        },
        update = { view ->
            view.player = currentPlayer
        },
        ...
    )
}
```

`key(currentPlayer)` forces a fresh `PlayerView` whenever the player is created
or replaced. The `update` block then binds that exact player. The view is
released and detached correctly when the screen leaves composition or the player
is replaced.

### 3.2 Compatibility fallback

The backend can expose a native stream plus an optional, relative
`compatUrl` for an on-demand H.264/AAC 480p rendition. The Android client:

- keeps the native stream when the device advertises a usable decoder;
- preempts to `compatUrl` only for HEVC when no HEVC decoder exists;
- starts a 15-second first-frame watchdog after `STATE_READY`; if no frame is
  rendered, it switches the **same ExoPlayer** to the compatibility media source
  (`reusePlayer = true`) rather than releasing/recreating the player;
- reports the actual failure layer (resolver, network, decoder, surface, or
  first-frame timeout) without logging signed URLs, cookies, or tokens.

The compatibility endpoint is bounded: one concurrent transcode by default,
`429` when busy, `503` when FFmpeg is absent, `400` for invalid request values,
`502` for upstream/transcoder failure, SSRF validation of the resolved source,
and process cleanup on disconnect/timeout.

---

## 4. Production backend status

Checked on 2026-10-22:

| Check | Result |
|---|---|
| `GET /health` | `200` |
| `GET /stream/...` | `200`, old response shape: no `compatUrl`, `mimeType`, or codec dimensions |
| `HEAD /play-compat/...` | `404` |

The deployed Render backend is **older than this working tree**. Its `/stream`
does not advertise the compatibility URL, and `/play-compat` is not deployed.
The current release therefore plays the native provider stream directly. On this
emulator the native HEVC title renders. A real device without a usable HEVC
decoder will not receive the compatibility fallback until the newer backend and
FFmpeg are deployed. No Render dashboard or deployment access was available.

---

## 5. Verification performed

### 5.1 Native HEVC, debug build

- Title: `Epic - First Semester`
- First video frame: `13:59:03.056`
- Decoder: `c2.goldfish.hevc.decoder`
- Video: 850x480
- Rendered screenshot: real movie frame visible; black-video symptom gone.

### 5.2 Native H.264, debug build

- Title: `King Azalaar 1`
- First video frame: `13:55:42.553`
- Codec: H.264 High `avc1.64001F`, 960x538
- Result: rendered frame, confirming the fix was not codec-specific.

### 5.3 Compatibility path, debug build

- Compatibility output: H.264 Constrained Baseline `avc1.42C028`, 850x480,
  AAC stereo 48 kHz.
- A preempted compatibility run rendered a first frame after the binding fix.
- The same-player fallback was exercised by temporarily forcing the app to
  switch from native HEVC to compatibility after two seconds. It called
  `setMediaSource(..., reusePlayer = true)` on the already-bound ExoPlayer; the
  H.264 decoder initialized and the first frame rendered at `15:51:38.617`
  (`c2.goldfish.h264.decoder`, 850x480). No `PlayerView` rebind was needed.
- Backend FFmpeg output was also probed directly and rendered by a Media3
  instrumentation test during development.
- The production watchdog was not triggered during normal playback because
  native HEVC now renders immediately; the forced test verifies the same code
  path without shipping a test flag.

### 5.4 Signed production release, emulator

- Backend log: `Backend base URL: https://play-dot.onrender.com/ (debug=false)`
- Title: `Epic - First Semester` (production API)
- First video frame: `15:42:35.613`
- Decoder: `c2.goldfish.hevc.decoder`
- Video: 850x480
- Screenshot: real rendered frame from the production signed APK.

### 5.5 Interaction matrix (debug build, same player code)

| Test | Result |
|---|---|
| Portrait → landscape | Passed; frame remained rendered and controls usable |
| Landscape → portrait | Passed |
| Pause / resume | Passed; frame held and resumed |
| Seek forward/back | Passed; new position rendered |
| Background → foreground | Passed; playback resumed and frame advanced |
| 12-second network interruption | Passed from buffered media; frame continued, then playback continued after network restoration |
| Lower-quality compatibility stream | Passed at 480p H.264/AAC |
| Public H.264 HLS | Media3 instrumentation test passed |
| DASH | Not tested; no DASH source was available in this app/backend |
| Full reconnect/error recovery | Not forced; the 12-second loss was absorbed by the buffer, so the explicit error/retry path was not exercised |

### 5.6 Automated tests

- Backend: `43` Python unittest tests pass, including `/stream` metadata,
  `/play-compat` HEAD/validation, private-source rejection, busy-slot behavior,
  FFmpeg command bounds, contract/SSRF checks, and `/app-update` contract tests.
- Android unit tests: `26` pass, including new DTO coverage and 10 update-policy
  tests.
- Android instrumentation HLS test passes.

---

## 6. APK artifact

| Field | Value |
|---|---|
| Path | `android-app/app/build/outputs/apk/release/app-release.apk` |
| Size | 16 MB |
| Version | versionCode `3`, versionName `1.1.0` |
| Embedded backend | `https://play-dot.onrender.com/` |
| Release network config | `cleartextTrafficPermitted="false"`; no local domain exceptions |
| SHA-256 | `166a92061a99af55345cb24b0b488be251298d880467a03bdc733539a619cbc8` |
| Signature | APK Signature Scheme v2 |
| Signer | `CN=Play Dot, OU=Mobile, O=Play Dot, L=Dhaka, C=BD` |
| Certificate SHA-256 | `26464e001c365a0142c80882665ebdefadfa4b9cdbef2b837f4fdd9d2f5bcd3d` |
| Key | RSA 4096-bit |

The release dex contains the production origin and no `10.0.2.2` backend
origin. It still contains generic `http://localhost/` and `127.0.0.1` constants
inside bundled third-party libraries (Retrofit/Guava helpers); these are not the
app's configured backend and are not reachable through the release network
policy.

---

## 7. Files changed in this fix

- `android-app/app/src/main/java/com/streambox/mobile/ui/player/PlayerScreen.kt`
  — key the `PlayerView` by player identity; explicit binding, layout, detach, and
  non-secret diagnostics.
- `android-app/app/src/main/java/com/streambox/mobile/ui/player/PlayerViewModel.kt`
  — source metadata, native/compat decisions, same-player fallback, first-frame
  watchdog, true failure-layer diagnostics.
- `android-app/app/src/main/java/com/streambox/mobile/data/remote/Dtos.kt`
  — additive stream metadata/compat DTO fields.
- `android-app/app/src/main/res/xml/network_security_config.xml`
  — release baseline disables cleartext; local allow-list lives only in debug.
- `android-app/app/src/debug/res/xml/network_security_config.xml`
  — debug-only local cleartext exceptions.
- `android-app/app/src/main/java/com/streambox/mobile/data/update/` and
  `ui/update/` — direct-APK updater.
- `android-app/app/src/main/AndroidManifest.xml`, `res/xml/file_paths.xml` —
  install permission and cache-only FileProvider.
- `moviebox_api_server.py`, `tests/test_api_contract.py` — `/app-update`,
  `/app-release.apk`, contract tests.
- `android-app/app/src/test/java/com/streambox/mobile/data/remote/DtoParsingTest.kt`
  — metadata and legacy-backend parsing coverage.
- `android-app/app/src/androidTest/java/com/streambox/mobile/ui/player/VideoRenderingInstrumentedTest.kt`
  — public HLS rendering test; temporary local compat test removed.
- `moviebox_api_server.py`
  — `/stream` metadata and `compatUrl`, `/play-compat` FFmpeg endpoint,
  SSRF/validation, concurrency, HEAD, disconnect cleanup, retry.
- `tests/test_api_contract.py`
  — contract, compatibility, validation, SSRF, and command tests.
- `README.md`
  — corrected `/stream` and `/play-compat` contract.
- `docs/PRODUCTION_FAILURE_REPORT.md`
  — this report.

No signed media URLs, cookies, tokens, keystore contents, or signing passwords
were printed or committed.

---

## 8. Rebuild and verify

```bash
cd android-app

# Signed release; refuses to run without an explicit public HTTPS origin.
./gradlew :app:assembleRelease \
  -Pstreambox.baseUrl=https://play-dot.onrender.com/

# Verify the embedded backend and signature.
grep STREAM_BASE_URL \
  app/build/generated/source/buildConfig/release/com/streambox/mobile/BuildConfig.java

$ANDROID_HOME/build-tools/<version>/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk

sha256sum app/build/outputs/apk/release/app-release.apk
```

Install the signed APK on the target device:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
adb logcat -s PlayDot:V PlayerViewModel:I PlayerScreen:I
```

The startup line must say:

```text
Backend base URL: https://play-dot.onrender.com/ (debug=false)
```

---

## 9. Remaining limitations

- **No physical-device first frame.** All rendering evidence is from the Pixel 7
  emulator. The release APK's production first frame was rendered there, not on
  a real phone.
- **Production compat fallback is not deployed.** `/play-compat` returns `404`
  and `/stream` lacks `compatUrl` on the live Render service. The current APK's
  fallback code cannot function in production until the backend is redeployed
  with FFmpeg. Direct native HEVC is the only verified production path.
- **No DASH matrix.** No DASH source was available, so DASH behavior is
  unverified.
- **No forced reconnect failure.** Buffered playback survived a 12-second
  network loss; the app's explicit retry/reconnect path was not exercised.
- **Quality switching, subtitles, HDR, DRM, and audio-route behavior** were not
  part of this verification.
- **Backend load/security caveat.** `/play-compat` starts an FFmpeg process per
  concurrent request and is unauthenticated. The semaphore bounds it, but a
  public deployment should add edge rate limiting and monitor CPU usage.

---

## 10. Final status

| Claim | Status |
|---|---|
| Root cause identified as Android player/view binding | Verified |
| Native HEVC renders on emulator after fix | Verified |
| Native H.264 renders on emulator after fix | Verified |
| Same-player fallback switches to H.264 and renders | Verified (forced debug path) |
| Signed release embeds production HTTPS origin | Verified |
| Signed release renders a production HEVC frame on emulator | Verified |
| Signed release renders on a physical phone | **Not verified** |
| Production compatibility fallback available | **No, endpoint 404** |
| Backend/unit/instrumentation tests pass | Verified |
