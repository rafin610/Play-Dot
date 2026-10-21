"""Guest-token expiry handling: JWT decoding and expired-bearer fallback.

Offline only: the provider session is replaced with a stub that records the
headers the client would send.
"""

import base64
import json
import os
import time
import unittest
from unittest.mock import patch

from moviebox_api.auth import MovieBoxAuth, decode_jwt_expiry
from moviebox_api.client import MovieBoxClient


def fake_jwt(exp: int) -> str:
    payload = base64.urlsafe_b64encode(json.dumps({"exp": exp}).encode()).decode().rstrip("=")
    return f"eyJhbGciOiJIUzI1NiJ9.{payload}.signature"


class ProviderResponse:
    status_code = 200
    text = "{}"
    headers: dict = {}

    def json(self):
        return {"code": 0, "data": {}}


class DecodeJwtExpiryTest(unittest.TestCase):
    def test_reads_exp_claim(self):
        self.assertEqual(1_800_000_000, decode_jwt_expiry(fake_jwt(1_800_000_000)))

    def test_returns_none_for_opaque_or_malformed_tokens(self):
        for token in ("fixture-token", "", "a.b", "a.!!!.c", None):
            self.assertIsNone(decode_jwt_expiry(token))


class MovieBoxAuthExpiryTest(unittest.TestCase):
    def test_expired_token_is_detected(self):
        auth = MovieBoxAuth(token=fake_jwt(int(time.time()) - 60), user_id="u")
        self.assertTrue(auth.is_token_expired())

    def test_fresh_token_is_not_expired(self):
        auth = MovieBoxAuth(token=fake_jwt(int(time.time()) + 86400), user_id="u")
        self.assertFalse(auth.is_token_expired())

    def test_refresh_updates_expiry(self):
        auth = MovieBoxAuth(token=fake_jwt(int(time.time()) - 60), user_id="u")
        auth.update_session(fake_jwt(int(time.time()) + 86400), "u2")
        self.assertFalse(auth.is_token_expired())
        self.assertEqual("u2", auth.user_id)

    def test_opaque_token_is_left_to_the_provider(self):
        auth = MovieBoxAuth(token="fixture-token", user_id="u")
        self.assertFalse(auth.is_token_expired())


class ExpiredBearerFallbackTest(unittest.TestCase):
    def setUp(self):
        self.env = patch.dict(
            os.environ,
            {
                "MOVIEBOX_GUEST_TOKEN": "",
                "MOVIEBOX_USER_ID": "",
                "MOVIEBOX_GATEWAY_SECRET": "Zml4dHVyZQ==",
                "MOVIEBOX_APPID": "fixture-app",
            },
        )
        self.env.start()
        self.addCleanup(self.env.stop)

    def _captured_headers(self, token: str) -> dict:
        client = MovieBoxClient(auth=MovieBoxAuth(token=token, user_id="u"))
        captured = {}

        def fake_request(**kwargs):
            captured.update(kwargs["headers"])
            return ProviderResponse()

        client.session.request = fake_request
        client.request("GET", "/wefeed-mobile-bff/tab-operating", params={"tabId": 1})
        return captured

    def test_expired_bearer_is_sent_anonymously(self):
        headers = self._captured_headers(fake_jwt(int(time.time()) - 60))
        self.assertNotIn("Authorization", headers)
        self.assertIn("X-Client-Token", headers)

    def test_fresh_bearer_is_sent(self):
        headers = self._captured_headers(fake_jwt(int(time.time()) + 86400))
        self.assertTrue(headers.get("Authorization", "").startswith("Bearer "))


if __name__ == "__main__":
    unittest.main()
