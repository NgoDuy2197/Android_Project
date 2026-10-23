"""
Flow capture for ProxySpy.

[FlowStore] is a thread-safe, bounded store of intercepted request/response pairs.
mitmproxy calls the [Capture] addon hooks on its own asyncio thread; the dashboard
HTTP server reads the store from another thread, so every access takes a lock.

Bodies are captured up to [FlowStore.max_body] bytes; textual bodies are kept as
decoded text, binary bodies as base64 (both marked `truncated` when cut).
"""

import base64
import threading
import time

# Content types we show as text (everything else is treated as binary / base64).
_TEXT_HINTS = ("text/", "json", "xml", "javascript", "x-www-form-urlencoded",
               "html", "csv", "graphql", "/ecmascript", "+json", "+xml")


def _looks_text(sample: bytes) -> bool:
    """Heuristic for bodies with no/unknown content-type: mostly printable + valid UTF-8-ish."""
    if not sample:
        return True
    try:
        sample.decode("utf-8")
        return True
    except Exception:
        printable = sum(1 for b in sample if b == 9 or b == 10 or b == 13 or 32 <= b < 127)
        return printable >= len(sample) * 0.85


def _is_textual(content_type: str, sample: bytes) -> bool:
    ct = (content_type or "").lower()
    if any(h in ct for h in _TEXT_HINTS):
        return True
    if ct == "" or ct.startswith("application/octet-stream"):
        return _looks_text(sample)
    return False


def _extract_body(msg, cap: int) -> dict:
    """Returns {kind, size, truncated, contentType, text|base64} for a request/response message."""
    try:
        content = msg.content  # decoded (de-chunked / decompressed) bytes
        if content is None:
            content = b""
    except Exception:
        # Compression/encoding we cannot decode: fall back to the raw bytes.
        try:
            content = msg.raw_content or b""
        except Exception:
            content = b""
    full = len(content)
    ct = ""
    try:
        ct = msg.headers.get("content-type", "") or ""
    except Exception:
        pass
    chunk = content[:cap]
    truncated = full > cap
    if _is_textual(ct, chunk[:2048]):
        try:
            text = chunk.decode("utf-8", errors="replace")
        except Exception:
            text = ""
        return {"kind": "text", "text": text, "size": full, "truncated": truncated, "contentType": ct}
    return {
        "kind": "binary",
        "base64": base64.b64encode(chunk).decode("ascii"),
        "size": full,
        "truncated": truncated,
        "contentType": ct,
    }


def _headers(msg) -> list:
    out = []
    try:
        for k, v in msg.headers.items(multi=True):
            out.append([str(k), str(v)])
    except Exception:
        pass
    return out


class FlowStore:
    def __init__(self, max_flows: int = 3000, max_body: int = 2_000_000):
        self._lock = threading.Lock()
        self._flows = {}      # id -> full record
        self._order = []      # ids in arrival order
        self._seq = 0
        self.max_flows = max_flows
        self.max_body = max_body
        self.paused = False

    def _trim_locked(self):
        while len(self._order) > self.max_flows:
            old = self._order.pop(0)
            self._flows.pop(old, None)

    def upsert(self, rec: dict):
        with self._lock:
            self._seq += 1
            rec["seq"] = self._seq
            if rec["id"] not in self._flows:
                self._order.append(rec["id"])
            self._flows[rec["id"]] = rec
            self._trim_locked()

    @staticmethod
    def _summary(rec: dict) -> dict:
        return {
            "id": rec["id"],
            "seq": rec["seq"],
            "time": rec.get("time"),
            "method": rec.get("method"),
            "scheme": rec.get("scheme"),
            "host": rec.get("host"),
            "path": rec.get("path"),
            "status": rec.get("status"),
            "resType": rec.get("resType"),
            "reqSize": rec.get("req", {}).get("size", 0),
            "resSize": rec.get("res", {}).get("size", 0) if rec.get("res") else 0,
            "durationMs": rec.get("durationMs"),
            "pending": rec.get("res") is None,
        }

    def list_since(self, since_seq: int):
        with self._lock:
            flows = [self._summary(self._flows[i]) for i in self._order
                     if self._flows[i]["seq"] > since_seq]
            return self._seq, flows, len(self._order)

    def get(self, fid: str):
        with self._lock:
            rec = self._flows.get(fid)
            return dict(rec) if rec else None

    def clear(self):
        with self._lock:
            self._flows.clear()
            self._order.clear()


class Capture:
    """mitmproxy addon: records each request immediately and fills in the response when it arrives."""

    def __init__(self, store: FlowStore):
        self.store = store

    def request(self, flow):
        try:
            if self.store.paused:
                return
            self.store.upsert(self._record(flow, with_response=False))
        except Exception:
            pass

    def response(self, flow):
        try:
            if self.store.paused:
                return
            self.store.upsert(self._record(flow, with_response=True))
        except Exception:
            pass

    def error(self, flow):
        try:
            if self.store.paused or flow.request is None:
                return
            rec = self._record(flow, with_response=False)
            rec["error"] = str(getattr(flow, "error", "") or "lỗi kết nối")
            self.store.upsert(rec)
        except Exception:
            pass

    def _record(self, flow, with_response: bool) -> dict:
        req = flow.request
        start = getattr(req, "timestamp_start", None) or time.time()
        rec = {
            "id": flow.id,
            "time": start,
            "method": req.method,
            "scheme": req.scheme,
            "host": req.pretty_host,
            "port": req.port,
            "path": req.path,
            "url": req.pretty_url,
            "httpVersion": getattr(req, "http_version", ""),
            "req": {
                "headers": _headers(req),
                "body": _extract_body(req, self.store.max_body),
            },
            "res": None,
            "resType": None,
            "status": None,
            "durationMs": None,
            "error": None,
        }
        if with_response and flow.response is not None:
            res = flow.response
            body = _extract_body(res, self.store.max_body)
            rec["res"] = {
                "status": res.status_code,
                "reason": getattr(res, "reason", "") or "",
                "headers": _headers(res),
                "body": body,
            }
            rec["status"] = res.status_code
            rec["resType"] = (body.get("contentType") or "").split(";")[0]
            end = getattr(res, "timestamp_end", None)
            if end and start:
                rec["durationMs"] = int((end - start) * 1000)
        return rec
