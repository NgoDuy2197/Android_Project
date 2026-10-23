"""
Dashboard HTTP server for ProxySpy (Python stdlib only).

Runs in a background thread next to the mitmproxy event loop and serves:
  GET  /                     the dashboard page (dashboard.html)
  GET  /api/info             proxy address, LAN IPs, counts, paused state
  GET  /api/flows?since=N    summaries of flows whose seq > N (live polling)
  GET  /api/flow/<id>        full request/response of one flow
  POST /api/clear            drop all captured flows
  POST /api/pause?on=1|0     pause / resume capture
  GET  /ca/cert.pem          mitmproxy CA (PEM, for most platforms)
  GET  /ca/cert.cer          mitmproxy CA (DER-ish .cer, convenient on Android)

Bound to localhost by default so the captured data stays on the operator's PC.
"""

import json
import os
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

# Where bundled resources (dashboard.html) live: the PyInstaller temp dir when frozen, else this folder.
RES_DIR = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))


def make_handler(store, info: dict, confdir: str):
    class Handler(BaseHTTPRequestHandler):
        # Keep the console clean; errors still surface via _send.
        def log_message(self, *args):
            pass

        # ---- helpers ----
        def _send(self, code: int, body: bytes, ctype: str, extra=None):
            try:
                self.send_response(code)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Cache-Control", "no-store")
                self.send_header("Access-Control-Allow-Origin", "*")
                for k, v in (extra or {}).items():
                    self.send_header(k, v)
                self.end_headers()
                if self.command != "HEAD":
                    self.wfile.write(body)
            except Exception:
                pass

        def _json(self, code: int, obj):
            self._send(code, json.dumps(obj, ensure_ascii=False).encode("utf-8"),
                       "application/json; charset=utf-8")

        def _file(self, path: str, ctype: str, download_name: str = None):
            try:
                with open(path, "rb") as f:
                    data = f.read()
            except Exception:
                self._json(404, {"ok": False, "message": "Chưa có file (CA sinh ra sau khi proxy khởi động)."})
                return
            extra = {}
            if download_name:
                extra["Content-Disposition"] = f'attachment; filename="{download_name}"'
            self._send(200, data, ctype, extra)

        # ---- routing ----
        def do_GET(self):
            u = urlparse(self.path)
            p = u.path
            try:
                if p == "/" or p == "/index.html":
                    self._file(os.path.join(RES_DIR, "dashboard.html"), "text/html; charset=utf-8")
                elif p == "/api/info":
                    seq, _, count = store.list_since(10 ** 18)  # count only
                    self._json(200, {
                        "ok": True,
                        "proxyHost": info.get("proxyHost"),
                        "proxyPort": info.get("proxyPort"),
                        "ips": info.get("ips", []),
                        "dashPort": info.get("dashPort"),
                        "dashLan": info.get("dashLan", False),
                        "paused": store.paused,
                        "count": count,
                        "seq": store.list_since(10 ** 18)[0],
                    })
                elif p == "/api/flows":
                    since = 0
                    try:
                        since = int(parse_qs(u.query).get("since", ["0"])[0])
                    except Exception:
                        since = 0
                    seq, flows, count = store.list_since(since)
                    self._json(200, {"ok": True, "seq": seq, "count": count, "flows": flows})
                elif p.startswith("/api/flow/"):
                    fid = p[len("/api/flow/"):]
                    rec = store.get(fid)
                    if rec is None:
                        self._json(404, {"ok": False, "message": "Không còn flow này."})
                    else:
                        self._json(200, {"ok": True, "flow": rec})
                elif p == "/api/qr":
                    data = parse_qs(u.query).get("data", [""])[0][:1024]
                    if not data:
                        self._json(400, {"ok": False, "message": "thiếu data"})
                    else:
                        try:
                            import io
                            import segno
                            qr = segno.make(data, error="m")
                            buf = io.BytesIO()
                            qr.save(buf, kind="svg", scale=7, border=2, dark="#000000", light="#ffffff")
                            self._send(200, buf.getvalue(), "image/svg+xml")
                        except Exception as e:
                            self._json(500, {"ok": False, "message": f"QR lỗi: {e}"})
                elif p == "/ca/cert.pem":
                    self._file(os.path.join(confdir, "mitmproxy-ca-cert.pem"),
                               "application/x-pem-file", "ProxySpy-CA.pem")
                elif p == "/ca/cert.cer":
                    self._file(os.path.join(confdir, "mitmproxy-ca-cert.cer"),
                               "application/x-x509-ca-cert", "ProxySpy-CA.cer")
                else:
                    self._json(404, {"ok": False, "message": "not found"})
            except Exception as e:
                self._json(500, {"ok": False, "message": str(e)})

        def do_HEAD(self):
            self.do_GET()

        def do_POST(self):
            u = urlparse(self.path)
            p = u.path
            try:
                if p == "/api/clear":
                    store.clear()
                    self._json(200, {"ok": True})
                elif p == "/api/pause":
                    on = parse_qs(u.query).get("on", ["1"])[0]
                    store.paused = on in ("1", "true", "yes", "on")
                    self._json(200, {"ok": True, "paused": store.paused})
                else:
                    self._json(404, {"ok": False, "message": "not found"})
            except Exception as e:
                self._json(500, {"ok": False, "message": str(e)})

    return Handler


def start_dashboard(host: str, port: int, store, info: dict, confdir: str) -> ThreadingHTTPServer:
    httpd = ThreadingHTTPServer((host, port), make_handler(store, info, confdir))
    httpd.daemon_threads = True
    t = threading.Thread(target=httpd.serve_forever, name="proxyspy-dashboard", daemon=True)
    t.start()
    return httpd
