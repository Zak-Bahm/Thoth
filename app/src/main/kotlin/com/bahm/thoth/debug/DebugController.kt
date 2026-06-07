package com.bahm.thoth.debug

import android.content.Context
import android.util.Log
import com.bahm.thoth.inference.AnswerMode
import com.bahm.thoth.inference.LlmService
import com.bahm.thoth.inference.ModelDownloadService
import com.bahm.thoth.inference.ToolHandler
import com.bahm.thoth.knowledge.SearchService
import com.bahm.thoth.knowledge.ZimDownloadService
import com.bahm.thoth.knowledge.ZimRepository
import com.bahm.thoth.knowledge.models.Article
import com.bahm.thoth.knowledge.models.SearchResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Headless test harness driven over adb. Lets queries/searches run (and the model load)
 * without the UI, storing results to files so testing can be scripted via adb.
 *
 * Trigger (quote the whole remote command AND the query, or adb shell splits the spaces):
 *   adb shell "am broadcast -n com.bahm.thoth/.debug.DebugReceiver -a com.bahm.thoth.DEBUG_QUERY  --es q 'why is the sky blue'"
 *   adb shell "am broadcast -n com.bahm.thoth/.debug.DebugReceiver -a com.bahm.thoth.DEBUG_SEARCH --es q 'last states join usa'"
 *   adb shell  am broadcast -n com.bahm.thoth/.debug.DebugReceiver -a com.bahm.thoth.DEBUG_LOAD
 *
 * Outputs (in {externalFilesDir}/debug/):
 *   status.txt           — IDLE | LOADING | READY | BUSY | SEARCHING | ERROR
 *   debug_session.jsonl  — one object per completed DEBUG_QUERY (query, status, response, grounded)
 *   last_response.txt    — most recent query response (convenience)
 *   search_result.json   — most recent DEBUG_SEARCH (pretty-printed: xapian articles + pipeline passages)
 *   search_log.jsonl     — append-only history of DEBUG_SEARCH results
 *
 * Keep the app foregrounded while a DEBUG_QUERY runs (~60-150s) so the OS doesn't kill the process.
 */
