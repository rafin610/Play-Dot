#!/usr/bin/env python3
"""Rotate MOVIEBOX_GUEST_TOKEN (and its paired MOVIEBOX_USER_ID) in `.env`.

Provider guest JWTs are short-lived (the seeded one lasts 90 days). The gateway
issues a fresh one through the `x-user` response header when the client performs
an anonymous handshake, which is exactly what this script does.

Usage (from the repository root):

    .venv/bin/python scripts/refresh_guest_token.py            # rewrite .env
    .venv/bin/python scripts/refresh_guest_token.py --print    # print export lines only
    .venv/bin/python scripts/refresh_guest_token.py --dry-run  # show the new expiry, write nothing

The new token is never logged to the console unless --print is passed; update
Render's environment variables separately when rotating there.
"""

import argparse
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
ENV_PATH = REPO_ROOT / ".env"

sys.path.insert(0, str(REPO_ROOT))

from dotenv import load_dotenv  # noqa: E402

load_dotenv(ENV_PATH, override=False)

from moviebox_api import MovieBoxAuth, MovieBoxClient  # noqa: E402
from moviebox_api.auth import decode_jwt_expiry  # noqa: E402
from moviebox_api.content import MovieBoxContent  # noqa: E402

REQUIRED_VARS = ("MOVIEBOX_GATEWAY_SECRET", "MOVIEBOX_APPID")


def mint_guest_token():
    """Return (token, user_id) from an anonymous provider handshake."""
    missing = [name for name in REQUIRED_VARS if not os.getenv(name, "").strip()]
    if missing:
        raise SystemExit(f"{', '.join(missing)} must be set in {ENV_PATH} first.")

    auth = MovieBoxAuth()
    auth.token = ""
    auth.token_expires_at = None
    auth.is_logged_in = False  # anonymous handshake: X-Client-Token instead of Bearer
    auth.is_guest_mode = True

    client = MovieBoxClient(auth=auth)
    response = MovieBoxContent(client).get_categories(category_id=1, page=1)
    code = response.get("code") if isinstance(response, dict) else None
    if not auth.token:
        raise SystemExit(f"The provider did not return a guest token (code={code}).")
    if code not in (None, 0, 200):
        raise SystemExit(f"The provider rejected the handshake (code={code}).")
    return auth.token, auth.user_id


def write_env(values: dict) -> None:
    """Rewrite the two keys in place, preserving comments and ordering."""
    lines = ENV_PATH.read_text(encoding="utf-8").splitlines() if ENV_PATH.exists() else []
    pending = dict(values)
    output = []

    for line in lines:
        match = re.match(r"^\s*(MOVIEBOX_GUEST_TOKEN|MOVIEBOX_USER_ID)\s*=", line)
        if match and match.group(1) in pending:
            key = match.group(1)
            output.append(f"{key}={pending.pop(key)}")
        else:
            output.append(line)

    for key, value in pending.items():
        output.append(f"{key}={value}")

    tmp_path = ENV_PATH.parent / (ENV_PATH.name + ".tmp")
    tmp_path.write_text("\n".join(output) + "\n", encoding="utf-8")
    os.replace(tmp_path, ENV_PATH)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--print", action="store_true", help="print the new values as shell exports")
    parser.add_argument("--dry-run", action="store_true", help="mint and report, but do not modify .env")
    args = parser.parse_args()

    token, user_id = mint_guest_token()
    expires_at = decode_jwt_expiry(token)
    expires_label = (
        datetime.fromtimestamp(expires_at, timezone.utc).isoformat() if expires_at else "unknown"
    )

    if args.print:
        print(f"MOVIEBOX_GUEST_TOKEN={token}")
        print(f"MOVIEBOX_USER_ID={user_id}")
    elif args.dry_run:
        print(f"New guest token minted (expires {expires_label}); .env left unchanged.")
    else:
        write_env({"MOVIEBOX_GUEST_TOKEN": token, "MOVIEBOX_USER_ID": user_id})
        print(f"Updated {ENV_PATH} (guest token expires {expires_label}).")

    print(f"Paired MOVIEBOX_USER_ID: {user_id}", file=sys.stderr if args.print else sys.stdout)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
