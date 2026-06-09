"""Stdlib client for the Thoth desktop server (desktop/Server.kt). Used by Phase 1 and the
extraction pipeline."""

from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request
from typing import Any


class ServerError(RuntimeError):
    pass


def _get(base_url: str, path: str, params: dict[str, Any] | None = None, timeout: float = 30.0) -> Any:
    url = base_url.rstrip("/") + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    try:
        with urllib.request.urlopen(url, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raise ServerError(f"GET {path} -> {e.code}: {e.read().decode('utf-8', 'replace')}") from e
    except urllib.error.URLError as e:
        raise ServerError(f"GET {path} failed: {e}") from e


def health(base_url: str, timeout: float = 5.0) -> dict[str, Any]:
    return _get(base_url, "/health", timeout=timeout)


def search(base_url: str, query: str, top_k: int = 10, timeout: float = 60.0) -> dict[str, Any]:
    return _get(base_url, "/search", {"q": query, "topK": top_k}, timeout=timeout)


def article(base_url: str, title: str, timeout: float = 60.0) -> dict[str, Any]:
    return _get(base_url, "/article", {"title": title}, timeout=timeout)


def query(base_url: str, q: str, mode: str, timeout: float = 600.0) -> dict[str, Any]:
    """POST /query — runs the full pipeline and returns the EvalRecord synchronously."""
    data = json.dumps({"query": q, "mode": mode}).encode("utf-8")
    req = urllib.request.Request(
        base_url.rstrip("/") + "/query",
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raise ServerError(f"POST /query -> {e.code}: {e.read().decode('utf-8', 'replace')}") from e
    except urllib.error.URLError as e:
        raise ServerError(f"POST /query failed: {e}") from e
