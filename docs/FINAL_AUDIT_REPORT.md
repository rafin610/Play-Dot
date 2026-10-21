# Final Audit Report

**Audit date:** 2026-10-21
**Repository:** `solo12345689/moviebox-internal-api`
**Baseline commit:** `cc3b937` (`main`, working tree modified by this audit)
**Deployment status:** **Not deployed.** No Render service, public hostname, or production APK was created.
**Scope:** backend API, Android client, security/failure behavior, Render readiness, tests, and local integration evidence.

This report distinguishes three levels of evidence:

1. **Mocked tests** — deterministic offline provider fakes; prove adapter contracts and error handling.
2. **Live local integration** — the real provider through the real backend on `127.0.0.1:8101`.
3. **Emulator verification** — the signed release APK installed on Android 16 against that local backend.

It does **not** claim Render, physical-device, Play Protect, or production-HTTPS verification.

---

## 1. Executive summary

Confirmed defects were fixed rather than hidden in the UI. The highest-impact
issues were:

- Provider credentials hardcoded in source and a stale guest token.
- `/home` and `/episodes` returning HTTP 200 with empty/incomplete data when the
  provider failed, so Android could not show a retry state.
- A 19-digit provider language ID modeled as a Kotlin `Int`; values were silently
  truncated during detail deserialization.
- `/subtitles/search` being shadowed by `/subtitles/{subject_id}`.
- Stream health probes disabling TLS verification and logging signed CDN URLs.
- SSRF exposure in `/sub-proxy` and command injection/shell fallback in
  `/launch-player`.
- Unbounded in-process sessions and non-atomic history writes.
- Android creating a new backend/provider session for every screen because no
  cookie jar was configured.
- Health checks that always returned 200 even with missing configuration.

Final evidence:

| Check | Result |
|---|---|
| Backend mocked tests | **20 passed, 0 failed** |
| Android unit tests | **15 passed, 0 failed** (5 resume, 8 DTO, 2 cookie) |
| Android lint | `BUILD SUCCESSFUL`; **0 errors**, 79 warnings, 2 informational |
| Signed release APK | Built, v2-signed, installed and launched |
| Live endpoints | Health/home/trending/search/detail/episodes/subtitles/stream all `200` |
| Live stream | `200`, duration 3600 s, one subtitle, ~1.49 s |
| Emulator | Home, search, detail, seasons, watchlist, stream request, H.264 decoder + audio |
| Production APK | **Blocked**: requires the real Render HTTPS origin |

The current APK was deliberately built with `http://10.0.2.2:8101/` for emulator
verification. It is **not** a production artifact. Rebuild with the actual
Render HTTPS URL after deployment.

---

## 2. Confirmed defects and fixes

