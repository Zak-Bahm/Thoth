#!/usr/bin/env python3
"""Phase 2 — local LLM-as-judge (fast, cross-family: Qwen2.5-7B via llama-server).

Reads a Phase-1 artifact and re-scores each answer for correctness + faithfulness, using the cited
passage text (RetrievedHit.text) as the evidence. Greedy + grammar-enforced JSON, so verdicts are
deterministic and schema-valid. Never touches the product model.

  python eval/judge.py run  runs/<ts>/phase1.jsonl --judge http://localhost:8081 \
      --model qwen2.5-7b-instruct
  python eval/judge.py calibrate runs/<ts>/phase2.jsonl --cases eval/dataset/cases.json

`calibrate` compares verdicts against hand labels (case.human) and reports Cohen's kappa — run it
once before trusting the judge.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lib import judge_client as jc  # noqa: E402
from lib.schema import load_cases, strip_html  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))

SYSTEM = (
    "You are a strict evaluator for an offline Wikipedia question-answering assistant. "
    "Judge ONLY against the EVIDENCE passages provided; do not use outside knowledge. "
    "Return a JSON object with exactly these fields:\n"
    "- correctness: 'correct' | 'partially_correct' | 'incorrect' | 'abstained' "
    "(use 'abstained' only if the answer declines to answer / says it couldn't find it).\n"
    "- faithfulness: is the answer's claim supported by the cited EVIDENCE? "
    "'supported' | 'partial' | 'unsupported' | 'not_applicable' "
    "(use 'not_applicable' if the answer abstained or there is no evidence).\n"
    "- answer_relevance: does the answer address the QUESTION? 'relevant' | 'partial' | 'irrelevant'.\n"
    "- rationale: one short sentence.\n"
    "Be conservative: if the evidence does not state a fact, the claim is 'unsupported' even if it "
    "is true in general."
)

PROMPT_VERSION = "1.0.0"


def _cited_evidence(record: dict) -> str:
    """Text of the passages the answer actually cites, matched to claims by zimEntryPath+anchor,
    falling back to all retrieved hits when nothing is cited."""
    hits = record.get("retrievedHits", [])
    by_key = {(h.get("zimEntryPath", ""), h.get("sectionAnchor", "")): h for h in hits}
    by_path = {h.get("zimEntryPath", ""): h for h in hits}
    chosen: list[dict] = []
    seen: set[str] = set()
    for c in record.get("claims", []):
        if not c.get("grounded"):
            continue
        key = (c.get("articleTitle", ""), c.get("sectionAnchor", ""))
        # claims carry articleTitle, not zimEntryPath; match hit by anchor+title via path fallback.
        h = None
        for hk, hv in by_key.items():
            if hv.get("articleTitle") == c.get("articleTitle") and hk[1] == c.get("sectionAnchor", ""):
                h = hv
                break
        if h is None:
            h = next((hv for hv in hits if hv.get("articleTitle") == c.get("articleTitle")), None)
        if h and h.get("zimEntryPath") not in seen and h.get("text"):
            seen.add(h.get("zimEntryPath"))
            label = h.get("articleTitle", "") + (f" / {h['sectionHeading']}" if h.get("sectionHeading") else "")
            chosen.append({"label": label, "text": h["text"]})
    if not chosen:
        for h in hits[:3]:
            if h.get("text"):
                label = h.get("articleTitle", "") + (f" / {h['sectionHeading']}" if h.get("sectionHeading") else "")
                chosen.append({"label": label, "text": h["text"]})
    return "\n".join(f"[{e['label']}] {e['text']}" for e in chosen) or "(no evidence retrieved)"


def _user_prompt(question: str, key_facts, answer_html: str, evidence: str) -> str:
    facts = ", ".join(str(f) for f in key_facts) if key_facts else "(none provided)"
    return (
        f"QUESTION: {question}\n\n"
        f"REFERENCE KEY FACTS (the answer should convey these): {facts}\n\n"
        f"EVIDENCE PASSAGES:\n{evidence}\n\n"
        f"ASSISTANT ANSWER: {strip_html(answer_html)}\n\n"
        "Return the verdict JSON now."
    )


def cmd_run(args: argparse.Namespace) -> int:
    cases = {c.id: c for c in load_cases(args.cases, include_review=True)}
    if not jc.health(args.judge):
        print(f"Judge server not healthy at {args.judge}", file=sys.stderr)
        return 2
    out_path = args.out or os.path.join(os.path.dirname(args.phase1), "phase2.jsonl")
    prompt_hash = hashlib.sha256((SYSTEM + PROMPT_VERSION).encode()).hexdigest()[:12]

    n = 0
    with open(args.phase1, encoding="utf-8") as fin, open(out_path, "w", encoding="utf-8") as fout:
        for line in fin:
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            case = cases.get(row["case_id"])
            record = row.get("record") or {}
            verdict: dict
            if row.get("error") or not record:
                verdict = {"error": row.get("error") or "empty record"}
            else:
                user = _user_prompt(
                    case.question if case else record.get("query", ""),
                    case.keyFacts if case else [],
                    record.get("answerHtml", ""),
                    _cited_evidence(record),
                )
                try:
                    verdict = jc.judge(args.judge, args.model, SYSTEM, user, seed=args.seed)
                except jc.JudgeError as e:
                    verdict = {"error": str(e)}
            row["judge"] = {
                "model": args.model,
                "prompt_version": PROMPT_VERSION,
                "prompt_hash": prompt_hash,
                "seed": args.seed,
                "verdict": verdict,
            }
            fout.write(json.dumps(row) + "\n")
            n += 1
            v = verdict.get("correctness", verdict.get("error", "?"))
            print(f"  {row['case_id']:30s} {row['mode']:8s} rep{row['repeat']}  -> {v}")
    print(f"\nPhase 2 complete: {n} rows -> {out_path}")
    return 0


def cmd_calibrate(args: argparse.Namespace) -> int:
    """Cohen's kappa between judge correctness and human labels (case.human.correctness)."""
    cases = {c.id: c for c in load_cases(args.cases, include_review=True)}
    pairs: list[tuple[str, str]] = []
    with open(args.phase2, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            case = cases.get(row["case_id"])
            if not case or not case.human:
                continue
            human = case.human.get("correctness")
            judged = (row.get("judge") or {}).get("verdict", {}).get("correctness")
            if human and judged:
                pairs.append((judged, human))
    if not pairs:
        print("No human-labeled cases (case.human.correctness) found.", file=sys.stderr)
        return 2
    kappa, po = _cohens_kappa(pairs)
    print(f"Judge calibration over {len(pairs)} labeled (case,mode,repeat) rows:")
    print(f"  raw agreement: {po:.3f}")
    print(f"  Cohen's kappa: {kappa:.3f}")
    print("  (kappa >= 0.6 substantial, >= 0.8 near-human; below that, prefer a stronger judge)")
    return 0


def _cohens_kappa(pairs: list[tuple[str, str]]) -> tuple[float, float]:
    cats = sorted({c for p in pairs for c in p})
    n = len(pairs)
    po = sum(1 for a, b in pairs if a == b) / n
    pa = {c: sum(1 for a, _ in pairs if a == c) / n for c in cats}
    pb = {c: sum(1 for _, b in pairs if b == c) / n for c in cats}
    pe = sum(pa[c] * pb[c] for c in cats)
    kappa = (po - pe) / (1 - pe) if pe < 1 else 1.0
    return kappa, po


def main() -> int:
    ap = argparse.ArgumentParser(description="Thoth eval — Phase 2 (LLM-as-judge)")
    sub = ap.add_subparsers(dest="cmd", required=True)

    r = sub.add_parser("run", help="judge a phase1.jsonl")
    r.add_argument("phase1")
    r.add_argument("--judge", default="http://localhost:8081", help="llama-server base URL")
    r.add_argument("--model", default="qwen2.5-7b-instruct")
    r.add_argument("--cases", default=os.path.join(HERE, "dataset", "cases.json"))
    r.add_argument("--seed", type=int, default=0)
    r.add_argument("--out", default="")
    r.set_defaults(func=cmd_run)

    c = sub.add_parser("calibrate", help="Cohen's kappa vs human labels")
    c.add_argument("phase2")
    c.add_argument("--cases", default=os.path.join(HERE, "dataset", "cases.json"))
    c.set_defaults(func=cmd_calibrate)

    args = ap.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
