#!/usr/bin/env python3
"""Local mock of the Beakon API for the Android companion app.

Standard library only. Serves HTTPS with a self-signed certificate so the app's
user-driven fingerprint-acceptance flow can be exercised end to end.

Endpoints implemented (matching REQUIREMENTS.md):

    POST /login               form-encoded username + password.
                              Success -> 302 + `session` cookie (HttpOnly).
                              Failure -> 200 form re-render, no cookie.
    POST /api/updatetoken     form-encoded `secret`, session-authenticated.
                              Overwrites the user's stored API token.
    POST /api/recordlocation  JSON body, authenticated by `username`/`secret`
                              headers. 201 created / 400 missing lat-lon /
                              401 auth failure / 429 rate limited.
    GET  /api/locations       session-authenticated. Optional ?date=.
                              {"locations": [...], "latest": {...}|null}

Rate limiting mirrors the real instance: N requests per window seconds per
client IP. Every POST /api/recordlocation attempt counts, including rejected
ones, because the limit is applied before authentication.

Usage:
    python3 mock/beakon_mock.py --port 8443
    python3 mock/beakon_mock.py --port 8443 --user alice:pw --rate-limit-max 5

The default certificate/key are mock/cert.pem and mock/key.pem (self-signed,
SAN localhost + 127.0.0.1). Pass --cert/--key to use a different pair; a second
pair (cert2.pem/key2.pem) is included for "fingerprint changed" scenarios.
"""

import argparse
import json
import os
import secrets
import ssl
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

DEFAULT_USER = "demo"
DEFAULT_PASSWORD = "correct-horse"
HERE = os.path.dirname(os.path.abspath(__file__))


class State:
    def __init__(self, users, rate_max, rate_window):
        self.users = users  # username -> password
        self.sessions = {}  # session token -> username
        self.tokens = {}  # username -> api secret
        self.locations = {}  # username -> [ {lat, lon, timestamp, ...} ]
        self.rate_max = rate_max
        self.rate_window = rate_window
        self.rate = {}  # client ip -> [timestamps]
        self.lock = threading.Lock()

    def check_rate_limit(self, ip):
        """Returns (limited, retry_after_seconds). Counts the attempt."""
        if self.rate_max <= 0:
            return False, 0
        now = time.time()
        with self.lock:
            stamps = [t for t in self.rate.get(ip, []) if now - t < self.rate_window]
            if len(stamps) >= self.rate_max:
                retry = int(self.rate_window - (now - stamps[0])) + 1
                self.rate[ip] = stamps
                return True, max(retry, 1)
            stamps.append(now)
            self.rate[ip] = stamps
            return False, 0

    def add_location(self, username, location):
        with self.lock:
            self.locations.setdefault(username, []).append(location)


