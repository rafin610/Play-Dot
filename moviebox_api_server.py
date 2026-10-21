from fastapi import FastAPI, HTTPException, Query, Response, Cookie, Request
from fastapi.responses import JSONResponse, StreamingResponse
import httpx
from fastapi.middleware.cors import CORSMiddleware
from typing import Optional, List, Dict
from pydantic import BaseModel
import uvicorn
import os
import subprocess
import shutil
import base64
import json
import asyncio
import logging
import uuid
import re
import socket
import ipaddress
import threading
import time
from contextlib import asynccontextmanager
from urllib.parse import quote, urljoin, urlparse
from dotenv import load_dotenv

# Load local configuration (.env, gitignored) before any module consumes it.
# Render injects real environment variables; load_dotenv() does not override them.
load_dotenv()

from moviebox_api import MovieBoxClient, MovieBoxAuth, MovieBoxContent, MovieBoxStream, MovieBoxUser
from moviebox_api.auth import default_guest_token

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

APP_VERSION = "1.0.0"
SERVICE_NAME = "moviebox-internal-api"

# The provider gateway cannot sign requests without all four values. Validate
# names only; never log or return the actual values.
REQUIRED_ENV_VARS = (
    "MOVIEBOX_GUEST_TOKEN",
    "MOVIEBOX_USER_ID",
    "MOVIEBOX_GATEWAY_SECRET",
    "MOVIEBOX_APPID",
)


def configuration_errors() -> List[str]:
    """Return human-readable configuration problems without exposing secrets."""
    errors = [f"{name} is not set" for name in REQUIRED_ENV_VARS if not os.getenv(name, "").strip()]

    secret = os.getenv("MOVIEBOX_GATEWAY_SECRET", "").strip()
    if secret:
        try:
            base64.b64decode(secret, validate=True)
        except Exception:
            errors.append("MOVIEBOX_GATEWAY_SECRET is not valid base64")
    return errors


def _safe_url_label(url: str) -> str:
    """Log a URL without its query string (signed CDN queries are credentials)."""
    try:
        parsed = urlparse(url)
        return f"{parsed.scheme}://{parsed.netloc}{parsed.path}"
    except Exception:
        return "<invalid-url>"


def _normalize_subtitles(items) -> List[dict]:
    """Normalize provider subtitle fields to the Android string-based contract."""
    normalized = []
    if not isinstance(items, list):
        return normalized
    for item in items:
        if not isinstance(item, dict):
            continue
        entry = dict(item)
        for key in ("id", "lan", "lanName", "url"):
            value = entry.get(key)
            if value is not None:
                entry[key] = str(value)
        normalized.append(entry)
    return normalized


def _validate_public_http_url(raw_url: str) -> str:
    """Reject SSRF targets (private/loopback/link-local/metadata hosts)."""
    if not isinstance(raw_url, str) or not raw_url.strip():
        raise HTTPException(status_code=400, detail="A subtitle URL is required.")
    parsed = urlparse(raw_url.strip())
    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        raise HTTPException(status_code=400, detail="Only absolute http(s) subtitle URLs are supported.")
    if parsed.username or parsed.password:
        raise HTTPException(status_code=400, detail="Credentials in subtitle URLs are not supported.")

    host = parsed.hostname.lower().rstrip(".")
    if host == "localhost" or host.endswith(".localhost") or host.endswith(".local") or host.endswith(".internal"):
        raise HTTPException(status_code=400, detail="Subtitle host is not public.")

    try:
        addresses = {
            info[4][0]
            for info in socket.getaddrinfo(host, parsed.port or (443 if parsed.scheme == "https" else 80), type=socket.SOCK_STREAM)
        }
    except OSError:
        raise HTTPException(status_code=400, detail="Subtitle host could not be resolved.")

    if not addresses:
        raise HTTPException(status_code=400, detail="Subtitle host could not be resolved.")
    for address in addresses:
        try:
            ip = ipaddress.ip_address(address.split("%", 1)[0])
        except ValueError:
            raise HTTPException(status_code=400, detail="Subtitle host resolved to an invalid address.")
        if not ip.is_global:
            raise HTTPException(status_code=400, detail="Subtitle host is not public.")
    return raw_url.strip()


@asynccontextmanager
async def lifespan(_app: FastAPI):
    errors = configuration_errors()
    if errors:
        logger.error("Backend configuration is incomplete: %s", "; ".join(errors))
    else:
        logger.info("Backend configuration validated.")
    yield


app = FastAPI(title="MovieBox Unofficial API Backend", version=APP_VERSION, lifespan=lifespan)


@app.get("/")
def root_health():
    """Liveness endpoint. Configuration readiness is reported by /health."""
    return {"status": "ok", "service": SERVICE_NAME}


@app.get("/health")
def health_check():
    """Readiness endpoint used by Render and deployment smoke tests."""
    errors = configuration_errors()
    if errors:
        return JSONResponse(
            status_code=503,
            content={
                "status": "error",
                "service": SERVICE_NAME,
                "version": APP_VERSION,
                "missing_env": errors,
            },
        )
    return {"status": "ok", "service": SERVICE_NAME, "version": APP_VERSION}

