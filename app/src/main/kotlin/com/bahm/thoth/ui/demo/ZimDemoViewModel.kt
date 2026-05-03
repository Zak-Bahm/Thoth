package com.bahm.thoth.ui.demo

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bahm.thoth.inference.LlmService
import com.bahm.thoth.inference.LlmState
import com.bahm.thoth.inference.ModelDownloadForegroundService
import com.bahm.thoth.inference.ModelDownloadForegroundService.DownloadState as ModelDownloadState
import com.bahm.thoth.inference.ModelDownloadService
import com.bahm.thoth.knowledge.SearchService
import com.bahm.thoth.knowledge.ZimDownloadForegroundService
import com.bahm.thoth.knowledge.ZimDownloadForegroundService.DownloadState
import com.bahm.thoth.knowledge.ZimDownloadService
import com.bahm.thoth.knowledge.ZimRepository
import com.bahm.thoth.knowledge.models.Article
import com.bahm.thoth.knowledge.models.Passage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ArchiveInfo(
    val filename: String,
    val articleCount: Int,
    val allEntryCount: Int,
    val hasFulltextIndex: Boolean,
)

data class SearchResultItem(
    val title: String,
    val path: String,
)

data class PipelineResultItem(
    val passage: Passage,
    val score: Double,
)

data class PipelineSearchState(
    val results: List<PipelineResultItem> = emptyList(),
    val searchTimeMs: Long = 0,
    val isSearching: Boolean = false,
)

sealed class DownloadStatus {
    data object Idle : DownloadStatus()
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : DownloadStatus()
    data object Complete : DownloadStatus()
    data class Error(val message: String) : DownloadStatus()
}

data class ChatMessage(
    val role: String,
    val text: String,
)

