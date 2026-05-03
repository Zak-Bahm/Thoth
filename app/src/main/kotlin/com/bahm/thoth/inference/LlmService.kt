package com.bahm.thoth.inference

import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

sealed class LlmState {
    data object Uninitialized : LlmState()
    data object Initializing : LlmState()
    data object Ready : LlmState()
    data class Error(val message: String) : LlmState()
}

@Singleton
class LlmService @Inject constructor() {

    companion object {
        private const val TAG = "LlmService"
    }

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    private val _state = MutableStateFlow<LlmState>(LlmState.Uninitialized)
    val state: StateFlow<LlmState> = _state.asStateFlow()

    fun isInitialized(): Boolean = _state.value is LlmState.Ready

    suspend fun initialize(modelPath: String) = withContext(Dispatchers.Default) {
        _state.value = LlmState.Initializing
        try {
            Log.d(TAG, "Loading model from: $modelPath")
            val startTime = System.currentTimeMillis()

            val config = EngineConfig(modelPath)
            val eng = Engine(config)
            eng.initialize()

            engine = eng
            val elapsed = (System.currentTimeMillis() - startTime) / 1_000.0
            Log.d(TAG, "Model loaded in ${"%.1f".format(elapsed)}s")
            _state.value = LlmState.Ready
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: ${e.message}", e)
            _state.value = LlmState.Error(e.message ?: "Failed to load model")
        }
    }

    fun createConversation() {
        val eng = engine ?: throw IllegalStateException("Engine not initialized")
        conversation?.close()

        val samplerConfig = SamplerConfig(
            /* topK = */ 40,
            /* topP = */ 0.95,
            /* temperature = */ 0.7,
            /* seed = */ 0,
        )
        val conversationConfig = ConversationConfig(samplerConfig = samplerConfig)
        conversation = eng.createConversation(conversationConfig)
        Log.d(TAG, "Conversation created")
    }

    fun sendMessage(text: String): Flow<String> {
        val conv = conversation ?: throw IllegalStateException("Conversation not created")
        val accumulated = StringBuilder()
        return conv.sendMessageAsync(text, emptyMap())
            .map { message ->
                val token = message.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("") { it.text }
                accumulated.append(token)
                accumulated.toString()
            }
    }

    fun resetConversation() {
        conversation?.close()
        conversation = null
        createConversation()
    }

    fun release() {
        Log.d(TAG, "Releasing resources")
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
        _state.value = LlmState.Uninitialized
    }
}
