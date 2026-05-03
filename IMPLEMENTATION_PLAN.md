# Thoth Implementation Plan

## Project Overview

Thoth is an offline Android knowledge assistant. Users ask natural language questions and receive grounded, cited answers drawn from Wikipedia — with no internet connection required after initial setup.

The app runs **Gemma 4 E4B** (a 4-billion-parameter language model by Google) on-device via **LiteRT-LM**, and retrieves source material from **ZIM files** (compressed Wikipedia archives) via **java-libkiwix**. Every answer is anchored to a verifiable source article.

### Why This Architecture

- **On-device LLM** eliminates cloud dependency, latency, and privacy concerns
- **ZIM files** are the established standard for offline Wikipedia (maintained by the Kiwix project)
- **RAG (Retrieval-Augmented Generation)** grounds responses in source material, reducing hallucination
- **Gemma 4 E4B** was chosen over smaller variants because reliable tool calling and passage reasoning require the larger model; the 26B/31B variants target desktop GPUs and are not viable for mobile

### Two-Stage Implementation

The implementation is split into two stages to allow rapid concept validation before investing in retrieval optimization:

- **Stage 1** builds the complete end-to-end app using BM25-only retrieval (~62% recall on semantic queries)
- **Stage 2** integrates binary quantized embeddings and a cross-encoder reranker to improve recall to ~85%+

Each section (1.1, 1.2, etc.) is designed to be implementable in a single focused session.

---

## Specifications

| Spec | Value |
|---|---|
| Language | Kotlin |
| UI framework | Jetpack Compose |
| Min SDK | 31 (Android 12) |
| Target SDK | 35 |
| Minimum device RAM | 12 GB |
| LLM | Gemma 4 E4B, Q4_K_M quantization (~2.5 GB on disk, ~5 GB RAM) |
| LLM framework | LiteRT-LM (`com.google.ai.edge.litertlm:litertlm-android`) |
| Knowledge format | ZIM files via java-libkiwix |
| Default knowledge pack | Wikipedia mini (~11 GB) |
| Optional knowledge pack | Wikipedia nopic (~48 GB, for 256 GB+ devices) |
| DI framework | Hilt |
| Local database | Room |
| Architecture pattern | MVVM with unidirectional data flow |

---

## Project Structure

```
Thoth/
├── app/
│   ├── build.gradle.kts
│   ├── libs/
│   │   └── libkiwix.aar                # Built from java-libkiwix source
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── kotlin/com/bahm/thoth/
│       │   ├── MainActivity.kt
│       │   ├── ThothApplication.kt      # Hilt application class
│       │   ├── di/
│       │   │   └── AppModule.kt         # Hilt dependency providers
│       │   ├── ui/
│       │   │   ├── chat/
│       │   │   │   ├── ChatScreen.kt
│       │   │   │   ├── ChatViewModel.kt
│       │   │   │   └── MessageRenderer.kt
│       │   │   ├── setup/
│       │   │   │   ├── SetupScreen.kt
│       │   │   │   └── SetupViewModel.kt
│       │   │   ├── article/
│       │   │   │   └── ArticleScreen.kt  # Full article viewer
│       │   │   ├── components/
│       │   │   ├── navigation/
│       │   │   │   └── NavGraph.kt
│       │   │   └── theme/
│       │   │       ├── Theme.kt
│       │   │       └── Color.kt
│       │   ├── inference/
│       │   │   ├── LlmService.kt
│       │   │   └── ToolHandler.kt
│       │   ├── knowledge/
│       │   │   ├── ZimRepository.kt
│       │   │   ├── SearchService.kt
│       │   │   ├── ArticleChunker.kt
│       │   │   ├── Bm25Scorer.kt
│       │   │   ├── models/
│       │   │   │   ├── Article.kt
│       │   │   │   ├── Passage.kt
│       │   │   │   └── SearchResult.kt
│       │   │   └── embedding/            # Stage 2
│       │   │       ├── EmbeddingService.kt
│       │   │       ├── EmbeddingIndex.kt
│       │   │       └── CrossEncoderReranker.kt
│       │   └── data/
│       │       ├── AppDatabase.kt
│       │       ├── ChatHistoryDao.kt
│       │       └── models/
│       │           ├── ChatMessageEntity.kt
│       │           └── ConversationEntity.kt
│       └── res/
├── build.gradle.kts                      # Root build file
├── settings.gradle.kts
├── gradle.properties
└── tools/                                # Stage 2
    └── index_builder/
        ├── build_index.py
        └── requirements.txt
```

---

# Stage 1: End-to-End App with BM25 Retrieval

---

## Section 1.1 — Project Setup & Build Configuration ✅

### Goal

Create the Android project skeleton with all dependencies configured, a compilable app shell, and Hilt dependency injection wired up.

### Requirements

1. Initialize a Gradle KTS Android project at the repo root
2. Configure `build.gradle.kts` (root and app-level) with all Stage 1 dependencies
3. Create `AndroidManifest.xml` with required permissions
4. Create `ThothApplication.kt` with `@HiltAndroidApp`
5. Create `MainActivity.kt` with `@AndroidEntryPoint` and a placeholder Compose UI
6. Create `AppModule.kt` providing singletons for `ZimRepository`, `SearchService`, `LlmService`
7. Verify the project compiles (no runtime functionality yet)

### Dependencies (app/build.gradle.kts)

> **Note:** Several dependency versions were updated during implementation because LiteRT-LM 0.11.0-rc1 ships Kotlin metadata version 2.3.0, which forced a Kotlin upgrade from 2.1.0 to 2.3.0. This cascaded into KSP, Hilt, and AGP upgrades. The `kotlin.plugin.compose` plugin is also required for Kotlin 2.3.0 Compose support. See the actual `build.gradle.kts` files for authoritative versions.

**Root plugins (build.gradle.kts):**

| Plugin | Version |
|---|---|
| com.android.application | 8.9.1 |
| org.jetbrains.kotlin.android | 2.3.0 |
| org.jetbrains.kotlin.plugin.compose | 2.3.0 |
| com.google.dagger.hilt.android | 2.58 |
| com.google.devtools.ksp | 2.3.7 |

**Key dependency versions (app/build.gradle.kts):**

| Dependency | Version | Notes |
|---|---|---|
| LiteRT-LM | latest.release (resolved to 0.11.0-rc1) | Requires Kotlin 2.3.0 metadata |
| Compose BOM | 2025.04.00 | |
| Hilt | 2.58 | Last version supporting AGP 8.x |
| kotlin-metadata-jvm | 2.3.0 | Added as KSP dep — workaround for Hilt metadata compat |
| Room | 2.6.1 | |
| Navigation Compose | 2.8.0 | |
| Lifecycle | 2.8.0 | |
| Coroutines | 1.9.0 | |
| DataStore | 1.1.0 | |
| WebKit | 1.11.0 | |
| Jsoup | 1.17.2 | |
| java-libkiwix | 2.5.0 | From Maven Central (`org.kiwix:libkiwix`) |

