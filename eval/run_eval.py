#!/usr/bin/env python3
"""Phase 1 — deterministic collection.

Drives the Thoth desktop server (`serve`): runs every case x mode x repeat through POST /query,
captures the EvalRecord, and computes all judge-independent scores (score.py). Also runs a
model-free retrieval-only probe via GET /search per case. Writes a self-contained, reproducible
run directory; Phase 2 (judge.py) consumes phase1.jsonl without touching the product model.

Usage:
  python eval/run_eval.py --server http://localhost:8080 --cases eval/dataset/cases.json \
      --modes quick,thorough --repeats 3
"""

from __future__ import annotations

import argparse
import datetime as _dt
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import score  # noqa: E402
from lib import server_client as sc  # noqa: E402
from lib.schema import SCORER_VERSION, cases_hash, git_sha, load_cases  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))


def main() -> int:
    ap = argparse.ArgumentParser(description="Thoth eval — Phase 1 (deterministic collection)")
    ap.add_argument("--server", default="http://localhost:8080")
    ap.add_argument("--cases", default=os.path.join(HERE, "dataset", "cases.json"))
    ap.add_argument("--modes", default="quick,thorough")
    ap.add_argument("--repeats", type=int, default=3)
    ap.add_argument("--search-topk", type=int, default=10)
    ap.add_argument("--outdir", default=os.path.join(HERE, "runs"))
    ap.add_argument("--label", default="", help="optional suffix on the run dir name")
    args = ap.parse_args()

    modes = [m.strip() for m in args.modes.split(",") if m.strip()]
    cases = load_cases(args.cases)
    if not cases:
        print(f"No (non-review) cases in {args.cases}", file=sys.stderr)
        return 2

    try:
        h = sc.health(args.server)
    except sc.ServerError as e:
        print(f"Server health check failed ({args.server}): {e}", file=sys.stderr)
        return 2
    if not h.get("modelLoaded"):
        print(f"Server reports model not loaded: {h}", file=sys.stderr)
        return 2

    ts = _dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    run_name = f"{ts}{('-' + args.label) if args.label else ''}"
    run_dir = os.path.join(args.outdir, run_name)
    os.makedirs(run_dir, exist_ok=True)
    phase1_path = os.path.join(run_dir, "phase1.jsonl")

    rows = 0
    with open(phase1_path, "w", encoding="utf-8") as out:
        for case in cases:
            case_modes = [m for m in modes if m in case.modes]
            # Model-free retrieval probe (BM25 on the raw question) — fully deterministic.
            try:
                search_only = sc.search(args.server, case.question, top_k=args.search_topk)
                search_rel = _search_relevance(case, search_only)
            except sc.ServerError as e:
                search_rel = {"error": str(e)}
            for mode in case_modes:
                for rep in range(args.repeats):
                    try:
                        record = sc.query(args.server, case.question, mode)
                        scores = score.score_record(case, record)
                        err = None
                    except sc.ServerError as e:
                        record, scores, err = {}, {}, str(e)
                    row = {
                        "case_id": case.id,
                        "mode": mode,
                        "repeat": rep,
                        "answerable": case.answerable,
                        "record": record,
                        "scores": scores,
                        "search_only": search_rel,
                        "error": err,
                    }
                    out.write(json.dumps(row) + "\n")
                    rows += 1
                    status = "ok" if err is None else f"ERR {err}"
                    print(f"  {case.id:30s} {mode:8s} rep{rep}  {status}")

    meta = {
        "scorer_version": SCORER_VERSION,
        "git_sha": git_sha(),
        "cases_path": os.path.relpath(args.cases, HERE),
        "cases_hash": cases_hash(args.cases),
        "server": args.server,
        "server_health": h,
        "modes": modes,
        "repeats": args.repeats,
        "case_count": len(cases),
        "rows": rows,
        "timestamp": ts,
        "backend_note": "desktop-GPU; latency is NOT representative of on-device",
    }
    with open(os.path.join(run_dir, "run-meta.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2)

    print(f"\nPhase 1 complete: {rows} rows -> {phase1_path}")
    print(f"Provenance: {os.path.join(run_dir, 'run-meta.json')}")
    return 0


def _search_relevance(case, search_only) -> dict:
    """Retrieval-only metrics from the BM25 /search probe (independent of the model path)."""
    record = {"retrievedHits": [
        {"articleTitle": p.get("articleTitle", ""), "sectionHeading": p.get("sectionHeading", ""),
         "sectionAnchor": p.get("sectionAnchor", ""), "zimEntryPath": p.get("zimEntryPath", ""),
         "rank": p.get("rank", i)}
        for i, p in enumerate(search_only.get("passages", []))
    ]}
    return score.retrieval_metrics(case, record)


if __name__ == "__main__":
    raise SystemExit(main())
