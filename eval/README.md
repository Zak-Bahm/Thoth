# Thoth answer-quality eval

A two-phase, host-side regression eval for Thoth's two answer modes (`quick`, `thorough`). It tells
you whether an architectural change improves or degrades answer quality.

- **Phase 1 — deterministic collection** (`run_eval.py`): drives the desktop server, captures each
  `EvalRecord`, and computes every judge-independent metric (retrieval recall, citation match,
  abstention, grounding, latency). Reproducible; needs no judge.
- **Phase 2 — local LLM-as-judge** (`judge.py`): re-scores Phase-1 answers for **correctness** and
  **faithfulness** using a fast cross-family judge, **Qwen2.5-7B-Instruct** via `llama-server`.
  Cross-family (Qwen vs. the Gemma 4 system-under-test) avoids self-preference bias.

The two models never need VRAM at once: run Phase 1 (Gemma 4 in the server), stop it, then Phase 2
loads Qwen2.5-7B. Phase 2 re-runs against frozen Phase-1 artifacts without touching the product model.

Pure Python 3 stdlib — no `pip` deps. `llama-server` is launched separately.

## Layout

```
dataset/seed_titles.txt   seed titles for extraction
dataset/cases.json        reviewed gold cases (tracked)
extract_cases.py          Phase 0: /article -> draft cases (human-reviewed)
run_eval.py               Phase 1: /query + /search -> runs/<ts>/phase1.jsonl
judge.py                  Phase 2: phase1.jsonl -> phase2.jsonl  (+ `calibrate`)
score.py                  deterministic scoring (S1-S5, recall@k/MRR/nDCG, abstention)
report.py                 aggregate, Wilson CI, paired McNemar vs baseline
lib/                      schema, server client, judge client
baseline/baseline.jsonl   committed baseline phase2 artifact (per-case, for paired tests)
runs/<ts>/                run artifacts (git-ignored)
```

## Quick start

```bash
# 0. Start the product server (Phase 1 backend), model + ZIM resident:
THOTH_MODEL=/path/gemma-4-E4B-it.litertlm THOTH_ZIM=/path/wiki.zim THOTH_PORT=8080 \
  ./gradlew :desktop:run --args="serve"

# 1. Phase 1 — deterministic collection:
python eval/run_eval.py --server http://localhost:8080 --repeats 3
#   -> eval/runs/<ts>/phase1.jsonl + run-meta.json

# 2. Stop the product server. Start the judge:
llama-server -m Qwen2.5-7B-Instruct-Q5_K_M.gguf --port 8081 \
  --temp 0 --top-k 1 --ctx-size 8192 --n-gpu-layers 999 --flash-attn on --jinja

# 3. Phase 2 — judge:
python eval/judge.py run eval/runs/<ts>/phase1.jsonl \
  --judge http://localhost:8081 --model qwen2.5-7b-instruct

# 4. Report (+ compare to baseline):
python eval/report.py eval/runs/<ts>/phase2.jsonl
```

## Adding cases (extraction)

```bash
python eval/extract_cases.py --server http://localhost:8080   # -> dataset/cases.draft.json
```
Then **review every draft**: rephrase the question so it does NOT echo `_sourceSentence` (the source
shares vocabulary with the gold passage, which inflates BM25 retrieval unrealistically), sanity-check
`keyFacts`, drop `"review": true`, and add a few `answerable: false` out-of-corpus cases. Merge the
reviewed entries into `dataset/cases.json`.

Case schema: `id`, `question`, `goldArticles` (a SET of acceptable titles), optional
`goldSection`/`goldAnchor`, `keyFacts` (string or `[synonyms]` any-of), `answerable`, `modes`.
Optional `human: {"correctness": "..."}` per case enables judge calibration.

## Setting / comparing a baseline

```bash
python eval/report.py eval/runs/<good>/phase2.jsonl --set-baseline   # promote a known-good run
python eval/report.py eval/runs/<new>/phase2.jsonl                   # auto-compares to baseline
```
Comparison is a **paired McNemar** test over matched (case, mode) units; a stage is flagged
improved/regressed only when significant (`p < 0.05`), so within-noise deltas read as `unchanged`.

## Trusting the judge (do this once)

Hand-label `correctness` for ~20-30 cases (`case.human.correctness`), then:
```bash
python eval/judge.py calibrate eval/runs/<ts>/phase2.jsonl
```
This reports **Cohen's kappa** vs your labels. kappa >= 0.6 is substantial; below that, escalate to a
stronger judge (Phi-4 14B / Qwen3.6-35B) for the authoritative number and keep Qwen2.5-7B for the
fast CI pre-pass.

## Notes

- **Determinism.** BM25/`/search` is deterministic. The product model runs desktop-GPU at temp>0, so
  `--repeats` (default 3) collapses to a per-unit majority and reports self-consistency. The judge
  runs greedy (temp 0) with grammar-enforced JSON, so verdicts are reproducible.
- **Latency** is desktop-GPU and NOT representative of on-device; use it for relative regression only.
- **S5 (keyword)** is a cheap smoke proxy, not the correctness signal — correctness + faithfulness
  come from Phase 2.
- Requires the `RetrievedHit.text` field and the `/article` endpoint (both added in `:core` /
  `desktop/Server.kt`).
