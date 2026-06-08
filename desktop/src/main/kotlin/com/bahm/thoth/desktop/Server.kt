package com.bahm.thoth.desktop

import com.bahm.thoth.inference.AnswerMode
import com.bahm.thoth.inference.EngineSettings
import com.bahm.thoth.inference.EvalRunner
import com.bahm.thoth.inference.LlmService
import com.bahm.thoth.inference.PerfTracker
import com.bahm.thoth.inference.ThothTools
import com.bahm.thoth.inference.ToolHandler
import com.bahm.thoth.knowledge.SearchService
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Persistent HTTP server: loads the model once, then handles /query and /search requests
 * indefinitely. All requests are serialized through a single-threaded executor — ThothTools
 * carries mutable per-request state (retrievedHits, lastResponse) that isn't safe to share.
 *
 * Endpoints:
 *   GET  /health         → {"status":"ok","modelLoaded":true}
 *   POST /query          body: {"query":"…","mode":"quick|thorough"}  → eval record JSON
 *   GET  /search?q=…[&topK=N]  → search results JSON
 */
fun runServeCmd(
    search: SearchService,
    zim: DesktopZimRepository,
    modelPath: String,
    sink: DesktopOutputSink,
    backend: com.google.ai.edge.litertlm.Backend,
    port: Int,
) {
    val perf = PerfTracker(sink)
    val tools = ThothTools(search, zim, perf)
    val toolHandler = ToolHandler(tools)
    val llm = LlmService(tools, toolHandler, perf, search, EngineSettings(backend))

    runBlocking { llm.initialize(modelPath) }
    if (!llm.isInitialized()) {
        System.err.println("ERROR: model failed to load")
        return
    }
    println("Model loaded. Starting server on :$port")

    val executor = Executors.newSingleThreadExecutor()
    val server = HttpServer.create(InetSocketAddress(port), /*backlog=*/8)
    server.executor = executor

    server.createContext("/health") { ex ->
        ex.jsonResponse(200, """{"status":"ok","modelLoaded":true}""")
    }

    server.createContext("/search") { ex ->
        if (ex.requestMethod != "GET") { ex.jsonResponse(405, error("method not allowed")); return@createContext }
        val q = ex.queryParam("q")
        if (q == null) { ex.jsonResponse(400, error("missing query param 'q'")); return@createContext }
        val topK = ex.queryParam("topK")?.toIntOrNull()?.coerceIn(1, 50) ?: 10
        try {
            val result = runBlocking { search.search(q, topK = topK) }
            val passages = JSONArray()
            result.passages.forEachIndexed { i, p ->
                passages.put(JSONObject()
                    .put("rank", i)
                    .put("articleTitle", p.articleTitle)
                    .put("sectionHeading", p.sectionHeading ?: "")
                    .put("sectionAnchor", p.sectionAnchor ?: "")
                    .put("zimEntryPath", p.zimEntryPath)
                    .put("text", p.text))
            }
            val body = JSONObject()
                .put("query", q)
                .put("articleCount", result.articleCount)
                .put("passageCount", result.candidatePassageCount)
                .put("timings", JSONObject()
                    .put("zimMs", result.zimMs)
                    .put("chunkMs", result.chunkMs)
                    .put("bm25Ms", result.bm25Ms))
                .put("passages", passages)
            ex.jsonResponse(200, body.toString())
        } catch (e: Exception) {
            ex.jsonResponse(500, error(e.message ?: "search failed"))
        }
    }

    server.createContext("/query") { ex ->
        if (ex.requestMethod != "POST") { ex.jsonResponse(405, error("method not allowed")); return@createContext }
        try {
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val json = JSONObject(body)
            val query = json.optString("query").trim()
            if (query.isEmpty()) { ex.jsonResponse(400, error("missing 'query' field")); return@createContext }
            val modeStr = json.optString("mode", "thorough").lowercase()
            val mode = if (modeStr == "quick") AnswerMode.QUICK else AnswerMode.THOROUGH

            val record = runBlocking { EvalRunner(llm, toolHandler, perf).runQuery(query, mode) }
            val line = record.toJson()
            File(sink.dir("debug"), "eval_session.jsonl").appendText(line + "\n")
            ex.jsonResponse(200, line)
        } catch (e: Exception) {
            ex.jsonResponse(500, error(e.message ?: "query failed"))
        }
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop(0)
        executor.shutdown()
        llm.release()
        runBlocking { zim.close() }
    })

    server.start()
    println("Listening on :$port  (Ctrl+C to stop)")
    Thread.currentThread().join()
}

private fun HttpExchange.queryParam(name: String): String? =
    requestURI.query
        ?.split("&")
        ?.firstOrNull { it.startsWith("$name=") }
        ?.substringAfter("=")
        ?.let { java.net.URLDecoder.decode(it, "UTF-8") }

private fun HttpExchange.jsonResponse(status: Int, body: String) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    responseHeaders.set("Content-Type", "application/json; charset=utf-8")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

private fun error(msg: String): String = JSONObject().put("error", msg).toString()