@HiltViewModel
class ZimDemoViewModel @Inject constructor(
    private val application: Application,
    private val zimRepository: ZimRepository,
    private val zimDownloadService: ZimDownloadService,
    private val searchService: SearchService,
    private val llmService: LlmService,
    private val modelDownloadService: ModelDownloadService,
) : AndroidViewModel(application) {

    // ZIM download state
    private val _downloadStatus = MutableStateFlow<DownloadStatus>(DownloadStatus.Idle)
    val downloadStatus: StateFlow<DownloadStatus> = _downloadStatus.asStateFlow()

    private val _archiveInfo = MutableStateFlow<ArchiveInfo?>(null)
    val archiveInfo: StateFlow<ArchiveInfo?> = _archiveInfo.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SearchResultItem>>(emptyList())
    val searchResults: StateFlow<List<SearchResultItem>> = _searchResults.asStateFlow()

    private val _selectedArticle = MutableStateFlow<Article?>(null)
    val selectedArticle: StateFlow<Article?> = _selectedArticle.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _pipelineState = MutableStateFlow(PipelineSearchState())
    val pipelineState: StateFlow<PipelineSearchState> = _pipelineState.asStateFlow()

    // Model download state
    private val _modelDownloadStatus = MutableStateFlow<DownloadStatus>(DownloadStatus.Idle)
    val modelDownloadStatus: StateFlow<DownloadStatus> = _modelDownloadStatus.asStateFlow()

    private val _modelFileExists = MutableStateFlow(false)
    val modelFileExists: StateFlow<Boolean> = _modelFileExists.asStateFlow()

    // LLM state
    val llmState: StateFlow<LlmState> = llmService.state

    // Chat state
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _chatInput = MutableStateFlow("")
    val chatInput: StateFlow<String> = _chatInput.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    companion object {
        private const val TAG = "ZimDemoVM"
        private const val BASE_URL = "https://download.kiwix.org/zim/wikipedia/"
        const val MINI_FILENAME = "wikipedia_en_all_mini_2026-03.zim"
        const val NOPIC_FILENAME = "wikipedia_en_all_nopic_2026-03.zim"
        private const val MODEL_BASE_URL =
            "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/"
        const val MODEL_FILENAME = "gemma-4-E4B-it.litertlm"
    }

    init {
        checkExistingFiles()
        observeDownloadProgress()
        checkExistingModel()
        observeModelDownloadProgress()
    }

    // --- ZIM download ---

    private fun checkExistingFiles() {
        val miniFile = zimDownloadService.getDownloadedFile(MINI_FILENAME)
        val nopicFile = zimDownloadService.getDownloadedFile(NOPIC_FILENAME)
        Log.d(TAG, "checkExistingFiles: mini=${miniFile?.absolutePath}, nopic=${nopicFile?.absolutePath}")
        val existingFile = miniFile ?: nopicFile
        if (existingFile != null) {
            openArchive(existingFile.absolutePath)
        }
    }

    private fun observeDownloadProgress() {
        viewModelScope.launch {
            ZimDownloadForegroundService.progress.collect { state ->
                when (state) {
                    is DownloadState.Idle -> {
                        _downloadStatus.value = DownloadStatus.Idle
                    }
                    is DownloadState.Downloading -> {
                        _downloadStatus.value = DownloadStatus.Downloading(
                            state.bytesDownloaded,
                            state.totalBytes,
                        )
                    }
                    is DownloadState.Complete -> {
                        _downloadStatus.value = DownloadStatus.Complete
                        val file = zimDownloadService.getDownloadedFile(state.filename)
                        if (file != null) {
                            openArchive(file.absolutePath)
                        }
                    }
                    is DownloadState.Error -> {
                        _downloadStatus.value = DownloadStatus.Error(state.message)
                    }
                }
            }
        }
    }

    fun downloadMini() {
        ZimDownloadForegroundService.start(
            application, BASE_URL + MINI_FILENAME, MINI_FILENAME,
        )
    }

    fun downloadNopic() {
        ZimDownloadForegroundService.start(
            application, BASE_URL + NOPIC_FILENAME, NOPIC_FILENAME,
        )
    }

    fun cancelDownload() {
        ZimDownloadForegroundService.cancel(application)
    }

    private fun openArchive(path: String) {
        viewModelScope.launch {
            Log.d(TAG, "openArchive: $path")
            try {
                zimRepository.open(path)
                Log.d(TAG, "Archive opened, reading info...")
                val info = ArchiveInfo(
                    filename = zimRepository.getFilename() ?: "Unknown",
                    articleCount = zimRepository.getArticleCount(),
                    allEntryCount = zimRepository.getAllEntryCount(),
                    hasFulltextIndex = zimRepository.hasFulltextIndex(),
                )
                Log.d(TAG, "Archive info: $info")
                _archiveInfo.value = info
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open archive: ${e.message}", e)
                _downloadStatus.value = DownloadStatus.Error("Failed to open archive: ${e.message}")
            }
        }
    }

    // --- ZIM search ---

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun search() {
        val query = _searchQuery.value.trim()
        if (query.isEmpty()) return

        viewModelScope.launch {
            try {
                val articles = zimRepository.searchArticles(query)
                Log.d(TAG, "Article search(\"$query\"): ${articles.size} results")
                _searchResults.value = articles.map { article ->
                    SearchResultItem(title = article.title, path = article.path)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Article search failed: ${e.message}", e)
                _searchResults.value = emptyList()
            }
        }
    }

    fun searchPipeline() {
        val query = _searchQuery.value.trim()
        if (query.isEmpty()) return

        viewModelScope.launch {
            _pipelineState.value = PipelineSearchState(isSearching = true)
            try {
                val result = searchService.search(query)
                _pipelineState.value = PipelineSearchState(
                    results = result.passages.map { PipelineResultItem(it, 0.0) },
                    searchTimeMs = result.searchTimeMs,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Pipeline search failed: ${e.message}", e)
                _pipelineState.value = PipelineSearchState()
            }
        }
    }

    fun selectArticle(path: String) {
        viewModelScope.launch {
            _selectedArticle.value = zimRepository.getArticleByPath(path)
        }
    }

    fun clearSelectedArticle() {
        _selectedArticle.value = null
    }

    // --- Model download ---

    private fun checkExistingModel() {
        val modelFile = modelDownloadService.getDownloadedFile(MODEL_FILENAME)
        Log.d(TAG, "checkExistingModel: ${modelFile?.absolutePath}")
        _modelFileExists.value = modelFile != null
        if (modelFile != null) {
            _modelDownloadStatus.value = DownloadStatus.Complete
        }
    }

    private fun observeModelDownloadProgress() {
        viewModelScope.launch {
            ModelDownloadForegroundService.progress.collect { state ->
                when (state) {
                    is ModelDownloadState.Idle -> {
                        // Don't reset to Idle if file already exists
                        if (!_modelFileExists.value) {
                            _modelDownloadStatus.value = DownloadStatus.Idle
                        }
                    }
                    is ModelDownloadState.Downloading -> {
                        _modelDownloadStatus.value = DownloadStatus.Downloading(
                            state.bytesDownloaded,
                            state.totalBytes,
                        )
                    }
                    is ModelDownloadState.Complete -> {
                        _modelDownloadStatus.value = DownloadStatus.Complete
                        _modelFileExists.value = true
                    }
                    is ModelDownloadState.Error -> {
                        _modelDownloadStatus.value = DownloadStatus.Error(state.message)
                    }
                }
            }
        }
    }

    fun downloadModel() {
        ModelDownloadForegroundService.start(
            application, MODEL_BASE_URL + MODEL_FILENAME, MODEL_FILENAME,
        )
    }

    fun cancelModelDownload() {
        ModelDownloadForegroundService.cancel(application)
    }

    // --- LLM loading and chat ---

    fun releaseModel() {
        _chatMessages.value = emptyList()
        llmService.release()
    }

    fun loadModel() {
        val modelFile = modelDownloadService.getDownloadedFile(MODEL_FILENAME) ?: return
        viewModelScope.launch {
            llmService.initialize(modelFile.absolutePath)
            if (llmService.isInitialized()) {
                llmService.createConversation()
            }
        }
    }

    fun updateChatInput(text: String) {
        _chatInput.value = text
    }

    fun sendChatMessage() {
        val text = _chatInput.value.trim()
        if (text.isEmpty() || _isGenerating.value) return

        _chatInput.value = ""
        _chatMessages.value = _chatMessages.value + ChatMessage(role = "user", text = text)
        _isGenerating.value = true

        viewModelScope.launch {
            var responseText = ""
            _chatMessages.value = _chatMessages.value + ChatMessage(role = "assistant", text = "")

            try {
                llmService.sendMessage(text)
                    .catch { e ->
                        Log.e(TAG, "Generation error: ${e.message}", e)
                        responseText = "Error: ${e.message}"
                        updateLastAssistantMessage(responseText)
                    }
                    .collect { chunk ->
                        responseText = chunk
                        updateLastAssistantMessage(responseText)
                    }
            } catch (e: Exception) {
                Log.e(TAG, "sendMessage failed: ${e.message}", e)
                responseText = "Error: ${e.message}"
                updateLastAssistantMessage(responseText)
            } finally {
                _isGenerating.value = false
            }
        }
    }

    private fun updateLastAssistantMessage(text: String) {
        val messages = _chatMessages.value.toMutableList()
        val lastIndex = messages.lastIndex
        if (lastIndex >= 0 && messages[lastIndex].role == "assistant") {
            messages[lastIndex] = messages[lastIndex].copy(text = text)
            _chatMessages.value = messages
        }
    }

    fun resetChat() {
        _chatMessages.value = emptyList()
        if (llmService.isInitialized()) {
            llmService.resetConversation()
        }
    }

    override fun onCleared() {
        super.onCleared()
        llmService.release()
        viewModelScope.launch {
            zimRepository.close()
        }
    }
}