```kotlin
// Kotlin jvmTarget is now configured via the compilerOptions DSL (required by Kotlin 2.3.0):
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
// The old kotlinOptions { jvmTarget = "17" } DSL is an error in Kotlin 2.3.0.
```

### Permissions (AndroidManifest.xml)

```xml
<!-- No internet permission — app is fully offline -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />
<!-- Foreground service for model loading notification -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

### Guidance

- java-libkiwix is available on Maven Central as `org.kiwix:libkiwix:2.5.0`. No local AAR build needed.
- Use KSP (not KAPT) for annotation processing — it's faster and is the recommended path for Hilt and Room.
- LiteRT-LM version coordinates may change; check https://ai.google.dev/edge/litert-lm/android for the latest.

### Verification

- [x] `./gradlew assembleDebug` completes without errors
- [x] App installs and launches on a device/emulator showing a placeholder screen
- [x] Hilt injection graph compiles (no missing bindings)

---

## Section 1.2 — ZIM Integration & Search ✅

### Goal

Integrate java-libkiwix, add in-app ZIM archive downloading, implement data models and ZimRepository, and deliver a demo UI for downloading Wikipedia archives and performing manual searches.

### Requirements

#### 1.2a — Data Models

Create `knowledge/models/`:

```kotlin
// Article.kt
data class Article(
    val title: String,
    val path: String,           // ZIM entry path
    val htmlContent: String,    // Raw HTML from ZIM
)

// Passage.kt
data class Passage(
    val id: String,             // "{articlePath}#{chunkIndex}"
    val text: String,           // Plain text content
    val articleTitle: String,
    val sectionHeading: String?,
    val zimEntryPath: String,
    val chunkIndex: Int,
)

// SearchResult.kt
data class SearchResult(
    val passages: List<Passage>,
    val query: String,
    val searchTimeMs: Long,
)
```

#### 1.2b — ZimRepository

Wrapper around java-libkiwix's `Archive` class. java-libkiwix is available on Maven Central as `org.kiwix:libkiwix:2.5.0` — the `org.kiwix.libzim` package provides full Xapian search support via `Searcher`, `Query`, `Search`, and `SearchIterator` classes.

```kotlin
@Singleton
class ZimRepository @Inject constructor() {
    private var archive: Archive? = null

    suspend fun open(zimFilePath: String)
    suspend fun close()
    fun isOpen(): Boolean
    suspend fun getArticleByTitle(title: String): Article?
    suspend fun getArticleByPath(path: String): Article?
    suspend fun getArticleContent(path: String): String   // Returns raw HTML
    suspend fun searchArticles(query: String, maxResults: Int = 20): List<Article>
}
```

**Full-text search implementation:** Use libzim's Xapian full-text search:
- `Searcher(archive).search(Query(queryString)).getResults(0, maxResults)`
- `SearchIterator` yields `Entry` objects with `getTitle()`, `getPath()`, `getScore()`
- Load HTML via `entry.getItem(true).getData().getData()` (follow redirects)
- Check `archive.hasFulltextIndex()` — Wikipedia mini/nopic ZIMs include Xapian indexes
- Fall back to `SuggestionSearcher` (title-prefix) if no fulltext index is present

**Resource management:** All libzim objects (`Searcher`, `Query`, `Search`, `SearchIterator`, `Blob`) have `dispose()` methods — call them via try/finally to prevent native memory leaks.

**Thread safety:** The `Archive` object is not thread-safe — use a `Mutex` and confine all access to `Dispatchers.IO`.

#### 1.2c — ZimDownloadService

Downloads ZIM archives from `https://download.kiwix.org/zim/wikipedia/`.

```kotlin
@Singleton
class ZimDownloadService @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class DownloadProgress(val bytesDownloaded: Long, val totalBytes: Long)

    fun download(url: String, filename: String): Flow<DownloadProgress>
    fun cancelDownload()
    fun getDownloadDir(): File  // context.getExternalFilesDir(null)/zim/
}
```

- Use OkHttp streaming download to `context.getExternalFilesDir(null)/zim/`
- Emit `DownloadProgress` via `Flow` for UI progress bar
- Support resume via HTTP Range header (ZIM files are large)
- Cancellation via coroutine cancellation

Available archives (hardcoded for now, OPDS discovery can be added later):
- Wikipedia mini EN: `wikipedia_en_all_mini_2026-03.zim` (~12 GB)
- Wikipedia nopic EN: `wikipedia_en_all_nopic_2026-03.zim` (~48 GB)

#### 1.2d — Demo UI

Replace the placeholder "Thoth" text in `MainActivity` with a demo screen (`ui/demo/ZimDemoScreen.kt` + `ZimDemoViewModel.kt`):

1. **Download section:** Two buttons — "Download Wikipedia Mini (~12 GB)" and "Download Wikipedia Nopic (~48 GB)". Progress bar showing download progress. Status text (downloading, complete, error).
2. **Archive section:** After download, show archive info (filename, article count from `archive.getAllEntryCount()`, has fulltext index).
3. **Search section:** Text field for entering search queries. "Search" button. Results list showing article titles and Xapian relevance scores. Tap an article to see its raw HTML content in a scrollable text view.

This is a temporary developer screen — it will be replaced by the real setup flow in Section 1.7.

### Dependencies

Add to `app/build.gradle.kts`:

```kotlin
// java-libkiwix (replaces commented-out local AAR)
implementation("org.kiwix:libkiwix:2.5.0")

// OkHttp (for ZIM downloading)
implementation("com.squareup.okhttp3:okhttp:4.12.0")

// Test dependencies
testImplementation("junit:junit:4.13.2")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
```

Add to `AndroidManifest.xml`:

```xml
<!-- Required for ZIM archive downloads — app remains offline after setup -->
<uses-permission android:name="android.permission.INTERNET" />
```

### Guidance

- Use KSP (not KAPT) for annotation processing — already configured in Section 1.1.
- All ZIM I/O must run on `Dispatchers.IO` behind a `Mutex`.
- The demo UI is throwaway — keep it simple, no need for polish.
- For the download service, OkHttp's streaming body is sufficient. No need for a full download manager library.

### Verification

- [x] `./gradlew assembleDebug` compiles with libkiwix from Maven Central
- [x] App launches and shows the demo screen with download buttons
- [x] Downloading a ZIM file shows progress and completes successfully
- [x] After download, archive info displays (article count, fulltext index status)
- [x] Typing a query and searching returns relevant article titles with scores
- [x] Tapping a result shows the article's raw HTML content
- [x] Full-text search works (not just title matching)

