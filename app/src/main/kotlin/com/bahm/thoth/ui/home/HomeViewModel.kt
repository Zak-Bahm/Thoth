package com.bahm.thoth.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bahm.thoth.inference.LlmService
import com.bahm.thoth.inference.LlmState
import com.bahm.thoth.inference.ModelDownloadService
import com.bahm.thoth.knowledge.ZimDownloadService
import com.bahm.thoth.knowledge.ZimRepository
import com.bahm.thoth.ui.settings.SettingsViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

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
     * Loads the model and creates the conversation if not already initialized.
     * Safe to call repeatedly; no-op when already ready or loading.
     */
    fun loadModel() {
        if (llmService.isInitialized()) return
        if (llmState.value is LlmState.Initializing) return
        val modelFile =
            modelDownloadService.getDownloadedFile(SettingsViewModel.MODEL_FILENAME) ?: return
        viewModelScope.launch {
            llmService.initialize(modelFile.absolutePath)
            if (llmService.isInitialized()) {
                llmService.createConversation()
            }
        }
    }
}