| # | Severity | Confirmed root cause | Fix | Evidence |
|---|---|---|---|---|
| 1 | Critical | Provider guest token, user ID, gateway secret, and appid were hardcoded in source. | Load all four from environment; `.env` is local-only; `.env.example` has placeholders. Removed fallback constants. | Config unit tests; source scan; `.env` ignored. |
| 2 | High | `/health` always returned 200, so Render could route to an unconfigured service. | Readiness now validates required names and base64 secret syntax; returns `503` with `missing_env`; startup logs names only. `/` remains liveness. | 2 health tests; live 200. |
| 3 | High | `/home` caught provider errors and returned `200` with an empty list. | Provider/type failures now raise `502`; Android shows a retry path. | Mocked 502 test with upstream exception; live 200. |
| 4 | High | `/episodes` swallowed provider failures and could emit duplicate episode numbers. | `502` on provider/type failure; season numbers normalized; duplicate numbers removed; fallback bounded by `maxEp`. | Mocked failure/dedup tests; live 2 seasons, 52/1 episodes. |
| 5 | Critical | Android `LanguageDto.subjectId`/`id` were `Int?`, truncating 19-digit provider IDs. | Backend emits strings; Android DTO uses `String?`; 19-digit fixture added. `postCount` also normalized to string. | 8 DTO tests; live detail: 5 language IDs, all length 19, `postCount` `"1685"`. |
| 6 | High | `/trending` eagerly called three provider contracts, tripling load/rate-limit risk. | Sequential lazy attempts; stop at first non-empty catalogue. | Unit test asserts one call and no fallback call. |
| 7 | High | `/subtitles/{subject_id}` was declared before `/subtitles/search`, shadowing the static route. The search route also returned `200` empty on failure. | Static route moved before dynamic; provider/type failures return `502`; IDs normalized. | Route test asserts static dispatch; live static route 200 (0 upstream results at audit time). |
| 8 | High | Stream probes used `verify=False`; raw play-info containing signed URLs was logged; 404 conflated rate limits/auth. | TLS verification restored; probe redirects followed; logs use query-free labels/stream-count summaries; raw catalogue, search, episode, auth, and signature logs removed; 429 -> 503, 401/403 -> 502, genuine empty -> 404. | Stream unit test checks `verify` not false; live stream 200; final log scan showed no signed URLs. |
| 9 | Critical | `/sub-proxy` allowed arbitrary URLs (SSRF), no redirect/size bound, disabled TLS validation. | Validate public HTTP(S), reject credentials/private/loopback/link-local/metadata, resolve DNS, cap redirects/size, verify TLS, reject HTML. | Five private/invalid URL cases return 400; live loopback 400. |
| 10 | Critical | `/launch-player` constructed a shell command and had a duplicate dead route; cookie/URL input could inject. | Removed duplicate; direct argv execution; allowlist mpv/vlc; HTTP(S)-only URL/subtitle; strip CR/LF from cookie; no shell fallback; `ENABLE_EXTERNAL_PLAYER=false` returns 501. | Unit tests for disabled, `file://`, unknown player; local POST 503 (mpv not installed here). |
| 11 | High | Sessions were unbounded and history writes were not atomic. | Session TTL/max with lock; prune/evict; copy under lock; atomic `os.replace` history writes. | Session prune test; source review; live session reuse. |
| 12 | High | Android had no cookie jar; every screen created a new backend session and provider guest bootstrap. | Memory-only OkHttp `CookieJar` for `session_id`. | 2 cookie tests; post-fix emulator run created only initial sessions and reused one for search/detail/episodes/stream. |
| 13 | Medium | Android showed backend-empty/partial states without actionable retry, and error copy referenced a localhost backend. | Home section retry banner; episode-section retry; production-neutral network copy; removed localhost wording. | Compile/lint; UI live smoke for normal paths; error banners not force-triggered live. |
| 14 | Medium | Render defaults and local-only assumptions were undocumented/inconsistent. | Added `render.yaml` (free, main, health check, auto-deploy off, one worker), `.python-version` 3.12.3, pinned requirements, env template, deployment guide. | YAML parsed; Render not deployed. |

Additional hardening:

- `/detail`, `/episodes`, `/stream`, `/trending`, `/search`, and `/movies` no
  longer return upstream exception text to clients.
- Provider-derived cast, collection, see-time, language, subtitle, quality, and
  section fields are type-checked/normalized.
- Startup logs configuration status without printing values.
- External player route is a local convenience only; Render disables it.
- `OFFICIAL_API_DOCUMENTATION.md` static secret values were redacted. Git history
  still contains old values and must be treated as compromised.

---

## 3. Mocked test results

Command:

```bash
.venv/bin/python -m unittest discover -s tests -v
```

Result:

```text
Ran 20 tests
OK
```

The fake session supplies deterministic provider payloads. These tests do **not**
call MovieBox. They cover:

- Trending mapping, 19-digit IDs, `seeTime`, `isCollection`, lazy fallback.
- Home sections, cookie, upstream 502.
- Detail string IDs, string `postCount`, language/resource IDs, upstream 502.
- Episode normalization/deduplication and provider 502.
- Stream flat contract, subtitle normalization, TLS-verification flag, 429.
- Subtitle search route dispatch/normalization and upstream 502.
- Missing configuration -> 503; health 200/503.
- Session expiry pruning.
- Private/loopback/link-local/file URL rejection in subtitle proxy.
- External-player disabled, unsupported scheme, unknown binary.

Android unit tests:

```text
ResumePolicyTest        5 passed
DtoParsingTest          8 passed
CookieJarTest           2 passed
Total                  15 passed, 0 failed
```

`DtoParsingTest` includes a 19-digit language-ID fixture. `CookieJarTest` covers
host-scoped session-cookie storage and no leakage to a different host.

Android lint:

```text
BUILD SUCCESSFUL
0 errors, 79 warnings, 2 informational
```

Warnings are non-blocking dependency/resource/style findings; no lint error.

---

## 4. Live local integration results

Environment:

- Backend: real provider, `uvicorn` at `127.0.0.1:8101`.
- Session cookie: Python request session.
- No mock provider.
- Times below are single-run observations and vary with provider latency.

