# PLAY DOT — Android App

Native Kotlin + Jetpack Compose client for the `moviebox-internal-api` backend.
Display name: **PLAY DOT**. Package ID: `com.streambox.mobile` (kept stable for updates).
Current release: **1.1.0** (versionCode `3`).

## Screens

| Screen | Source |
|---|---|
| Splash | Supplied full Play Dot logo |
| Home | `GET /trending`, `GET /home?page=1` |
| Search | `GET /search?q=` (debounced) |
| Detail (movie/series) | `GET /detail/{id}`, `GET /episodes/{id}` |
| Watchlist | Local DataStore only |
| Player | `GET /stream/{id}` via Media3/ExoPlayer, local resume progress |

The player keeps one ViewModel-owned Media3 instance across Compose recompositions
and uses a TextureView-backed `PlayerView` for reliable composition with Compose
controls. Settings are built from supported Media3 tracks: adaptive video tracks
can be selected manually or returned to Auto, audio tracks use actual language
metadata, and CC lists embedded or backend-provided subtitles with an Off option.
Fixed-quality sources do not expose invented quality choices.

> **Provider notice:** Some titles play a 22.7-second “VERSION LEGACY /
> SUPPORT ENDING”/“This version will be discontinued soon” placeholder served
> by the third-party provider. It is inside the video stream, not Play Dot UI,
> signing, or update state. It cannot be removed by updating this app; use the
> official current provider app or a licensed source. No anti-piracy bypass is
> implemented. See [`../docs/UPDATE_SYSTEM.md`](../docs/UPDATE_SYSTEM.md).

## In-app updates

The app checks the backend’s `GET /app-update` after the first frame and never
blocks launch:

- Optional updates can be dismissed with **Later**; the skipped version is
  stored in DataStore and hidden on later launches. Mandatory updates (installed
  version below `minimumSupportedVersionCode`) have no dismiss path.
- The downloaded APK is limited to 250 MiB, must match `sizeBytes`/`sha256` when
  published, must have package `com.streambox.mobile` and an acceptable
  versionCode, and must be signed by the **installed app’s current certificate**.
- Verification failure deletes the APK and shows Retry; the installer is never
  opened.
- Installation always uses the Android system installer (with the
  `REQUEST_INSTALL_PACKAGES` user grant and a cache-only FileProvider). There is
  no silent install, no Play In-App Updates, and no signature-check weakening.
- Release builds require HTTPS. Debug builds may use the emulator/loopback
  cleartext test origin; the debug network-security config independently
  restricts it.
- An old backend (`404`) or intentionally disabled endpoint (`503`) is treated
  as “no update” silently.

See [`../docs/UPDATE_SYSTEM.md`](../docs/UPDATE_SYSTEM.md) for the schema,
`APP_UPDATE_*` variables, APK hosting, deployment, and actual test results.

## Build

Requirements: JDK 17, Android SDK 35 (`local.properties`), Gradle wrapper 8.10.2.

```bash
cd android-app

# Debug APK, emulator backend (default http://10.0.2.2:8000/)
./gradlew :app:assembleDebug

# Debug APK using adb reverse for a physical device (no LAN IP in the app)
adb reverse tcp:8000 tcp:8000
./gradlew :app:assembleDebug -Pstreambox.baseUrl=http://127.0.0.1:8000/

# Production release: explicit public HTTPS origin (verified deployment shown)
./gradlew :app:assembleRelease -Pstreambox.baseUrl=https://play-dot.onrender.com/

# CI equivalent
STREAM_BOX_BASE_URL=https://play-dot.onrender.com/ ./gradlew :app:assembleRelease

# Verification
./gradlew :app:testDebugUnitTest :app:lintDebug

# Emulator playback verification (requires device internet access)
./gradlew :app:connectedDebugAndroidTest
```

The connected playback test uses Mux's public H.264/AAC HLS sample to verify a
Media3 first-frame callback on the TextureView-backed `PlayerView`, real video
quality/audio track groups, manual track overrides, pause, seek, and resume. This
isolates Android rendering from MovieBox provider availability; it does not
certify that a provider title has a compatible or currently accessible stream.

Outputs:

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

Debug builds default to the emulator host `http://10.0.2.2:8000/`, which only
exists inside an emulator. `assembleRelease` **requires** an explicit public
origin (`-Pstreambox.baseUrl` or `STREAM_BOX_BASE_URL`), rejects non-HTTPS URLs,
and rejects loopback/emulator hosts via the `checkReleaseBaseUrl` task. A missing
or mistyped property therefore fails the build instead of shipping an APK that
cannot reach the backend. Trailing slashes are normalized automatically.

