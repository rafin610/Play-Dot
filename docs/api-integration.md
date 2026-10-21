# MovieBox Internal API — Android Integration Contract (Phase 1)

Contract based on the actual implementation in `moviebox_api_server.py` and verified by
real tests on 2026-10-20 (Linux, Python 3.12). Anything not listed here was not verified
in Phase 1.

## 1. Overview

- **Base URL**: `http://<host>:8000` (development default: `http://localhost:8000`).
- **Framework**: FastAPI + Uvicorn, Python 3.12.3 (audited/pinned runtime).
- **Upstream provider**: MovieBox/AOneRoom mobile BFF (`apig.inmoviebox.com`), accessed
  through the signed client in `moviebox_api/`. This backend is the **only** integration
  surface for the Android app — do not call the provider directly.
- **Sessions**: the backend maintains internal provider sessions keyed by an
  `session_id` HttpOnly cookie that it sets automatically on first use. The Android
  client keeps this cookie in an in-memory OkHttp cookie jar and reuses it for later
  requests; the cookie is not persisted across app-process restarts. Other clients
  that do not send the cookie create a fresh guest session per request. The backend
  bounds both idle TTL and total session count.
- **CORS**: not relevant for a native client, but enabled for `localhost:3000/3001`
  plus any `CORS_ORIGINS`/`FRONTEND_URL` values.

### General conventions

- Successful payloads use `{"code": 0, "data": ...}` where `code` is an integer
  (`0` = success). Some group/community endpoints pass the provider envelope through
  (`{"code": 0|1|200, "msg": ..., "data": ...}`) — treat `code != 0` as failure.
- Provider-level failures on main content endpoints are surfaced as HTTP errors:
  `404` (no streams / not found), `502` (upstream unavailable), `400` (bad request).

### Media playback (Android / Media3)

The `/stream` response is designed for ExoPlayer/Media3:

```http
User-Agent: ExoPlayerLib/2.18.7
Cookie: <cookie value from /stream response>
```

- Use `url + cookie` exactly as returned. Never log or persist the cookie longer than needed.
- `isHls: true` → feed `url` directly to Media3 HLS; otherwise assume progressive/DASH
  (DASH manifests `index.mpd` are returned by the provider already).
- `duration` is seconds (probed via metadata/ffprobe/HLS parse; falls back to 3600).
- `subtitles` is a list of `{id, lan, lanName, url, ...}`; `.srt` URLs can be converted
  to WebVTT by the backend via `/sub-proxy?u=<url>` if needed.

## 2. Verified endpoints (tested)

### Health

| Method | Path | Response |
|---|---|---|
| GET | `/` | `{"status": "ok", "service": "moviebox-internal-api"}` |
| GET | `/health` | `{"status": "ok"}` |

### Trending / featured — `GET /trending`

Verified 200 with real content (40-item mapped list).

```json
{
  "code": 0,
  "data": [ { item } ]
}
```

### Home sections — `GET /home?page=1`

Verified 200. Returns sections (`IN Banner`, curated lists):

```json
{"code": 0, "data": {"list": [ {"title": "IN Banner", "type": "BANNER", "items": [item...]} ]}}
```

### Search — `GET /search?q=<keyword>&page=1` (q required)

Verified 200 with real results (`Inception`, `Breaking Bad`).

```json
{"code": 0, "data": {"items": [item...]}}
```

Search suggestions: `GET /search-suggestions?q=<partial>`

```json
{"code": 0, "data": ["suggestion 1", "suggestion 2"]}   // strings
```
Verified 200. Without `q` it returns provider ranking terms (endpoint exists but
returns provider-shaped items — verify before relying on it).

### Movie details — `GET /detail/{subjectId}`

Verified 200 (movie `Inception [Hindi]`, `subjectType 1`).

```json
{
  "code": 0,
  "data": {
    "subjectId": "977486567826752424",
    "title": "Inception [Hindi]",
    "subjectType": 1,            // 1 = Movie, 2 = Series (verified), 6/8/9 = clips/UGC/shorts
    "cover": "https://...",
    "poster": "https://...",
    "score": "8.8",
    "releaseTime": "2010",       // display year (string, may be "N/A")
    "runtime": "2h 28m",
    "description": "...",
    "cast": [ {"name": "...", "role": "...", "avatar": "..."} ],
    "languages": [ {"id": null, "subjectId": "...", "name": "Hindi dub", "type": "dub"} ],
    "postCount": "0",
    "likeStatus": 0,
    "isCollection": false,
    "collectionItems": [item...]  // only when isCollection
  }
}
```

Casting a `subjectType: 6 | 8 | 9` to a player page is **not** supported by `/stream`
(varies per item); treat those as detail-only entries.

### Series seasons & episodes — `GET /episodes/{seriesId}`

Verified 200 (`Breaking Bad`, 5 seasons).

```json
{
  "code": 0,
  "data": {"seasons": [
    {"seasonNumber": 1, "episodes": [{"episodeNumber": "1", "title": "Episode 1", "id": "S1E1 key"}]}
  ]}
}
```

