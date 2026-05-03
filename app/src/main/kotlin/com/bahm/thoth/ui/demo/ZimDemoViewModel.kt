package com.bahm.thoth.ui.demo

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

@HiltViewModel
class ZimDemoViewModel @Inject constructor(
    private val application: Application,
    private val zimRepository: ZimRepository,
    private val zimDownloadService: ZimDownloadService,
    private val searchService: SearchService,
) : AndroidViewModel(application) {

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

    companion object {
        private const val TAG = "ZimDemoVM"
        private const val BASE_URL = "https://download.kiwix.org/zim/wikipedia/"
        const val MINI_FILENAME = "wikipedia_en_all_mini_2026-03.zim"
        const val NOPIC_FILENAME = "wikipedia_en_all_nopic_2026-03.zim"
    }

    init {
        checkExistingFiles()
        observeDownloadProgress()
    }

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

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            zimRepository.close()
        }
    }
}