| Endpoint | HTTP | Observed result | Time |
|---|---:|---|---:|
| `/health` | 200 | `status=ok`, service/version present | 0.00 s |
| `/trending` | 200 | 8 items on final recheck (9 on an earlier run; catalogue varies); first `subjectId` JSON string | 2.36 s |
| `/home` | 200 | 20 sections | 0.69 s |
| `/search?q=Black%20Clover` | 200 | 10 results | 0.30 s |
| `/detail/2316544915590759384` | 200 | `subjectId` string; `postCount` `"1685"` string; 5 languages; all IDs length 19 | 0.78 s |
| `/episodes/2316544915590759384` | 200 | 2 seasons; episode counts 52 and 1 | 0.30 s |
| `/subtitles/...?...se=1&ep=1` | 200 | 1 track; `id` string | 0.14 s |
| `/stream/...?season=1&episode=1&quality=720p` | 200 | URL present; duration 3600; `isHls=false`; 1 subtitle; no top-level envelope `code` | 1.49 s |
| `/sub-proxy?u=http://127.0.0.1:8101/health` | 400 | private host rejected before fetch | 0.06 s |
| `POST /launch-player` (local config) | 503 | `mpv` absent on this machine; no launch | immediate |

`/subtitles/search?query=English` returned 200 with zero upstream results at
audit time; this proves dispatch, not subtitle availability.

Catalog IDs and availability are provider-dependent. The tested series was
identified from live `/trending`/`/search`; another title tested earlier had no
playable stream (`404`). That is expected for an incomplete catalogue, not proof
that every title works.

### Session reuse after CookieJar fix

After installing the final APK, the backend log showed:

- 2 new sessions for the initial concurrent `/trending` + `/home` startup
  (there is no cookie before the first responses).
- `/search`, `/detail`, `/episodes`, and `/stream` then reused the existing
  session: **0 additional session creations** during the final navigation and
  playback pass.

This reduces provider guest bootstraps and rate-limit exposure. The backend TTL
and max-session bounds remain necessary for restarts, evictions, and clients
without cookies.

---

## 5. Android release APK / emulator verification

| Item | Value |
|---|---|
| Package | `com.streambox.mobile` |
| Version | `1.0.0-playdot` (`versionCode 2`) |
| Build type | release, signed |
| Signing | APK Signature Scheme v2 verified; RSA 4096; cert DN `CN=Play Dot` |
| Emulator | `emulator-5554`, Android 16, `sdk_gphone64_x86_64` |
| Injected URL | `http://10.0.2.2:8101/` (local verification only) |
| APK path | `android-app/app/build/outputs/apk/release/app-release.apk` |
| Size | 15,930,823 bytes |
| SHA-256 | `54de8261a0fe9e07773783b2a598820faa4b83a3003da556b4a333c644ee3963` |

Do not use this SHA for a production upload: rebuild with the Render HTTPS URL,
which changes `BuildConfig.STREAM_BASE_URL` and therefore the APK bytes/SHA.

Verified on the emulator:

- Installation via `adb install -r` succeeded.
- Cold launch succeeded after a fresh install.
- Home loaded live `/trending` and `/home` content; "Trending Now" rows rendered.
- Search for `Black Clover` returned live results.
- Detail opened; audio/dub chips rendered without serialization failure; seasons
  S1/S2 and episodes appeared.
- Watchlist add changed the control to "Remove from watchlist"; after an app
  restart the Watchlist tab still showed the saved title (local DataStore).
- Episode 1 requested `/stream/...` and received 200.
- ExoPlayer initialized Media3 and an H.264 (`video/avc`) decoder; audio advanced
  with an `AudioTrack` delivering ~723,968 frames before stopping.
- No `FATAL EXCEPTION`/serialization crash appeared in the inspected logcat.
- A DEX string scan found no provider JWT, gateway secret, appid, or `MOVIEBOX_*`
  environment names in the APK; the only injected backend URL was the local test
  URL.

Limits of this verification:

- Screenshots of the video surface were black. Android SurfaceView/hardware video
  frames often do not appear in `screencap`; this is **not** counted as visual
  playback proof. Decoder initialization, stream 200, and audio progress are
  separate evidence.
- Full-length playback, seeking, subtitle track switching, audio-track switching,
  and auto-next were not exhaustively exercised.
- No physical device, Play Protect scan, or production HTTPS build was tested.

---

## 6. Production readiness / remaining risks

