"""The web portal: config editor, live status, and a 'try it' box.

Binds to 127.0.0.1 by default. If you bind it to a LAN address you must set
portal.token - every request then needs `X-Token` (or ?token=) - because the
page edits API keys. Standard library only, no external dependencies.
"""
import json
import os
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import assistant, display, llm, search, state
from .config import APP_DIR, CFG

WEB_DIR = os.path.join(APP_DIR, "web")


class Handler(BaseHTTPRequestHandler):
    server_version = "iohelper/1.0"

    def log_message(self, fmt, *args):      # keep the console quiet
        pass

    # -- helpers ------------------------------------------------------------
    def _send(self, code, body, ctype="application/json; charset=utf-8"):
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def _authorized(self):
        token = CFG.get("portal.token")
        if not token:
            return True
        q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        return self.headers.get("X-Token") == token or q.get("token", [""])[0] == token

    def _json_body(self):
        n = int(self.headers.get("Content-Length") or 0)
        try:
            return json.loads(self.rfile.read(n) or b"{}")
        except json.JSONDecodeError:
            return {}

    # -- routes ---------------------------------------------------------------
    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path
        if path in ("/", "/index.html"):
            try:
                with open(os.path.join(WEB_DIR, "index.html"), "rb") as fh:
                    return self._send(200, fh.read(), "text/html; charset=utf-8")
            except OSError:
                return self._send(404, {"error": "web/index.html missing"})
        if not self._authorized():
            return self._send(401, {"error": "token required"})
        if path == "/api/config":
            return self._send(200, CFG.public())
        if path == "/api/status":
            snap = state.snapshot()
            # keys saved via the portal take effect immediately; reflect that
            # without waiting for the startup quota check to run again
            if search.available() and snap["status"].get("serpapi") in ("", "no key"):
                snap["status"]["serpapi"] = "key set"
            elif not search.available():
                snap["status"]["serpapi"] = "no key"
            snap["config_path"] = CFG.path
            snap["backend"] = CFG.get("llm.backend")
            snap["trigger"] = CFG.get("wake.trigger")
            snap["portal"] = f"http://{CFG.get('portal.host')}:{CFG.get('portal.port')}/"
            return self._send(200, snap)
        if path == "/api/models":
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            backend = q.get("backend", [CFG.get("llm.backend")])[0]
            models = llm.list_models(backend)
            return self._send(200, {"backend": backend, "models": models,
                                    "suggested": llm.pick_model(backend, models) if models else ""})
        if path == "/api/search/account":
            return self._send(200, search.account() if search.available()
                              else {"error": "no SerpApi key"})
        if path == "/api/store":
            from . import commands
            return self._send(200, commands.load_store())
        return self._send(404, {"error": "not found"})

    def do_POST(self):
        path = urllib.parse.urlparse(self.path).path
        if not self._authorized():
            return self._send(401, {"error": "token required"})
        body = self._json_body()
        if path == "/api/config":
            CFG.update(body)
            CFG.save()
            state.log("config saved from portal")
            return self._send(200, {"ok": True, "config": CFG.public()})
        if path == "/api/ask":
            q = (body.get("text") or "").strip()
            if not q:
                return self._send(400, {"error": "text required"})
            state.log(f'portal ask: "{q}"')
            res = assistant.ask(q, deliver=bool(body.get("deliver", True)))
            return self._send(200, res)
        if path == "/api/send":
            text = (body.get("text") or "").strip()
            if not text:
                return self._send(400, {"error": "text required"})
            if not display.ensure_connected():
                return self._send(503, {"error": "glasses bridge unreachable"})
            return self._send(200, display.deliver(text, ttl=body.get("title") or None))
        if path == "/api/connect":
            return self._send(200, {"connected": display.ensure_connected(),
                                    "adb": state.STATUS.get("adb")})
        if path == "/api/timer":
            from . import commands
            secs = commands.parse_duration(str(body.get("duration", ""))) or 0
            if secs <= 0:
                return self._send(400, {"error": "duration like '10 min'"})
            t = commands.add_timer(secs, body.get("label", ""))
            return self._send(200, t)
        if path == "/api/todo":
            from . import commands
            text = (body.get("text") or "").strip()
            if not text:
                return self._send(400, {"error": "text required"})
            return self._send(200, commands.add_todo(text))
        return self._send(404, {"error": "not found"})


def serve(stop_event: threading.Event):
    host, port = CFG.get("portal.host"), int(CFG.get("portal.port"))
    if host not in ("127.0.0.1", "localhost", "::1") and not CFG.get("portal.token"):
        state.log(f"portal: refusing to bind {host} without portal.token - using 127.0.0.1")
        host = "127.0.0.1"
    httpd = ThreadingHTTPServer((host, port), Handler)
    httpd.daemon_threads = True
    httpd.timeout = 1
    state.log(f"portal: http://{host}:{port}/")
    try:
        while not stop_event.is_set():
            httpd.handle_request()
    finally:
        httpd.server_close()