---

## Section 1.3 — Article Chunking & Search Pipeline ✅

### Goal

Add the passage-level retrieval pipeline: chunk HTML articles into ~512-token passages, rank them with BM25, and orchestrate the full search flow via `SearchService`. Extend the demo UI to show chunked passages and BM25-ranked results.

### Requirements

#### 1.3a — ArticleChunker

Converts raw HTML articles into ~512-token plain text passages.

**Algorithm:**
1. Parse HTML and extract structural elements (headings, paragraphs, tables, lists)
2. Walk elements in document order, tracking the current `<h2>`/`<h3>` section heading
3. Accumulate text into a buffer. Start a new chunk when:
   - A `<h2>` or `<h3>` is encountered (always starts a new chunk)
   - The buffer exceeds ~512 tokens (~2048 characters as a rough proxy, or use a simple whitespace tokenizer)
4. When splitting within a paragraph, find the last sentence boundary (`.`, `!`, `?` followed by whitespace) before the 512-token limit
5. Overlap: when starting a new chunk, carry the last 64 tokens (~256 characters) from the previous chunk's end
6. Tables: if a `<table>` fits in 512 tokens, keep it as a single chunk. If larger, skip it (tables in Wikipedia are often data-heavy and low-value for QA).
7. Lists: keep `<ul>`/`<ol>` items together when possible

**HTML parsing:** Use Jsoup (`org.jsoup:jsoup:1.17.2`, already in deps) for DOM traversal and structural extraction.

**Output:** `List<Passage>` with metadata (article title, section heading, ZIM path, chunk index).

#### 1.3b — Bm25Scorer

In-memory BM25 scoring over a set of candidate passages. This is NOT a search index — it re-ranks passages that have already been retrieved.

**Implementation:**
```
score(query, passage) = sum over query terms t:
    IDF(t) * (tf(t, passage) * (k1 + 1)) / (tf(t, passage) + k1 * (1 - b + b * |passage| / avgdl))

where:
    tf(t, passage) = count of term t in passage
    IDF(t) = log((N - n(t) + 0.5) / (n(t) + 0.5) + 1)
    N = total number of candidate passages
    n(t) = number of passages containing term t
    |passage| = length of passage in tokens
    avgdl = average passage length across candidates
    k1 = 1.2, b = 0.75
```

- Tokenize by splitting on whitespace and punctuation, lowercasing
- Remove a small stopword set (the, is, at, which, on, a, an, and, or, but, in, with, to, for, of)
- IDF is computed over the candidate set only (typically 50-200 passages), not the global corpus

#### 1.3c — SearchService

Orchestrates the full retrieval pipeline.

```kotlin
@Singleton
class SearchService @Inject constructor(
    private val zimRepository: ZimRepository,
    private val chunker: ArticleChunker,
    private val bm25Scorer: Bm25Scorer,
) {
    suspend fun search(query: String, topK: Int = 5): SearchResult {
        // 1. Search ZIM for top 20 matching articles
        // 2. Retrieve HTML content for each
        // 3. Chunk each article into passages
        // 4. BM25-rank all passages against query
        // 5. Return top-K passages with metadata
    }
}
```

Run the entire pipeline on `Dispatchers.IO`. Measure and log total search time.

#### 1.3d — Demo UI Extension

Extend the demo screen from Section 1.2 with a pipeline search tab/section:

- Text field + "Search Pipeline" button running `SearchService.search()` end-to-end
- Results displayed as ranked passages with: article title, section heading, passage text (truncated preview), BM25 score, search time in milliseconds
- Allows comparing raw ZIM search (article-level) vs. pipeline search (passage-level)

### Guidance

- The BM25 scorer is ~50 lines of code. Don't over-engineer it — it processes at most a few hundred passages per query.
- For tokenization in BM25, a simple `text.lowercase().split(Regex("[\\s\\p{Punct}]+"))` is sufficient.
- Cap the search pipeline at a ~2 second timeout. If ZIM search is slow, reduce the article count from 20 to 10.

### Verification

- [x] ArticleChunker produces passages of ~400-600 tokens with proper overlap
- [x] Chunks respect section boundaries and sentence boundaries
- [x] BM25 scorer ranks passages with query-term matches higher
- [x] Full `SearchService.search()` pipeline completes in <2 seconds
- [x] Unit tests pass for `ArticleChunker` and `Bm25Scorer` with sample HTML input
- [x] Demo UI shows passage-level results with scores and timing

---

## Section 1.4 — LLM Integration (Model Download, Loading & Basic Chat)

### Goal

Integrate Gemma 4 E4B via LiteRT-LM: download the model, load it into RAM, and get basic (non-tool-calling) chat working in the demo UI. Tool calling, system prompts, and citation enforcement are deferred to Section 1.5.

### Requirements

#### 1.4a — LlmService

Manages engine lifecycle and basic conversation.

```kotlin
@Singleton
class LlmService @Inject constructor() {
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    suspend fun initialize(modelPath: String)       // Load model, ~5-15 sec
    fun isInitialized(): Boolean
    fun createConversation()                         // Basic chat, no tools yet
    fun sendMessage(text: String): Flow<String>      // Streaming token output
    fun resetConversation()
    fun release()                                    // Free model resources
}
```

**Model loading:**
```kotlin
suspend fun initialize(modelPath: String) = withContext(Dispatchers.Default) {
    val config = EngineConfig(modelPath = modelPath)
    engine = Engine(config)
    engine!!.initialize()  // Blocking — loads weights into RAM
}
```

**Conversation setup (basic — no tools or system prompt):**
```kotlin
fun createConversation() {
    val config = ConversationConfig(
        samplerConfig = SamplerConfig(topK = 40, topP = 0.95f, temperature = 0.7f),
    )
    conversation = engine!!.createConversation(config)
}
```

**Streaming inference:**
```kotlin
fun sendMessage(text: String): Flow<String> {
    return conversation!!.sendMessageAsync(text)
        .map { message ->
            // Each emission is a Message object — extract text content
            message.contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString("") { it.text }
        }
}
```

**API notes:**
- `ConversationConfig.systemInstruction` takes `Contents?`, not `String` — use `Contents.of(...)` when adding system prompt in Section 1.5
- Engine and Conversation implement `AutoCloseable` — use `.close()` in `release()`
- `engine.initialize()` is a blocking CPU-heavy call — must not run on main thread

#### 1.4b — ModelDownloadService

Downloads the Gemma 4 E4B `.litertlm` model file to app storage. Follows the same pattern as `ZimDownloadService`/`ZimDownloadForegroundService`.

```kotlin
@Singleton
class ModelDownloadService @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun getDownloadDir(): File      // context.getExternalFilesDir(null)/models/
    fun getModelFile(): File?       // Returns model file if it exists
}
```

