#!/usr/bin/env python3
"""Aggregate a Phase-2 artifact into a report, and compare against a baseline.

Repeats are collapsed to a per-(case,mode) majority vote (system self-consistency is reported
separately). Rates carry Wilson 95% CIs. Baseline comparison uses a paired McNemar test on the
matched (case,mode) units — the right test for "did this change help?". A stage is only flagged
regressed/improved when the paired test is significant; CI-overlapping deltas are "unchanged".

  python eval/report.py runs/<ts>/phase2.jsonl
  python eval/report.py runs/<ts>/phase2.jsonl --baseline eval/baseline/baseline.jsonl
  python eval/report.py runs/<ts>/phase2.jsonl --set-baseline
"""

from __future__ import annotations

import argparse
import json
import math
import os
import shutil
import sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))

BOOL_STAGES = [
    "s1_retrieval_article",
    "s2_retrieval_section",
    "s3_citation_article",
    "s4_citation_section",
    "s5_keyword_smoke",
]
RETRIEVAL_KEYS = ["recall@1", "recall@3", "recall@5", "mrr", "ndcg"]
Z = 1.96


# --- stats -------------------------------------------------------------------------------------


def wilson(k: int, n: int) -> tuple[float, float, float]:
    if n == 0:
        return (0.0, 0.0, 0.0)
    p = k / n
    denom = 1 + Z * Z / n
    centre = (p + Z * Z / (2 * n)) / denom
    half = (Z * math.sqrt(p * (1 - p) / n + Z * Z / (4 * n * n))) / denom
    return (p, max(0.0, centre - half), min(1.0, centre + half))


def mcnemar(pairs: list[tuple[int, int]]) -> dict:
    """pairs of (baseline_bool, candidate_bool). Returns discordants and a continuity-corrected
    McNemar p-value (chi-square, 1 df) via erfc — no scipy."""
    b = sum(1 for base, cand in pairs if base == 1 and cand == 0)  # regressed
    c = sum(1 for base, cand in pairs if base == 0 and cand == 1)  # improved
    if b + c == 0:
        return {"b": 0, "c": 0, "p": 1.0, "stat": 0.0}
    stat = (abs(b - c) - 1) ** 2 / (b + c)
    p = math.erfc(math.sqrt(stat / 2))  # survival of chi-square(1 df)
    return {"b": b, "c": c, "p": p, "stat": stat}


# --- loading / collapsing repeats --------------------------------------------------------------


def _verdict(row: dict) -> dict:
    return (row.get("judge") or {}).get("verdict", {}) or {}


def collapse(path: str) -> dict[tuple[str, str], dict]:
    """Group rows by (case_id, mode); majority-collapse repeats. Returns per-unit booleans +
    retrieval means + judge labels + a self-consistency score."""
    groups: dict[tuple[str, str], list[dict]] = defaultdict(list)
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            groups[(row["case_id"], row["mode"])].append(row)

    units: dict[tuple[str, str], dict] = {}
    for key, rows in groups.items():
        n = len(rows)
        unit: dict = {"answerable": rows[0].get("answerable", True), "n": n}
        consistencies = []
        for stage in BOOL_STAGES + ["correct_abstention", "hallucinated", "errored"]:
            vals = [int((r.get("scores") or {}).get(stage, 0)) for r in rows]
            maj = 1 if sum(vals) * 2 >= n else 0
            unit[stage] = maj
            consistencies.append(max(sum(vals), n - sum(vals)) / n)
        for rk in RETRIEVAL_KEYS:
            vals = [float((r.get("scores") or {}).get(rk, 0.0)) for r in rows]
            unit[rk] = sum(vals) / n
        lat = [(r.get("scores") or {}).get("total_ms") for r in rows]
        lat = [x for x in lat if isinstance(x, (int, float))]
        unit["total_ms"] = sum(lat) / len(lat) if lat else None
        # judge: majority correctness label; "judge_correct" = majority is exactly 'correct'.
        labels = [_verdict(r).get("correctness") for r in rows]
        unit["judge_correctness"] = _majority_label(labels)
        unit["judge_correct"] = 1 if unit["judge_correctness"] == "correct" else 0
        unit["judge_ok"] = 1 if unit["judge_correctness"] in ("correct", "partially_correct") else 0
        faiths = [_verdict(r).get("faithfulness") for r in rows]
        fl = _majority_label(faiths)
        unit["judge_faithful"] = 1 if fl == "supported" else 0
        unit["self_consistency"] = sum(consistencies) / len(consistencies)
        units[key] = unit
    return units


def _majority_label(labels: list) -> str | None:
    labels = [x for x in labels if x]
    if not labels:
        return None
    counts: dict[str, int] = defaultdict(int)
    for x in labels:
        counts[x] += 1
    return max(counts.items(), key=lambda kv: kv[1])[0]


# --- aggregation -------------------------------------------------------------------------------


