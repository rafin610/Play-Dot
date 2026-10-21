# Render Deployment Guide

Status as of **2026-10-21**: this repository contains a Docker-based Render
Blueprint and hardened backend configuration, but **no Render service was
created and no public HTTPS URL exists**. Do not treat any value in this guide
as an already-live endpoint. Deployment is an explicit owner action.

The Android app must be rebuilt with the real HTTPS origin after the service is
created. A release APK built with a local URL cannot be used as the production
artifact.

## 1. What is configured

| File | Purpose |
|---|---|
| `render.yaml` | Blueprint: paid Docker web service with FFmpeg, `main` branch, one instance, `/health` check, manual deploys only. |
| `Dockerfile` | Python 3.12 image with FFmpeg/ffprobe for media compatibility and HLS/DASH handling. |
| `.python-version` | Pins Python `3.12.3`, the version used for the audit. |
| `requirements.txt` | Exact tested versions of FastAPI, Uvicorn, Requests, Pydantic, HTTPX, and python-dotenv. |
| `.env.example` | Names and descriptions of required/optional settings. Contains placeholders only. |
| `.gitignore` | Keeps `.env`, signing material, APKs, and runtime files out of Git. |
| `moviebox_api_server.py` | Reads `HOST`/`PORT` in `__main__`; Render starts it with the Uvicorn CLI. |

`autoDeployTrigger` is set to `"off"`. Creating/importing the service is itself
an explicit deployment action and may trigger the initial build. Later commits
will **not** deploy automatically. Use **Manual Deploy** in Render only after
reviewing a release.

## 2. Preconditions

1. The repository is pushed to
   `https://github.com/rafin610/Play-Dot.git` on `main`.
   `render.yaml` references that repository and branch.
2. The Render account has access to the repository. For a private repository,
   grant Render's GitHub app access first.
3. Obtain fresh provider credentials:
   - `MOVIEBOX_GUEST_TOKEN`
   - `MOVIEBOX_USER_ID`
   - `MOVIEBOX_GATEWAY_SECRET` (base64 key material)
   - `MOVIEBOX_APPID`
4. Guest JWTs last ~90 days. Rotate the local pair before deploying:
   ```bash
   .venv/bin/python scripts/refresh_guest_token.py
   ```
   The script performs the anonymous provider handshake, rewrites
   `MOVIEBOX_GUEST_TOKEN` and its paired `MOVIEBOX_USER_ID` in `.env`, and
   prints the new expiry. Paste both values into Render's Environment UI.
   `/health` reports `guest_token.expires_at` and adds a warning during the
   final 7 days; an expired seed remains recoverable because new sessions
   bootstrap an anonymous token, but it should still be rotated.
5. Treat any credential that ever reached Git history as compromised and rotate
   it at the provider. Redaction in the current tree does not remove history.
6. Never paste credentials into `render.yaml`, source files, tests, docs, chat,
   or an APK. Enter them only in Render's Environment UI (or via the Render
   dashboard/API using a secret manager).

Python deployment target: **3.12.3**. Do not rely on Render's default Python
version; newer defaults may be incompatible with the pinned dependency set.

## 3. Local preflight

Run these from the repository root before creating the service:

```bash
# Backend contract/security tests (offline, fake provider)
.venv/bin/python -m unittest discover -s tests -v

# Local readiness check (fill .env first; never commit it)
.venv/bin/uvicorn moviebox_api_server:app --host 127.0.0.1 --port 8000
curl -sS -i http://127.0.0.1:8000/health
```

Expected `200` body:

```json
{"status":"ok","service":"moviebox-internal-api","version":"1.0.0"}
```

If it returns `503`, the body names each missing/invalid variable under
`missing_env`. Fix the configuration; do not remove the health check or bypass
it. `/` is liveness only and is not a readiness proof.

## 4. Create the Render service

### Blueprint import (recommended)

1. In the Render Dashboard, choose **New +** -> **Blueprint**.
2. Connect the GitHub repository and select the `main` branch.
3. Review the service created from `render.yaml`:
   - type: Web Service
   - runtime: Docker
   - plan: Starter (paid; confirm the current Render price in the dashboard)
   - branch: `main`
   - health check: `/health`
   - one worker