| Risk | Status |
|---|---|
| Render service and HTTPS URL | **Blocked pending owner approval.** No URL exists in this report. |
| Fresh provider credentials | Required. Local `.env` predates the audit; its guest JWT had an expiry of 2026-10-11. Rotate all provider values. |
| Old secrets in Git history | Still present in history. Redaction of current files does not remove them; rotate. |
| Provider reliability | Rate limits, expired guest sessions, catalogue changes, regional 404s, and incomplete seasons remain upstream risks. `/health` cannot prove content availability. |
| Free Render plan | Sleep/cold starts can exceed Android timeouts and show retry UI. |
| In-memory sessions | One worker only; restart loses sessions. Android's cookie jar is process-local. |
| Ephemeral disk | `local_history.json` is not durable on Render. Android watchlist/progress are local DataStore and unaffected. |
| FFmpeg absent | `/download` for HLS/DASH returns 503 on Render; MP4 may still work. |
| External player | Disabled on Render via `ENABLE_EXTERNAL_PLAYER=false`; local-only feature. |
| `/sub-proxy` DNS rebinding | Public-host validation resolves before HTTPX connects; a hostile DNS server could theoretically rebind between checks. No user-controlled allowlist is present. |
| Dashboard | Not the Android deployment target; still retains localhost assumptions and was not production-verified. Do not publish it as part of this release. |
| Visual playback | Black emulator frames prevent full visual proof; physical-device check remains. |
| Security testing | No comprehensive penetration test, malware scan, or load test was performed. |

### Recommended release sequence

1. Rotate all four provider credentials; install them only in Render.
2. Import `render.yaml` only after deployment approval; provide the four secrets.
3. Confirm `/health` 200 on the real hostname, then smoke `/trending`, `/home`,
   `/search`, `/detail`, `/episodes`, `/subtitles`, and `/stream`.
4. Rebuild the release APK with
   `-Pstreambox.baseUrl=https://<actual-host>/`, verify the signer, and record the
   new SHA-256.
5. Install on a physical device; verify video frames, audio, subtitle selection,
   seek/resume, watchlist persistence, and a cold backend start.
6. Keep automatic deploys off unless the owner explicitly changes that policy.

---

## 7. Files changed for this audit

Backend/security/configuration:

- `moviebox_api_server.py`
- `moviebox_api/auth.py`
- `moviebox_api/client.py`
- `moviebox_api/utils.py`
- `requirements.txt`
- `.gitignore`
- `.env.example`
- `.python-version`
- `render.yaml`
- `OFFICIAL_API_DOCUMENTATION.md` (redactions)
- `README.md` (deployment pointer, runtime note)
- `docs/api-integration.md` (session-cookie/runtime contract)
- `docs/RENDER_DEPLOYMENT_GUIDE.md`
- `docs/FINAL_AUDIT_REPORT.md`
- `tests/test_api_contract.py`

Android:

- `android-app/app/src/main/java/com/streambox/mobile/data/remote/Dtos.kt`
- `android-app/app/src/main/java/com/streambox/mobile/data/remote/MovieBoxApi.kt`
- `android-app/app/src/main/java/com/streambox/mobile/data/ApiConfig.kt`
- `android-app/app/src/main/java/com/streambox/mobile/data/repo/ContentRepository.kt`
- `android-app/app/src/main/java/com/streambox/mobile/ui/screens/home/HomeScreen.kt`
- `android-app/app/src/main/java/com/streambox/mobile/ui/screens/detail/DetailScreen.kt`
- `android-app/app/src/main/java/com/streambox/mobile/ui/screens/detail/DetailViewModel.kt`
- `android-app/app/src/main/java/com/streambox/mobile/ui/screens/search/SearchScreen.kt`
- `android-app/app/src/main/java/com/streambox/mobile/ui/screens/search/SearchViewModel.kt`
- `android-app/app/src/main/java/com/streambox/mobile/ui/player/PlayerViewModel.kt`
- `android-app/app/src/main/res/xml/network_security_config.xml`
- `android-app/app/src/debug/res/xml/network_security_config.xml`
- `android-app/app/src/test/java/com/streambox/mobile/data/remote/DtoParsingTest.kt`
- `android-app/app/src/test/java/com/streambox/mobile/data/remote/CookieJarTest.kt`
- `android-app/README.md`

No commits, pushes, or deployments were made. The working tree remains dirty;
review `git status`, untracked Android sources, and ignored secret/signing files
before committing.