Note: season 1 of some series can under-report episodes (first season had gap numbers,
1..7 with 6 entries). Treat `episodeNumber` as the only stable key.

### Stream resolution — `GET /stream/{subjectId}?season&episode&quality`

| Param | Default | Notes |
|---|---|---|
| `season` | 1 | ignored (≈1) for movies |
| `episode` | 1 | ignored for movies |
| `quality` | `720p` | lowercase; used as preference upstream |
| `resource_id` | — | optional; pick a `languages[i].id=="resource"` id from `/detail` |

Verified 200 for both a movie (MP4 host `macdn.aoneroom.com`) and a series episode
(HTTP 200 on the returned URL with the cookie header).

```json
{
  "url": "https://macdn.aoneroom.com/....mp4",
  "cookie": "<signed CDN cookie>",
  "duration": 13680,
  "subtitles": [ {"id": "...", "lan": "en", "lanName": "English", "url": "https://..."} ],
  "subtitle_url": "https://...",       // best-effort English pick
  "isHls": false,
  "streamId": "6135533249432642296",
  "qualityList": [],                    // frequently empty
  "season": 1,
  "episode": 1
}
```

Errors: `404` `{"detail": "No streams found."}` — real streams are
provider-side; unverified titles may return this. Not all catalog titles are
guaranteed resolvable.

### Subtitles — `GET /subtitles/{subjectId}?se=1&ep=1`

Verified 200 (`{"code": 0, "data": {"list": [...]}}`, signed provider subtitle URLs).

### User info — `GET /user-info`

Verified 200:

```json
{"logged_in": true, "mode": "Official Account", "user": {"userId": "...", "userType": 0}, "session_id": "..."}
```

> In guest mode returns `{"logged_in": false, "mode": "Guest Access", ...}`.
> The provider bearer token is intentionally filtered from this response (Phase 1 fix).

## 3. Existing but not Phase-1-verified

These routes exist in the code and are documented in `README.md`, but were **not**
tested in Phase 1 — verify during later phases before relying on them:

`/movies`, `/anime`, `/rankings`, `/discovery`, `/short-tv`, `/kids`, `/education`,
`/music`, `/asian`, `/western`, `/nollywood`, `/game`, `/tab/{tab_id}`,
`/rooms/recommend`, `/rooms/{id}`, `/sports/live`, `/subtitles/search`,
`/subtitles/external/{id}` (referenced), `/history` (+position/seen/delete/progress),
`/watchlist`, `/watchlist/toggle`, `/post/*`, `/groups/*`, `/launch-player`
(desktop player launcher — requires mpv/vlc binaries; Linux may error),
`/download/{subjectId}` (FFmpeg-based), `/sub-proxy`.

`/login`, `/register`, `/request-otp`, `/logout` exist and pass through provider
results (`{"status": "success"|"error", "message": ...}`); an account was not created
during Phase 1, so flows are unverified end-to-end.

## 4. Media item shape (all content endpoints)

`{ "subjectId": "...", "id": "...", "title": "...", "cover": "...", "poster": "...",
"score": "8.8", "releaseTime": "2010", "subjectType": 1|2|6|8|9, "runtime": "2h 18m",
"season": 0, "episode": 0, "likeStatus": 0, "description": "...", "actionType":
"movie"|"category"|"playlist"|"actor"|"post", "categoryId": null, "deepLink": "",
"isPost": false, "streamUrl": null, "isCollection": false, "items": [] }`

`subjectType` values verified: `1` (movie), `2` (series); `6`, `8`, `9` appear in
search/trending (UGC, shorts, clips, trailers). Treat anything other than 1/2 as
browse-and-detail only.

## 5. Backend configuration (`.env`, gitignored)

| Variable | Purpose |
|---|---|
| `MOVIEBOX_GUEST_TOKEN` | Shared guest bearer credential (JWT) |
| `MOVIEBOX_USER_ID` | User ID paired with the guest token |
| `MOVIEBOX_GATEWAY_SECRET` | Base64 gateway HMAC secret for `x-tr-signature` request signing |
| `MOVIEBOX_APPID` | Provider `appid` header value |
| `CORS_ORIGINS` / `FRONTEND_URL` | Optional extra CORS origins (comma separated) |

Secrets are never printed to backend logs (Phase 1 redacted token/cookie logging).

Guest JWTs expire after ~90 days. Rotate the token and its paired user ID with
`.venv/bin/python scripts/refresh_guest_token.py`; `/health` exposes
`guest_token.expires_at` / `expires_in_days` and warns during the last 7 days.
An expired seed token is recoverable at runtime (each session bootstraps an
anonymous token through the `x-user` header), but requests made with an expired
bearer are sent anonymously so the gateway can issue a replacement.

## 6. Running locally

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
cp .env.example .env      # then fill in the 4 MOVIEBOX_* values
.venv/bin/python moviebox_api_server.py
```

Server listens on `http://0.0.0.0:8000`. Swagger docs: `http://localhost:8000/docs`.
