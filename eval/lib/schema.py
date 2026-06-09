"""Case + record types, normalization, and run provenance for the Thoth eval harness.

Pure stdlib. A "case" is one gold question; a "record" is the EvalRecord JSON the desktop
server returns from POST /query (schema defined in core/.../inference/EvalRecord.kt).
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
from dataclasses import dataclass, field
from typing import Any

SCORER_VERSION = "1.0.0"


@dataclass
class Case:
    """One gold question. goldArticles is a SET of acceptable articles; keyFacts items may be a
    string or a list of synonyms (any-of). answerable=False marks an out-of-corpus case that the
    system should refuse. `human` (optional) holds a hand label for judge calibration."""

    id: str
    question: str
    goldArticles: list[str] = field(default_factory=list)
    goldSection: str = ""
    goldAnchor: str = ""
    keyFacts: list[Any] = field(default_factory=list)
    answerable: bool = True
    modes: list[str] = field(default_factory=lambda: ["quick", "thorough"])
    human: dict[str, Any] | None = None
    review: bool = False

    @staticmethod
    def from_dict(d: dict[str, Any]) -> "Case":
        return Case(
            id=d["id"],
            question=d["question"],
            goldArticles=d.get("goldArticles", []),
            goldSection=d.get("goldSection", ""),
            goldAnchor=d.get("goldAnchor", ""),
            keyFacts=d.get("keyFacts", []),
            answerable=d.get("answerable", True),
            modes=d.get("modes", ["quick", "thorough"]),
            human=d.get("human"),
            review=d.get("review", False),
        )


def load_cases(path: str, include_review: bool = False) -> list[Case]:
    with open(path, encoding="utf-8") as f:
        raw = json.load(f)
    cases = [Case.from_dict(d) for d in raw]
    if not include_review:
        cases = [c for c in cases if not c.review]
    return cases


# --- normalization -------------------------------------------------------------------------

_WS = re.compile(r"\s+")
_TAG = re.compile(r"<[^>]+>")
_PUNCT = re.compile(r"[^\w\s]")


def normalize_title(s: str) -> str:
    """Case/whitespace-fold a title and treat '_' and space as equivalent (ZIM paths use '_')."""
    return _WS.sub(" ", s.replace("_", " ").strip().lower())


def strip_html(s: str) -> str:
    return _WS.sub(" ", _TAG.sub(" ", s)).strip()


def fold(s: str) -> str:
    """Lowercase, drop punctuation, collapse whitespace — for keyword/substring matching."""
    return _WS.sub(" ", _PUNCT.sub(" ", s.lower())).strip()


def cases_hash(path: str) -> str:
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()[:16]


def git_sha() -> str:
    try:
        return (
            subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=os.path.dirname(__file__))
            .decode()
            .strip()
        )
    except Exception:
        return "unknown"
