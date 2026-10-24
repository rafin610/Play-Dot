"""Focused contract and failure-mode tests for the FastAPI adapter.

These tests are deliberately offline: the provider client is replaced with a
deterministic fake session, so they prove the backend's mapping/error contract
without claiming that the real MovieBox provider is available. Live integration
checks are documented separately in docs/FINAL_AUDIT_REPORT.md.
"""

import base64
import hashlib
import json
import os
import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from fastapi.testclient import TestClient

import moviebox_api_server as server


def fake_jwt(exp: int) -> str:
    """Unsigned JWT-shaped token whose only meaningful claim is `exp`."""
    payload = base64.urlsafe_b64encode(json.dumps({"exp": exp}).encode()).decode().rstrip("=")
    return f"eyJhbGciOiJIUzI1NiJ9.{payload}.signature"
from tests.test_auth_expiry import fake_jwt


LONG_DUB_ID = "8078269238353584824"
OTHER_DUB_ID = "7000000000000000001"
RESOURCE_ID = "9000000000000000002"


class FakeCookieJar:
    def get(self, key, default=None):
        return "fixture-sign-cookie" if key == "signCookie" else default


class FakeClient:
    BASE_URL = "https://provider.example.test"

    def __init__(self):
        self.session = SimpleNamespace(cookies=FakeCookieJar(), headers={})
        self.requests = []

    def request(self, method, endpoint, **kwargs):
        self.requests.append((method, endpoint))
        if "post/count" in endpoint:
            return {"data": {"count": 42}}
        if "subtitle-search" in endpoint:
            return {"data": {"items": [{"id": 7, "lanName": "English", "url": "https://cdn.example.test/s.vtt"}]}}
        if "get-ext-captions" in endpoint:
            return {
                "data": {
                    "extCaptions": [
                        {
                            "id": 11,
                            "lan": "en",
                            "lanName": "English",
                            "url": "https://cdn.example.test/subs/en.vtt",
                        }
                    ]
                }
            }
        if endpoint == "/wefeed-mobile-bff/subject-api/get":
            return {
                "data": {
                    "resourceDetectors": [
                        {"resourceId": RESOURCE_ID, "name": "Hindi"},
                    ]
                }
            }
        return {"data": {}}

    def logout(self):
        return None


class FakeAuth:
    is_guest_mode = True
    is_logged_in = True
    token = "fixture-token"
    user_id = "fixture-user"
    user_info = {}


class FakeContent:
    def __init__(self):
        self.trending_calls = 0

    @staticmethod
    def _item():
        return {
            "subjectId": "1234567890123456789",
            "id": "1234567890123456789",
            "title": "Fixture Title",
            "cover": "https://img.example.test/cover.jpg",
            "poster": "https://img.example.test/poster.jpg",
            "subjectType": 1,
            "score": 8.4,
            "releaseTime": "2024-01-01",
            "runtime": "120 min",
            "description": "Fixture description",
            "deepLink": "moviebox://movie/detail?subjectId=1234567890123456789",
            "isCollection": 1,
            "seeTime": "42",
            "items": [],
        }

    def get_trending(self):
        self.trending_calls += 1
        return {"code": 0, "data": {"list": [self._item()]}}

    def get_home_list(self, category_id=1, page=1):
        raise AssertionError("lazy trending must not call get_home_list after a successful candidate")

    def get_categories(self, category_id=1, page=1):
        return {
            "code": 0,
            "data": {
                "list": [
                    {
                        "title": "Featured",
                        "subjectType": 1,
                        "subjects": [self._item()],
                    }
                ]
            },
        }

    def search(self, keyword, page=1):
        return {"code": 0, "data": {"items": [self._item()]}}

    def get_movie_detail(self, subject_id):
        return {
            "code": 0,
            "data": {
                **self._item(),
                "dubs": [
                    {"subjectId": int(LONG_DUB_ID), "lanName": "Hindi"},
                    {"subjectId": OTHER_DUB_ID, "lanName": "Tamil"},
                ],
                "cast": [{"name": "Fixture Actor", "character": "Lead", "avatarUrl": ""}],
            },
        }

    def get_episode_list(self, series_id):
        return {
            "code": 0,
            "data": {
                "seasons": [
                    {
                        "se": "1",
                        "allEp": "1,2,2,10",
                        "epList": [
                            {"ep": 1, "title": "One"},
                            {"ep": 2, "title": "Two"},
                        ],
                    }
                ]
            },
        }