4. Render prompts for the four `sync: false` variables. Enter the fresh values
   from step 2.
5. Create/import the Blueprint. **This is the deployment approval step.** Do not
   import it until the owner approves deployment.
6. If the service name `play-dot-api` is already taken, choose another
   name. Use the actual assigned hostname everywhere below.

### Manual web-service alternative

If Blueprint import is not used, create a Docker Web Service with:

| Setting | Value |
|---|---|
| Repository | `rafin610/Play-Dot` |
| Branch | `main` |
| Runtime | Docker (`./Dockerfile`) |
| Health check path | `/health` |
| Auto deploy | Off |
| Instances/workers | 1 |

Do not set `PORT` manually; Render supplies it. `HOST` defaults to `0.0.0.0`.

## 5. Environment variables

| Variable | Required | Render value |
|---|---|---|
| `MOVIEBOX_GUEST_TOKEN` | Yes | Fresh provider guest JWT (~90-day lifetime); secret, `sync: false`. Rotate with `scripts/refresh_guest_token.py`. |
| `MOVIEBOX_USER_ID` | Yes | Provider user ID paired with the token; update it whenever the token rotates. |
| `MOVIEBOX_GATEWAY_SECRET` | Yes | Base64 gateway secret; secret, never logged. |
| `MOVIEBOX_APPID` | Yes | Provider appid header value; secret. |
| `PYTHON_VERSION` | Yes | `3.12.3` (also pinned by `.python-version`). |
| `COOKIE_SECURE` | Yes | `true` on HTTPS Render. |
| `ENABLE_EXTERNAL_PLAYER` | No | `false` on Render. No desktop player exists there. |
| `SESSION_TTL_SECONDS` | No | `1800` recommended; expires idle in-memory sessions. |
| `MAX_SESSIONS` | No | `500` recommended; bounds memory. Keep one worker. |
| `CORS_ORIGINS` | No | Only if a browser dashboard calls this service. |
| `FRONTEND_URL` | No | Backwards-compatible alias for CORS; not needed for Android. |
| `MAX_SUBTITLE_PROXY_BYTES` | No | Defaults to 10 MiB. |
| `HISTORY_FILE` | No | Do not expect durable history on Render's ephemeral disk. |

Native Android HTTP is not subject to CORS. Configure `CORS_ORIGINS` only for a
browser dashboard, and list exact HTTPS origins rather than `*` when possible.

## 6. Verify the deployment

Use the actual hostname shown by Render in place of
`<service-name>.onrender.com`.

```bash
curl -sS -i https://<service-name>.onrender.com/health
curl -sS https://<service-name>.onrender.com/trending
curl -sS 'https://<service-name>.onrender.com/search?q=Black%20Clover'
```

Expected readiness response:

```json
{"status":"ok","service":"moviebox-internal-api","version":"1.0.0"}
```

Checks:

- `200` from `/health` means the required variables are present and the base64
  gateway secret is syntactically valid. It does **not** prove the provider will
  accept the credentials or that content is available.
- `502` from a content endpoint means the provider/catalogue failed. Retry; do
  not convert it into an empty `200` response in the client.
- `503` from `/health` means Render will not consider the service healthy. Read
  `missing_env`, fix the variable names, and redeploy/restart.
- `POST /launch-player` should return `501` because
  `ENABLE_EXTERNAL_PLAYER=false`.
- `/sub-proxy?u=http://127.0.0.1/...` should return `400`; private/loopback
  targets are rejected.
- Check Render logs for startup line `Backend configuration validated.` and for
  provider failures. Signed media URLs and credential values should not appear
  in current logs.

Optional live content checks (IDs change as the catalogue changes):

```bash
curl -sS https://<service-name>.onrender.com/detail/<series-id>
curl -sS https://<service-name>.onrender.com/episodes/<series-id>
curl -sS 'https://<service-name>.onrender.com/subtitles/<series-id>?se=1&ep=1'
curl -sS 'https://<service-name>.onrender.com/stream/<series-id>?season=1&episode=1&quality=720p'
```