class Handler(BaseHTTPRequestHandler):
    server_version = "BeakonMock/1.0"
    protocol_version = "HTTP/1.1"
    state = None  # type: State
    _body = b""

    def log_message(self, fmt, *args):
        sys.stderr.write("mock: " + (fmt % args) + "\n")

    # --- helpers -----------------------------------------------------------

    def read_body(self):
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length else b""

    def read_form(self):
        raw = self._body.decode("utf-8", "replace")
        return {key: values[0] for key, values in parse_qs(raw).items()}

    def send(self, code, body="", content_type="text/plain; charset=utf-8", headers=None):
        data = body.encode("utf-8") if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        if data:
            self.wfile.write(data)

    def session_user(self):
        cookie = self.headers.get("Cookie", "")
        for part in cookie.split(";"):
            if "=" in part:
                key, value = part.strip().split("=", 1)
                if key == "session":
                    return self.state.sessions.get(value)
        return None

    # --- routes ------------------------------------------------------------

    def do_POST(self):
        # Always drain the body: an early error response (401/429/400) must not
        # leave unread bytes that corrupt the next keep-alive request.
        self._body = self.read_body()
        path = urlparse(self.path).path
        if path == "/login":
            self.handle_login()
        elif path == "/api/updatetoken":
            self.handle_updatetoken()
        elif path == "/api/recordlocation":
            self.handle_recordlocation()
        else:
            self.send(404, "not found")

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/api/locations":
            self.handle_locations()
        elif path in ("/", "/index"):
            self.send(200, "Beakon mock is running")
        else:
            self.send(404, "not found")

    def handle_login(self):
        form = self.read_form()
        username = form.get("username", "")
        password = form.get("password", "")
        if self.state.users.get(username) == password:
            token = secrets.token_hex(16)
            self.state.sessions[token] = username
            self.send(
                302,
                "redirecting",
                headers={"Location": "/", "Set-Cookie": f"session={token}; Path=/; HttpOnly"},
            )
        else:
            self.send(
                200,
                "<form>invalid credentials</form>",
                content_type="text/html; charset=utf-8",
            )

    def handle_updatetoken(self):
        user = self.session_user()
        if not user:
            self.send(401, "authentication required")
            return
        secret = self.read_form().get("secret", "")
        if not secret:
            self.send(400, "missing secret")
            return
        self.state.tokens[user] = secret
        self.send(200, "ok")

    def handle_recordlocation(self):
        ip = self.client_address[0]
        limited, retry_after = self.state.check_rate_limit(ip)
        if limited:
            self.send(429, "rate limited", headers={"Retry-After": str(retry_after)})
            return

        username = self.headers.get("username", "")
        secret = self.headers.get("secret", "")
        if not username or not secret or self.state.tokens.get(username) != secret:
            self.send(401, "authentication failed")
            return

        try:
            body = json.loads(self._body.decode("utf-8") or "{}")
        except json.JSONDecodeError:
            self.send(400, "invalid JSON")
            return

        if body.get("lat") is None or body.get("lon") is None:
            self.send(400, "missing lat/lon")
            return

        location = {
            "lat": body.get("lat"),
            "lon": body.get("lon"),
            "timestamp": int(time.time()),
        }
        if body.get("batt") is not None:
            location["batt"] = body["batt"]
        if body.get("ischarging") is not None:
            location["ischarging"] = body["ischarging"]
        self.state.add_location(username, location)
        self.send(201, json.dumps({"status": "created"}), content_type="application/json")

    def handle_locations(self):
        user = self.session_user()
        if not user:
            self.send(401, "authentication required")
            return
        locations = self.state.locations.get(user, [])
        latest = locations[-1] if locations else None
        self.send(
            200,
            json.dumps({"locations": locations, "latest": latest}),
            content_type="application/json",
        )


def build_users(specs):
    users = {}
    for spec in specs:
        if ":" in spec:
            username, password = spec.split(":", 1)
            users[username] = password
    if not users:
        users[DEFAULT_USER] = DEFAULT_PASSWORD
    return users


def main():
    parser = argparse.ArgumentParser(description="Local mock of the Beakon API")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8443)
    parser.add_argument("--cert", default=os.path.join(HERE, "cert.pem"))
    parser.add_argument("--key", default=os.path.join(HERE, "key.pem"))
    parser.add_argument("--user", action="append", default=[], metavar="USER:PASSWORD")
    parser.add_argument("--rate-limit-max", type=int, default=5)
    parser.add_argument("--rate-limit-window", type=int, default=300)
    args = parser.parse_args()

    Handler.state = State(build_users(args.user), args.rate_limit_max, args.rate_limit_window)

    httpd = ThreadingHTTPServer((args.host, args.port), Handler)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(certfile=args.cert, keyfile=args.key)
    httpd.socket = context.wrap_socket(httpd.socket, server_side=True)

    host, port = httpd.server_address[0], httpd.server_address[1]
    print(f"LISTENING {host} {port}", flush=True)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()


if __name__ == "__main__":
    main()
