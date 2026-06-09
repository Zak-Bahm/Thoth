# Thoth desktop runner (`:desktop`)

A headless x86_64 Linux runner that exercises the **same** `:core` pipeline as the Android app, but
runs inference **in-process** on the desktop LiteRT-LM build (GPU via Vulkan) and reads the ZIM
in-process via locally-built `libzim` JNI bindings. **No sidecar processes.** It emits the same
`eval_session.jsonl` schema as the Android debug harness, so a future scoring harness is
platform-agnostic.

## Prerequisites

- **JDK 21+** — `litertlm-jvm` is compiled for Java 21. The `:desktop` module uses a Java 21
  Gradle toolchain; install a JDK 21 (e.g. `apt install openjdk-21-jdk`) so Gradle can find it.
- **C++ toolchain** for the one-time native build: `g++`, `cmake`-era tools not required (we call
  `g++` directly), plus the JDK's `jni.h`. Already covered by the JDK + `build-essential`.
- **A Vulkan driver** for GPU inference (`vulkaninfo` should list your GPU). Without one,
  LiteRT-LM falls back to bundled SwiftShader (CPU). NVIDIA's proprietary driver provides the
  Vulkan ICD.
- The **model** (`gemma-4-E4B-it.litertlm`) and a **ZIM** file (e.g. `wikipedia_en_all_mini_*.zim`).

## One-time setup

```bash
git submodule update --init third_party/java-libkiwix   # libzim JNI bindings source
./desktop/build-zim-native.sh                            # builds libzim.so.9 + libzim_wrapper.so
```

`build-zim-native.sh` downloads the prebuilt desktop `libzim` (deps statically bundled), generates
JNI headers (`javac -h`) from the submodule's `org.kiwix.libzim` sources, compiles the wrapper, and
stages both `.so` into `desktop/src/main/resources/native/linux-x86_64/` (git-ignored; regenerate as
needed).

## Run

```bash
# Retrieval only (no model needed) — validates the in-process ZIM + chunk + BM25 path:
THOTH_ZIM=/path/to/wiki.zim ./gradlew :desktop:run --args="search deciduous abscission leaf"

# Full eval (GPU inference). Writes <THOTH_OUT>/debug/eval_session.jsonl (same schema as Android):
THOTH_MODEL=/path/to/gemma-4-E4B-it.litertlm THOTH_ZIM=/path/to/wiki.zim THOTH_OUT=./thoth-out \
  ./gradlew :desktop:run --args="query-quick 'what is the capital of france'"

THOTH_MODEL=... THOTH_ZIM=... ./gradlew :desktop:run --args="query 'why is the sky blue'"

# Persistent HTTP server — loads the model once, then handles requests indefinitely:
THOTH_MODEL=... THOTH_ZIM=... THOTH_PORT=8080 ./gradlew :desktop:run --args="serve"
```

| Env var | Meaning | Default |
|---------|---------|---------|
| `THOTH_MODEL` | path to the `.litertlm` model (query/load/serve only) | — |
| `THOTH_ZIM`   | path to the `.zim` archive | — (required) |
| `THOTH_OUT`   | output dir for `debug/eval_session.jsonl` + `perf/` | `./thoth-out` |
| `THOTH_BACKEND` | `gpu` or `cpu` | `gpu` |
| `THOTH_PORT`  | HTTP port for `serve` mode | `8080` |

Commands: `search <q>` · `load` · `query <q>` (thorough) · `query-quick <q>` · `serve`.

### HTTP server (`serve`)

Keeps the model and ZIM resident in memory. Requests are handled sequentially (the inference
engine is single-threaded). Appends to `<THOTH_OUT>/debug/eval_session.jsonl` on each `/query`
call, same as the one-shot commands.

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/health` | `GET` | `{"status":"ok","modelLoaded":true}` |
| `/search` | `GET` | BM25 retrieval — no model. Params: `q=<query>`, `topK=<N>` (default 10, max 50) |
| `/article` | `GET` | Chunked sections (heading, anchor, text) for one title — no model. Param: `title=<t>`. Feeds `eval/extract_cases.py`. |
| `/query`  | `POST` | Full pipeline. Body: `{"query":"…","mode":"quick\|thorough"}`. Returns eval record JSON. |
| `/evals`  | `GET` | All records from `eval_session.jsonl` as a JSON array. Param: `limit=<N>` for the last N records. |

```bash
curl http://localhost:8080/health

curl "http://localhost:8080/search?q=rayleigh+scattering&topK=5"

curl -X POST http://localhost:8080/query \
  -H "Content-Type: application/json" \
  -d '{"query":"why is the sky blue","mode":"quick"}'

curl "http://localhost:8080/evals?limit=10"
```

## Notes

- The eval record schema is defined once in `:core` (`EvalRecord`); Android (`DebugController`) and
  this runner emit byte-identical lines, so re-baseline per platform but compare relative changes.
- x86_64-desktop (Vulkan) vs arm64-device won't be bit-identical — that's expected.