class FakeStream:
    def __init__(self):
        self.calls = 0

    def get_play_info(self, subject_id, season=None, episode=None, resource_id=None):
        self.calls += 1
        return {
            "code": 0,
            "data": {
                "streamList": [
                    {
                        "url": "https://cdn.example.test/video/fixture.mp4",
                        "quality": "720p",
                        "signCookie": "fixture-sign-cookie",
                    }
                ],
                "subTitleList": [
                    {
                        "id": 11,
                        "lan": "en",
                        "lanName": "English",
                        "url": "https://cdn.example.test/subs/en.vtt",
                    }
                ],
            },
        }


class FakeUser:
    pass


def make_session():
    content = FakeContent()
    stream = FakeStream()
    client = FakeClient()
    return {
        "id": "fixture-session",
        "auth": FakeAuth(),
        "client": client,
        "content": content,
        "stream": stream,
        "user": FakeUser(),
    }


class ApiContractTest(unittest.TestCase):
    def setUp(self):
        self.session = make_session()
        self.session_patch = patch("moviebox_api_server.get_session", return_value=self.session)
        self.session_patch.start()
        self.addCleanup(self.session_patch.stop)
        self.client = TestClient(server.app)

    def test_trending_maps_large_ids_and_extension_fields(self):
        response = self.client.get("/trending")
        self.assertEqual(200, response.status_code)
        payload = response.json()
        self.assertEqual(0, payload["code"])
        item = payload["data"][0]
        self.assertEqual("1234567890123456789", item["subjectId"])
        self.assertIsInstance(item["subjectId"], str)
        self.assertEqual("2024", item["releaseTime"])
        self.assertEqual("8.4", item["score"])
        self.assertIs(item["isCollection"], True)
        self.assertEqual(42, item["seeTime"])
        # Exactly one provider catalogue call is made now.
        self.assertEqual(1, self.session["content"].trending_calls)

    def test_home_returns_sections_and_session_cookie(self):
        response = self.client.get("/home")
        self.assertEqual(200, response.status_code)
        payload = response.json()
        self.assertEqual(0, payload["code"])
        self.assertEqual("Featured", payload["data"]["list"][0]["title"])
        self.assertEqual("1", payload["data"]["list"][0]["type"])
        self.assertIn("session_id", response.headers.get("set-cookie", ""))

    def test_home_upstream_failure_is_http_502_not_empty_200(self):
        with patch.object(
            self.session["content"],
            "get_categories",
            side_effect=RuntimeError("upstream unavailable"),
        ):
            response = self.client.get("/home")
        self.assertEqual(502, response.status_code)
        self.assertNotIn("upstream unavailable", response.text)
        self.assertNotEqual(200, response.status_code)

    def test_detail_normalizes_provider_ids_and_post_count(self):
        response = self.client.get("/detail/1234567890123456789")
        self.assertEqual(200, response.status_code)
        item = response.json()["data"]
        self.assertEqual("42", item["postCount"])
        self.assertEqual(LONG_DUB_ID, item["languages"][0]["subjectId"])
        self.assertEqual(OTHER_DUB_ID, item["languages"][1]["subjectId"])
        self.assertTrue(all(isinstance(x["subjectId"], str) for x in item["languages"]))
        self.assertEqual(RESOURCE_ID, item["languages"][2]["id"])

    def test_detail_upstream_failure_is_502(self):
        with patch.object(
            self.session["content"],
            "get_movie_detail",
            side_effect=RuntimeError("provider 500"),
        ):
            response = self.client.get("/detail/123")
        self.assertEqual(502, response.status_code)
        self.assertNotIn("provider 500", response.text)

    def test_episodes_preserves_seasons_and_deduplicates_numbers(self):
        response = self.client.get("/episodes/1234567890123456789")
        self.assertEqual(200, response.status_code)
        seasons = response.json()["data"]["seasons"]
        self.assertEqual(1, seasons[0]["seasonNumber"])
        self.assertEqual(["1", "2", "10"], [e["episodeNumber"] for e in seasons[0]["episodes"]])

    def test_episodes_provider_error_is_502(self):
        with patch.object(
            self.session["content"],
            "get_episode_list",
            return_value={"code": 500, "data": {}},
        ):
            response = self.client.get("/episodes/123")
        self.assertEqual(502, response.status_code)

    def test_stream_flat_contract_and_normalized_subtitles(self):
        class HeadResponse:
            status_code = 200

        with patch("requests.head", return_value=HeadResponse()) as head:
            response = self.client.get("/stream/1234567890123456789")
        self.assertEqual(200, response.status_code)
        payload = response.json()
        self.assertIn("url", payload)
        self.assertNotIn("code", payload)
        self.assertFalse(payload["isHls"])
        self.assertEqual(7200, payload["duration"])
        self.assertEqual("11", payload["subtitles"][0]["id"])
        self.assertEqual("720p", payload["qualityList"][0])
        self.assertTrue(head.called)
        # TLS verification must not be disabled for the probe.
        self.assertTrue(all(call.kwargs.get("verify") is not False for call in head.call_args_list))
        # Compatibility metadata is additive and must not replace the native URL.
        self.assertEqual(
            "/play-compat/1234567890123456789?season=1&episode=1&quality=720p",
            payload["compatUrl"],
        )
        self.assertEqual("720p", payload["selectedQuality"])
        self.assertFalse(payload["isTranscoded"])
        self.assertFalse(payload["isDash"])
        self.assertEqual("video/mp4", payload["mimeType"])

    def test_stream_does_not_decode_urlprefix_or_bypass_provider_notice(self):
        class HeadResponse:
            status_code = 200

        hidden_prefix = base64.urlsafe_b64encode(
            b"https://hidden.example.test/dash/fixture/"
        ).decode().rstrip("=")
        dummy_url = "https://cdn.example.test/video/legacy-notice.mp4"
        play_info = {
            "code": 0,
            "data": {
                "streamList": [
                    {
                        "url": dummy_url,
                        "quality": "720p",
                        "signCookie": f"fixture=1; urlprefix={hidden_prefix}:tail",
                    }
                ],
                "subTitleList": [],
            },
        }
        with (
            patch.object(self.session["stream"], "get_play_info", return_value=play_info),
            patch("requests.head", return_value=HeadResponse()),
        ):
            response = self.client.get("/stream/1234567890123456789")

        self.assertEqual(200, response.status_code)
        self.assertEqual(dummy_url, response.json()["url"])
        self.assertNotIn("index.mpd", response.json()["url"])
        self.assertNotIn("hidden.example.test", response.text)

    def test_stream_prefers_metadata_confirmed_h264_over_hevc(self):
        class HeadResponse:
            status_code = 200

        h264_url = "https://cdn.example.test/video/opaque-a.mp4"
        play_info = {
            "code": 0,
            "data": {
                "streamList": [
                    {
                        "url": "https://cdn.example.test/video/opaque-b.mp4",
                        "quality": "1080p",
                        "codecName": "H.265",
                    },
                    {"url": h264_url, "quality": "720p", "codecName": "AVC1"},
                ],
                "subTitleList": [],
            },
        }
        with (
            patch.object(self.session["stream"], "get_play_info", return_value=play_info),
            patch("requests.head", return_value=HeadResponse()),
        ):
            response = self.client.get("/stream/1234567890123456789")

        self.assertEqual(200, response.status_code)
        self.assertEqual(h264_url, response.json()["url"])

    def test_stream_does_not_select_candidate_after_access_probes_fail(self):
        class HeadResponse:
            status_code = 403

        play_info = {
            "code": 0,
            "data": {
                "streamList": [
                    {"url": "https://cdn.example.test/video/opaque.mp4", "quality": "720p"},
                ],
                "subTitleList": [],
            },
        }
        with (
            patch.object(self.session["stream"], "get_play_info", return_value=play_info),
            patch("requests.head", return_value=HeadResponse()),
        ):
            response = self.client.get("/stream/1234567890123456789")

        self.assertEqual(404, response.status_code)
        self.assertEqual("No streams found for this title.", response.json()["detail"])

    def test_stream_provider_rate_limit_is_clear(self):
        with patch.object(
            self.session["stream"],
            "get_play_info",
            return_value={"code": 429, "data": {}},
        ):
            response = self.client.get("/stream/123")
        self.assertEqual(503, response.status_code)
        self.assertIn("rate-limiting", response.json()["detail"])

    def test_subtitles_normalizes_string_fields(self):
        response = self.client.get("/subtitles/123")
        self.assertEqual(200, response.status_code)
        item = response.json()["data"]["list"][0]
        self.assertEqual("11", item["id"])
        self.assertEqual("en", item["lan"])

    def test_subtitles_upstream_error_is_not_empty_200(self):
        with patch.object(
            self.session["client"],
            "request",
            side_effect=RuntimeError("upstream failure"),
        ):
            response = self.client.get("/subtitles/123")
        self.assertEqual(502, response.status_code)

    def test_static_subtitle_search_route_is_not_shadowed(self):
        response = self.client.get("/subtitles/search", params={"query": "fixture"})
        self.assertEqual(200, response.status_code)
        self.assertEqual("7", response.json()["data"]["list"][0]["id"])