- Download URL: from HuggingFace (`https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/<filename>`)
- Destination: `context.getExternalFilesDir(null)/models/`
- Uses a foreground service for download (same pattern as ZIM downloads)
- Emits download progress via `StateFlow` for UI progress bar
- Supports resume via HTTP Range header (model is ~2.5 GB)
- Cancellation via coroutine cancellation
- Uses OkHttp (already in deps)

#### 1.4c — Demo UI Extension

Extend the demo screen with model download, loading, and basic chat sections:

**Model Download section:**
- "Download Gemma 4 E4B (~2.5 GB)" button
- Progress bar during download
- Status text (downloading / complete / error)

**Model Loading section (visible after download):**
- "Load Model" button — shows loading spinner during 5-15 sec load
- Status indicator: Loading / Ready / Error

**Basic Chat section (visible after model loaded):**
- Text input field
- "Send" button — disabled while generating
- Scrollable text area showing streaming response

### Dependencies

No new dependencies needed — LiteRT-LM and OkHttp are already configured.

### Guidance

- LiteRT-LM documentation: https://ai.google.dev/edge/litert-lm/android
- LiteRT-LM Kotlin API: https://github.com/google-ai-edge/LiteRT-LM
- The model file format is `.litertlm` — ensure the correct Gemma 4 E4B quantized model is used. Check https://huggingface.co/litert-community for pre-converted models.
- Model loading takes 5-15 seconds. Show a loading indicator.
- Token generation speed: expect 6-12 tok/s on flagship devices (Snapdragon 8 Gen 2+), slower on mid-range. A 500-token response takes 40-80 seconds.
- Batch UI updates during token streaming (every ~50ms) to avoid excessive recomposition.

### Verification

- [ ] `./gradlew assembleDebug` compiles without errors
- [ ] Model download shows progress and completes successfully
- [ ] Model loads successfully and reports ready state (5-15 sec with loading indicator)
- [ ] Sending a message produces streamed token output in the demo UI
- [ ] Error handling: app shows meaningful error if model file is missing or corrupt
- [ ] Memory: model + inference stays under ~5.5 GB RAM
- [ ] Reset/release works — no memory leaks after releasing model

---

## Section 1.5 — Tool Calling & Cited Responses

### Goal

Wire the LLM to the search pipeline via LiteRT-LM's tool calling so the model can invoke `searchKnowledge` and `lookupArticle`, add a system prompt enforcing citation rules, and implement fallback logic for when the model skips search.

### Requirements

#### 1.5a — ThothTools (Tool Definitions)

```kotlin
class ThothTools @Inject constructor(
    private val searchService: SearchService,
    private val zimRepository: ZimRepository,
) : ToolSet {

    var callCount: Int = 0  // Instrumentation for ToolHandler

    @Tool(description = "Search Wikipedia for articles matching a query. You MUST call this tool before answering ANY factual question. Use specific keywords, names, and technical terms.")
    fun searchKnowledge(
        @ToolParam(description = "Search query with specific keywords")
        query: String
    ): List<Map<String, String>> {
        callCount++
        val result = runBlocking { searchService.search(query, topK = 5) }
        return result.passages
            .enforceBudget(MAX_CONTEXT_CHARS)
            .map { passage ->
                mapOf(
                    "title" to passage.articleTitle,
                    "section" to (passage.sectionHeading ?: ""),
                    "content" to passage.text,
                )
            }
    }

    @Tool(description = "Retrieve the full content of a specific Wikipedia article by its exact title. Use this when you need more detail from an article found via searchKnowledge.")
    fun lookupArticle(
        @ToolParam(description = "Exact Wikipedia article title")
        title: String
    ): Map<String, String> {
        callCount++
        val article = runBlocking { zimRepository.getArticleByTitle(title) }
        // Strip HTML to plain text with Jsoup, truncate to ~16000 chars
        val plainText = Jsoup.parse(article?.htmlContent ?: "").text().take(16000)
        return mapOf(
            "title" to (article?.title ?: "Not found"),
            "content" to plainText.ifEmpty { "Article not found." },
        )
    }
}
```

**Note on `runBlocking`:** LiteRT-LM's tool calling executes tools synchronously on its internal thread. The tools must return values directly, not suspend functions. Use `runBlocking` to bridge to the suspend-based `SearchService`. This is acceptable because tool execution happens on LiteRT-LM's inference thread, not the main thread.

#### 1.5b — Context Budget

- Limit tool results injected into context to ~4,000 tokens total (~16,000 characters)
- This means top 5 passages of ~800 tokens each
- Gemma 4 E4B has a 128K context window, but small models degrade with long contexts — attention becomes diffuse and the model may hallucinate or ignore later passages
- If passage text exceeds the budget, drop trailing passages (keep highest-ranked)

#### 1.5c — System Prompt

```
You are Thoth, an offline knowledge assistant. You answer questions using Wikipedia articles stored on this device.

RULES:
1. ALWAYS call searchKnowledge before answering any factual question. Never answer from memory alone.
2. After receiving search results, synthesize a clear answer based ONLY on the retrieved content.
3. Cite your sources: end each factual claim with [Article Title].
4. If the search results don't contain enough information to answer, say so honestly. Never fabricate information.
5. You may format your response using basic HTML: <b>, <i>, <table>, <ul>, <ol>, <li>, <h3>, <p>, <br>.
6. Keep responses concise — 2-4 paragraphs maximum unless the user asks for detail.
7. For follow-up questions about a topic already discussed, you may reference previously retrieved content without searching again.
```

Store as a constant in `SystemPrompt.kt` for easy iteration.

#### 1.5d — ToolHandler (Fallback Logic)

The `ToolHandler` guards against the model answering without calling tools.

**Detection:** Use instrumentation — `ThothTools.callCount` is reset before each user message and checked after response completes. If `callCount == 0`, the model skipped search.

**Action on skip:** Return the user's query as a fallback search query. The ViewModel can then manually call `searchKnowledge`, inject results, and re-prompt — or log a warning for tuning in Section 1.8.

**Malformed tool calls:** LiteRT-LM's constrained decoding should prevent this when tool definitions are registered. If it still occurs, catch JSON parse errors and fall back to `searchKnowledge` with extracted query terms.

**Implementation depends on LiteRT-LM's behavior with `automaticToolCalling`:** If the framework guarantees tools are always called before a response is generated (based on the system prompt), explicit detection may not be needed. Test this empirically during implementation and add fallback logic only if the model produces ungrounded responses.

#### 1.5e — LlmService Updates

Modify `LlmService` (from Section 1.4) to wire in tools and system prompt:

