# CLAUDE.md

Guidance for working in this repo. Keep it current when architecture or workflows change.

## What Thoth is

An **offline Android knowledge assistant**: answers questions from Wikipedia with grounded
citations, no internet required. On-device LLM (**Gemma 4 E4B** via **LiteRT-LM**) + offline
Wikipedia (**ZIM** files, read through `java-libkiwix`) + a **RAG** pipeline (chunk → BM25 →
inject → generate). Stage 1 (BM25-only retrieval) is implemented; Stage 2 (embedding retrieval)
is planned. Full design and section-by-section status live in `IMPLEMENTATION_PLAN.md`.

## Module topology (read this before touching build files)

Three Gradle modules with a deliberate split — see `settings.gradle.kts`:

- **`:core`** — platform-neutral pipeline (retrieval + inference orchestration + eval). A plain
  Kotlin/JVM library (`org.jetbrains.kotlin.jvm`), **not** Android. Packages: `knowledge/`
  (ZIM + chunking + BM25), `inference/` (LLM service, tools, prompts, eval), `core/` (Log,
  OutputSink). Shared by both `:app` and `:desktop`.
- **`:app`** — the Android app (Compose UI, Hilt DI, downloads, adb debug harness). Depends on
  `:core`.
- **`:desktop`** — a headless x86_64 Linux runner that exercises the **same `:core` pipeline**
  in-process (GPU inference via Vulkan, ZIM via locally-built `libzim` JNI). Used for eval and
  fast iteration without a device. See `desktop/README.md`.

**Why `:core` uses `compileOnly` for `litertlm` and `org.json`** (`core/build.gradle.kts`): the
large, platform-specific native LiteRT-LM artifact must be supplied by each app (`litertlm-android`
in `:app`, `litertlm-jvm` in `:desktop`), and `org.json` is built into Android but must be added
on desktop — declaring them `compileOnly` in `:core` avoids duplicate-class clashes. If you add a
dependency `:core` needs at runtime, remember to also declare it in **both** `:app` and `:desktop`.

DI: `:app` uses **Hilt** (processor lives only in `:app`); `:core` carries just `javax.inject`
annotations. `:desktop` wires the same graph **manually** (`desktop/Main.kt`). Platform seams are
interfaces in `:core`: `ZimSource` (Android `ZimRepository` / `DesktopZimRepository`), `OutputSink`,
`EngineSettings`.

## Build / run / test

Android SDK is required. Always export the absolute path (tilde expands inconsistently):

```bash
export ANDROID_HOME=/home/zak/Android/Sdk

./gradlew assembleDebug          # build the debug APK
./gradlew installDebug           # install to a connected device/emulator
./gradlew :core:test             # unit tests (ArticleChunker, Bm25Scorer)
```

`:desktop` needs a **JDK 21** toolchain (litertlm-jvm is compiled for Java 21); `:core`/`:app`
compile at Java 17. One-time desktop native setup (builds `libzim.so.9` + wrapper, git-ignored):

```bash
git submodule update --init third_party/java-libkiwix
./desktop/build-zim-native.sh
```

Run the desktop pipeline (see `desktop/README.md` for all commands/env vars):

```bash
THOTH_ZIM=/path/wiki.zim ./gradlew :desktop:run --args="search rayleigh scattering"   # retrieval only, no model
THOTH_MODEL=/path/gemma-4-E4B-it.litertlm THOTH_ZIM=/path/wiki.zim THOTH_PORT=8080 \
  ./gradlew :desktop:run --args="serve"                                               # persistent HTTP server
```

## The answer pipeline (`:core/inference` + `:core/knowledge`)

Two answer modes, selected by `AnswerMode` — both go through `LlmService.sendMessage`:

- **Quick (default)** `sendQuickMessage`: retrieve in code, inject top-3 passages into ONE prompt,
  run a single tool-free, no-reasoning generation. This is the fast path. Grounding is **post-hoc**:
  the answer is attributed to the injected passage with the most term overlap (`pickSourcePassage`),
  not blindly to the top BM25 hit.
- **Thorough** `sendThoroughMessage`: the original agentic loop — Gemma reasoning (`<|think|>`) +
  `searchKnowledge`/`lookupArticle`/`submitAnswer` tools (`ThothTools`), single round of tool
  calling. The per-message "Search in detail" fallback.

Retrieval (`SearchService.search`): ZIM article search (`ZimSource`) → HTML chunking
(`ArticleChunker`, Jsoup, strips Wikipedia chrome) → `Bm25Scorer` ranking → top-K passages.

**Critical, non-obvious design points** (don't "simplify" these away):
- **Keyword pass before search.** Both modes first run a tiny LLM turn to convert the
  natural-language question into Wikipedia keywords (`extractKeywords` / `QUICK_KEYWORDS_SYSTEM_PROMPT`).
  Searching the raw question retrieves garbage (matches song titles, not physics). Keep the keyword
  prompt SHORT — on-device cost is dominated by **prefill**, so a long few-shot block was ~4×
  slower.
- **Latency is ~99% LLM inference, ~1% retrieval.** Reasoning tokens (`<|think|>`) and the
  two-pass agentic loop are the cost drivers — that's why Quick mode omits `<|think|>` and collapses
  to one pass.
- **Nonce grounding** (`NonceRegistry`): citations use 3 case-sensitive letters, not longer ids —
  the 4B model corrupted 8-char hex ids ~30% of the time, falsely un-grounding correct answers.
- `MAX_NUM_TOKENS = 8192` in `LlmService` is a deliberate engine budget; tuning knobs are the
  `QUICK_*` constants there. System prompts are all in `SystemPrompt.kt`.

## Testing & eval infrastructure

- **adb debug harness** (`app/debug/DebugController.kt`): headless, drives queries/searches over
  broadcasts without the UI. Actions: `DEBUG_QUERY` (thorough), `DEBUG_QUERY_QUICK`, `DEBUG_SEARCH`,
  `DEBUG_LOAD`. Outputs to `{externalFilesDir}/debug/`. Keep the app foregrounded during a query.
- **Python two-phase eval** (`eval/`, stdlib only — see `eval/README.md`): Phase 1 (`run_eval.py`)
  drives the desktop `serve` server and computes deterministic metrics; Phase 2 (`judge.py`) scores
  correctness/faithfulness with a cross-family LLM judge (Qwen2.5-7B via `llama-server`).
  `report.py` does a paired McNemar test vs `baseline/baseline.jsonl`. Gold cases in
  `dataset/cases.json`.
- The `EvalRecord` schema is defined once in `:core` so Android and desktop emit **byte-identical**
  lines; re-baseline per platform (desktop-Vulkan vs arm64 aren't bit-identical) and compare
  relative changes only.

## Conventions & gotchas

- **Package root:** `com.bahm.thoth`. App namespace/appId: `com.bahm.thoth`. minSdk 31, target/compile 35.
- **`third_party/java-libkiwix` is a git submodule** — init it before any desktop native build.
- Desktop native `.so` files under `desktop/src/main/resources/native/` are git-ignored; regenerate
  with `build-zim-native.sh`. Keep `LIBZIM_VER` in sync with the submodule's `lib/build.gradle`.
- Write clear, short Bash command descriptions so they're easy to allowlist.
- `IMPLEMENTATION_PLAN.md` is the source of truth for scope, requirements, and per-section
  verification criteria; module details live in `desktop/README.md` and `eval/README.md`.