class CompatibilityStreamTest(unittest.TestCase):
    """Contract tests for the on-demand H.264 compatibility endpoint.

    These stay offline: provider resolution and ffmpeg are patched. A live
    transcoder check is documented separately because CI has no provider
    credentials and may not have ffmpeg installed.
    """

    def setUp(self):
        self.client = TestClient(server.app)

    def test_compat_head_reports_transcoded_contract(self):
        with patch("moviebox_api_server.shutil.which", return_value="/usr/bin/ffmpeg"):
            response = self.client.head(
                "/play-compat/1234567890123456789",
                params={"season": 1, "episode": 1, "quality": "480p"},
            )
        self.assertEqual(200, response.status_code)
        self.assertEqual("video/mp4", response.headers["content-type"])
        self.assertEqual("1", response.headers["x-playdot-transcoded"])
        self.assertEqual("none", response.headers["accept-ranges"])
        self.assertEqual("no-store", response.headers["cache-control"])

    def test_compat_rejects_invalid_ids_quality_and_start_time(self):
        with patch("moviebox_api_server.shutil.which", return_value="/usr/bin/ffmpeg"):
            bad_id = self.client.head("/play-compat/not-a-number")
            bad_quality = self.client.head(
                "/play-compat/123",
                params={"quality": "8k"},
            )
            bad_start = self.client.head(
                "/play-compat/123",
                params={"start_time": -1},
            )
            bad_resource = self.client.head(
                "/play-compat/123",
                params={"resource_id": "../../etc/passwd"},
            )
        for response in (bad_id, bad_quality, bad_start, bad_resource):
            self.assertEqual(400, response.status_code, response.text)

    def test_compat_rejects_private_resolved_source_and_releases_slot(self):
        private_stream = {
            "url": "http://127.0.0.1:8000/private.mp4",
            "cookie": "",
            "duration": 60,
        }
        with (
            patch.object(server, "get_stream", return_value=private_stream),
            patch("moviebox_api_server.shutil.which", return_value="/usr/bin/ffmpeg"),
        ):
            response = self.client.get("/play-compat/1234567890123456789")
        self.assertEqual(502, response.status_code)
        self.assertIn("publicly reachable", response.json()["detail"])
        # The semaphore must be released on the failure path.
        self.assertTrue(server._COMPAT_SLOTS.acquire(blocking=False))
        server._COMPAT_SLOTS.release()

    def test_compat_busy_returns_429_before_resolving(self):
        self.assertTrue(server._COMPAT_SLOTS.acquire(blocking=False))
        try:
            with patch("moviebox_api_server.shutil.which", return_value="/usr/bin/ffmpeg"):
                response = self.client.get("/play-compat/1234567890123456789")
            self.assertEqual(429, response.status_code)
            self.assertIn("busy", response.json()["detail"])
        finally:
            server._COMPAT_SLOTS.release()

    def test_compat_ffmpeg_command_is_bounded_h264_mp4(self):
        with patch("moviebox_api_server.shutil.which", return_value="/usr/bin/ffmpeg"):
            command = server._build_compat_ffmpeg_command(
                "https://cdn.example.test/video/fixture.mp4?st=stub",
                "fixture-sign-cookie",
                12.5,
                2,
            )
        self.assertIn("-nostdin", command)
        self.assertIn("-ss", command)
        self.assertIn("12.500", command)
        self.assertIn("libx264", command)
        self.assertIn("scale=-2:480", command)
        self.assertIn("aac", command)
        self.assertEqual("mp4", command[command.index("-f") + 1])
        self.assertEqual("pipe:1", command[-1])
        self.assertIn("-headers", command)
        self.assertTrue(command[0].endswith("ffmpeg"))