- Add `ThothTools` to constructor
- Update `createConversation()`:
  - Add `systemInstruction = Contents.of(SystemPrompt.THOTH_SYSTEM_PROMPT)`
  - Add `tools = listOf(tool(thothTools))`
  - Add `automaticToolCalling = true`
- Expose `getToolCallCount()` and `resetToolCallCount()` for ToolHandler

**If `automaticToolCalling` doesn't reliably force search:** Switch to `automaticToolCalling = false` and manually parse + route tool calls in `sendMessage()`.

### Guidance

- Test tool calling behavior early. If `automaticToolCalling` doesn't reliably force search, switch to `automaticToolCalling = false` and manually parse + route tool calls.
- Add ProGuard keep rule for `ThothTools`: `-keep class com.bahm.thoth.inference.ThothTools { *; }` — `@Tool`/`@ToolParam` annotations use runtime reflection.
- Token generation speed: expect 6-12 tok/s on flagship devices (Snapdragon 8 Gen 2+), slower on mid-range. A 500-token response takes 40-80 seconds.

### Verification

- [ ] Tool calling works: model generates `searchKnowledge` call, results are returned, model produces cited response
- [ ] System prompt enforcement: model cites sources in every factual response
- [ ] Context stays within ~4,000 token budget for retrieved passages
- [ ] `lookupArticle` returns stripped plain text, not raw HTML
- [ ] ToolHandler detects when model skips search
- [ ] Error handling: meaningful errors for edge cases (no search results, article not found)

---

## Section 1.6 — Chat UI

### Goal

Build the chat interface: message list with streaming responses, HTML rendering via WebView, source citation chips, and a text input bar.

### Requirements

#### 1.6a — Chat Data Model (UI layer)

```kotlin
enum class Role { USER, ASSISTANT }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val content: String,
    val htmlContent: String? = null,     // Rendered HTML for assistant messages
    val sources: List<Source> = emptyList(),
    val isStreaming: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
)

data class Source(
    val articleTitle: String,
    val zimEntryPath: String,
)
```

#### 1.6b — ChatViewModel

```kotlin
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val llmService: LlmService,
    private val searchService: SearchService,
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    fun sendMessage(text: String) {
        viewModelScope.launch {
            // 1. Add user message to list
            // 2. Add empty assistant message with isStreaming = true
            // 3. Collect tokens from llmService.sendMessage()
            // 4. Update assistant message content progressively
            // 5. When complete: set isStreaming = false, extract sources, persist
        }
    }
}
```

**Streaming UX:** Update the assistant message in `_messages` as each token arrives. Use `StateFlow` — Compose will recompose only the affected message item. Avoid updating more frequently than every ~50ms to prevent excessive recomposition (batch tokens if they arrive faster).

#### 1.6c — ChatScreen

```kotlin
@Composable
fun ChatScreen(viewModel: ChatViewModel = hiltViewModel()) {
    val messages by viewModel.messages.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Message list
        LazyColumn(
            modifier = Modifier.weight(1f),
            reverseLayout = true,  // Newest messages at bottom
        ) {
            items(messages.reversed(), key = { it.id }) { message ->
                when (message.role) {
                    Role.USER -> UserMessageBubble(message)
                    Role.ASSISTANT -> AssistantMessageBubble(message)
                }
            }
        }

        // Input bar
        ChatInputBar(
            onSend = { viewModel.sendMessage(it) },
            enabled = !isGenerating,
        )
    }
}
```

#### 1.6d — MessageRenderer (WebView for HTML)

Assistant responses may contain HTML formatting (tables, lists, bold/italic). Render them in a sandboxed WebView.

```kotlin
@Composable
fun AssistantMessageBubble(message: ChatMessage) {
    Column {
        // HTML content in WebView
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = false   // No JS needed
                    settings.domStorageEnabled = false
                    settings.allowFileAccess = false
                    // Sandboxed: no network, no JS, no file access
                }
            },
            update = { webView ->
                val styledHtml = wrapWithCss(message.content)
                webView.loadDataWithBaseURL(
                    null, styledHtml, "text/html", "utf-8", null
                )
            }
        )

        // Source citation chips
        if (message.sources.isNotEmpty()) {
            FlowRow {
                message.sources.forEach { source ->
                    SourceChip(source = source, onClick = { /* navigate to article */ })
                }
            }
        }
    }
}
```

**CSS wrapper:** Provide a `wrapWithCss()` function that wraps the model's HTML output in a `<html><head><style>...</style></head><body>...</body></html>` template with:
- Font family matching the app's Material theme
- Reasonable max-width and padding
- Table styling (borders, padding)
- Dark/light mode support via `@media (prefers-color-scheme: dark)`

**WebView height:** WebView inside a `LazyColumn` item needs explicit height. Use `WebView.addJavascriptInterface` to measure content height and update the Compose layout, OR use a `NestedScrollView` approach. Alternatively, avoid WebView for simple responses and only use it when the response contains HTML tags.

**Simpler alternative:** For v1, consider rendering plain text with Compose `Text` and only using WebView for responses containing `<table>` or other complex HTML. Use Compose's `AnnotatedString` for basic bold/italic.

#### 1.6e — Source Citations

- Extract sources from the tool call results that were used to generate the response
- Display as Material3 `AssistChip` components below each message
- Tapping a chip navigates to `ArticleScreen` which renders the full ZIM article HTML in a WebView

#### 1.6f — ChatInputBar

- `TextField` with a send `IconButton`
- Disabled while `isGenerating` is true
- Show a subtle progress indicator (e.g., pulsing dots) in the assistant message area while generating

### Guidance

- Start with plain text rendering. Get the full pipeline working (type question -> get answer) before adding WebView HTML rendering. WebView in LazyColumn has known complexity around height measurement.
- Use `reverseLayout = true` on LazyColumn so new messages appear at the bottom and the list auto-scrolls.
- For WebView height, the simplest approach is setting a fixed `maxHeight` (e.g., 400.dp) with scrolling inside the WebView. Refine later.
- Test on a real device, not just emulator — WebView performance varies significantly.

### Verification

- [ ] User can type a message and see it appear in the chat
- [ ] Assistant response streams in token-by-token
- [ ] HTML content renders correctly in WebView (tables, lists, bold)
- [ ] Source citation chips appear below assistant messages
- [ ] Tapping a source chip shows the full article
- [ ] Input is disabled during generation
- [ ] Chat scrolls to newest message automatically
- [ ] UI is responsive — no jank during token streaming

---

## Section 1.7 — Setup Flow & Data Persistence

### Goal

Build the first-launch setup screen (file selection for model and ZIM) and Room-based chat history persistence.

### Requirements

#### 1.7a — SetupScreen

First-launch flow presented when model or ZIM file paths are not configured:

1. **Welcome screen** explaining what the app does and what files are needed
2. **Model file selection:** File picker to locate the Gemma 4 E4B `.litertlm` file on device storage. Show expected file size (~2.5 GB). Validate the file exists and is readable.
3. **ZIM file selection:** File picker to locate the Wikipedia mini `.zim` file. Show expected file size (~11 GB). Validate by attempting to open as an `Archive`.
4. **Initialization:** Load model (show progress bar — 5-15 seconds). Open ZIM archive. Navigate to ChatScreen on success.

Store file paths in DataStore Preferences. On subsequent launches, check if paths are still valid (files may have been deleted), and go directly to ChatScreen if so.

```kotlin
@HiltViewModel
class SetupViewModel @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val llmService: LlmService,
    private val zimRepository: ZimRepository,
) : ViewModel() {
    val setupState: StateFlow<SetupState>  // NotStarted, SelectingModel, SelectingZim, Initializing, Ready, Error
    fun setModelPath(uri: Uri)
    fun setZimPath(uri: Uri)
    suspend fun initialize(): Result<Unit>
}
```

**File access:** Use `ACTION_OPEN_DOCUMENT` intent with the Storage Access Framework (SAF) for file selection. Copy or note the persistent URI. For ZIM files in app-specific storage (`getExternalFilesDir()`), direct file paths work. For files elsewhere, SAF URIs must be resolved.

**Note:** ZIM files are large. Copying them into app-specific storage is ideal for performance (direct file I/O) but doubles storage usage temporarily. Consider allowing in-place access if the file is already on accessible storage.

#### 1.7b — Room Database

```kotlin
@Database(
    entities = [ConversationEntity::class, ChatMessageEntity::class],
    version = 1,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatHistoryDao(): ChatHistoryDao
}

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,       // Auto-generated from first user message
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "messages")
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,        // "user" or "assistant"
    val content: String,
    val sourcesJson: String, // JSON array of {title, path} objects
    val timestamp: Long,
)

@Dao
interface ChatHistoryDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessages(conversationId: String): Flow<List<ChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessages(conversationId: String)
}
```

#### 1.7c — Navigation

```kotlin
// NavGraph.kt
@Composable
fun ThothNavGraph(navController: NavHostController) {
    NavHost(navController, startDestination = "setup_or_chat") {
        composable("setup") { SetupScreen(navController) }
        composable("chat") { ChatScreen(navController) }
        composable("chat/{conversationId}") { ChatScreen(navController, conversationId) }
        composable("article/{zimPath}") { ArticleScreen(navController, zimPath) }
    }
}
```

Determine start destination by checking DataStore for valid model + ZIM paths on app launch.

### Guidance

- Use Kotlin serialization (`kotlinx.serialization.json`) for `sourcesJson` in Room entities rather than hand-rolling JSON.
- Auto-generate conversation titles by truncating the first user message to ~50 characters.
- Don't build a full conversation list UI in this section — just ensure data persistence works. A conversation list can be added later.
- For the file picker, `ActivityResultContracts.OpenDocument()` with MIME type `*/*` works for both model and ZIM files.

### Verification

- [ ] First launch shows setup screen
- [ ] File picker allows selecting model and ZIM files
- [ ] Invalid files show appropriate error messages
- [ ] Model loading shows progress and completes successfully
- [ ] Subsequent launches skip setup and go to chat
- [ ] Chat messages persist across app restarts
- [ ] Conversation data can be retrieved from Room

---

## Section 1.8 — Integration Testing & Polish

### Goal

Wire all components together, test end-to-end on a real device, and fix issues found during integration.

### Requirements

1. **End-to-end flow:** Setup -> ask question -> search -> generate response -> display with citations -> persist
2. **Error states:** Handle and display errors for:
   - Model file missing or corrupt
   - ZIM file missing or corrupt
   - Model out of memory (catch OOM, show message to close other apps)
   - Search returns no results (show "I couldn't find relevant information" message)
3. **Loading states:** Show appropriate indicators during:
   - Model initialization (5-15 seconds)
   - Search + generation (potentially 30-90 seconds for full response)
4. **Memory monitoring:** Log RSS memory usage during inference. Verify total stays under 7 GB.

### Verification (Stage 1 complete)

- [ ] App launches, loads model, opens ZIM file without crashing on a 12 GB device
- [ ] User can type a question and receive a streamed response
- [ ] Every response includes at least one source citation
- [ ] Source citations link to the actual ZIM article content
- [ ] Factual queries with clear keywords ("population of France", "who invented penicillin") return correct answers
- [ ] Semantic queries ("why is the sky blue") surface relevant passages (may be imperfect — this is the BM25 baseline)
- [ ] Model never answers without searching (enforced by ToolHandler fallback)
- [ ] App handles error states gracefully (missing files, OOM, no results)
- [ ] Chat history persists across app restarts
- [ ] Memory usage stays under 7 GB total during active inference

---

# Stage 2: Embedding-Based Retrieval Integration

---

## Section 2.1 — Server-Side Embedding Index Builder

### Goal

Build a Python tool that processes a ZIM file and produces a binary embedding index + passage metadata file for distribution alongside the ZIM.

### Requirements

Create `tools/index_builder/` with:

#### 2.1a — build_index.py

