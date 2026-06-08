package com.bahm.thoth.desktop

import com.bahm.thoth.inference.AnswerMode
import com.bahm.thoth.inference.EngineSettings
import com.bahm.thoth.inference.EvalRunner
import com.bahm.thoth.inference.LlmService
import com.bahm.thoth.inference.PerfTracker
import com.bahm.thoth.inference.ThothTools
import com.bahm.thoth.inference.ToolHandler
import com.bahm.thoth.knowledge.ArticleChunker
import com.bahm.thoth.knowledge.Bm25Scorer
import com.bahm.thoth.knowledge.SearchService
import com.google.ai.edge.litertlm.Backend
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Headless desktop runner — the analog of the Android DebugController, for fast GPU-accelerated
 * eval. Wires the shared :core pipeline manually (no Hilt) and emits the same eval_session.jsonl.
 *
 *   THOTH_MODEL=/path/to/gemma-4-E4B-it.litertlm THOTH_ZIM=/path/to/wikipedia.zim \
 *     ./gradlew :desktop:run --args="query-quick 'population of france'"
 *
 * Commands: search <q> | load | query <q> | query-quick <q>
 * Env: THOTH_MODEL, THOTH_ZIM, THOTH_OUT (default ./thoth-out), THOTH_BACKEND (gpu|cpu, default gpu)
 */
fun main(args: Array<String>) {
    val command = args.firstOrNull()
    if (command == null || command in setOf("-h", "--help", "help")) {
        usage(); return
    }
    val query = args.drop(1).joinToString(" ").trim()

    val zimPath = env("THOTH_ZIM") ?: fail("THOTH_ZIM not set (path to .zim)")
    val sink = DesktopOutputSink(File(env("THOTH_OUT") ?: "thoth-out"))
    val zim = DesktopZimRepository()
    runBlocking { zim.open(zimPath) }
    val search = SearchService(zim, ArticleChunker(), Bm25Scorer())
    val perf = PerfTracker(sink)

    try {
        when (command) {
            // ZIM-only path: no inference engine constructed.
            "search" -> runSearch(search, requireQuery(query))
            "load" -> runQueryCmd(search, zim, perf, sink, requireModel(), null, AnswerMode.THOROUGH)
            "query" -> runQueryCmd(search, zim, perf, sink, requireModel(), requireQuery(query), AnswerMode.THOROUGH)
            "query-quick" -> runQueryCmd(search, zim, perf, sink, requireModel(), requireQuery(query), AnswerMode.QUICK)
            else -> { System.err.println("Unknown command: $command"); usage() }
        }
    } finally {
        runBlocking { zim.close() }
    }
}

private fun runSearch(search: SearchService, query: String) {
    val result = runBlocking { search.search(query, topK = 10) }
    println(
        "search \"$query\": ${result.articleCount} articles -> ${result.candidatePassageCount} passages " +
            "(zim=${result.zimMs}ms chunk=${result.chunkMs}ms bm25=${result.bm25Ms}ms)",
    )
    result.passages.forEachIndexed { i, p ->
        println("  [$i] ${p.articleTitle}${p.sectionHeading?.let { ":$it" } ?: ""} — ${p.text.take(100).replace("\n", " ")}")
    }
}

/** Builds the inference pipeline lazily (loads litertlm), runs the query, emits the eval record. */
private fun runQueryCmd(
    search: SearchService,
    zim: DesktopZimRepository,
    perf: PerfTracker,
    sink: DesktopOutputSink,
    modelPath: String,
    query: String?,
    mode: AnswerMode,
) {
    val backend = if (env("THOTH_BACKEND")?.lowercase() == "cpu") Backend.CPU() else Backend.GPU()
    val tools = ThothTools(search, zim, perf)
    val toolHandler = ToolHandler(tools)
    val llm = LlmService(tools, toolHandler, perf, search, EngineSettings(backend))
    try {
        runBlocking { llm.initialize(modelPath) }
        if (!llm.isInitialized()) {
            System.err.println("ERROR: model failed to load")
            return
        }
        if (query == null) { // `load` command: just confirm readiness.
            println("READY")
            return
        }
        val record = runBlocking { EvalRunner(llm, toolHandler, perf).runQuery(query, mode) }
        val line = record.toJson()
        File(sink.dir("debug"), "eval_session.jsonl").appendText(line + "\n")
        println(line)
    } finally {
        llm.release()
    }
}

private fun requireModel(): String = env("THOTH_MODEL") ?: fail("THOTH_MODEL not set (path to .litertlm)")
private fun requireQuery(query: String): String = query.ifBlank { fail("missing query text") }
private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

private fun fail(msg: String): Nothing {
    System.err.println("ERROR: $msg")
    usage()
    kotlin.system.exitProcess(2)
}

private fun usage() {
    System.err.println(
        """
        Thoth desktop runner
          Commands: search <q> | load | query <q> | query-quick <q>
          Env: THOTH_MODEL=<.litertlm>  THOTH_ZIM=<.zim>  THOTH_OUT=<dir>  THOTH_BACKEND=gpu|cpu
        """.trimIndent(),
    )
}
