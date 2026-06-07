package com.bahm.thoth.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bahm.thoth.inference.AnswerMode
import com.bahm.thoth.inference.LlmService
import com.bahm.thoth.inference.LlmState
import com.bahm.thoth.inference.ModelDownloadService
import com.bahm.thoth.knowledge.ZimDownloadService
import com.bahm.thoth.knowledge.ZimRepository
import com.bahm.thoth.ui.common.AnswerCitation
import com.bahm.thoth.ui.settings.SettingsViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A quick answer rendered on the Home page, with the question that produced it. */
data class QuickAnswer(
    val question: String,
    val html: String,
    val citations: List<AnswerCitation> = emptyList(),
    val isGenerating: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val zimRepository: ZimRepository,
    private val zimDownloadService: ZimDownloadService,
    private val modelDownloadService: ModelDownloadService,
    private val llmService: LlmService,
) : ViewModel() {

    companion object {
        private const val TAG = "HomeViewModel"
    }

    val llmState: StateFlow<LlmState> = llmService.state

    private val _zimReady = MutableStateFlow(zimRepository.isOpen())
    val zimReady: StateFlow<Boolean> = _zimReady.asStateFlow()

    private val _modelPresent = MutableStateFlow(false)
    val modelPresent: StateFlow<Boolean> = _modelPresent.asStateFlow()

    private val _answer = MutableStateFlow<QuickAnswer?>(null)
    val answer: StateFlow<QuickAnswer?> = _answer.asStateFlow()

    private val _isAnswering = MutableStateFlow(false)
    val isAnswering: StateFlow<Boolean> = _isAnswering.asStateFlow()

    init {
        _modelPresent.value =
            modelDownloadService.getDownloadedFile(SettingsViewModel.MODEL_FILENAME) != null
        ensureZimOpen()
    }

    /** Opens the knowledge archive on launch if a ZIM is present and not already open. */
    private fun ensureZimOpen() {
        if (zimRepository.isOpen()) {
            _zimReady.value = true
            return
        }
        val file = zimDownloadService.getDownloadedFile(SettingsViewModel.MINI_FILENAME)
            ?: zimDownloadService.getDownloadedFile(SettingsViewModel.NOPIC_FILENAME)
        if (file == null) {
            Log.d(TAG, "No ZIM file present yet")
            return
        }
        viewModelScope.launch {
            try {
                zimRepository.open(file.absolutePath)
                _zimReady.value = true
                Log.d(TAG, "Archive opened: ${file.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open archive: ${e.message}", e)
            }
        }
    }

    /**
     * Ask a Quick Answer question on the Home page. Loads the model on first use (the only slow
     * step — generation itself is fast), then runs the single-pass quick path and surfaces the
     * grounded answer + citations.
     */
    fun askQuick(text: String) {
        val q = text.trim()
        if (q.isEmpty() || _isAnswering.value) return
        if (!_modelPresent.value || !_zimReady.value) return

        _isAnswering.value = true
        _answer.value = QuickAnswer(question = q, html = "", isGenerating = true)
        Log.d(TAG, "askQuick | \"${q.take(60)}\"")

        viewModelScope.launch {
            var html = ""
            try {
                ensureModelLoaded()
                if (!llmService.isInitialized()) {
                    _answer.value = QuickAnswer(q, "<p>Model failed to load.</p>")
                    return@launch
                }
                llmService.sendMessage(q, AnswerMode.QUICK)
                    .catch { e ->
                        Log.e(TAG, "Quick answer error: ${e.message}", e)
                        html = "<p>Error: ${e.message}</p>"
                    }
                    .collect { chunk -> html = chunk }
                val citations = llmService.getLastSources().map {
                    AnswerCitation(articleTitle = it.articleTitle, zimEntryPath = it.zimEntryPath)
                }
                _answer.value = QuickAnswer(q, html, citations)
            } catch (e: Exception) {
                Log.e(TAG, "askQuick failed: ${e.message}", e)
                _answer.value = QuickAnswer(q, "<p>Error: ${e.message}</p>")
            } finally {
                _isAnswering.value = false
            }
        }
    }

    /**
     * Loads the model (and creates the conversation) ahead of asking, so the input only appears
     * once we're ready. Safe to call repeatedly; no-op when already ready or loading.
     */
    fun prepareModel() {
        if (llmService.isInitialized() || llmState.value is LlmState.Initializing) return
        viewModelScope.launch { ensureModelLoaded() }
    }

    /** Clear the current quick answer (e.g. to ask another question). */
    fun clearAnswer() {
        _answer.value = null
    }

    private suspend fun ensureModelLoaded() {
        if (llmService.isInitialized()) return
        val modelFile =
            modelDownloadService.getDownloadedFile(SettingsViewModel.MODEL_FILENAME) ?: return
        llmService.initialize(modelFile.absolutePath)
        if (llmService.isInitialized()) {
            llmService.createConversation()
        }
    }
}
