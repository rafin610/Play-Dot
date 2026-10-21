import json
import os
from .utils import generate_client_token, get_default_client_info, md5_hex


def default_guest_token() -> str:
    """Shared guest credential, loaded from the local environment (.env).

    Never hardcoded in source: see .env.example for the expected variable.
    """
    return os.getenv("MOVIEBOX_GUEST_TOKEN", "")


def default_user_id() -> str:
    """User ID paired with MOVIEBOX_GUEST_TOKEN (local, non-secret config)."""
    return os.getenv("MOVIEBOX_USER_ID", "")


class MovieBoxAuth:
    def __init__(self, token: str = None, user_id: str = None):
        self.token = token if token is not None else default_guest_token()
        self.user_id = user_id or default_user_id()
        self.is_logged_in = bool(self.token)
        self.is_guest_mode = True
        self.client_info = get_default_client_info()
        self.user_info = None
        # REMOVED: self.load_session() - We no longer use a global file for multiple sessions

    def login_guest(self):
        """Perform guest login (Reset back to anonymous state)."""
        self.token = default_guest_token()
        self.user_id = default_user_id()
        self.is_logged_in = bool(self.token)
        self.is_guest_mode = True
        self.user_info = None

    def update_session(self, token: str, user_id: str = None, user_info: dict = None):
        """Updates the session with a new bearer token and user metadata."""
        self.token = token
        if user_id:
            self.user_id = user_id
        if user_info:
            self.user_info = user_info
        self.is_logged_in = True

    def save_session(self):
        """No-op: Sessions are now managed in memory by the server per-user session ID."""
        pass

    def load_session(self):
        """No-op: Sessions are now managed in memory by the server per-user session ID."""
        pass

    def get_auth_headers(self) -> dict:
        """Returns the current auth headers based on state (Guest vs Authenticated)."""
        headers = {}
        if self.is_logged_in and self.token:
            headers["Authorization"] = f"Bearer {self.token}"
            headers["X-Client-Status"] = "0"
        else:
            headers["X-Client-Token"] = generate_client_token()
            headers["X-Client-Status"] = "1"
        
        headers["X-Client-Info"] = json.dumps(self.client_info, separators=(',', ':'))
        return headers