# Enable CORS for local and deployed Wotchly frontends.
# Render/Netlify deployments have historically used both variable names, so
# treat FRONTEND_URL as a backwards-compatible alias for CORS_ORIGINS.
_allowed_origins = [
    origin.strip()
    for raw in (os.getenv("CORS_ORIGINS", ""), os.getenv("FRONTEND_URL", ""))
    for origin in raw.split(",")
    if origin.strip()
]
_allowed_origins += ["http://localhost:3000", "http://localhost:3001", "http://127.0.0.1:3000", "http://127.0.0.1:3001"]
app.add_middleware(
    CORSMiddleware,
    allow_origins=sorted(set(_allowed_origins)),
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Multi-session management
# session_id -> {auth, client, content, stream, user}
#
# A request without a session_id creates a new provider session. Android keeps
# its cookie only in process memory, and other clients may not send one at all,
# so keep this bounded and expire idle entries; otherwise a long-running Render
# instance leaks memory indefinitely.
sessions: Dict[str, Dict] = {}
_session_last_seen: Dict[str, float] = {}
_session_lock = threading.Lock()


def _positive_int_env(name: str, default: int) -> int:
    try:
        return max(1, int(os.getenv(name, str(default))))
    except (TypeError, ValueError):
        logger.warning("Invalid %s; using %s", name, default)
        return default


SESSION_TTL_SECONDS = _positive_int_env("SESSION_TTL_SECONDS", 1800)
MAX_SESSIONS = _positive_int_env("MAX_SESSIONS", 500)

HISTORY_FILE = os.getenv("HISTORY_FILE", "local_history.json")
_history_lock = threading.Lock()


def load_local_history() -> dict:
    with _history_lock:
        if os.path.exists(HISTORY_FILE):
            try:
                with open(HISTORY_FILE, "r", encoding="utf-8") as f:
                    data = json.load(f)
                if isinstance(data, dict):
                    return data
                logger.error("Local history is not a JSON object; ignoring it.")
            except Exception as e:
                logger.error(f"Failed to load local history: {e}")
    return {"default": [], "blacklist": []}


def save_local_history(h: dict):
    """Write history atomically so a crash cannot leave truncated JSON."""
    tmp_path = f"{HISTORY_FILE}.tmp"
    with _history_lock:
        try:
            with open(tmp_path, "w", encoding="utf-8") as f:
                json.dump(h, f, indent=2, ensure_ascii=False)
                f.flush()
                os.fsync(f.fileno())
            os.replace(tmp_path, HISTORY_FILE)
        except Exception as e:
            logger.error(f"Failed to save local history: {e}")
            try:
                if os.path.exists(tmp_path):
                    os.remove(tmp_path)
            except OSError:
                pass


def _prune_sessions_locked(now: float):
    expired = [
        sid
        for sid, last_seen in _session_last_seen.items()
        if now - last_seen > SESSION_TTL_SECONDS
    ]
    for sid in expired:
        sessions.pop(sid, None)
        _session_last_seen.pop(sid, None)


def get_session(session_id: Optional[str] = None):
    # Fail with a clear readiness error instead of a provider-side 500 when
    # required Render environment variables are missing.
    if configuration_errors():
        raise HTTPException(
            status_code=503,
            detail="Backend configuration is incomplete. Check the /health endpoint and server logs.",
        )

    now = time.time()
    with _session_lock:
        if session_id:
            existing = sessions.get(session_id)
            if existing and now - _session_last_seen.get(session_id, 0) <= SESSION_TTL_SECONDS:
                _session_last_seen[session_id] = now
                return existing
            # Stale/invalid cookie: drop it and create a fresh session.
            sessions.pop(session_id, None)
            _session_last_seen.pop(session_id, None)

        _prune_sessions_locked(now)
        if len(sessions) >= MAX_SESSIONS:
            oldest = min(_session_last_seen, key=_session_last_seen.get)
            sessions.pop(oldest, None)
            _session_last_seen.pop(oldest, None)
            logger.warning("Session store full; evicted oldest session %s", oldest)

        sid = str(uuid.uuid4())
        auth = MovieBoxAuth()
        client = MovieBoxClient(auth=auth)
        session = {
            "id": sid,
            "auth": auth,
            "client": client,
            "content": MovieBoxContent(client),
            "stream": MovieBoxStream(client),
            "user": MovieBoxUser(client),
        }
        sessions[sid] = session
        _session_last_seen[sid] = now

    logger.info(f"Created new session: {sid}")

    # Bootstrap fresh guest credentials by calling a public endpoint. Do this
    # outside the lock so a slow upstream call does not block other requests.
    try:
        logger.info(f"Bootstrapping guest credentials for session {sid}...")
        # X-Client-Status 1 forces guest token allocation from x-user response header
        auth.is_logged_in = False
        MovieBoxContent(client).get_categories(category_id=1, page=1)
        # Ensure we toggle auth.is_logged_in back to True (which uses Authorization: Bearer <Token>)
        # so that details/play-info endpoints can use the bearer token.
        # Compare against the locally configured baseline (MOVIEBOX_GUEST_TOKEN, from .env).
        baseline_token = default_guest_token()
        if auth.token and auth.token != baseline_token:
            logger.info("Bootstrap guest token updated")
            auth.is_logged_in = True
        else:
            logger.warning("Bootstrap guest token did not update credentials.")
    except Exception as e:
        logger.error(f"Failed to bootstrap guest session: {e}")

    return session

class LoginRequest(BaseModel):
    account: str
    password: str
    authType: int = 1

class RegisterRequest(BaseModel):
    account: str
    password: str
    otp: str
    authType: int = 1

class OtpRequest(BaseModel):
    account: str
    authType: int = 1
    type: int = 1 # 1: Register, 2: Login


def _public_user_payload(value) -> dict:
    """Return only non-sensitive account fields to API clients."""
    if not isinstance(value, dict):
        return {}
    blocked = {"token", "accessToken", "refreshToken", "sessionId", "session_id", "signCookie", "cookie"}
    return {key: item for key, item in value.items() if key not in blocked}


def _set_session_cookie(response: Response, session_id: str, *, max_age: Optional[int] = None):
    response.set_cookie(
        key="session_id",
        value=session_id,
        httponly=True,
        secure=os.getenv("COOKIE_SECURE", "false").strip().lower() in ("1", "true", "yes", "on"),
        samesite="lax",
        max_age=max_age,
    )

@app.post("/request-otp")
def request_otp(req: OtpRequest, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    res = s["client"].request_otp(req.account, req.authType, req.type)
    if res["status"] == "error":
        raise HTTPException(status_code=400, detail=res["message"])
    return res

@app.post("/login")
def login(req: LoginRequest, response: Response, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    res = s["client"].login(req.account, req.password, req.authType)
    if res["status"] == "error":
        raise HTTPException(status_code=400, detail=res["message"])
    
    # Always set/refresh the session cookie on login
    _set_session_cookie(response, s["id"], max_age=3600 * 24 * 30)
    return {"status": "success", "user": _public_user_payload(res.get("user"))}

@app.post("/register")
def register(req: RegisterRequest, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    res = s["client"].register(req.account, req.password, req.otp, req.authType)
    if res["status"] == "error":
        raise HTTPException(status_code=400, detail=res["message"])
    return {"status": "success", "user": _public_user_payload(res.get("user"))}

@app.post("/logout")
def logout(response: Response, session_id: Optional[str] = Cookie(None)):
    if session_id:
        with _session_lock:
            session = sessions.pop(session_id, None)
            _session_last_seen.pop(session_id, None)
        if session:
            session["client"].logout()
    response.delete_cookie("session_id")
    return {"status": "success"}

@app.get("/user-info")
def get_user_info(response: Response, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    
    # If no session_id was provided, set the newly created one
    if not session_id:
        _set_session_cookie(response, s["id"])

    if not s["auth"].is_logged_in:
        return {"logged_in": False, "mode": "Guest Access"}

    # Never echo the provider bearer token to clients.
    user_meta = dict(s["auth"].user_info or {})
    user_meta.pop("token", None)
    return {
        "logged_in": True,
        "mode": "Official Account", 
        "user": _public_user_payload(user_meta)
    }

def map_actor(actor: dict):
    avatar = actor.get("avatarUrl") or actor.get("avatar") or actor.get("photo") or actor.get("poster") or ""
    if isinstance(avatar, dict): avatar = avatar.get("url") or ""
    if isinstance(avatar, str) and avatar.startswith("//"): avatar = "https:" + avatar
    return {
        "name": str(actor.get("name") or actor.get("actorName") or "Unknown"),
        "role": str(actor.get("character") or actor.get("role") or "Cast"),
        "avatar": str(avatar or "")
    }

def map_room(src: dict):
    """Maps Community/Room data to local format."""
    return {
        "id": str(src.get("groupId") or src.get("id")),
        "name": src.get("name") or "Community Room",
        "title": src.get("name") or "Community Room",
        "avatar": src.get("cover", {}).get("url") if isinstance(src.get("cover"), dict) else src.get("avatar") or "",
        "description": src.get("description") or "",
        "members": src.get("userCount") or 0,
        "posts": src.get("postCount") or 0,
        "tags": src.get("tags") or []
    }

def map_item(src: dict, depth: int = 0):
    if "subject" in src and isinstance(src["subject"], dict):
        item = src["subject"]
    else:
        item = src

    sid = str(item.get("subjectId") or item.get("id") or "")
    # Title mapping: favor specific names, then check wrappers
    title = (
        item.get("title") or 
        item.get("name") or 
        item.get("subjectName") or 
        item.get("subject_name") or
        item.get("categoryName") or
        item.get("content") or 
        item.get("keyword") or
        item.get("keywordName") or
        item.get("itemName") or
        item.get("show_name") or
        item.get("showTitle") or
        item.get("titleName") or
        item.get("title_en") or
        item.get("tag") or
        item.get("label") or
        item.get("extra") or
        item.get("subtitle") or
        item.get("tabName") or
        item.get("tab_name") or
        item.get("searchName") or
        item.get("promotionName") or
        src.get("title") or
        src.get("name") or
        src.get("content") or
        src.get("keyword") or
        src.get("label") or
        "Unknown"
    )
    
    # Final effort: try to infer from deepLink if still unknown
    dlink = str(item.get("deepLink") or src.get("deepLink") or "")
    action_type = "movie" # Default
    category_id = None
    
    if dlink:
        if "/home/category" in dlink:
            action_type = "category"
            if "categoryType=" in dlink:
                category_id = dlink.split("categoryType=")[1].split("&")[0]
                # Only use category_id as sid if item has no real subjectId of its own
                if not sid or sid == "0":
                    sid = category_id
        elif "/playlist/detail" in dlink:
            action_type = "playlist"
            if "playlistId=" in dlink:
                extracted_id = dlink.split("playlistId=")[1].split("&")[0]
                if not sid or sid == "0":
                    sid = extracted_id
        elif "/movie/detail" in dlink:
            action_type = "movie"
            if "subjectId=" in dlink:
                extracted_id = dlink.split("subjectId=")[1].split("&")[0]
                if not sid or sid == "0":
                    sid = extracted_id
        elif "/actor/" in dlink:
            action_type = "actor"
            if "actorId=" in dlink:
                extracted_id = dlink.split("actorId=")[1].split("&")[0]
                if not sid or sid == "0":
                    sid = extracted_id
        elif "/post/" in dlink or "detailVideo" in dlink:
            action_type = "post"
            if "id=" in dlink:
                # Extract the post ID from the deepLink
                extracted_id = dlink.split("id=")[1].split("&")[0]
                if sid == "0" or not sid:
                    sid = extracted_id

    if title == "Unknown":
        pass
    
    poster = item.get("poster")
    poster_url = ""
    if isinstance(poster, dict): poster_url = poster.get("url")
    elif isinstance(poster, str): poster_url = poster
    
    if not poster_url:
        cover = item.get("cover")
        poster_url = cover.get("url") if isinstance(cover, dict) else cover

    # Aggressive Deep Search for Images
    if not poster_url:
        img_terms = ["image", "img", "thumb", "thumbnail", "poster", "cover", "icon", "banner", "pic", "picture"]
        # Priority 1: Check standard nested objects
        for term in img_terms:
            val = item.get(term)
            if isinstance(val, dict) and val.get("url"):
                poster_url = val.get("url")
                break
            elif isinstance(val, str) and (val.startswith("http") or val.startswith("//")):
                poster_url = val
                break
        
        # Priority 2: Check keys with _url or _path suffix
        if not poster_url:
            for k, v in item.items():
                if any(t in k.lower() for t in img_terms) and isinstance(v, str) and (v.startswith("http") or v.startswith("//")):
                    poster_url = v
                    break

    if not poster_url:
        hp = item.get("horizontalPoster") or item.get("horizontalCover")
        poster_url = hp.get("url") if isinstance(hp, dict) else hp

    if not poster_url:
        # Final fallback - check for a banner object
        banner = item.get("banner")
        if isinstance(banner, dict):
            poster_url = banner.get("image", {}).get("url") or banner.get("url")

    if isinstance(poster_url, str) and poster_url.startswith("//"):
        poster_url = "https:" + poster_url

    score = item.get("imdbRatingValue") or item.get("imdbRate") or item.get("starRating") or item.get("score") or "N/A"
    
    # Year mapping fix using official releaseDate field
    release_date = item.get("releaseDate") or item.get("releaseTime") or item.get("year") or ""
    display_year = release_date[:4] if release_date and len(release_date) >= 4 else "N/A"

    runtime = item.get("duration") or item.get("runtime") or item.get("minute")
    if isinstance(runtime, int): runtime = f"{runtime}m"

    raw_see_time = item.get("seeTime")
    try:
        see_time = int(raw_see_time) if raw_see_time is not None else None
    except (TypeError, ValueError):
        see_time = None

    raw_collection = item.get("isCollection")
    is_collection = (
        raw_collection is True
        or raw_collection == 1
        or str(raw_collection).lower() in ("1", "true")
    )

    return {
        "subjectId": sid,
        "id": sid,
        "title": title,
        "cover": poster_url,
        "poster": poster_url,
        "score": str(score),
        "releaseTime": display_year,
        "subjectType": item.get("subjectType") or item.get("type") or item.get("subject_type") or (2 if item.get("episodeCount") or item.get("seasonCount") else 1),
        "runtime": runtime,
        "season": item.get("season"),
        "episode": item.get("episode") or item.get("ep"),
        "seeTime": see_time,
        "seenStatus": item.get("seenStatus"),
        # Map ALL variations of favorite/like status across ALL regional API versions
        # Standard: isFavorite, isLike
        # Legacy: collected, collectedStatus, is_favorite, fav, is_fav, isCollect
        "likeStatus": 1 if (
            item.get("isFavorite") == 1 or 
            item.get("is_favorite") == 1 or
            item.get("fav") == 1 or
            item.get("is_fav") == 1 or
            item.get("isLike") == 1 or 
            item.get("is_like") == 1 or
            item.get("wantToSee") == 1 or
            item.get("likeStatus") == 1 or
            item.get("collected") == 1 or
            item.get("isCollect") == 1 or
            item.get("collectedStatus") == 1 or
            str(item.get("likeType")) == "1" or
            item.get("isCollect") is True or
            item.get("isFavorite") is True
        ) else 0,
        "description": item.get("description") or "",
        "actionType": action_type,
        "categoryId": category_id,
        "deepLink": dlink,
        "isPost": action_type == "post",
        "streamUrl": item.get("playUrl", {}).get("playUrl") if isinstance(item.get("playUrl"), dict) else None,
        "isCollection": is_collection,
        "items": item.get("items") if isinstance(item.get("items"), list) else []
    }

@app.get("/home")
def get_home(response: Response, page: int = 1, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    response.set_cookie(key="session_id", value=s["id"], httponly=True, samesite="lax")
    try:
        # Category ID 1 corresponds to "Trending", which is the primary "Home" feed in the app.
        res = s["content"].get_categories(category_id=1, page=page)
        if not isinstance(res, dict):
            raise ValueError("provider returned a non-object response")
        if res.get("code") not in (None, 0, 200):
            raise ValueError(f"provider returned code {res.get('code')}")
        data = res.get("data") or {}
        if not isinstance(data, dict):
            data = {}
        items = data.get("list") or data.get("items") or data.get("subjects") or []
        if not isinstance(items, list):
            items = []

        # We can format it identically to all other robust sections we built
        return {"code": 0, "data": {"list": format_tab_sections(items)}}
    except HTTPException:
        raise
    except Exception:
        logger.exception("Home upstream error")
        # Do not return HTTP 200 with an empty feed: the Android client would
        # otherwise show a silent empty home screen instead of a retry state.
        raise HTTPException(status_code=502, detail="MovieBox home feed is unavailable. Please retry.")

@app.get("/anime")
def get_anime(page: int = 1, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    try:
        res = s["content"].get_categories(category_id=8, page=page)
        data = res.get("data") or {}
        items = data.get("list") or data.get("items") or data.get("subjects") or []
        return {"code": 0, "data": {"list": format_tab_sections(items)}}
    except Exception as e:
        logger.error(f"Anime error: {e}")
        return {"code": 1, "data": []}

@app.get("/rankings")
def get_rankings(response: Response, tabId: int = 1, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    # Ensure session persists even for guest GET requests
    response.set_cookie(key="session_id", value=s["id"], httponly=True, samesite="lax")
    # The official path from Smali class zm/c is /wefeed-mobile-bff/tab/ranking-list
    variants = ["/wefeed-mobile-bff/tab/ranking-list", "/tab/ranking-list", "/subject-api/ranking-list"]
    
    for v in variants:
        try:
            res = s["content"].get_rankings(v, tab_id=tabId)
            data = res.get("data")
            if not data: continue
            
            formatted = []
            # RankAllData format: 'subjects' contains the items
            if "subjects" in data and isinstance(data["subjects"], list):
                items = data["subjects"]
                if items:
                    formatted.append({"title": "Top Rankings", "items": [map_item(i) for i in items[:10]]})
            else:
                lists = data.get("lists") or data.get("list") 
                if isinstance(lists, list):
                    for l in lists:
                        if not isinstance(l, dict): continue
                        title = l.get("name") or l.get("title") or "Rankings"
                        items = l.get("items") or l.get("list") or []
                        if items:
                            formatted.append({"title": title, "items": [map_item(i) for i in items[:10]]})
            
            if formatted: return {"code": 0, "data": formatted}
        except Exception as e:
            logger.error(f"Error parsing rankings variant {v}: {e}")
            continue
        
    return {"code": 0, "data": []}

@app.get("/discovery")
def get_discovery(session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    try:
        res = s["content"].get_discovery()
        data = res.get("data") or {}
        items = data.get("list") or data.get("items") or []
        return {"code": 0, "data": [map_item(i) for i in items[:20]]}
    except Exception as e:
        logger.error(f"Discovery error: {e}")
        return {"code": 1, "data": []}

def _extract_items(value, depth: int = 0) -> list:
    """Extract content from the different envelopes returned by MovieBox regions."""
    if value is None or depth > 6:
        return []
    if isinstance(value, list):
        flattened = []
        for item in value:
            if isinstance(item, dict) and any(isinstance(item.get(k), (list, dict)) for k in ("items", "list", "subjects", "movieList", "data")):
                nested = next((item.get(k) for k in ("items", "list", "subjects", "movieList", "data") if item.get(k) is not None), None)
                flattened.extend(_extract_items(nested, depth + 1) or [item])
            else:
                flattened.append(item)
        return flattened
    if isinstance(value, dict):
        for key in ("items", "list", "subjects", "movieList", "results", "data"):
            if key in value:
                items = _extract_items(value[key], depth + 1)
                if items:
                    return items
    return []

@app.get("/trending")
def get_trending(response: Response, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    response.set_cookie(key="session_id", value=s["id"], httponly=True, samesite="lax")
    try:
        # MovieBox has moved the guest home feed between BFF endpoints. Try
        # known contracts lazily and return the first non-empty catalogue;
        # evaluating all three eagerly triples provider load and rate-limit risk.
        candidate_getters = (
            lambda: s["content"].get_trending(),
            lambda: s["content"].get_home_list(category_id=1),
            lambda: s["content"].get_categories(category_id=1, page=1),
        )
        for candidate_index, getter in enumerate(candidate_getters, start=1):
            try:
                res = getter()
            except Exception:
                logger.warning("Trending candidate %d failed; trying the next contract", candidate_index, exc_info=True)
                continue
            items = _extract_items(res)
            logger.info("Trending candidate %d returned %d items", candidate_index, len(items))
            mapped = [map_item(i) for i in items[:40] if isinstance(i, dict)]
            if mapped:
                return {"code": 0, "data": mapped}
        return {"code": 0, "data": [], "meta": {"source": "moviebox", "empty": True}}
    except Exception:
        logger.exception("Trending upstream error")
        raise HTTPException(status_code=502, detail="MovieBox trending is unavailable. Please retry.")

@app.get("/movies")
def get_movies(page: int = 1, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    try:
        res = s["content"].get_categories(category_id=2, page=page)
        data = res.get("data") or {}
        items = data.get("list") or data.get("items") or data.get("subjects") or []
        return {"code": 0, "data": {"list": format_tab_sections(items)}}
    except Exception:
        logger.exception("Movies upstream error")
        raise HTTPException(status_code=502, detail="MovieBox movies are unavailable. Please retry.")

def format_tab_sections(items: list):
    sections = []
    
    # Catch-all if it's already a direct movie list (no inner sections)
    is_direct_movies = True
    for row in items:
        if isinstance(row, dict) and (row.get("list") or row.get("items") or row.get("subjects") or row.get("movieList") or row.get("customData") or row.get("banner")):
            is_direct_movies = False
            break
            
    if is_direct_movies and items:
         mapped = [map_item(m) for m in items if m.get("subjectId") or m.get("id")]
         if mapped: return [{"title": "Content", "items": mapped}]

    for row in items:
        if not isinstance(row, dict): continue
        title = row.get("title") or row.get("name") or "Section"
        
        inner = []
        if isinstance(row.get("list"), list) and row.get("list"): inner = row.get("list")
        elif isinstance(row.get("items"), list) and row.get("items"): inner = row.get("items")
        elif isinstance(row.get("subjects"), list) and row.get("subjects"): inner = row.get("subjects")
        elif isinstance(row.get("movieList"), list) and row.get("movieList"): inner = row.get("movieList")
        elif isinstance(row.get("customData"), dict) and isinstance(row["customData"].get("items"), list) and row["customData"]["items"]:
            inner = row["customData"]["items"]
        elif isinstance(row.get("banner"), dict) and isinstance(row["banner"].get("banners"), list) and row["banner"]["banners"]:
            inner = row["banner"]["banners"]
            
        real_movies = []
        for i in inner:
            if not isinstance(i, dict): continue
            if isinstance(i.get("subject"), dict):
                 real_movies.append(i["subject"])
            elif i.get("subjectId") or i.get("id"):
                 real_movies.append(i)
        
        if real_movies:
            mapped = [map_item(m) for m in real_movies]
            section_type = row.get("subjectType") or row.get("type") or "SUBJECTS_MOVIE"
            sections.append({
                "title": str(title),
                "type": str(section_type),
                "items": mapped
            })
    return sections

@app.get("/short-tv")
def get_short_tv(page: int = 1, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    try:
        res = s["content"].get_categories(category_id=13, page=page)
        data = res.get("data") or {}
        items = data.get("list") or data.get("items") or data.get("subjects") or []
        return {"code": 0, "data": {"list": format_tab_sections(items)}}
    except Exception as e:
        logger.error(f"ShortTV error: {e}")
        return {"code": 1, "data": []}

@app.get("/kids")
def get_kids(page: int = 1, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    try:
        res = s["content"].get_categories(category_id=23, page=page)
        data = res.get("data") or {}
        items = data.get("list") or data.get("items") or data.get("subjects") or []
        return {"code": 0, "data": {"list": format_tab_sections(items)}}
    except Exception as e:
        logger.error(f"Kids error: {e}")
        return {"code": 1, "data": []}

@app.get("/education")
def get_education(page: int = 1, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    try:
        res = s["content"].get_categories(category_id=3, page=page)
        data = res.get("data") or {}
        items = data.get("list") or data.get("items") or data.get("subjects") or []
        return {"code": 0, "data": {"list": format_tab_sections(items)}}
    except Exception as e:
        logger.error(f"Education error: {e}")
        return {"code": 1, "data": []}

@app.get("/music")
def get_music(page: int = 1, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    try:
        res = s["content"].get_categories(category_id=4, page=page)
        data = res.get("data") or {}
        items = data.get("list") or data.get("items") or data.get("subjects") or []
        return {"code": 0, "data": {"list": format_tab_sections(items)}}
    except Exception as e:
        logger.error(f"Music error: {e}")
        return {"code": 1, "data": []}

@app.get("/asian")
def get_asian(page: int = 1, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    try:
        res = s["content"].get_categories(category_id=18, page=page)
        data = res.get("data") or {}
        items = data.get("list") or data.get("items") or data.get("subjects") or []
        return {"code": 0, "data": {"list": format_tab_sections(items)}}
    except Exception as e:
        logger.error(f"Asian error: {e}")
        return {"code": 1, "data": []}

@app.get("/western")
def get_western(page: int = 1, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    try:
        res = s["content"].get_categories(category_id=19, page=page)
        data = res.get("data") or {}
        items = data.get("list") or data.get("items") or data.get("subjects") or []
        return {"code": 0, "data": {"list": format_tab_sections(items)}}
    except Exception as e:
        logger.error(f"Western error: {e}")
        return {"code": 1, "data": []}

@app.get("/nollywood")
def get_nollywood(page: int = 1, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    try:
        # Reverting to tab-operating GET vertical for tabId 28
        res = s["content"].get_categories(category_id=28, page=page)
        if not isinstance(res, dict):
             return {"code": 1, "message": "Invalid response format", "data": {"list": []}}
        
        data = res.get("data") or {}
        items = data.get("list") or data.get("items") or data.get("subjects") or []
        
        # Log detected items to verify if we are getting Home content
        if items:
            sample = items[0].get('name') or items[0].get('title') or "Unknown"
            logger.info(f"Nollywood Detection: First item is '{sample}'")
            
        return {"code": 0, "data": {"list": format_tab_sections(items)}}
    except Exception as e:
        logger.error(f"Nollywood error: {e}")
        return {"code": 1, "message": str(e), "data": {"list": []}}

@app.get("/tab/{tab_id}")
def get_tab_generic(tab_id: int, page: int = 1, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    try:
        res = s["content"].get_categories(category_id=tab_id, page=page)
        data = res.get("data") or {}
        items = data.get("list") or data.get("items") or data.get("subjects") or []
        return {"code": 0, "data": {"list": format_tab_sections(items)}}
    except Exception as e:
        logger.error(f"Generic Tab {tab_id} error: {e}")
        return {"code": 1, "message": str(e), "data": {"list": []}}

@app.get("/game")
def get_game(page: int = 1, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    try:
        # Reverting to tab-operating GET vertical for tabId 11
        res = s["content"].get_categories(category_id=11, page=page)
        if not isinstance(res, dict):
             return {"code": 1, "message": "Invalid response format", "data": {"list": []}}
             
        data = res.get("data") or {}
        items = data.get("list") or data.get("items") or data.get("subjects") or []
        
        if items:
            sample = items[0].get('name') or items[0].get('title') or "Unknown"
            logger.info(f"Game Detection: First item is '{sample}'")
            
        return {"code": 0, "data": {"list": format_tab_sections(items)}}
    except Exception as e:
        logger.error(f"Game error: {e}")
        return {"code": 1, "message": str(e), "data": {"list": []}}

@app.get("/search-suggestions")
def get_search_suggestions(response: Response, q: Optional[str] = None, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    # Ensure session persists even for guest GET requests
    response.set_cookie(key="session_id", value=s["id"], httponly=True, samesite="lax")
    try:
        if q:
            # If there's a query, use actual search logic for autocomplete suggestions
            res = s["content"].search(q, page=1)
            data = res.get("data", {})
            items = data.get("list") or data.get("items") or data.get("movie") or data.get("subjects") or []
        else:
            res = s["content"].get_search_suggestions()
            data = res.get("data") if isinstance(res, dict) else {}
            if not isinstance(data, dict):
                return {"code": 0, "data": []}
            items = data.get("list") or data.get("items") or data.get("movie") or data.get("subjects") or []
            
        suggestions = []
        for i in items:
            if isinstance(i, str):
                suggestions.append(i)
            elif isinstance(i, dict):
                suggestions.append(i.get("keyword") or i.get("title") or i.get("name"))
        
        return {"code": 0, "data": [s for s in suggestions if s]}
    except Exception as e:
        logger.error(f"Search suggestions error: {e}")
        return {"code": 0, "data": []}

@app.get("/search")
def search(response: Response, q: str, page: int = 1, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    response.set_cookie(key="session_id", value=s["id"], httponly=True, samesite="lax")
    try:
        if not q.strip():
            return {"code": 0, "data": {"items": []}}
        res = s["content"].search(q.strip(), page=page)
        items = _extract_items(res)
        logger.info("Search for %r returned %d items", q, len(items))
        return {"code": 0, "data": {"items": [map_item(i) for i in items[:40] if isinstance(i, dict)]}}
    except Exception:
        logger.exception("Search upstream error for %s", q)
        raise HTTPException(status_code=502, detail="MovieBox search is unavailable. Please retry.")

@app.get("/rooms/recommend")
def get_rooms(page: int = 1, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    try:
        res = s["content"].get_rooms(page=page)
        data = res.get("data") or {}
        items = data.get("list") or data.get("items") or []
        return {"code": 0, "data": [map_room(r) for r in items]}
    except Exception as e:
        logger.error(f"Rooms error: {e}")
        return {"code": 1, "data": []}

@app.get("/rooms/{room_id}")
def get_room_detail(room_id: str, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    try:
        res = s["content"].get_room_detail(room_id)
        data = res.get("data") or {}
        return {"code": 0, "data": map_room(data)}
    except Exception as e:
        logger.error(f"Room detail error: {e}")
        return {"code": 1, "data": {}}

@app.get("/sports/live")
def get_sports_live(session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    try:
        # 1. Fetch live channels from MovieBox BFF
        res = s["content"].get_live_channels()
        data = res.get("data") or {}
        items = data.get("list") or data.get("items") or []
        channels = [map_item(c) for c in items]
        
        # 2. Append the specialized external aggregator link
        # This is for sportslivetoday integration
        channels.append({
            "id": "external_sports_aggregator",
            "title": "Live Sports Aggregator (Cricket/Football)",
            "name": "Live Sports Today",
            "type": "external_web",
            "url": "https://sportslivetoday.com/live/detail?id=3552262265162844888&sportType=cricket",
            "cover": "https://img.icons8.com/color/48/000000/cricket.png",
            "tag": "LIVE"
        })
        
        return {"code": 0, "data": channels}
    except Exception as e:
        logger.error(f"Sports live error: {e}")
        return {"code": 1, "data": []}

@app.get("/detail/{subject_id}")
def get_detail(subject_id: str, response: Response, depth: int = 0, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    response.set_cookie(key="session_id", value=s["id"], httponly=True, samesite="lax")
    try:
        res = s["content"].get_movie_detail(subject_id)
    except Exception:
        logger.exception("Detail upstream error for %s", subject_id)
        raise HTTPException(status_code=502, detail="MovieBox details are unavailable. Please retry.")
    if not isinstance(res, dict):
        raise HTTPException(status_code=502, detail="The provider returned an invalid detail response.")
    data = res.get("data", {})
    if not isinstance(data, dict):
        data = {}
    
    is_collection = False
    items = []

    # 1. Check if it's a category ID (If standard detail fails or has no title)
    if not data or not (data.get("title") or data.get("name")):
        try:
           # Try as post detail first
           post_res = s["client"].request("GET", "/wefeed-mobile-bff/post/get", params={"postId": subject_id})
           post_data = post_res.get("data", {})
           if post_data and post_data.get("postId"):
               media = post_data.get("media") or {}
               video_url = ""
               if media.get("mediaType") == "VIDEO":
                   video_list = media.get("video") or []
                   if video_list and len(video_list) > 0:
                       video_url = video_list[0].get("url") or video_list[0].get("playUrl") or ""
                   elif media.get("url"):
                       video_url = media.get("url")
                       
               # Synthesize a "Movie" object for the post
               data = {
                   "subjectId": subject_id,
                   "title": "Community Post",
                   "description": post_data.get("content") or post_data.get("text") or "",
                   "isPost": True,
                   "streamUrl": video_url,
                   "poster": media.get("cover", {}).get("url") if isinstance(media.get("cover"), dict) else None,
                   "source": f"@{post_data.get('user', {}).get('nickname', 'User')}"
               }
        except: pass

        if not data or not (data.get("title") or data.get("name")):
            try:
               cat_res = s["client"].request(
                   "POST",
                   "/wefeed-mobile-bff/subject-api/genre-top",
                   data={"type": subject_id, "page": 1, "perPage": 50}
               )
               cat_data = cat_res.get("data", {})
               desc = cat_data.get("desc") or "Curated Collection"
               items = cat_data.get("list") or cat_data.get("items") or cat_data.get("subjects") or []
               
               # Unbox nested info objects from genre-top response
               raw_items = [i.get("info") if isinstance(i, dict) and i.get("info") else i for i in items]
               mapped_items = [map_item(m) for m in raw_items]
               if mapped_items:
                   items = mapped_items  # update outer items variable for collectionItems
                   is_collection = True
                   # Synthesize a "Movie" object for the collection
                   data = {
                       "subjectId": subject_id,
                       "title": desc,
                       "isCollection": True,
                       "items": mapped_items
                   }
            except: pass

    if not data: return {"code": 1, "msg": "Not found"}
    
    # Logic to force status sync: Check the actual list if cloud detail is stale
    is_fav = False
    if not s["auth"].is_guest_mode:
        try:
            wl_res = s["user"].get_watchlist(page=1, per_page=50) # Check first page of favorites
            wl_items = wl_res.get("data", {}).get("items") or wl_res.get("data", {}).get("list") or []
            for item in wl_items:
                if str(item.get("subject_id") or item.get("id") or item.get("subjectId")) == str(subject_id):
                    is_fav = True
                    break
        except: pass

    if is_fav:
        data["isFavorite"] = 1 # Force it

    # Existing field cross-check
    status_fields = ["isFavorite", "is_favorite", "fav", "is_fav", "collected", "isLike", "wantToSee", "likeStatus"]
    for f in status_fields:
        if f in res and f not in data:
            data[f] = res[f]

    # Fetch Community Post Count. The Android DTO models this as String?; cast
    # here so a numeric provider value cannot break detail deserialization.
    try:
        post_res = s["client"].request("GET", "/wefeed-mobile-bff/post/count/subject", params={"subjectId": subject_id})
        post_count = (post_res.get("data") or {}).get("count")
        data["postCount"] = str(post_count) if post_count is not None else "0"
    except Exception:
        data["postCount"] = "0"

    mapped = map_item(data, depth=depth)
    mapped["postCount"] = str(data.get("postCount", "0"))
    mapped["isCollection"] = is_collection
    
    # Restore synthesized post fields that map_item might have overwritten
    if data.get("isPost"):
        mapped["isPost"] = True
        mapped["streamUrl"] = data.get("streamUrl")
        mapped["source"] = data.get("source")
    
    if is_collection and depth == 0:
        mapped["collectionItems"] = [map_item(i, depth=depth+1) for i in items[:24]]
        # Use first item's poster as collection poster
        if items and not mapped.get("poster"):
            first = map_item(items[0], depth=depth+1)
            mapped["poster"] = first.get("poster")
            mapped["cover"] = first.get("cover")

    raw_cast = data.get("staffList") or data.get("actorList") or []
    mapped["cast"] = [map_actor(a) for a in raw_cast]
    
    # Fetch Available Languages / Dubs (Consolidated)
    all_languages = []
    
    # 1. Check for Dubs list (Versions linked via different Subject IDs).
    # Provider IDs exceed 32-bit range, so always emit strings for the Android DTO.
    raw_dubs = data.get("dubs") or []
    if isinstance(raw_dubs, list):
        for dub in raw_dubs:
            if not isinstance(dub, dict):
                continue
            dub_subject_id = dub.get("subjectId")
            all_languages.append({
                "id": None,
                "subjectId": str(dub_subject_id) if dub_subject_id is not None else None,
                "name": dub.get("lanName") or "Custom Dub",
                "type": "dub"
            })

    # 2. Check for Resource Detectors (Tracks within the same/similar ID).
    try:
        det_res = s["client"].request('GET', '/wefeed-mobile-bff/subject-api/get', params={'subjectId': subject_id})
        det_data = det_res.get('data') if isinstance(det_res, dict) else {}
        detectors = (det_data or {}).get('resourceDetectors') or []
        if isinstance(detectors, list):
            for d in detectors:
                if not isinstance(d, dict):
                    continue
                resource_id = d.get("resourceId")
                all_languages.append({
                    "id": str(resource_id) if resource_id is not None else None,
                    "subjectId": str(subject_id),
                    "name": d.get("name") or "Resource",
                    "type": "resource"
                })
    except Exception:
        pass

    mapped["languages"] = all_languages
    return {"code": 0, "data": mapped}

@app.get("/episodes/{series_id}")
def get_episodes(series_id: str, response: Response, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    response.set_cookie(key="session_id", value=s["id"], httponly=True, samesite="lax")
    try:
        res = s["content"].get_episode_list(series_id)
    except Exception:
        logger.exception("Episodes upstream error for %s", series_id)
        raise HTTPException(status_code=502, detail="Episodes are unavailable. Please retry.")

    if not isinstance(res, dict):
        raise HTTPException(status_code=502, detail="The provider returned an invalid episodes response.")

    data = res.get("data") or {}
    if not isinstance(data, dict):
        data = {}
    # Handle data wrapper vs top-level.
    raw_seasons = data.get("seasons") or res.get("seasons") or []
    if not raw_seasons:
        raw_seasons = data.get("seasonList") or data.get("list") or []
    if not isinstance(raw_seasons, list):
        raw_seasons = []
    logger.info("Episodes for %s: %d seasons", series_id, len(raw_seasons))

    provider_code = res.get("code")
    if provider_code not in (None, 0, 200) and not raw_seasons:
        logger.error("Episodes provider failure for %s: code=%s", series_id, provider_code)
        raise HTTPException(status_code=502, detail="The provider could not load episodes. Please retry.")

    mapped_seasons = []
    for s_raw in raw_seasons:
        if not isinstance(s_raw, dict):
            continue
        raw_num = s_raw.get("se") or s_raw.get("seasonNumber") or 1
        try:
            num = int(raw_num)
        except (TypeError, ValueError):
            num = 1

        eps = []
        seen_episode_numbers = set()

        def add_episode(raw_number, title=None, episode_id=None):
            if raw_number is None:
                return
            ep_number = str(raw_number).strip()
            if not ep_number or ep_number in seen_episode_numbers:
                return
            seen_episode_numbers.add(ep_number)
            eps.append({
                "episodeNumber": ep_number,
                "title": title or f"Episode {ep_number}",
                "id": episode_id or f"{series_id}_{num}_{ep_number}",
            })

        # Check all possible list/string fields for episodes.
        for key in ["allEp", "epList", "episodeList", "episodes", "list", "items"]:
            pool = s_raw.get(key)
            if not pool:
                continue

            if isinstance(pool, str):
                for e_num in pool.split(","):
                    add_episode(e_num)
            elif isinstance(pool, list):
                for item in pool:
                    if isinstance(item, dict):
                        en = item.get("ep") or item.get("episodeNumber") or item.get("episode_number")
                        add_episode(en, item.get("title"))
                    else:
                        add_episode(item)

            if eps:
                break  # Stop once we found a pool.

        # FALLBACK: If no explicit pool found, but maxEp is set, generate sequence [1..maxEp].
        # This uses only provider metadata; it never invents episodes beyond maxEp.
        if not eps:
            max_ep = s_raw.get("maxEp") or s_raw.get("max_ep") or 0
            if isinstance(max_ep, str) and max_ep.isdigit():
                max_ep = int(max_ep)
            if max_ep and isinstance(max_ep, int):
                for i in range(1, max_ep + 1):
                    add_episode(i)

        if eps:
            mapped_seasons.append({"seasonNumber": num, "episodes": eps})
    return {"code": 0, "data": {"seasons": mapped_seasons}}

@app.get("/stream/{subject_id}")
def get_stream(subject_id: str, response: Response, season: int = 1, episode: int = 1, quality: Optional[str] = "720p", resource_id: Optional[str] = None, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    response.set_cookie(key="session_id", value=s["id"], httponly=True, samesite="lax")
    # 0. Detect subject type to avoid se/ep for movies
    try:
        subject_detail = s["content"].get_movie_detail(subject_id).get("data") or {}
        is_movie = (str(subject_detail.get("subjectType") or subject_detail.get("type")) == "1")
    except: is_movie = False

    logger.info(f"RESOLVING STREAM: ID={subject_id} Type={'Movie' if is_movie else 'Series'} Resource={resource_id}")
    
    # If it's a movie, se and ep MUST be None for the official play-info API to respond correctly in some regions
    res_se = None if is_movie else season
    res_ep = None if is_movie else episode

    res = s["stream"].get_play_info(subject_id, season=res_se, episode=res_ep, resource_id=resource_id)

    if not isinstance(res, dict):
        raise HTTPException(status_code=502, detail="The provider returned an invalid stream response.")
    data = res.get("data", {})
    if not isinstance(data, dict):
        data = {}
    primary_code = res.get("code")
    # Log a summary only. Raw play-info payloads contain signed CDN URLs.
    streams = data.get("streamList") or data.get("streams") or []
    if not isinstance(streams, list):
        streams = []
    logger.info("PLAY-INFO response: provider_code=%s streams=%s", primary_code, len(streams))
    
    # OVERRIDE DUMMY URL WITH URLPREFIX DECODE
    import base64
    import re
    for _st in streams:
        _sc = _st.get("signCookie", "")
        if "urlprefix=" in _sc:
            match = re.search(r'urlprefix=([^:]+)', _sc)
            if match:
                b64_prefix = match.group(1)
                b64_prefix += "=" * ((4 - len(b64_prefix) % 4) % 4)
                try:
                    prefix_url = base64.b64decode(b64_prefix).decode('utf-8')
                    if prefix_url.startswith("http"):
                        # MovieBox Dash/HLS streams use index.mpd for DASH streams
                        _st["url"] = prefix_url + "index.mpd"
                        logger.info("ANTI-PIRACY BYPASS: Decoded real URL -> %s", _safe_url_label(_st["url"]))
                except Exception as e:
                    logger.error(f"URLPREFIX decode error: {e}")
    
    # Fallback to resourceDetectors inside movie/show detail if no streams found in play-info
    if not streams:
        logger.info(f"No streams from play-info. Checking resourceDetectors for subject {subject_id}...")
        try:
            detectors = subject_detail.get("resourceDetectors") or []
            for det in detectors:
                if resource_id and str(det.get("resourceId")) != str(resource_id):
                    continue
                res_list = det.get("resolutionList") or []
                for res_item in res_list:
                    if not is_movie:
                        item_se = res_item.get("se") or res_item.get("season")
                        item_ep = res_item.get("ep") or res_item.get("episode")
                        if item_se is not None and item_ep is not None:
                            if int(item_se) != int(season) or int(item_ep) != int(episode):
                                continue
                    stream_url = res_item.get("resourceLink") or res_item.get("downloadUrl")
                    if stream_url:
                        streams.append({
                            "url": stream_url,
                            "quality": f"{res_item.get('resolution')}p" if res_item.get("resolution") else "Auto",
                            "signCookie": det.get("signCookie") or res_item.get("signCookie") or "",
                            "codec": res_item.get("codecName") or det.get("codecName") or "",
                            "id": res_item.get("resourceId") or det.get("resourceId") or "",
                            "duration": res_item.get("duration") or det.get("duration") or 0
                        })
            if streams:
                logger.info(f"Recovered {len(streams)} streams directly from details resourceDetectors.")
        except Exception as e:
            logger.error(f"Error extracting from resourceDetectors: {e}")
            
    # signCookie can be in root, in data, or in the session cookies
    # Never use the provider bearer token as a media cookie or return it to clients.
    global_cookie = res.get("signCookie") or data.get("signCookie") or s["client"].session.cookies.get("signCookie")
    logger.info(f"Phase 1 - Primary Result: {len(streams)} streams found (code: {res.get('code')}) (Cookie: {'YES' if global_cookie else 'NO'})")
    import requests
    
    # Silent Failover Logic - Mobile Handshake Enforcement
    working_stream = None
    working_cookie = None
    
    # Normalize quality for official API (expects lowercase)
    official_quality = quality.lower() if quality else "720p"
    
    # Try current stream list first (Strict Codec Enforcement)
    # Browsers generally FAIL to play H.265 (HEVC) or complex HEV1 DASH manifests.
    # We strictly prioritize H.264 (AVC) MP4 > HLS > (Anything else).
    
    def prioritize_h264(st):
        u = st.get("url", "").lower()
        if "h265" in u or "x265" in u or "hev1" in u: return 10 # Very low priority
        if ".mp4" in u: return 0  # Highest priority
        if ".m3u8" in u: return 1 # Good priority
        return 5 # DASH is risky but better than H265
    
    prioritized_streams = sorted(streams, key=prioritize_h264)

    # Pass 1: Try to find a playable non-HEVC stream
    for st in prioritized_streams:
        url = st.get("url")
        if not url: continue
        if any(bad in url.lower() for bad in ["h265", "x265", "hev1"]):
            continue
        cookie = st.get("signCookie") or global_cookie or ""
        try:
            head_res = requests.head(
                url,
                headers={"User-Agent": "ExoPlayerLib/2.18.7", "Cookie": cookie},
                timeout=3,
                allow_redirects=True,
            )
            if head_res.status_code in [200, 206, 302]:
                working_stream = st
                working_cookie = cookie
                break
        except: continue

    # Pass 2: Fallback to playable HEVC stream if no non-HEVC stream is available
    if not working_stream:
        for st in prioritized_streams:
            url = st.get("url")
            if not url: continue
            cookie = st.get("signCookie") or global_cookie or ""
            try:
                head_res = requests.head(
                    url,
                    headers={"User-Agent": "ExoPlayerLib/2.18.7", "Cookie": cookie},
                    timeout=3,
                    allow_redirects=True,
                )
                if head_res.status_code in [200, 206, 302]:
                    logger.info("Using fallback H265/HEVC stream: %s", _safe_url_label(url))
                    working_stream = st
                    working_cookie = cookie
                    break
            except: continue
    subtitles_source = data.get("subTitleList", [])
    
    # PROIRITY: Resource Mirrors (UGC/Dubs like eyosi_as_iam)
    if not working_stream:
        logger.info(f"Primary Cloud Offline for {subject_id}. Engaing Resource Mirror Rotation...")
        try:
            hdrs = {
                "User-Agent": "MovieBox/4.0.02 (Android 14; Pixel 6)",
                "X-M-Version": "4.0.02"
            }
            
            # PHASE 1: Resource Discovery via Metadata (High Parity with Subtitles)
            r_ids = []
            try:
                det_res = s["client"].request('GET', '/wefeed-mobile-bff/subject-api/get', params={'subjectId': subject_id}, headers=hdrs)
                logger.info(f"Metadata Probe Result: {det_res.get('code')} - {len(det_res.get('data', {}).get('resourceDetectors', []))} detectors found")
                detectors = (det_res.get('data') or {}).get('resourceDetectors') or []
                for d in detectors:
                    if d.get('resourceId'): r_ids.append(d.get('resourceId'))
            except Exception as ex: 
                logger.error(f"Metadata Probe Crash: {ex}")

            # PHASE 2: Fallback to See-List (UGC Discoverability)
            if not r_ids:
                try:
                    res_list = s["client"].request('GET', '/wefeed-mobile-bff/subject-api/see-list-v2', params={'subjectId': subject_id, 'page': 1, 'pageSize': 20, 'seeType': 1}, headers=hdrs)
                    logger.info(f"See-List Probe Result: {res_list.get('code')}")
                    r_items = (res_list.get('data') or {}).get('items') or []
                    for item in r_items:
                        if item.get('id'): r_ids.append(item.get('id'))
                except: pass

            # PHASE 3: Iterative Recovery
            for r_id in r_ids:
                logger.info(f"Probing Resource: {r_id} for {subject_id}")
                # USE BOTH FULL AND ABBREVIATED PARAMS
                # ALSO PROBE WITHOUT QUALITY (UGC often fails if quality mismatch)
                p_params = {
                    'subjectId': subject_id, 
                    'resourceId': r_id
                }
                if not is_movie:
                    p_params.update({'se': season, 'ep': episode, 'season': season, 'episode': episode})
                
                # First attempt: With quality
                q_params = p_params.copy()
                q_params['quality'] = official_quality
                p_info = s["client"].request('GET', '/wefeed-mobile-bff/subject-api/play-info', params=q_params, headers=hdrs)
                p_data = p_info.get('data') or {}
                p_streams = p_data.get('streamList') or p_data.get('streams') or []
                
                # Second attempt: Without quality (Discovery mode)
                if not p_streams:
                    logger.info(f"Retrying probe {r_id} without quality constraint...")
                    p_info = s["client"].request('GET', '/wefeed-mobile-bff/subject-api/play-info', params=p_params, headers=hdrs)
                    p_data = p_info.get('data') or {}
                    p_streams = p_data.get('streamList') or p_data.get('streams') or []

                logger.info(f"Probe {r_id} Result: {len(p_streams)} streams recovered")
                if p_streams:
                    working_stream = p_streams[0]
                    
                    # PROACTIVE PING: Force a session handshake with the media CDN
                    if "hakunaymatata" in working_stream.get("url", ""):
                        try:
                            import requests
                            logger.info("Ping session for CDN handshake: %s", _safe_url_label(working_stream.get("url", "")))
                            # Matches Android okhttp behavior: HEAD with timeout.
                            requests.head(
                                working_stream.get("url"),
                                headers={"User-Agent": "ExoPlayerLib/2.19.1"},
                                timeout=3,
                                cookies=s["client"].session.cookies,
                                allow_redirects=True,
                            )
                        except: pass

                    # DEEP CAPTURE: Check body, then individual stream, then session cookies, then fallback
                    working_cookie = (
                        p_info.get('signCookie') or 
                        p_data.get('signCookie') or 
                        working_stream.get('signCookie') or # EMBEDDED IN STREAM OBJECT (CRITICAL)
                        s["client"].session.cookies.get("signCookie") or 
                        global_cookie or ""
                    )
                    
                    # If still NO cookie for a protected CDN, we have a failover issue
                    if not working_cookie and "hakunaymatata" in working_stream.get("url", ""):
                        logger.warning("PROTECTED CDN DETECTED WITH NO COOKIE AFTER PING! Attempting session flush...")
                        working_cookie = s["client"].session.cookies.get("signCookie") or ""

                    subtitles_source = p_data.get("subTitleList", []) or subtitles_source
                    logger.info(f"RESOURCE MIRROR SUCCESS: Recovered stream via User-Resource {r_id} (Cookie: {'YES' if working_cookie else 'NO'})")
                    break
        except Exception as e:
            logger.warning(f"Resource Mirror Lookup Failed: {e}")

    # PROACTIVE AUTO-FAILOVER: Scan for HEVC/H.265 and trigger transcode 
    # Browser standard support is H.264 (AVC) and VP9/AV1.
    is_hevc = any("h265" in st.get("url", "").lower() or "x265" in st.get("url", "").lower() or "hev1" in st.get("url", "").lower() or "h.265" in st.get("url", "").lower() for st in streams)
    
    # We also check for 'hev1' codec signatures in manifest URLs if found
    h265_candidate = next((st for st in streams if any(bad in st.get("url", "").lower() for bad in ["h265", "x265", "hev1", "h.265"])), None)
    
    if is_hevc and h265_candidate:
        logger.info(f"Detected HEVC/H.265. Directly serving RAW stream for Native Player support...")
        working_stream = h265_candidate
        working_cookie = working_stream.get("signCookie") or global_cookie or ""

    # SECONDARY FALLBACK: API Cluster Rotation
    if not working_stream:
        logger.info(f"Entering Phase 4 Cluster Rotation for {subject_id}")
        
        clusters = [
            ("https://apig.inmoviebox.com", "/wefeed-mobile-bff/subject-api/play-info"),
            ("https://api5.aoneroom.com", "/wefeed-mobile-bff/subject-api/play-info"),
            ("https://api-sin.aoneroom.com", "/wefeed-mobile-bff/subject-api/play-info"),
            ("https://v-ios.aoneroom.com", "/wefeed-mobile-bff/subject-api/play-info"),
            ("https://h5-api.aoneroom.com", "/wefeed-h5api-bff/subject/detail-rec"),
            ("https://h5.aoneroom.com", "/index/video/v_detail")
        ]
        
        hdrs = {
            "User-Agent": "MovieBox/4.0.02 (Android 14; Pixel 6)",
            "X-M-Version": "4.0.02"
        }
        
        orig_base = s["client"].BASE_URL
        for cluster_url, endpoint in clusters:
            try:
                logger.info(f"ROTATING CLUSTER: {cluster_url} -> {endpoint}")
                s["client"].BASE_URL = cluster_url
                
                # Standard params
                pms = {'subjectId': subject_id, 'se': season, 'ep': episode, 'quality': official_quality}
                if "detail-rec" in endpoint:
                    pms["perPage"] = 12
                    pms["page"] = 1
                
                method = 'POST' if "v_detail" in endpoint else 'GET'
                
                fb_res = s["client"].request(method, endpoint, params=pms if method=='GET' else None, data=pms if method=='POST' else None, headers=hdrs)
                if not isinstance(fb_res, dict):
                    logger.warning(f"Cluster {cluster_url} returned invalid response type: {type(fb_res)}")
                    continue
                fb_data = fb_res.get('data', {})
                srcs = fb_data.get('streamList') or fb_data.get('streams') or []
                
                if not srcs and "detail-rec" in endpoint:
                    items = fb_data.get('items') or []
                    if items:
                         # Try to find a playable resource in items
                         logger.info(f"H5 Detail contains {len(items)} sibling items, probing first...")
                
                logger.info(f"Cluster {cluster_url} Result: {len(srcs)} streams found")
                
                # Handshake validation for any candidate found
                if not srcs and fb_data.get('url'): srcs = [fb_data]
                
                for cand in srcs:
                    url = cand.get('url')
                    if not url: continue
                    cookie = cand.get('signCookie') or fb_res.get('signCookie') or fb_data.get('signCookie') or s["client"].session.cookies.get("signCookie") or global_cookie or ""
                    try:
                        v_res = requests.head(
                            url,
                            headers={"User-Agent": "ExoPlayerLib/2.18.7", "Cookie": cookie},
                            timeout=3,
                            allow_redirects=True,
                        )
                        if v_res.status_code in [200, 206]:
                            working_stream = cand
                            working_cookie = cookie
                            subtitles_source = fb_data.get("subTitleList", []) or subtitles_source
                            logger.info(f"CLUSTER SUCCESS: Recovered stream via {cluster_url} Cluster")
                            break
                    except: continue
                if working_stream: break
            except Exception as e:
                logger.warning(f"Cluster {cluster_url} failed: {e}")
            finally:
                s["client"].BASE_URL = orig_base
    
    if not working_stream and streams:
        working_stream = streams[0]
        working_cookie = working_stream.get("signCookie") or global_cookie or ""

    if not working_stream:
        logger.error(
            "RESOLUTION FAILURE: No usable streams found for subject %s (provider code=%s)",
            subject_id,
            primary_code,
        )
        if primary_code == 429:
            raise HTTPException(status_code=503, detail="The provider is rate-limiting stream requests. Please retry shortly.")
        if primary_code in (401, 403):
            raise HTTPException(status_code=502, detail="The provider rejected the stream session. Please retry.")
        raise HTTPException(status_code=404, detail="No streams found for this title.")

    # CALCULATE METADATA DURATION FOR FRONTEND OVERRIDE
    total_duration = 0
    source_method = "metadata"
    try:
        subject_detail = s["content"].get_movie_detail(subject_id).get("data") or {}
        runtime_str = subject_detail.get("runtime") or subject_detail.get("duration") or subject_detail.get("totalDuration") or "0"
        
        # Deep probe for episode-specific runtime if possible (Strict String Comparison)
        for sl in subject_detail.get("seasonList", []):
            if str(sl.get("season")) == str(season):
                for ep_item in sl.get("episodeList", []):
                    if str(ep_item.get("episode")) == str(episode):
                        runtime_str = ep_item.get("runtime") or ep_item.get("duration") or ep_item.get("totalDuration") or runtime_str
                        break
        
        runtime_str = str(runtime_str)
        if ":" in runtime_str:
            parts = [int(p) for p in runtime_str.split(':')]
            if len(parts) == 3: total_duration = parts[0] * 3600 + parts[1] * 60 + parts[2]
            elif len(parts) == 2: total_duration = parts[0] * 60 + parts[1]
        else:
            total_duration = int(''.join(filter(str.isdigit, runtime_str))) * 60
    except Exception as e:
        logger.error(f"Metadata duration extraction failed: {e}")
    
    # ADVANCED PROBE: Native HLS Parsing (Fast & Accurate for .m3u8)
    if (total_duration < 600 or total_duration > 15000) and working_stream.get("url", "").lower().endswith(".m3u8"):
        try:
           target_v = working_stream.get("url")
           logger.info("Metadata suspicious. Engaging HLS Parse for %s", _safe_url_label(target_v))
           h_res = httpx.get(
               target_v,
               headers={"User-Agent": "ExoPlayerLib/2.18.7", "Cookie": working_cookie},
               timeout=5,
               follow_redirects=True,
           )
           if h_res.status_code == 200:
              lines = h_res.text.splitlines()
              h_dur = sum(float(line.split(":")[1].split(",")[0]) for line in lines if line.startswith("#EXTINF:"))
              if h_dur > 0:
                 total_duration = int(h_dur)
                 source_method = "hls-parse"
        except Exception as he:
           logger.warning(f"HLS duration parse failed: {he}")

    # ADVANCED PROBE: ffprobe for absolute accuracy for MP4/Others
    if source_method == "metadata" and (total_duration < 600 or total_duration > 15000):
        try:
           target_v = working_stream.get("url")
           if target_v:
              logger.info("Metadata suspicious (%ss). Engaging FFPROBE for %s", total_duration, _safe_url_label(target_v))
              ff_cmd = [
                  'ffprobe', '-v', 'error', '-show_entries', 'format=duration',
                  '-of', 'default=noprint_wrappers=1:nokey=1',
                  '-headers', f'Cookie: {working_cookie}\r\nUser-Agent: ExoPlayerLib/2.18.7\r\n',
                  target_v
              ]
              ff_proc = subprocess.run(ff_cmd, capture_output=True, text=True, timeout=5)
              if ff_proc.returncode == 0 and ff_proc.stdout.strip():
                  f_dur = float(ff_proc.stdout.strip())
                  if f_dur > 0:
                      total_duration = int(f_dur)
                      source_method = "ffprobe"
        except Exception as fe:
           logger.warning(f"FFPROBE duration probe failed: {fe}")

    if total_duration < 60: # Final fallback
        total_duration = 3600 # 1 hour default

    logger.info(f"DURATION LOCK [{source_method}]: {subject_id} S{season}E{episode} -> {total_duration}s (Source: {runtime_str})")

    # SUBTITLE DISCOVERY: Combine stream-internal and external BFF captions
    all_subtitles = subtitles_source
    try:
        ext_hdrs = {"User-Agent": "Dalvik/2.1.0 (Linux; U; Android 13; SM-S918B Build/TP1A.220624.014)", "X-M-Version": "11.7.0"}
        # Resolve Resource ID for External Subtitles
        sub_resource_id = subject_id
        try:
            sd_res = s["client"].request('GET', '/wefeed-mobile-bff/subject-api/get', params={'subjectId': subject_id}, headers=ext_hdrs)
            sd_detectors = sd_res.get('data', {}).get('resourceDetectors', [])
            if sd_detectors:
                sub_resource_id = sd_detectors[0].get('resourceId') or subject_id
        except: pass
        
        ext_res = s["client"].request('GET', '/wefeed-mobile-bff/subject-api/get-ext-captions', 
                                    params={'resourceId': sub_resource_id, 'subjectId': subject_id, 'episode': episode}, 
                                    headers=ext_hdrs)
        ext_list = ext_res.get("data", {}).get("extCaptions") or ext_res.get("data", {}).get("list") or []
        for esub in ext_list:
            if not any(x.get("url") == esub.get("url") for x in all_subtitles):
                all_subtitles.append(esub)
    except Exception as se:
        logger.warning(f"External subtitle probe failed: {se}")

    all_subtitles = _normalize_subtitles(all_subtitles)

    # PICK BEST SUBTITLE FOR MPV (Default to English if found)
    best_sub = next((s.get("url") for s in all_subtitles if s.get("lan") == "en" or "english" in (s.get("lanName") or "").lower()), None)

    # DIRECT NATIVE LINK (No Proxy)
    return {
        "url": working_stream.get("url", ""),
        "cookie": working_cookie,
        "duration": total_duration,
        "subtitles": all_subtitles,
        "subtitle_url": best_sub,
        "isHls": working_stream.get("url", "").lower().endswith(".m3u8") or ".m3u8" in working_stream.get("url", "").lower(),
        "streamId": working_stream.get("id"),
        "qualityList": list({st.get("quality") for st in streams if isinstance(st, dict) and st.get("quality")}),
        "episode": episode,
        "season": season
    }

import os

@app.get("/download/{subject_id}")
async def proxy_download(
    request: Request,
    subject_id: str, 
    season: int = 1, 
    episode: int = 1, 
    quality: str = "720p", 
    title: str = "Movie",
    session_id: Optional[str] = Cookie(None)
):
    s = get_session(session_id)
    
    # PRIORITY 1: Carrier 301 (Legacy Mirror) specifically for raw MP4s
    logger.info(f"Targeting Carrier 301 Raw Mirrors for {subject_id}...")
    c_res = s["client"].request('POST', '/index/video/v_detail', data={'subjectId': subject_id, 'carrier': '301', 'quality': quality})
    data = c_res.get("data")
    if not isinstance(data, dict): data = {}
    streams = data.get("streamList") or data.get("streams") or []
    if not isinstance(streams, list): streams = []

    # PRIORITY 2: Mobile Protocol (DASH/HLS) if MP4 is missing
    if not streams:
        res = s["stream"].get_play_info(subject_id, season=season, episode=episode)
        data = res.get("data", {}) if isinstance(res, dict) else {}
        if not isinstance(data, dict): data = {}
        streams = data.get("streamList") or data.get("streams") or []
        if not isinstance(streams, list): streams = []

    if not streams:
        raise HTTPException(status_code=404, detail="No downloadable mirrors found")

    match = None
    for st in streams:
        if isinstance(st, dict) and ".mp4" in str(st.get("url", "")).lower():
            match = st
            break
    if not match: match = next((st for st in streams if isinstance(st, dict)), None)
    if not match:
        raise HTTPException(status_code=404, detail="No downloadable mirrors found")

    url = match.get("url")
    cookie = data.get("signCookie") or match.get("signCookie") or ""
    if not url:
        raise HTTPException(status_code=404, detail="No downloadable mirrors found")
    if not str(url).lower().startswith(("http://", "https://")):
        raise HTTPException(status_code=502, detail="The provider returned an invalid download URL.")

    clean_title = "".join(c for c in title if c.isalnum() or c in (' ', '-', '_')).strip()

    # CASE 1: Native MP4 stream (Fully seekable via HTTPX byte ranges)
    if ".mp4" in str(url).lower():
        req_hdrs = {"User-Agent": "ExoPlayerLib/2.18.7", "Cookie": cookie}
        range_header = request.headers.get("Range")
        if range_header: req_hdrs["Range"] = range_header

        client = httpx.AsyncClient(
            timeout=httpx.Timeout(15.0, read=None),
            follow_redirects=True,
        )
        try:
            req = client.build_request("GET", url, headers=req_hdrs)
            r = await client.send(req, stream=True)
        except Exception:
            await client.aclose()
            logger.exception("Download upstream request failed for %s", subject_id)
            raise HTTPException(status_code=502, detail="The media CDN is unavailable. Please retry.")

        if r.status_code >= 400:
            await r.aclose()
            await client.aclose()
            raise HTTPException(status_code=502, detail="The media CDN rejected the download request.")

        filename = f"{clean_title}_S{season}_E{episode}.mp4"
        headers = {
            "Content-Disposition": f"attachment; filename=\"{filename}\"",
            "Accept-Ranges": r.headers.get("Accept-Ranges", "bytes"),
            "Content-Length": r.headers.get("Content-Length", ""),
            "Content-Range": r.headers.get("Content-Range", ""),
            "Content-Type": "video/mp4"
        }

        async def stream_generator():
            try:
                async for chunk in r.aiter_bytes(chunk_size=1024 * 1024):
                    yield chunk
            finally:
                await r.aclose()
                await client.aclose()

        return StreamingResponse(
            stream_generator(),
            status_code=r.status_code,
            headers={k: v for k, v in headers.items() if v},
        )

    # CASE 2: DASH (.mpd) or HLS (.m3u8) needs FFMPEG demuxing
    # We pipe as MPEG-TS (.ts) because it is inherently seekable without needing a faststart moov atom!
    if not shutil.which("ffmpeg"):
        raise HTTPException(status_code=503, detail="FFmpeg is not installed on this server; this format cannot be downloaded.")
    filename = f"{clean_title}_S{season}_E{episode}.ts"

    def iter_ffmpeg():
        cmd = [
            'ffmpeg', '-y', '-hide_banner', '-loglevel', 'error',
            '-headers', f'Cookie: {cookie}\r\nUser-Agent: ExoPlayerLib/2.18.7\r\n',
            '-i', url,
            '-c', 'copy', '-f', 'mpegts', '-'
        ]
        process = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        try:
            while True:
                chunk = process.stdout.read(2048 * 1024)
                if not chunk: break
                yield chunk
        finally:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()

    return StreamingResponse(
        iter_ffmpeg(), 
        media_type="video/mp2t",
        headers={
            "Content-Disposition": f"attachment; filename=\"{filename}\""
        }
    )
@app.get("/subtitles/search")
def subtitle_search(query: str, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    try:
        hdrs = {"User-Agent": "Dalvik/2.1.0 (Linux; U; Android 13; SM-S918B Build/TP1A.220624.014)", "X-M-Version": "11.7.0"}
        # Parameter 'q' is mandatory for v11.7.0 BFF search
        res = s["client"].request('GET', '/wefeed-mobile-bff/subject-api/subtitle-search', params={'q': query}, headers=hdrs)
        if not isinstance(res, dict):
            raise ValueError("provider returned a non-object response")
        if res.get("code") not in (None, 0, 200):
            raise ValueError(f"provider returned code {res.get('code')}")
        data = res.get("data", {})
        if not isinstance(data, dict): data = {}
        ls = data.get("items") or data.get("list") or []
        return {"code": 0, "data": {"list": _normalize_subtitles(ls)}}
    except Exception:
        logger.exception("Subtitle search upstream error for %s", query)
        raise HTTPException(status_code=502, detail="Subtitle search is unavailable. Please retry.")


@app.get("/subtitles/{subject_id}")
def get_subtitles(subject_id: str, response: Response, se: int = 1, ep: int = 1, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    response.set_cookie(key="session_id", value=s["id"], httponly=True, samesite="lax")
    try:
        hdrs = {"User-Agent": "Dalvik/2.1.0 (Linux; U; Android 13; SM-S918B Build/TP1A.220624.014)", "X-M-Version": "11.7.0"}

        # Resolve actual resourceId for content mapping parity
        resource_id = subject_id
        try:
            det_res = s["client"].request('GET', '/wefeed-mobile-bff/subject-api/get', params={'subjectId': subject_id}, headers=hdrs)
            det_data = det_res.get('data') if isinstance(det_res, dict) else {}
            detectors = (det_data or {}).get('resourceDetectors') or []
            if isinstance(detectors, list) and detectors and isinstance(detectors[0], dict):
                resource_id = detectors[0].get('resourceId') or subject_id
        except Exception:
            pass

        # Fetch using high-parity external captions endpoint
        res = s["client"].request('GET', '/wefeed-mobile-bff/subject-api/get-ext-captions',
                                    params={'resourceId': resource_id, 'subjectId': subject_id, 'episode': ep},
                                    headers=hdrs)
        if not isinstance(res, dict):
            raise ValueError("provider returned a non-object response")
        if res.get("code") not in (None, 0, 200):
            raise ValueError(f"provider returned code {res.get('code')}")
        data = res.get("data", {})
        if not isinstance(data, dict): data = {}
        ls = data.get("extCaptions") or data.get("list") or []
        return {"code": 0, "data": {"list": _normalize_subtitles(ls)}}
    except Exception:
        logger.exception("Subtitles upstream error for %s", subject_id)
        raise HTTPException(status_code=502, detail="Subtitles are unavailable. Please retry.")

# --- NEW: Cloud Sync & Advanced Subtitles ---

@app.get("/history/position")
def get_history_position(subject_id: str, resource_id: str, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    res = s["client"].request('GET', '/wefeed-mobile-bff/subject-api/resource-position', params={'subjectId': subject_id, 'resourceId': resource_id})
    return res

@app.post("/history/position")
def save_history_position(subject_id: str, resource_id: str, position: int, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    res = s["client"].request('POST', '/wefeed-mobile-bff/subject-api/resource-position', data={'subjectId': subject_id, 'resourceId': resource_id, 'position': position})
    return res

@app.post("/history/seen")
def mark_have_seen(subject_id: str = None, progress: int = 0, total: int = 0, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    if not subject_id: return {"code": -1, "msg": "Missing ID"}
    res = s["user"].report_history(subject_id, progress, total)
    return res
@app.post("/analytics/operation")
def track_operation(action: str, target: str, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    res = s["client"].request('POST', '/wefeed-mobile-bff/statistics/user-operation', data={'action': action, 'target': target})
    return res

@app.get("/history")
def get_history(page: int = 1, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    h = load_local_history()
    
    # Merge both guest/default history and user-specific history
    default_history = h.get("default", [])
    user_specific = h.get(session_id, []) if session_id else []
    blacklist = set(h.get("blacklist", []))
    
    # Combine uniquely by subjectId
    combined_history_dict = {str(x.get("subjectId") or x.get("id")): x for x in default_history + user_specific}
    user_history = list(combined_history_dict.values())
    
    if not s["auth"].is_guest_mode:
        try:
            res = s["user"].get_history(page=page)
            data = res.get("data", {})
            if not isinstance(data, dict): data = {}
            cloud_list = data.get("items") or data.get("list") or []
            
            seen_ids = set(combined_history_dict.keys())
            for c in cloud_list:
                sid_str = str(c.get("subjectId"))
                # CRITICAL FIX: Only add from cloud if NOT in our local blacklist
                if sid_str not in seen_ids and sid_str not in blacklist:
                    mapped = map_item(c)
                    mapped["seeTime"] = c.get("seeTime") or c.get("updateTime") or c.get("progress") or 0
                    mapped["subjectId"] = c.get("subjectId")
                    mapped["id"] = c.get("subjectId")
                    user_history.append(mapped)
        except Exception as e:
            pass
        
    user_history = [x for x in user_history if x.get("subjectId") and str(x.get("subjectId")) != "None"]
    # AND filter out anything in blacklist again to be 100% sure
    user_history = [x for x in user_history if str(x.get("subjectId")) not in blacklist]
    
    # Sort history by seeTime/updateTime descending
    user_history.sort(key=lambda x: int(x.get("seeTime", 0) or 0), reverse=True)
    
    return {"code": 0, "data": {"list": user_history}}


@app.get("/watchlist")
def get_watchlist(page: int = 1, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    if s["auth"].is_guest_mode:
        return {"code": 0, "data": {"list": []}}
    try:
        res = s["user"].get_watchlist(page=page)
        data = res.get("data", {})
        if not isinstance(data, dict): data = {}
        cloud_list = data.get("items") or data.get("list") or []
        return {"code": 0, "data": {"list": [map_item(x) for x in cloud_list]}}
    except:
        return {"code": 0, "data": {"list": []}}

@app.post("/history/delete/{subject_id}")
def delete_history_item(subject_id: str, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    if s["auth"].is_guest_mode:
        raise HTTPException(status_code=401, detail="Please Sign In to manage your history.")
    res = s["user"].report_history(subject_id, 0, 0, status=0) 
    return {"status": "success", "raw": res}


@app.post("/watchlist/toggle")
def toggle_watchlist(subject_id: str, active: bool, subject_type: int = 1, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    if s["auth"].is_guest_mode:
        raise HTTPException(status_code=401, detail="Please Sign In to manage your watchlist.")
    action = 1 if active else 2
    res = s["user"].toggle_watchlist(subject_id, action=action, subject_type=subject_type)
    return {"status": "success", "raw": res}

class ProgressReport(BaseModel):
    subject_id: str
    progress_ms: int
    total_ms: int
    status: int = 1

@app.post("/history/progress")
def report_progress(req: ProgressReport, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    if s["auth"].is_guest_mode:
        return {"status": "success"}
    return s["user"].report_history(req.subject_id, req.progress_ms, req.total_ms, req.status)

@app.get("/post/count/{subject_id}")
def get_post_count(subject_id: str, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    try:
        res = s["client"].request("GET", "/wefeed-mobile-bff/post/count/subject", params={"subjectId": subject_id})
        count = res.get("data", {}).get("count") or "0"
        return {"code": 0, "count": count}
    except:
        return {"code": 0, "count": "0"}

@app.get("/groups/trending")
def get_trending_groups(session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    try:
        res = s["client"].request("POST", "/wefeed-mobile-bff/group/list/trending-entrance", data={})
        data = res.get("data", {})
        items = data.get("items") or []
        return {"code": 0, "data": items}
    except:
        return {"code": 0, "data": []}

@app.post("/post/like")
def like_post(post_id: str, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    try:
        res = s["client"].request("POST", "/wefeed-mobile-bff/interactive/post/like", data={"postId": post_id})
        return res
    except Exception as e:
        return {"code": 1, "msg": str(e)}

@app.post("/post/create")
def create_post(subject_id: str, content: str, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    try:
        # /post/create logic from Smali
        data = {
            "subjectId": subject_id,
            "content": content,
            "type": "1" # Public post
        }
        res = s["client"].request("POST", "/wefeed-mobile-bff/post/create", data=data)
        return res
    except Exception as e:
        return {"code": 1, "msg": str(e)}

@app.get("/groups/interactive")
def get_interactive_posts(session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    try:
        # Pull from public interactive ranking feed
        res = s["client"].request("POST", "/wefeed-mobile-bff/interactive/post/list", 
                                 data={"page": 1, "pageSize": 20})
        return res
    except Exception as e:
        return {"code": 1, "msg": str(e)}

@app.get("/post/list/{subject_id}")
def get_subject_posts(subject_id: str, page: int = 1, session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    try:
        # subject-specific post list
        res = s["client"].request("POST", "/wefeed-mobile-bff/post/list/subject", 
                                 data={"subjectId": subject_id, "page": page, "pageSize": 10})
        return res
    except Exception as e:
        return {"code": 1, "msg": str(e)}

@app.get("/debug_history")
def debug_history(session_id: Optional[str] = Cookie(None)):
    s = get_session(session_id)
    results = {}
    for i in range(1, 6):
        try:
            res = s["client"].request(
                "GET",
                "/wefeed-mobile-bff/subject-api/see-list-v2",
                params={"page": "1", "pageSize": "10", "seeType": str(i)}
            )
            data = res.get("data", {})
            if not isinstance(data, dict): data = {}
            lst = data.get("list") or data.get("items") or res.get("list") or []
            results[f"seeType_{i}_count"] = len(lst)
            if len(lst) > 0:
                results[f"seeType_{i}_sample_keys"] = list(lst[0].keys())
        except Exception as e:
            results[f"seeType_{i}_error"] = str(e)
            
    try:
        results["tab_record"] = s["client"].request("GET", "/wefeed-mobile-bff/tab/play-record")
    except: pass
    return results

# DIRECT NATIVE PLAYBACK ONLY (PROXIES REMOVED)

@app.get("/sub-proxy")
async def subtitle_proxy(u: str):
    """Proxy a public subtitle URL for browser CORS, without opening an SSRF hole."""
    current_url = u
    max_bytes = _positive_int_env("MAX_SUBTITLE_PROXY_BYTES", 10 * 1024 * 1024)

    async with httpx.AsyncClient(
        timeout=httpx.Timeout(15.0, read=30.0),
        follow_redirects=False,
        verify=True,
    ) as client:
        for _ in range(4):
            current_url = _validate_public_http_url(current_url)
            try:
                async with client.stream(
                    "GET",
                    current_url,
                    headers={"User-Agent": "ExoPlayerLib/2.18.7"},
                ) as upstream:
                    if upstream.status_code in (301, 302, 303, 307, 308):
                        location = upstream.headers.get("location")
                        if not location:
                            raise HTTPException(status_code=502, detail="Subtitle redirect had no destination.")
                        current_url = urljoin(current_url, location)
                        continue

                    if upstream.status_code >= 400:
                        raise HTTPException(status_code=502, detail="The subtitle host rejected the request.")

                    content_type = (upstream.headers.get("content-type") or "").split(";", 1)[0].strip().lower()
                    if content_type == "text/html":
                        raise HTTPException(status_code=415, detail="The subtitle URL returned HTML, not subtitles.")

                    body = bytearray()
                    async for chunk in upstream.aiter_bytes():
                        body.extend(chunk)
                        if len(body) > max_bytes:
                            raise HTTPException(status_code=413, detail="Subtitle file is too large.")

                    return Response(
                        content=bytes(body),
                        media_type=content_type or "text/vtt",
                        headers={"Access-Control-Allow-Origin": "*", "X-Content-Type-Options": "nosniff"},
                    )
            except HTTPException:
                raise
            except httpx.HTTPError:
                logger.exception("Subtitle proxy upstream request failed")
                raise HTTPException(status_code=502, detail="The subtitle host is unavailable.")

    raise HTTPException(status_code=400, detail="Too many subtitle redirects.")

@app.post("/launch-player")
def launch_player(
    player: str = Query(...),
    url: str = Query(...),
    cookie: Optional[str] = Query(None),
    subject_id: Optional[str] = Query(None),
    season: Optional[int] = Query(None),
    episode: Optional[int] = Query(None),
    title: Optional[str] = Query(None),
    cover: Optional[str] = Query(None),
    start_time: Optional[int] = Query(0),
    subtitle_url: Optional[str] = Query(None),
    duration: Optional[int] = Query(0)
):
    """Launch a desktop player on the machine running the backend.

    This is a local convenience feature for the web dashboard, not an Android
    endpoint. It is disabled on Render (ENABLE_EXTERNAL_PLAYER=false) and never
    falls back to a shell, so a crafted URL cannot execute commands.
    """
    if os.getenv("ENABLE_EXTERNAL_PLAYER", "true").strip().lower() not in ("1", "true", "yes", "on"):
        raise HTTPException(status_code=501, detail="External player launch is disabled on this deployment.")
    if urlparse(url).scheme not in ("http", "https") or not urlparse(url).netloc:
        raise HTTPException(status_code=400, detail="Only absolute http(s) media URLs are supported.")
    if subtitle_url and (urlparse(subtitle_url).scheme not in ("http", "https") or not urlparse(subtitle_url).netloc):
        raise HTTPException(status_code=400, detail="Only absolute http(s) subtitle URLs are supported.")

    player_name = player.strip().lower()
    logger.info("Launching external player: %s for %s (start_time=%s)", player_name, _safe_url_label(url), start_time)
    cmd = []
    if player_name == "mpv":
        cmd = ["mpv", url]
        if title:
            display_title = title
            if season and episode:
                display_title += f" S{season}E{episode}"
            cmd.append(f"--title={display_title}")
        if start_time and start_time > 0:
            cmd.append(f"--start={start_time}")
        if subtitle_url:
            cmd.append(f"--sub-file={subtitle_url}")
        cmd.append("--user-agent=ExoPlayerLib/2.18.7")
        if cookie:
            cmd.append(f"--http-header-fields=Cookie: {cookie.replace(chr(13), '').replace(chr(10), '')}")
    elif player_name == "vlc":
        cmd = ["vlc", url]
        if title:
            display_title = title
            if season and episode:
                display_title += f" S{season}E{episode}"
            cmd.extend(["--meta-title", display_title])
        if start_time and start_time > 0:
            cmd.extend(["--start-time", str(start_time)])
        if subtitle_url:
            cmd.extend(["--sub-file", subtitle_url])
        cmd.extend(["--http-user-agent", "ExoPlayerLib/2.18.7"])
    else:
        raise HTTPException(status_code=400, detail=f"Unsupported player: {player}")

    try:
        creation_flags = 0
        if os.name == 'nt':
            creation_flags = 0x00000008 | 0x00000200
        logger.info("Executing external player: %s", player_name)
        subprocess.Popen(cmd, creationflags=creation_flags, close_fds=True)
        return {"status": "success", "message": f"Launched {player_name}"}
    except FileNotFoundError:
        logger.warning("External player binary is not installed: %s", player_name)
        raise HTTPException(status_code=503, detail=f"{player_name} is not installed on the server.")
    except Exception as e:
        logger.error("Failed to launch external player: %s", type(e).__name__)
        raise HTTPException(status_code=500, detail="Could not launch the external player.")

if __name__ == "__main__":
    # Render sets PORT dynamically. Honor HOST/PORT so `python moviebox_api_server.py`
    # works both locally and in production; render.yaml uses the Uvicorn CLI form.
    try:
        bind_port = int(os.getenv("PORT", "8000"))
    except ValueError:
        bind_port = 8000
    uvicorn.run(
        "moviebox_api_server:app",
        host=os.getenv("HOST", "0.0.0.0"),
        port=bind_port,
        reload=False,
        proxy_headers=True,
    )