def aggregate(units: dict[tuple[str, str], dict], mode: str | None) -> dict:
    sel = [u for (cid, m), u in units.items() if mode is None or m == mode]
    n = len(sel)
    agg: dict = {"units": n}
    if n == 0:
        return agg
    # Gold-dependent stages (retrieval/citation/keyword) and answer correctness/faithfulness only
    # make sense on answerable cases; unanswerable cases have no gold and are scored via abstention.
    answerable = [u for u in sel if u["answerable"]]
    unanswerable = [u for u in sel if not u["answerable"]]
    na = len(answerable)
    agg["answerable_n"] = na
    for stage in BOOL_STAGES + ["judge_correct", "judge_ok", "judge_faithful"]:
        k = sum(u[stage] for u in answerable)
        p, lo, hi = wilson(k, na)
        agg[stage] = {"rate": p, "ci": [lo, hi], "k": k, "n": na}
    for rk in RETRIEVAL_KEYS:
        agg[rk] = (sum(u[rk] for u in answerable) / na) if na else 0.0
    if unanswerable:
        agg["correct_abstention_rate"] = sum(u["correct_abstention"] for u in unanswerable) / len(unanswerable)
        agg["hallucination_rate"] = sum(u["hallucinated"] for u in unanswerable) / len(unanswerable)
        agg["unanswerable_n"] = len(unanswerable)
    agg["error_rate"] = sum(u.get("errored", 0) for u in sel) / n
    lat = [u["total_ms"] for u in sel if u["total_ms"] is not None]
    agg["mean_total_ms"] = sum(lat) / len(lat) if lat else None
    agg["mean_self_consistency"] = sum(u["self_consistency"] for u in sel) / n
    return agg


def compare(cur: dict[tuple, dict], base: dict[tuple, dict]) -> dict:
    out: dict = {}
    shared = sorted(set(cur) & set(base))
    for stage in BOOL_STAGES + ["judge_correct", "judge_ok", "judge_faithful"]:
        pairs = [(base[k][stage], cur[k][stage]) for k in shared]
        m = mcnemar(pairs)
        delta = (sum(c for _, c in pairs) - sum(b for b, _ in pairs)) / len(pairs) if pairs else 0.0
        if m["p"] < 0.05 and m["c"] != m["b"]:
            flag = "improved" if m["c"] > m["b"] else "regressed"
        else:
            flag = "unchanged"
        out[stage] = {"delta": delta, "flag": flag, **m}
    out["_shared_units"] = len(shared)
    return out


# --- printing ----------------------------------------------------------------------------------


def _fmt_rate(d: dict) -> str:
    return f"{d['rate']*100:5.1f}%  [{d['ci'][0]*100:4.1f},{d['ci'][1]*100:4.1f}]  ({d['k']}/{d['n']})"


def print_report(by_mode: dict[str, dict], overall: dict, cmp: dict | None) -> None:
    print("\n=== Thoth eval report ===")
    modes = list(by_mode.keys())
    rows = BOOL_STAGES + ["judge_correct", "judge_ok", "judge_faithful"]
    for scope, agg in list(by_mode.items()) + [("OVERALL", overall)]:
        if agg.get("units", 0) == 0:
            continue
        print(f"\n-- {scope}  ({agg['units']} units; {agg.get('answerable_n', 0)} answerable, "
              f"stage rates over answerable) --")
        for r in rows:
            print(f"  {r:22s} {_fmt_rate(agg[r])}")
        print(f"  {'recall@1/3/5':22s} {agg['recall@1']:.2f} / {agg['recall@3']:.2f} / {agg['recall@5']:.2f}"
              f"   mrr {agg['mrr']:.2f}  ndcg {agg['ndcg']:.2f}")
        if "hallucination_rate" in agg:
            print(f"  {'abstention(unans.)':22s} correct {agg['correct_abstention_rate']*100:.0f}%  "
                  f"hallucinated {agg['hallucination_rate']*100:.0f}%  (n={agg['unanswerable_n']})")
        if agg.get("error_rate"):
            print(f"  {'pipeline error rate':22s} {agg['error_rate']*100:.0f}%")
        if agg.get("mean_total_ms") is not None:
            print(f"  {'mean latency':22s} {agg['mean_total_ms']:.0f} ms  (desktop-GPU; not on-device)")
        print(f"  {'self-consistency':22s} {agg['mean_self_consistency']*100:.0f}%")
    if cmp:
        print(f"\n-- vs baseline ({cmp['_shared_units']} shared units, paired McNemar) --")
        for r in rows:
            d = cmp[r]
            arrow = {"improved": "↑", "regressed": "↓", "unchanged": "="}[d["flag"]]
            print(f"  {r:22s} {arrow} {d['flag']:9s} Δ{d['delta']*100:+5.1f}%  "
                  f"(b={d['b']} c={d['c']} p={d['p']:.3f})")


def main() -> int:
    ap = argparse.ArgumentParser(description="Thoth eval — aggregate + baseline compare")
    ap.add_argument("phase2")
    ap.add_argument("--baseline", default=os.path.join(HERE, "baseline", "baseline.jsonl"))
    ap.add_argument("--set-baseline", action="store_true", help="promote this run to the baseline")
    args = ap.parse_args()

    units = collapse(args.phase2)
    if not units:
        print("No rows in phase2 artifact.", file=sys.stderr)
        return 2
    modes = sorted({m for _, m in units})
    by_mode = {m: aggregate(units, m) for m in modes}
    overall = aggregate(units, None)

    cmp = None
    if not args.set_baseline and os.path.exists(args.baseline):
        cmp = compare(units, collapse(args.baseline))

    print_report(by_mode, overall, cmp)

    report = {"by_mode": by_mode, "overall": overall, "comparison": cmp}
    out_path = os.path.join(os.path.dirname(args.phase2), "report.json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)
    print(f"\nWrote {out_path}")

    if args.set_baseline:
        os.makedirs(os.path.dirname(args.baseline), exist_ok=True)
        shutil.copyfile(args.phase2, args.baseline)
        print(f"Baseline set -> {args.baseline}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