## Signing

- **Debug:** AGP's standard debug keystore (`~/.android/debug.keystore`).
  Debug APKs are debuggable and are for development only.
- **Release:** signed from `keystore.properties` plus `playdot-release.keystore`
  in this directory. Both are git-ignored (mode 600). The keystore password is
  never placed in the APK.
- Back up `playdot-release.keystore` and `keystore.properties` somewhere safe.
  Losing the release key means future releases cannot update an installed
  release build; the app must be uninstalled first.
- If `keystore.properties` is absent, `assembleRelease` produces an unsigned
  APK. That is intentional: there is no implicit fallback to the debug key.

## Install / update

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
# or
adb install -r app/build/outputs/apk/release/app-release.apk
```

- Debug and release APKs have **different signing keys**. Switching between
  them requires `adb uninstall com.streambox.mobile` and therefore erases local
  watchlist/progress (`android:allowBackup="false"`).
- Updates with the same signing key and an equal/higher `versionCode` install
  with `-r` normally. If Android says "App not installed", check for a signature
  mismatch or version downgrade before assuming APK corruption.
- A sideloaded APK may be scanned by Play Protect. Use the signed release build
  for normal use; do not disable Play Protect or device security to force an
  install.
- In-app updates require the published APK to have the same application ID,
  the existing release signer, and a versionCode at or above the advertised
  `latestVersionCode`. A different signer is rejected before the installer is
  opened. The app uses the cached APK only after SHA/size (when configured),
  package, version, and certificate checks pass.
- The update endpoint must publish `sha256` and `sizeBytes` for reliable
  production delivery. The backend can compute both when it serves a local
  artifact; external hosting must set them explicitly.

## Networking / security

- Manifest permissions: `INTERNET` and `REQUEST_INSTALL_PACKAGES`.
  `ACCESS_NETWORK_STATE` is pulled in transitively by AndroidX Media3 and is a
  normal permission.
- A non-exported cache-only `FileProvider` shares only the verified update APK
  with the system package installer, for the duration of one
  `FLAG_GRANT_READ_URI_PERMISSION` intent.
- No app-authored services, background receivers, WebView, analytics, or
  dynamic code loading.
- `network_security_config.xml` permits cleartext HTTP only for the standard
  emulator host `10.0.2.2` and loopback (`localhost`/`127.0.0.1`). All other
  hosts require HTTPS. TLS verification is never disabled.
- For a physical-device local test, use `adb reverse tcp:8000 tcp:8000` and build
  with `http://127.0.0.1:8000/`; do not add a developer machine's LAN IP to the
  app.
- Provider credentials remain on the backend. No backend `.env` values are
  compiled into the APK.
- The backend's `session_id` cookie is kept in an in-memory OkHttp cookie jar.
  It is not written to disk. This avoids creating a new server/provider session
  for every screen, while the backend still bounds idle sessions by TTL and
  maximum count.
- For deployment and production URL injection, see
  [`../docs/RENDER_DEPLOYMENT_GUIDE.md`](../docs/RENDER_DEPLOYMENT_GUIDE.md).

## Brand assets

- Launcher: adaptive icon (v26 + v33 monochrome), round variants, and legacy
  density PNGs generated from the supplied symbol PNG.
- Splash: supplied full logo (`drawable-nodpi/play_dot_logo.png`) at its original
  aspect ratio.
- Palette: violet `#6954FB`, blue `#0D7EFA`, mid `#4363F9`, obsidian background
  `#06070B` — sampled from the supplied artwork.

## Architecture

```
app/src/main/java/com/streambox/mobile/
├── data/
│   ├── remote/                 # Retrofit service + DTOs
│   ├── repo/ContentRepository  # Result wrappers + error taxonomy
│   ├── local/                  # DataStore watchlist/progress, resume policy
│   └── update/                 # update policy/repository/DataStore/download/verifier/installer
├── ui/
│   ├── theme/                  # dark-only brand theme, type, shapes
│   ├── components/             # cards, rails, skeletons, error/empty states
│   ├── screens/{home,search,detail,watchlist,splash}/
│   ├── player/                 # PlayerScreen + PlayerViewModel (Media3)
│   ├── update/                 # UpdateViewModel + Compose dialog
│   └── navigation/StreamBoxNavHost.kt
└── MainActivity.kt, StreamBoxApplication.kt
```
