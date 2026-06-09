"""Deterministic, judge-independent scoring for one (case, EvalRecord) pair.

Pure functions — no device, no network. Stages mirror the pipeline so failures attribute to a
stage. S5 (keyword) is a cheap smoke proxy, NOT an authoritative correctness signal; correctness
and faithfulness come from the Phase-2 LLM judge.
"""

from __future__ import annotations

import math
import os
import sys
from typing import Any

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lib.schema import Case, fold, normalize_title, strip_html  # noqa: E402

# Phrases the pipeline emits when it declines to answer (LlmService.kt QUICK_MISS_HTML / the
# thorough degenerate-miss message, and the "none|..." submitAnswer convention).
_MISS_MARKERS = (
    "couldn't find a quick answer",
    "couldn't find a clear answer",
    "could not find information on this topic",
    "i don't know",
    "i dont know",
)

DEFAULT_KS = (1, 3, 5)


# --- matching helpers ----------------------------------------------------------------------


def _gold_set(case: Case) -> set[str]:
    return {normalize_title(a) for a in case.goldArticles}


def _title_in_gold(title: str, gold: set[str]) -> bool:
    return bool(title) and normalize_title(title) in gold


def _section_match(anchor: str, heading: str, case: Case) -> bool:
    """Anchor is authoritative when the case provides one; else fall back to heading text."""
    if case.goldAnchor:
        return normalize_title(anchor) == normalize_title(case.goldAnchor)
    if case.goldSection:
        return normalize_title(heading) == normalize_title(case.goldSection)
    return False


def _ranked_unique_hits(record: dict[str, Any]) -> list[dict[str, Any]]:
    """Flatten hits (thorough mode concatenates per-search lists, each rank 0..n) into one ranked
    list: dedupe by zimEntryPath keeping the best (lowest) rank, then sort by rank."""
    best: dict[str, dict[str, Any]] = {}
    for h in record.get("retrievedHits", []):
        key = h.get("zimEntryPath", "")
        if key not in best or h.get("rank", 1 << 30) < best[key].get("rank", 1 << 30):
            best[key] = h
    return sorted(best.values(), key=lambda h: h.get("rank", 1 << 30))


# --- deterministic stages ------------------------------------------------------------------


def s1_retrieval_article(case: Case, record: dict[str, Any]) -> bool:
    gold = _gold_set(case)
    return any(_title_in_gold(h.get("articleTitle", ""), gold) for h in record.get("retrievedHits", []))


def s2_retrieval_section(case: Case, record: dict[str, Any]) -> bool:
    if not (case.goldAnchor or case.goldSection):
        return False
    gold = _gold_set(case)
    for h in record.get("retrievedHits", []):
        if _title_in_gold(h.get("articleTitle", ""), gold) and _section_match(
            h.get("sectionAnchor", ""), h.get("sectionHeading", ""), case
        ):
            return True
    return False


def s3_citation_article(case: Case, record: dict[str, Any]) -> bool:
    gold = _gold_set(case)
    return any(
        c.get("grounded") and _title_in_gold(c.get("articleTitle", ""), gold)
        for c in record.get("claims", [])
    )


def s4_citation_section(case: Case, record: dict[str, Any]) -> bool:
    if not (case.goldAnchor or case.goldSection):
        return False
    gold = _gold_set(case)
    for c in record.get("claims", []):
        if (
            c.get("grounded")
            and _title_in_gold(c.get("articleTitle", ""), gold)
            and _section_match(c.get("sectionAnchor", ""), c.get("sectionHeading", ""), case)
        ):
            return True
    return False


def _fact_present(fact: Any, haystack: str) -> bool:
    """A keyFact is a string (substring/token match) or a list of synonyms (any-of)."""
    if isinstance(fact, list):
        return any(_fact_present(f, haystack) for f in fact)
    return fold(str(fact)) in haystack


def s5_keyword_smoke(case: Case, record: dict[str, Any]) -> bool:
    if not case.keyFacts:
        return False
    hay = fold(strip_html(record.get("answerHtml", "")))
    return all(_fact_present(f, hay) for f in case.keyFacts)


def is_abstention(record: dict[str, Any]) -> bool:
    # A pipeline error (e.g. a submitAnswer parse failure) is a failure, not a deliberate refusal.
    if record.get("status") == "error":
        return False
    text = strip_html(record.get("answerHtml", "")).lower()
    if any(m in text for m in _MISS_MARKERS):
        return True
    claims = record.get("claims", [])
    if record.get("grounded", 0) == 0 and len(claims) <= 1:
        single = claims[0]["text"].lower() if claims else ""
        return any(m in single for m in _MISS_MARKERS) or not claims
    return False


def retrieval_metrics(case: Case, record: dict[str, Any], ks=DEFAULT_KS) -> dict[str, float]:
    """Rank-aware retrieval quality over the deduped ranked hit list. Relevance is binary:
    a hit is relevant iff its article is in the gold set. recall@k = hit-rate@k (found at all
    within top-k); plus MRR and nDCG@max(k)."""
    gold = _gold_set(case)
    ranked = _ranked_unique_hits(record)
    rels = [1 if _title_in_gold(h.get("articleTitle", ""), gold) else 0 for h in ranked]
    out: dict[str, float] = {}
    for k in ks:
        out[f"recall@{k}"] = 1.0 if any(rels[:k]) else 0.0
    first = next((i for i, r in enumerate(rels) if r), None)
    out["mrr"] = 1.0 / (first + 1) if first is not None else 0.0
    kmax = max(ks)
    dcg = sum(r / math.log2(i + 2) for i, r in enumerate(rels[:kmax]))
    # Ideal DCG = all relevant docs ranked first (gold is a SET, so >1 can match).
    num_rel = min(sum(rels), kmax)
    idcg = sum(1 / math.log2(i + 2) for i in range(num_rel))
    out["ndcg"] = dcg / idcg if idcg else 0.0
    return out


def score_record(case: Case, record: dict[str, Any]) -> dict[str, Any]:
    """All deterministic scores for one (case, record). Booleans are stored as 0/1."""
    abstained = is_abstention(record)
    errored = record.get("status") == "error"
    scores: dict[str, Any] = {
        "errored": int(errored),
        "s1_retrieval_article": int(s1_retrieval_article(case, record)),
        "s2_retrieval_section": int(s2_retrieval_section(case, record)),
        "s3_citation_article": int(s3_citation_article(case, record)),
        "s4_citation_section": int(s4_citation_section(case, record)),
        "s5_keyword_smoke": int(s5_keyword_smoke(case, record)),
        "abstained": int(abstained),
        # For answerable cases, abstaining is a miss; for unanswerable, abstaining is correct.
        "correct_abstention": int(abstained and not case.answerable),
        "hallucinated": int(not abstained and not case.answerable),
        "grounded": record.get("grounded", 0),
        "total_claims": record.get("totalClaims", 0),
    }
    scores.update(retrieval_metrics(case, record))
    timings = record.get("timings") or {}
    scores["total_ms"] = timings.get("totalMs")
    scores["ttft_ms"] = timings.get("ttftMs")
    return scores