The provider can return `404` for titles that exist in a different catalogue or
have no playable mirror. A `404` for one ID is not proof that the backend is
broken; use a known-good ID from `/trending`.

## 7. Build the production APK

### Signing prerequisite

`android-app/keystore.properties` and the referenced keystore must exist locally.
They are git-ignored. Back them up securely; losing the key prevents updates to
an installed release build. Never commit the keystore or passwords.

If the local signing files are absent, `assembleRelease` produces an unsigned
APK. Do not distribute it. Generate/restore the release keystore separately.

### Build with the HTTPS base URL

The Gradle property must include the trailing slash and must use the real
hostname:

```bash
export JAVA_HOME=/path/to/jdk-17
export PATH="$JAVA_HOME/bin:$PATH"

cd android-app
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleRelease \
  -Pstreambox.baseUrl=https://<service-name>.onrender.com/
```

Output:

```text
android-app/app/build/outputs/apk/release/app-release.apk
```

Validate the signer and record the SHA-256:

```bash
export PATH="$ANDROID_HOME/build-tools/36.0.0:$PATH"
apksigner verify --verbose --print-certs \
  android-app/app/build/outputs/apk/release/app-release.apk
sha256sum android-app/app/build/outputs/apk/release/app-release.apk
```

Install on a test device only after the release URL is live:

```bash
adb install -r android-app/app/build/outputs/apk/release/app-release.apk
```

Then exercise: home/trending, search, series detail + season picker, episode
playback with audio/video, subtitle selection, watchlist add/remove, and process
restart. The audit's emulator verification used a local backend, not Render;
repeat these checks against the HTTPS build.

## 8. Operational limitations

- **Paid starter plan**: one instance is recommended initially because sessions
  are held in process memory. Add Redis/Postgres before horizontal scaling.
- **One worker**: sessions are held in process memory. Do not raise the worker
  count without shared session storage, or requests will land on different
  session stores.
- **Ephemeral disk**: `local_history.json` and any temporary files disappear on
  restart/redeploy. Android watchlist and resume progress use local DataStore and
  are unaffected.
- **FFmpeg**: the Docker image includes FFmpeg/ffprobe; long transcodes still
  consume CPU and should be tested with representative streams.
- **External player disabled**: `/launch-player` returns `501` by configuration.
- **Provider dependency**: upstream rate limits, expiring guest tokens, catalogue
  changes, and regional 404s remain outside this repository's control.
- **TLS**: verification is enabled for stream probes and subtitle proxying. Do
  not add a blanket `verify=False` fallback for a problematic CDN; fix the CDN
  or isolate the workaround behind an explicit, reviewed setting.

## 9. Rollback

Keep automatic deploys off. To roll back:

1. In Render, open the service -> **Deploys**.
2. Redeploy the last known-good deploy (or revert the commit and use **Manual
   Deploy**).
3. Recheck `/health` and the core content endpoints.
4. If credentials may have leaked, rotate them in Render and redeploy; a code
   rollback does not rotate secrets.

In-memory sessions are lost on restart. That is expected: Android obtains a new
session on its next request.

## 10. Troubleshooting

| Symptom | Check |
|---|---|
| Deploy never becomes healthy | Render logs; `/health` body; all four required variables; base64 gateway secret. |
| `/health` 200 but content 502 | Provider outage/rate limit, expired credentials, wrong region/catalogue. Check redacted logs. |
| Android says "Can't reach the server" | APK was built with the wrong base URL, missing trailing slash, wrong scheme, or service is asleep. |
| Android can load but playback 404 | Provider has no stream for that title/episode; try a known-good ID. |
| `/download` returns 503 | HLS/DASH on a host without FFmpeg; not a client bug. |
| Render rejects Blueprint | `autoDeployTrigger`, `plan`, or `runtime` schema may have changed; compare with Render's current Blueprint reference before editing secrets. |

Do not commit `.env`, keystore files, APKs, or Render credentials. Run
`git status --ignored` and a secret scan before pushing.
