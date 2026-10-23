"""
Rewrite rules for ProxySpy: match requests by URL/method, then mock or modify the response
(and/or find-replace in the body). Lets you e.g. force a login API to return a fake success.

A rule is a plain dict:
  { "enabled": true, "name": "...", "match": "/login", "method": "",
    "mock": false, "status": "", "body": "", "find": "", "replace": "",
    "contentType": "application/json; charset=utf-8" }
- match: substring of the full URL; prefix "re:" for a regex. Empty = match all.
- mock=true: don't contact the server, return status/body/contentType directly.
- mock=false: forward, then (if set) override status, replace whole body, or find->replace in body.
"""

import json
import os
import re
import threading

from mitmproxy import http


class RuleStore:
    def __init__(self, path: str):
        self.path = path
        self._lock = threading.Lock()
        self.rules = []
        self.load()

    def load(self):
        try:
            if os.path.exists(self.path):
                with open(self.path, encoding="utf-8") as f:
                    self.rules = json.load(f)
        except Exception:
            self.rules = []

    def save(self):
        try:
            with open(self.path, "w", encoding="utf-8") as f:
                json.dump(self.rules, f, ensure_ascii=False, indent=2)
        except Exception:
            pass

    def list(self):
        with self._lock:
            return list(self.rules)

    def set_all(self, rules):
        with self._lock:
            self.rules = rules if isinstance(rules, list) else []
        self.save()

    def matching(self, method: str, url: str):
        out = []
        with self._lock:
            rules = list(self.rules)
        for r in rules:
            if not r.get("enabled", True):
                continue
            m = r.get("match", "") or ""
            if m:
                if m.startswith("re:"):
                    try:
                        if not re.search(m[3:], url):
                            continue
                    except Exception:
                        continue
                elif m not in url:
                    continue
            meth = (r.get("method", "") or "").upper()
            if meth and meth != method.upper():
                continue
            out.append(r)
        return out


class Rules:
    """mitmproxy addon applying [RuleStore]. Must be added BEFORE the capture addon."""

    def __init__(self, store: RuleStore):
        self.store = store

    def request(self, flow):
        try:
            for r in self.store.matching(flow.request.method, flow.request.pretty_url):
                if r.get("mock"):
                    status = int(r.get("status") or 200)
                    body = (r.get("body") or "").encode("utf-8")
                    ct = r.get("contentType") or "application/json; charset=utf-8"
                    flow.response = http.Response.make(status, body, {"Content-Type": ct})
                    flow.metadata["proxyspy_rule"] = r.get("name", "mock")
                    return
        except Exception:
            pass

    def response(self, flow):
        try:
            if flow.response is None:
                return
            for r in self.store.matching(flow.request.method, flow.request.pretty_url):
                if r.get("mock"):
                    continue
                if r.get("status"):
                    try:
                        flow.response.status_code = int(r["status"])
                    except Exception:
                        pass
                if r.get("body"):
                    flow.response.text = r["body"]
                elif r.get("find"):
                    try:
                        flow.response.text = flow.response.text.replace(r["find"], r.get("replace", ""))
                    except Exception:
                        pass
                flow.metadata["proxyspy_rule"] = r.get("name", "modify")
        except Exception:
            pass
