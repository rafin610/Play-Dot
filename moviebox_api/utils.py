import hashlib
import hmac
import base64
import os
import time
import json
from typing import Union, Optional
from urllib.parse import urlparse, parse_qsl, urlencode, quote
import logging
logger = logging.getLogger(__name__)


def _gateway_secret() -> str:
    """Gateway HMAC secret, loaded from the local environment (.env).

    Never hardcoded in source: see .env.example for the expected variable.
    """
    secret = os.getenv("MOVIEBOX_GATEWAY_SECRET", "")
    if not secret:
        logger.error(
            "MOVIEBOX_GATEWAY_SECRET is not configured; request signing will fail. "
            "Set it in the local .env file."
        )
    return secret

GATEWAY_SECRET_ONLINE = _gateway_secret()

def md5_hex(data: Union[str, bytes]) -> str:
    """Calculates MD5 hash of string or bytes and returns hex string."""
    if not data:
        return ""
    if isinstance(data, str):
        data = data.encode('utf-8')
    return hashlib.md5(data).hexdigest()

def sha256_hex(data: Union[str, bytes]) -> str:
    """Calculates SHA256 hash of string or bytes and returns hex string."""
    if not data:
        return ""
    if isinstance(data, str):
        data = data.encode('utf-8')
    return hashlib.sha256(data).hexdigest()

def generate_client_token() -> str:
    """
    Generates the X-Client-Token used for anonymous requests.
    Format: <timestamp>,<md5(reversed_timestamp)>
    """
    ts = str(int(time.time() * 1000))
    reversed_ts = ts[::-1]
    return f"{ts},{md5_hex(reversed_ts)}"

def generate_tr_signature(method: str, url: str, body_data: str = "", timestamp: int = None) -> str:
    """
    Generates the x-tr-signature for the MovieBox API Gateway.
    
    Canonical String Format:
    METHOD\nAccept\nContent-Type\nContent-Length\ntimestamp\nbodyMD5\npath?sorted_query
    """
    if timestamp is None:
        timestamp = int(time.time() * 1000)
    
    parsed_url = urlparse(url)
    path = parsed_url.path
    
    # Extract and sort query parameters
    query_params = parse_qsl(parsed_url.query, keep_blank_values=True)
    if query_params:
        # GatewayInterceptor decodes keys/values and sorts by key
        query_params.sort(key=lambda x: x[0])
        # Manually join with '=' and '&' without encoding (app does this after decoding)
        query_string = "&".join([f"{k}={v}" for k, v in query_params])
        resource = f"{path}?{query_string}"
    else:
        resource = path
        
    # Smali truncates body at 0x19000 (102400) characters for MD5
    body_for_md5 = body_data[:0x19000] if body_data else ""
    body_md5 = md5_hex(body_for_md5) if body_data else ""
    
    # Standard headers for canonical string
    accept = "application/json"
    content_type = "application/json;charset=UTF-8"
    content_length = str(len(body_data)) if body_data else ""
    
    canonical_list = [
        method.upper(),
        accept,
        content_type,
        content_length,
        str(timestamp),
        body_md5,
        resource
    ]
    canonical_string = "\n".join(canonical_list)
    # Never log the canonical string: its query/path values may contain signed
    # provider parameters. Keep request signing silent.
    
    # App base64 decodes the secret key (Online Secret)
    secret = _gateway_secret()
    if not secret:
        raise RuntimeError("MOVIEBOX_GATEWAY_SECRET is not configured in the environment")
    key = base64.b64decode(secret)
    h = hmac.new(key, canonical_string.encode('utf-8'), hashlib.md5)
    
    # App base64 encodes the binary signature result
    signature_b64 = base64.b64encode(h.digest()).decode('utf-8')
    
    return f"{timestamp}|2|{signature_b64}"

def get_default_client_info() -> dict:
    """Returns randomized device metadata matching the official app's JSON structure."""
    import random
    
    # Realistic device id logic from app patterns
    device_id = "86820305" + "".join(random.choices("0123456789", k=7))
    
    return {
        "package_name": "com.community.oneroom",
        "version_name": "4.0.02",
        "version_code": 50020126,
        "os": "android",
        "os_version": "14",
        "install_ch": "ps",
        "device_id": device_id,
        "install_store": "ps",
        "gaid": "",
        "brand": "Google",
        "model": "Pixel 6",
        "system_language": "en",
        "net": "wifi",
        "region": "IN",
        "timezone": "Asia/Kolkata",
        "sp_code": "404"
    }

def generate_gslb_sign(package_name: str, device_id: str) -> str:
    """
    Generates the X-Gslb-Sign for the Global Server Load Balancer.
    Formula: sha256(packageName + sha256(deviceId))
    """
    key = sha256_hex(device_id)
    return sha256_hex(package_name + key)