class ConfigurationAndSecurityTest(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(server.app)

    def test_health_is_503_and_names_missing_variables(self):
        with patch.dict(
            os.environ,
            {
                "MOVIEBOX_GUEST_TOKEN": "",
                "MOVIEBOX_USER_ID": "",
                "MOVIEBOX_GATEWAY_SECRET": "",
                "MOVIEBOX_APPID": "",
            },
        ):
            response = self.client.get("/health")
        self.assertEqual(503, response.status_code)
        payload = response.json()
        self.assertEqual("error", payload["status"])
        self.assertGreaterEqual(len(payload["missing_env"]), 4)
        self.assertTrue(any("MOVIEBOX_GUEST_TOKEN" in item for item in payload["missing_env"]))
        # Values are never echoed, only variable names.
        self.assertNotIn("guest-token", response.text)

    def test_health_is_200_with_fake_complete_configuration(self):
        with patch.dict(
            os.environ,
            {
                "MOVIEBOX_GUEST_TOKEN": "fixture-token",
                "MOVIEBOX_USER_ID": "fixture-user",
                "MOVIEBOX_GATEWAY_SECRET": "Zml4dHVyZQ==",
                "MOVIEBOX_APPID": "fixture-app",
            },
        ):
            response = self.client.get("/health")
        self.assertEqual(200, response.status_code)
        self.assertEqual("ok", response.json()["status"])

    def test_health_reports_fresh_guest_token_expiry(self):
        token = fake_jwt(int(time.time()) + 30 * 86400)
        with patch.dict(
            os.environ,
            {
                "MOVIEBOX_GUEST_TOKEN": token,
                "MOVIEBOX_USER_ID": "fixture-user",
                "MOVIEBOX_GATEWAY_SECRET": "Zml4dHVyZQ==",
                "MOVIEBOX_APPID": "fixture-app",
            },
        ):
            response = self.client.get("/health")
        self.assertEqual(200, response.status_code)
        payload = response.json()
        self.assertFalse(payload["guest_token"]["expired"])
        self.assertNotIn("warnings", payload)
        self.assertNotIn(token, response.text)

    def test_expired_guest_token_is_a_warning_not_a_failure(self):
        token = fake_jwt(int(time.time()) - 60)
        with patch.dict(
            os.environ,
            {
                "MOVIEBOX_GUEST_TOKEN": token,
                "MOVIEBOX_USER_ID": "fixture-user",
                "MOVIEBOX_GATEWAY_SECRET": "Zml4dHVyZQ==",
                "MOVIEBOX_APPID": "fixture-app",
            },
        ):
            response = self.client.get("/health")
        # A stale seed token is recoverable (anonymous bootstrap), so health
        # stays 200 and reports a rotation warning instead of failing the deploy.
        self.assertEqual(200, response.status_code)
        payload = response.json()
        self.assertTrue(payload["guest_token"]["expired"])
        self.assertTrue(any("MOVIEBOX_GUEST_TOKEN" in item for item in payload["warnings"]))
        self.assertIn("refresh_guest_token.py", payload["warnings"][0])
        self.assertNotIn(token, response.text)

    def test_missing_configuration_blocks_provider_routes(self):
        with patch.dict(
            os.environ,
            {
                "MOVIEBOX_GUEST_TOKEN": "",
                "MOVIEBOX_USER_ID": "",
                "MOVIEBOX_GATEWAY_SECRET": "",
                "MOVIEBOX_APPID": "",
            },
        ):
            response = self.client.get("/trending")
        self.assertEqual(503, response.status_code)
        self.assertIn("/health", response.json()["detail"])

    def test_expired_sessions_are_pruned(self):
        old_sessions = dict(server.sessions)
        old_seen = dict(server._session_last_seen)
        try:
            server.sessions.clear()
            server._session_last_seen.clear()
            server.sessions["expired"] = {"id": "expired"}
            server.sessions["fresh"] = {"id": "fresh"}
            server._session_last_seen["expired"] = 10.0
            server._session_last_seen["fresh"] = 1900.0
            with patch.object(server, "SESSION_TTL_SECONDS", 1800):
                server._prune_sessions_locked(2000.0)
            self.assertNotIn("expired", server.sessions)
            self.assertIn("fresh", server.sessions)
        finally:
            server.sessions.clear()
            server.sessions.update(old_sessions)
            server._session_last_seen.clear()
            server._session_last_seen.update(old_seen)

    def test_sub_proxy_rejects_loopback_and_private_hosts(self):
        for url in (
            "http://127.0.0.1:8000/private",
            "http://localhost:8000/private",
            "http://10.0.0.5/subtitle.vtt",
            "http://169.254.169.254/latest/meta-data/",
            "file:///etc/passwd",
        ):
            response = self.client.get("/sub-proxy", params={"u": url})
            self.assertEqual(400, response.status_code, url)

    def test_launch_player_is_disabled_on_production_configuration(self):
        with patch.dict(os.environ, {"ENABLE_EXTERNAL_PLAYER": "false"}):
            response = self.client.post(
                "/launch-player",
                params={"player": "mpv", "url": "https://cdn.example.test/video.mp4"},
            )
        self.assertEqual(501, response.status_code)

    def test_launch_player_rejects_non_http_media_urls(self):
        with patch.dict(os.environ, {"ENABLE_EXTERNAL_PLAYER": "true"}):
            response = self.client.post(
                "/launch-player",
                params={"player": "mpv", "url": "file:///etc/passwd"},
            )
        self.assertEqual(400, response.status_code)

    def test_launch_player_rejects_unknown_player(self):
        with patch.dict(os.environ, {"ENABLE_EXTERNAL_PLAYER": "true"}):
            response = self.client.post(
                "/launch-player",
                params={"player": "sh", "url": "https://cdn.example.test/video.mp4"},
            )
        self.assertEqual(400, response.status_code)


class AppUpdateContractTest(unittest.TestCase):
    """Contract tests for the session-independent Android update endpoint."""

    def setUp(self):
        self.client = TestClient(server.app)
        # Keep these tests independent of any developer .env file.
        self.update_env = {
            "APP_UPDATE_APK_URL": "",
            "APP_UPDATE_APK_PATH": "",
            "APP_UPDATE_LATEST_VERSION_CODE": "3",
            "APP_UPDATE_LATEST_VERSION_NAME": "1.1.0",
            "APP_UPDATE_MINIMUM_VERSION_CODE": "1",
            "APP_UPDATE_RELEASE_NOTES": "Playback and stability fixes.",
            "APP_UPDATE_PACKAGE_NAME": "com.streambox.mobile",
            "APP_UPDATE_APK_SHA256": "",
            "APP_UPDATE_APK_SIZE_BYTES": "",
        }

    def test_unconfigured_update_metadata_is_503(self):
        with patch.dict(os.environ, self.update_env):
            response = self.client.get("/app-update")
        self.assertEqual(503, response.status_code)
        self.assertIn("not configured", response.json()["detail"])

    def test_https_metadata_has_stable_mobile_contract(self):
        env = {
            **self.update_env,
            "APP_UPDATE_APK_URL": "https://releases.example.test/playdot-1.1.0.apk",
            "APP_UPDATE_MINIMUM_VERSION_CODE": "2",
            "APP_UPDATE_APK_SHA256": "a" * 64,
            "APP_UPDATE_APK_SIZE_BYTES": "123456",
        }
        with patch.dict(os.environ, env):
            response = self.client.get("/app-update")
        self.assertEqual(200, response.status_code)
        payload = response.json()
        self.assertEqual(3, payload["latestVersionCode"])
        self.assertEqual("1.1.0", payload["latestVersionName"])
        self.assertEqual(2, payload["minimumSupportedVersionCode"])
        self.assertEqual("com.streambox.mobile", payload["packageName"])
        self.assertEqual("https://releases.example.test/playdot-1.1.0.apk", payload["apkUrl"])
        self.assertEqual("a" * 64, payload["sha256"])
        self.assertEqual(123456, payload["sizeBytes"])
        self.assertEqual("no-store", response.headers["cache-control"])

    def test_local_artifact_is_hashed_and_served(self):
        body = b"signed apk fixture bytes"
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "app-release.apk"
            apk.write_bytes(body)
            env = {
                **self.update_env,
                "APP_UPDATE_APK_PATH": str(apk),
                "APP_UPDATE_APK_URL": "http://localhost:8001/app-release.apk",
            }
            with patch.dict(os.environ, env):
                metadata = self.client.get("/app-update")
                artifact = self.client.get("/app-release.apk")

        self.assertEqual(200, metadata.status_code)
        self.assertEqual(hashlib.sha256(body).hexdigest(), metadata.json()["sha256"])
        self.assertEqual(len(body), metadata.json()["sizeBytes"])
        self.assertEqual(200, artifact.status_code)
        self.assertEqual(body, artifact.content)
        self.assertEqual("application/vnd.android.package-archive", artifact.headers["content-type"])

        with patch.dict(os.environ, self.update_env):
            missing = self.client.get("/app-release.apk")
        # An unconfigured route must not leak a local path.
        self.assertEqual(404, missing.status_code)
        self.assertNotIn("app-release.apk", missing.json().get("detail", ""))

    def test_public_cleartext_apk_url_is_rejected(self):
        env = {
            **self.update_env,
            "APP_UPDATE_APK_URL": "http://releases.example.test/playdot.apk",
        }
        with patch.dict(os.environ, env):
            response = self.client.get("/app-update")
        self.assertEqual(503, response.status_code)
        self.assertIn("invalid APK URL", response.json()["detail"])

    def test_invalid_minimum_version_is_rejected(self):
        env = {
            **self.update_env,
            "APP_UPDATE_APK_URL": "https://releases.example.test/playdot.apk",
            "APP_UPDATE_MINIMUM_VERSION_CODE": "4",
        }
        with patch.dict(os.environ, env):
            response = self.client.get("/app-update")
        self.assertEqual(503, response.status_code)
        self.assertIn("invalid minimum", response.json()["detail"])


if __name__ == "__main__":
    unittest.main()
