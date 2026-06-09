#!/usr/bin/env python3
"""Phase 0 — semi-automated gold-case extraction (human-reviewed).

For each seed title, pulls authoritative sections from the server's /article endpoint, picks a
salient factual sentence per section, and emits DRAFT cases (gold article/section authoritative;
question + keyFacts are drafts marked "review": true). A human then:
  - REPHRASES each question so it does NOT echo the source sentence (avoids retrieval leakage),
  - sanity-checks keyFacts, drops the "review" flag,
  - hand-adds a few unanswerable (answerable:false) out-of-corpus cases.

  python eval/extract_cases.py --server http://localhost:8080 \
      --seeds eval/dataset/seed_titles.txt --out eval/dataset/cases.draft.json
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lib import server_client as sc  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))

_SENT = re.compile(r"(?<=[.!?])\s+")
_NUM = re.compile(r"\b\d[\d,\.]*\b")
_PROPER = re.compile(r"\b([A-Z][a-z]{2,})\b")
_STOP = {"the", "this", "that", "these", "those", "there", "their", "with", "from", "have",
         "which", "while", "also", "such", "into", "over", "they", "than", "then", "when"}


def _salient_sentence(text: str) -> str | None:
    """Prefer a sentence with a number, a definition pattern, or a proper noun — these make for
    checkable facts."""
    best = None
    for raw in _SENT.split(text):
        s = raw.strip()
        if not (40 <= len(s) <= 240):
            continue
        score = 0
        if _NUM.search(s):
            score += 2
        if re.search(r"\b(is|are|was|were)\s+(a|an|the)\b", s):
            score += 1
        score += min(2, len(_PROPER.findall(s)))
        if best is None or score > best[0]:
            best = (score, s)
    return best[1] if best and best[0] >= 2 else None


def _key_facts(sentence: str) -> list[str]:
    """Draft keyFacts: numbers + salient lowercase content words from the source sentence."""
    facts: list[str] = []
    facts += _NUM.findall(sentence)
    for w in _PROPER.findall(sentence):
        lw = w.lower()
        if lw not in _STOP and lw not in facts:
            facts.append(lw)
    return facts[:3]


def _draft_question(title: str, heading: str) -> str:
    if heading:
        return f"DRAFT (rephrase me): What does {title} say about {heading.lower()}?"
    return f"DRAFT (rephrase me): What is {title} about?"


def main() -> int:
    ap = argparse.ArgumentParser(description="Thoth eval — Phase 0 (draft case extraction)")
    ap.add_argument("--server", default="http://localhost:8080")
    ap.add_argument("--seeds", default=os.path.join(HERE, "dataset", "seed_titles.txt"))
    ap.add_argument("--out", default=os.path.join(HERE, "dataset", "cases.draft.json"))
    ap.add_argument("--max-sections", type=int, default=3, help="draft cases per article")
    args = ap.parse_args()

    with open(args.seeds, encoding="utf-8") as f:
        titles = [t.strip() for t in f if t.strip() and not t.startswith("#")]

    drafts: list[dict] = []
    for title in titles:
        try:
            art = sc.article(args.server, title)
        except sc.ServerError as e:
            print(f"  SKIP {title}: {e}", file=sys.stderr)
            continue
        made = 0
        for sec in art.get("sections", []):
            if made >= args.max_sections:
                break
            sent = _salient_sentence(sec.get("text", ""))
            if not sent:
                continue
            heading = sec.get("heading", "")
            slug = re.sub(r"[^a-z0-9]+", "-", f"{title}-{heading or 'lead'}".lower()).strip("-")[:60]
            drafts.append({
                "id": slug,
                "question": _draft_question(art.get("title", title), heading),
                "goldArticles": [art.get("title", title)],
                "goldSection": heading,
                "goldAnchor": sec.get("anchor", ""),
                "keyFacts": _key_facts(sent),
                "answerable": True,
                "modes": ["quick", "thorough"],
                "review": True,
                "_sourceSentence": sent,
            })
            made += 1
        print(f"  {title}: {made} draft case(s)")

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(drafts, f, indent=2, ensure_ascii=False)
    print(f"\nWrote {len(drafts)} DRAFT cases -> {args.out}")
    print("Review: rephrase questions (don't echo _sourceSentence), check keyFacts, drop 'review',")
    print("add a few answerable:false out-of-corpus cases, then merge into dataset/cases.json.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