@Singleton
class DebugController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llmService: LlmService,
    private val toolHandler: ToolHandler,
    private val searchService: SearchService,
    private val zimRepository: ZimRepository,
    private val zimDownloadService: ZimDownloadService,
    private val modelDownloadService: ModelDownloadService,
) {
    companion object {
        private const val TAG = "DebugController"
        const val ACTION_QUERY = "com.bahm.thoth.DEBUG_QUERY"
        const val ACTION_QUERY_QUICK = "com.bahm.thoth.DEBUG_QUERY_QUICK"
        const val ACTION_SEARCH = "com.bahm.thoth.DEBUG_SEARCH"
        const val ACTION_LOAD = "com.bahm.thoth.DEBUG_LOAD"
        const val ACTION_DUMP = "com.bahm.thoth.DEBUG_DUMP"
        private const val MINI_FILENAME = "wikipedia_en_all_mini_2026-03.zim"
        private const val NOPIC_FILENAME = "wikipedia_en_all_nopic_2026-03.zim"
        private const val MODEL_FILENAME = "gemma-4-E4B-it.litertlm"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val busy = AtomicBoolean(false)

    fun onAction(action: String?, query: String?) {
        when (action) {
            ACTION_LOAD -> scope.launch {
                writeStatus("LOADING")
                ensureReady()
                writeStatus(if (llmService.isInitialized() && zimRepository.isOpen()) "READY" else "ERROR")
            }

            ACTION_SEARCH -> {
                val q = query?.trim().orEmpty()
                if (q.isEmpty()) {
                    Log.w(TAG, "DEBUG_SEARCH with empty 'q' — ignoring")
                    return
                }
                scope.launch {
                    try {
                        writeStatus("SEARCHING")
                        ensureZimOpen()
                        if (!zimRepository.isOpen()) {
                            Log.w(TAG, "DEBUG_SEARCH: no ZIM open")
                            return@launch
                        }
                        val articles = zimRepository.searchArticles(q, maxResults = 30)
                        val result = searchService.search(q, topK = 10)
                        writeSearch(q, articles, result)
                        Log.d(TAG, "DEBUG_SEARCH \"$q\": ${articles.size} xapian articles, ${result.passages.size} pipeline passages")
                    } catch (e: Exception) {
                        Log.e(TAG, "DEBUG_SEARCH failed: ${e.message}", e)
                    } finally {
                        writeStatus("IDLE")
                    }
                }
            }

            ACTION_QUERY, ACTION_QUERY_QUICK -> {
                val mode = if (action == ACTION_QUERY_QUICK) AnswerMode.QUICK else AnswerMode.THOROUGH
                val q = query?.trim().orEmpty()
                if (q.isEmpty()) {
                    Log.w(TAG, "$action with empty 'q' — ignoring")
                    return
                }
                if (!busy.compareAndSet(false, true)) {
                    Log.w(TAG, "Busy with another query — ignoring \"$q\"")
                    return
                }
                scope.launch {
                    try {
                        writeStatus("BUSY")
                        ensureReady()
                        if (!llmService.isInitialized()) {
                            writeResult(q, "error", "model not loaded", 0, 0)
                            return@launch
                        }
                        // Independent tests: start each query from a clean conversation so
                        // prior turns' context can't contaminate this one.
                        llmService.resetConversation()
                        Log.d(TAG, "$action running ($mode, fresh conversation): \"$q\"")
                        var response = ""
                        llmService.sendMessage(q, mode)
                            .catch { e -> response = "ERROR: ${e.message}" }
                            .collect { response = it }
                        val s = toolHandler.getStructuredResponse()
                        writeResult(q, "done", response, s?.groundedCount ?: 0, s?.claims?.size ?: 0)
                        Log.d(TAG, "DEBUG_QUERY done: respLen=${response.length} grounded=${s?.groundedCount ?: 0}/${s?.claims?.size ?: 0}")
                    } catch (e: Exception) {
                        Log.e(TAG, "DEBUG_QUERY failed: ${e.message}", e)
                        writeResult(q, "error", e.message ?: "unknown", 0, 0)
                    } finally {
                        busy.set(false)
                        writeStatus("IDLE")
                    }
                }
            }

            ACTION_DUMP -> {
                val q = query?.trim().orEmpty()
                if (q.isEmpty()) {
                    Log.w(TAG, "DEBUG_DUMP with empty 'q' — ignoring")
                    return
                }
                scope.launch {
                    try {
                        writeStatus("DUMPING")
                        ensureZimOpen()
                        if (!zimRepository.isOpen()) {
                            Log.w(TAG, "DEBUG_DUMP: no ZIM open")
                            return@launch
                        }
                        val article = zimRepository.getArticleByTitle(q)
                            ?: zimRepository.getArticleByPath(q)
                        if (article == null) {
                            Log.w(TAG, "DEBUG_DUMP: article not found for \"$q\"")
                            File(debugDir(), "dump_headings.txt").writeText("NOT FOUND: $q\n")
                            return@launch
                        }
                        writeDump(article)
                        Log.d(TAG, "DEBUG_DUMP \"$q\": ${article.htmlContent.length} chars")
                    } catch (e: Exception) {
                        Log.e(TAG, "DEBUG_DUMP failed: ${e.message}", e)
                    } finally {
                        writeStatus("IDLE")
                    }
                }
            }

            else -> Log.w(TAG, "Unknown debug action: $action")
        }
    }

    /** Temporary: dumps raw article HTML + a digest of every heading element's markup. */
    private fun writeDump(article: Article) {
        try {
            File(debugDir(), "dump_article.html").writeText(article.htmlContent)
            val doc = org.jsoup.Jsoup.parse(article.htmlContent)
            val sb = StringBuilder()
            sb.append("title=").append(article.title).append("\n")
            sb.append("path=").append(article.path).append("\n")
            sb.append("htmlChars=").append(article.htmlContent.length).append("\n\n")
            for (h in doc.select("h1, h2, h3, h4")) {
                sb.append("<").append(h.tagName()).append(">")
                sb.append(" id='").append(h.id()).append("'")
                val headline = h.selectFirst(".mw-headline")
                sb.append(" mw-headline.id='").append(headline?.id() ?: "").append("'")
                val anyId = h.selectFirst("[id]")
                sb.append(" firstDescId='").append(anyId?.id() ?: "").append("'")
                sb.append(" text='").append(h.text().take(60)).append("'")
                sb.append("\n    outerHtml: ").append(h.outerHtml().take(220).replace("\n", " "))
                sb.append("\n")
            }
            File(debugDir(), "dump_headings.txt").writeText(sb.toString())
        } catch (e: Exception) {
            Log.e(TAG, "writeDump failed: ${e.message}")
        }
    }

    private suspend fun ensureReady() {
        ensureZimOpen()
        ensureModelLoaded()
    }

    private suspend fun ensureZimOpen() {
        if (zimRepository.isOpen()) return
        val file = zimDownloadService.getDownloadedFile(MINI_FILENAME)
            ?: zimDownloadService.getDownloadedFile(NOPIC_FILENAME)
        if (file != null) {
            Log.d(TAG, "Opening ZIM: ${file.name}")
            zimRepository.open(file.absolutePath)
        } else {
            Log.w(TAG, "No ZIM file present")
        }
    }

    private suspend fun ensureModelLoaded() {
        if (llmService.isInitialized()) return
        val model = modelDownloadService.getDownloadedFile(MODEL_FILENAME)
        if (model != null) {
            Log.d(TAG, "Loading model: ${model.name}")
            llmService.initialize(model.absolutePath)
            if (llmService.isInitialized()) llmService.createConversation()
        } else {
            Log.w(TAG, "No model file present")
        }
    }

    private fun debugDir(): File {
        val dir = File(context.getExternalFilesDir(null), "debug")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun writeStatus(status: String) {
        try {
            File(debugDir(), "status.txt").writeText(status)
        } catch (e: Exception) {
            Log.e(TAG, "writeStatus failed: ${e.message}")
        }
    }

    private fun writeResult(query: String, status: String, response: String, grounded: Int, total: Int) {
        try {
            val obj = JSONObject()
                .put("timestamp", System.currentTimeMillis())
                .put("query", query)
                .put("status", status)
                .put("responseChars", response.length)
                .put("grounded", grounded)
                .put("totalClaims", total)
                .put("response", response)
            File(debugDir(), "debug_session.jsonl").appendText(obj.toString() + "\n")
            File(debugDir(), "last_response.txt").writeText(response)
        } catch (e: Exception) {
            Log.e(TAG, "writeResult failed: ${e.message}")
        }
    }

    private fun writeSearch(query: String, articles: List<Article>, result: SearchResult) {
        try {
            val xapian = JSONArray()
            articles.forEach { xapian.put(it.title) }
            val passages = JSONArray()
            result.passages.forEach { p ->
                passages.put(
                    JSONObject()
                        .put("title", p.articleTitle)
                        .put("section", p.sectionHeading ?: "")
                        .put("preview", p.text.take(140)),
                )
            }
            val obj = JSONObject()
                .put("timestamp", System.currentTimeMillis())
                .put("query", query)
                .put("zimMs", result.zimMs)
                .put("chunkMs", result.chunkMs)
                .put("bm25Ms", result.bm25Ms)
                .put("articleCount", result.articleCount)
                .put("candidatePassageCount", result.candidatePassageCount)
                .put("xapianArticles", xapian)
                .put("pipelinePassages", passages)
            File(debugDir(), "search_result.json").writeText(obj.toString(2))
            File(debugDir(), "search_log.jsonl").appendText(obj.toString() + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "writeSearch failed: ${e.message}")
        }
    }
}
