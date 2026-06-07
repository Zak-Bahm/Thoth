package com.bahm.thoth.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bahm.thoth.inference.LlmState
import com.bahm.thoth.ui.chat.AssistantMessageBubble
import com.bahm.thoth.ui.chat.ChatMessage
import com.bahm.thoth.ui.chat.Role
import com.bahm.thoth.ui.chat.Source
import com.bahm.thoth.ui.chat.UserMessageBubble

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onSearchDetail: (query: String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenArticle: (zimEntryPath: String, anchor: String?, heading: String?) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val llmState by viewModel.llmState.collectAsState()
    val zimReady by viewModel.zimReady.collectAsState()
    val modelPresent by viewModel.modelPresent.collectAsState()
    val answer by viewModel.answer.collectAsState()
    val isAnswering by viewModel.isAnswering.collectAsState()

    var input by rememberSaveable { mutableStateOf("") }
    // The centered "Ask a question" button has been tapped and the input is revealed.
    var inputActive by rememberSaveable { mutableStateOf(false) }
    // Waiting for the model to finish loading after tapping "Ask a question".
    var preparing by remember { mutableStateOf(false) }

    // Reveal the input once the model finishes loading after a tap.
    LaunchedEffect(llmState, preparing) {
        if (preparing) {
            when (llmState) {
                is LlmState.Ready -> {
                    preparing = false
                    inputActive = true
                }
                is LlmState.Error -> preparing = false
                else -> { /* still loading */ }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Thoth") },
                actions = {
                    if (answer != null) {
                        TextButton(
                            onClick = {
                                viewModel.clearAnswer()
                                input = ""
                                inputActive = true
                            },
                            enabled = !isAnswering,
                        ) {
                            Text("New")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { innerPadding ->
        val setupNeeded = !modelPresent || !zimReady
        if (setupNeeded) {
            SetupNeeded(
                modelPresent = modelPresent,
                zimReady = zimReady,
                onOpenSettings = onOpenSettings,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
            )
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val current = answer
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    // A question has been asked — show the conversation-style bubbles.
                    current != null -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        UserMessageBubble(ChatMessage(id = "home-q", role = Role.USER, content = current.question))
                        AssistantMessageBubble(
                            message = ChatMessage(
                                id = "home-a",
                                role = Role.ASSISTANT,
                                content = current.html,
                                isGenerating = current.isGenerating,
                                sources = current.citations.map { Source(it.articleTitle, it.zimEntryPath) },
                            ),
                            onOpenArticle = onOpenArticle,
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    // Input revealed: ask a question.
                    inputActive -> AskInput(
                        value = input,
                        onValueChange = { input = it },
                        onSend = {
                            viewModel.askQuick(input)
                            input = ""
                        },
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    )

                    // Initial hero: centered button to start.
                    else -> Hero(
                        loading = preparing && llmState is LlmState.Initializing,
                        error = (llmState as? LlmState.Error)?.message,
                        onAsk = {
                            if (llmState is LlmState.Ready) {
                                inputActive = true
                            } else {
                                preparing = true
                                viewModel.prepareModel()
                            }
                        },
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    )
                }
            }

            // Bottom slot: hand the question off to the detailed chat flow once answered.
            if (current != null && !isAnswering) {
                Button(
                    onClick = { onSearchDetail(current.question) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text("Search in detail")
                }
            }
        }
    }
}

@Composable
private fun Hero(
    loading: Boolean,
    error: String?,
    onAsk: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Thoth", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Your offline knowledge assistant",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onAsk, enabled = !loading) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp).width(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Text("Loading model…")
            } else {
                Text("Ask a question")
            }
        }
        if (!loading && error != null) {
            Text(
                "Failed to load model: $error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AskInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("Ask a question") },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onSend, enabled = value.isNotBlank()) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Ask")
        }
    }
}

@Composable
private fun SetupNeeded(
    modelPresent: Boolean,
    zimReady: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("Thoth", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Your offline knowledge assistant",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        val missing = buildList {
            if (!modelPresent) add("the model")
            if (!zimReady) add("a knowledge pack")
        }.joinToString(" and ")
        Text(
            "Setup needed: download $missing in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(onClick = onOpenSettings) {
            Text("Open Settings")
        }
    }
}