**Pipeline:**
1. Open ZIM file using `libzim` Python bindings (`pip install libzim`)
2. Iterate all articles, extract HTML content
3. Chunk using the same algorithm as the Android `ArticleChunker` — **chunks must be identical** so passage IDs from the index map correctly to on-device chunking
4. Encode all passages with `snowflake-arctic-embed-xs` (22M params, 384 dims)
5. Binary-quantize embeddings: `binary = (embedding > 0).astype(np.uint8)`, pack bits
6. Build USearch HNSW index over binary vectors
7. Write passage metadata (passage ID → article path + chunk index mapping)
8. Output files:
   - `thoth-embeddings-mini.usearch` (~1.1 GB for Wikipedia mini's ~12M passages)
   - `passage-metadata.bin` (~150 MB)

#### 2.1b — Chunking Parity

The Python chunker MUST produce identical output to the Kotlin `ArticleChunker` for the same input HTML. This is critical — if chunks differ, the passage IDs in the embedding index won't match what the Android app retrieves from the ZIM.

**Strategy:** Extract the chunking algorithm into a shared specification (this plan's Section 1.2c defines it). Implement once in Python, once in Kotlin, and verify with test cases. Alternatively, have the Python builder also output the chunk text alongside the index, and have the Android app use the stored text instead of re-chunking — at the cost of additional storage.

#### 2.1c — Evaluation

Run retrieval evaluation on a test set:
- Use NaturalQuestions or TriviaQA, filtered to questions answerable from Wikipedia
- For each question, check if the gold article appears in top-5 and top-20 retrieved passages
- Measure:
  - BM25-only recall@5 and recall@20 (baseline)
  - Binary embedding recall@5 and recall@20
  - Hybrid (RRF) recall@5 and recall@20
  - Hybrid + cross-encoder rerank precision@5

### Dependencies (requirements.txt)

```
libzim>=3.3.0
sentence-transformers>=3.0.0
usearch>=2.12.0
numpy>=1.26.0
torch>=2.3.0
beautifulsoup4>=4.12.0
tqdm>=4.66.0
```

### Guidance

- Encoding 12M passages on a single A100 takes ~2-4 hours. Use batched inference (`batch_size=512`).
- Binary quantization is trivial: `np.packbits((embeddings > 0).astype(np.uint8), axis=1)` — this packs 384 dims into 48 bytes per passage.
- USearch Python: `from usearch.index import Index; index = Index(ndim=384, metric='hamming', dtype='b1x8')`
- The passage metadata format should be simple: a flat binary file where passage ID `i` maps to a fixed-size record `{article_path_offset: uint32, chunk_index: uint16}` with a separate string table for article paths.
- Store a version/hash of the ZIM file in the index metadata so the app can verify compatibility.

### Verification

- [ ] Index builder runs to completion on Wikipedia mini ZIM
- [ ] Output files are the expected sizes (~1.1 GB + ~150 MB)
- [ ] Evaluation shows binary embedding recall@20 > 70%
- [ ] Evaluation shows hybrid (BM25 + embedding) recall@20 > 80%
- [ ] Index loads correctly in USearch and returns results for test queries

---

## Section 2.2 — Android Embedding Integration

### Goal

Add ONNX-based query encoding, USearch binary index search, and cross-encoder reranking to the Android app, fused with existing BM25 via Reciprocal Rank Fusion.

### Requirements

#### New Dependencies (app/build.gradle.kts additions)

```kotlin
// ONNX Runtime (for embedding model + cross-encoder)
implementation("com.microsoft.onnxruntime:onnxruntime-android:latest.release")

// USearch (for binary vector ANN search)
// Either from Maven: implementation("com.unum.usearch:usearch-android:latest.release")
// Or build JNI from source: https://github.com/unum-cloud/usearch
```

#### 2.2a — EmbeddingService

Loads the `snowflake-arctic-embed-xs` model (exported to ONNX, INT8 quantized, ~22 MB) and encodes queries at inference time.

```kotlin
@Singleton
class EmbeddingService @Inject constructor() {
    private var session: OrtSession? = null

    fun initialize(modelPath: String)   // Load ONNX model
    fun encode(query: String): FloatArray   // Returns 384-dim embedding
    fun encodeBinary(query: String): ByteArray  // Returns 48-byte binary vector
    fun release()
}
```

**ONNX model preparation:** Export `snowflake-arctic-embed-xs` to ONNX with INT8 dynamic quantization:
```python
from optimum.onnxruntime import ORTModelForFeatureExtraction
from optimum.onnxruntime.configuration import AutoQuantizationConfig

model = ORTModelForFeatureExtraction.from_pretrained("Snowflake/snowflake-arctic-embed-xs", export=True)
qconfig = AutoQuantizationConfig.avx512_vnni(is_static=False)  # Dynamic quantization
# Save quantized model
```

**Tokenization on Android:** The ONNX model expects tokenized input (input_ids, attention_mask). Options:
- Use `ai.djl.huggingface:tokenizers-android` (DJL tokenizers with Rust JNI for Android)
- Or bundle a `tokenizer.json` and use a lightweight tokenizer library
- The tokenizer for snowflake-arctic-embed-xs is a standard BERT WordPiece tokenizer

**Latency target:** Query encoding should complete in 50-100ms on Snapdragon 8 Gen 2+.

#### 2.2b — EmbeddingIndex

Wraps USearch for searching the pre-built binary embedding index.

```kotlin
@Singleton
class EmbeddingIndex @Inject constructor() {
    private var index: Index? = null
    private var metadata: PassageMetadata? = null

    fun load(indexPath: String, metadataPath: String)
    fun search(queryVector: ByteArray, k: Int = 30): List<PassageReference>
    fun isLoaded(): Boolean
    fun release()
}

data class PassageReference(
    val passageId: Long,
    val articlePath: String,
    val chunkIndex: Int,
    val distance: Float,    // Hamming distance
)
```

The index is memory-mapped by USearch — it does not load the full 1.1 GB into RAM. Expected RSS overhead: ~200 MB.

**USearch Android risk mitigation:** If USearch JNI doesn't work on Android:

**Fallback — brute-force binary search:**
```kotlin
class BruteForceBinaryIndex {
    private lateinit var vectors: ByteArray  // All vectors concatenated, 48 bytes each
    private var count: Int = 0

    fun load(path: String) {
        // Memory-map the flat binary file
    }

    fun search(query: ByteArray, k: Int): List<Pair<Int, Int>> {
        // For each vector: compute Hamming distance via Integer.bitCount(xor)
        // Use a min-heap of size k to track top results
        // 12M vectors x 48 bytes: ~120ms with ARM NEON optimization
    }
}
```

This is a viable fallback. 12M * 48 bytes = ~550 MB memory-mapped. Hamming distance is `popcount(a XOR b)` which ARM NEON handles natively.

#### 2.2c — CrossEncoderReranker

Loads `ms-marco-TinyBERT-L-2-v2` (4.4M params, INT8 ONNX, ~5 MB) to rerank passages.

```kotlin
@Singleton
class CrossEncoderReranker @Inject constructor() {
    private var session: OrtSession? = null

    fun initialize(modelPath: String)
    fun rerank(query: String, passages: List<Passage>, topK: Int = 5): List<Passage>
    fun release()
}
```

**How it works:** A cross-encoder takes `[CLS] query [SEP] passage [SEP]` as input and outputs a relevance score. Score each of the top-30 fused passages independently, sort by score, return top-5.

**Latency:** ~2-5ms per passage at INT8. For 30 passages: 60-150ms total. Well within budget.

**ONNX model preparation:** Export from `cross-encoder/ms-marco-TinyBERT-L-2-v2` using `optimum`:
```python
from optimum.onnxruntime import ORTModelForSequenceClassification
model = ORTModelForSequenceClassification.from_pretrained(
    "cross-encoder/ms-marco-TinyBERT-L-2-v2", export=True
)
```

#### 2.2d — Updated SearchService (Hybrid Pipeline)

Modify `SearchService` to run BM25 and embedding search in parallel, fuse with RRF, and rerank.

```kotlin
@Singleton
class SearchService @Inject constructor(
    private val zimRepository: ZimRepository,
    private val chunker: ArticleChunker,
    private val bm25Scorer: Bm25Scorer,
    private val embeddingService: EmbeddingService?,     // Nullable — absent in BM25-only mode
    private val embeddingIndex: EmbeddingIndex?,
    private val reranker: CrossEncoderReranker?,
) {
    suspend fun search(query: String, topK: Int = 5): SearchResult {
        // If embedding index is not loaded, fall back to BM25-only (Stage 1 behavior)
        if (embeddingIndex == null || !embeddingIndex.isLoaded()) {
            return searchBm25Only(query, topK)
        }

        // Run BM25 and embedding search in parallel
        val (bm25Passages, embeddingPassages) = coroutineScope {
            val bm25 = async(Dispatchers.IO) { searchBm25(query) }       // top 30
            val embed = async(Dispatchers.Default) { searchEmbedding(query) } // top 30
            Pair(bm25.await(), embed.await())
        }

        // Reciprocal Rank Fusion
        val fused = reciprocalRankFusion(bm25Passages, embeddingPassages, k = 60)

        // Cross-encoder reranking (if available)
        val reranked = if (reranker != null) {
            withContext(Dispatchers.Default) {
                reranker.rerank(query, fused.take(30), topK = topK)
            }
        } else {
            fused.take(topK)
        }

        return SearchResult(passages = reranked, query = query, searchTimeMs = elapsed)
    }
}
```

**RRF implementation:**
```kotlin
private fun reciprocalRankFusion(
    bm25Results: List<Passage>,
    embeddingResults: List<Passage>,
    k: Int = 60,
): List<Passage> {
    val scores = mutableMapOf<String, Double>()
    val passageMap = mutableMapOf<String, Passage>()

    bm25Results.forEachIndexed { rank, passage ->
        scores[passage.id] = (scores[passage.id] ?: 0.0) + 1.0 / (k + rank + 1)
        passageMap[passage.id] = passage
    }
    embeddingResults.forEachIndexed { rank, passage ->
        scores[passage.id] = (scores[passage.id] ?: 0.0) + 1.0 / (k + rank + 1)
        passageMap.putIfAbsent(passage.id, passage)
    }

    return scores.entries
        .sortedByDescending { it.value }
        .mapNotNull { passageMap[it.key] }
}
```

#### 2.2e — Setup Flow Updates

Modify `SetupScreen` to offer embedding index download after ZIM selection:

- "Download semantic search index (~1.3 GB) for better answer quality? You can add this later from Settings."
- If declined, app works with BM25-only
- If accepted, download `thoth-embeddings-mini.usearch` + `passage-metadata.bin` to app storage
- Add a Settings screen entry to download/remove the embedding index later

### Guidance

- **ONNX Runtime Android** docs: https://onnxruntime.ai/docs/get-started/with-java.html#android
- Use `OrtEnvironment.getEnvironment()` and `OrtSession.SessionOptions()` with `addNnapi()` for hardware acceleration. Fall back to CPU (`addXnnpack()`) if NNAPI isn't available.
- The embedding model and cross-encoder share the same ONNX Runtime environment — initialize it once in `AppModule`.
- For tokenization, consider shipping the tokenizer vocabulary as an asset and implementing WordPiece tokenization directly (~100 lines of Kotlin) rather than adding a tokenizer library dependency. snowflake-arctic-embed-xs uses a standard BERT uncased vocabulary.
- Test USearch Android integration early in this section. If it fails, switch to brute-force immediately rather than debugging JNI.

### Verification

- [ ] Embedding model loads and encodes a query in <150ms
- [ ] Embedding index loads and returns results in <100ms
- [ ] Cross-encoder reranks 30 passages in <200ms
- [ ] Total hybrid retrieval latency (BM25 + embedding + RRF + rerank) < 500ms
- [ ] Semantic queries return better results than BM25-only (qualitative comparison)
- [ ] Exact-match queries still work (BM25 contribution preserved by RRF)
- [ ] Memory usage stays under 8 GB total
- [ ] App falls back to BM25-only gracefully when embedding index is absent
- [ ] Run NaturalQuestions eval: recall@5 improves from ~62% to ~82%+

---

## Appendix A: Key Library References

| Library | Purpose | Docs / Source |
|---|---|---|
| LiteRT-LM | Gemma 4 inference on Android | https://ai.google.dev/edge/litert-lm/android |
| java-libkiwix | ZIM file access | https://github.com/kiwix/java-libkiwix |
| kiwix-android | Reference Android ZIM app | https://github.com/kiwix/kiwix-android |
| snowflake-arctic-embed-xs | Query/passage embedding model (22M params) | https://huggingface.co/Snowflake/snowflake-arctic-embed-xs |
| ms-marco-TinyBERT-L-2-v2 | Cross-encoder reranker (4.4M params) | https://huggingface.co/cross-encoder/ms-marco-TinyBERT-L-2-v2 |
| ONNX Runtime Android | Neural model inference | https://onnxruntime.ai/docs/get-started/with-java.html |
| USearch | ANN search over binary vectors | https://github.com/unum-cloud/usearch |
| Jsoup | HTML parsing for chunker | https://jsoup.org |

## Appendix B: Model Files Required

| File | Size | Source | When needed |
|---|---|---|---|
| Gemma 4 E4B (Q4_K_M .litertlm) | ~2.5 GB | https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm | Always (Stage 1+) |
| Wikipedia mini ZIM | ~11 GB | https://download.kiwix.org/zim/wikipedia/ | Always (Stage 1+) |
| snowflake-arctic-embed-xs (INT8 ONNX) | ~22 MB | Export from HuggingFace | Stage 2 |
| ms-marco-TinyBERT-L-2-v2 (INT8 ONNX) | ~5 MB | Export from HuggingFace | Stage 2 |
| Embedding index (binary vectors) | ~1.1 GB | Built by `tools/index_builder/` | Stage 2 |
| Passage metadata | ~150 MB | Built by `tools/index_builder/` | Stage 2 |

## Appendix C: Storage Budget

### Wikipedia mini (default)

| Component | Size |
|---|---|
| Gemma 4 E4B (Q4_K_M) | ~2.5 GB |
| Wikipedia mini ZIM | ~11 GB |
| Embedding index + metadata (optional) | ~1.3 GB |
| ONNX models (optional) | ~27 MB |
| Room database + app | <100 MB |
| **Total without embeddings** | **~13.6 GB** |
| **Total with embeddings** | **~14.9 GB** |

### Wikipedia nopic (power user, 256 GB+ devices)

| Component | Size |
|---|---|
| Gemma 4 E4B (Q4_K_M) | ~2.5 GB |
| Wikipedia nopic ZIM | ~48 GB |
| Embedding index + metadata (optional) | ~3.5 GB (estimated for ~30M passages) |
| ONNX models (optional) | ~27 MB |
| **Total with embeddings** | **~54 GB** |
