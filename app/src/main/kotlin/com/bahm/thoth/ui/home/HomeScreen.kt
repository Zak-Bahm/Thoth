package com.bahm.thoth.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bahm.thoth.inference.LlmState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onStartChat: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val llmState by viewModel.llmState.collectAsState()
    val zimReady by viewModel.zimReady.collectAsState()
    val modelPresent by viewModel.modelPresent.collectAsState()

    // True once the user has tapped "Start Chat" and we're waiting for the model to load.
    var starting by remember { mutableStateOf(false) }

    // Auto-navigate to chat once the model finishes loading after a Start tap.
    LaunchedEffect(llmState, starting) {
        if (starting) {
            when (llmState) {
                is LlmState.Ready -> {
                    starting = false
                    onStartChat()
                }
                is LlmState.Error -> starting = false
                else -> { /* still loading */ }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Thoth") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            val setupNeeded = !modelPresent || !zimReady
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "Thoth",
                    style = MaterialTheme.typography.headlineLarge,
                )
                Text(
                    "Your offline knowledge assistant",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))

                if (setupNeeded) {
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
                } else {
                    val loading = starting && llmState is LlmState.Initializing
                    Button(
                        onClick = {
                            if (llmState is LlmState.Ready) {
                                onStartChat()
                            } else {
                                starting = true
                                viewModel.loadModel()
                            }
                        },
                        enabled = !loading,
                    ) {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(18.dp).width(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Loading model…")
                        } else {
                            Text("Start Chat")
                        }
                    }
                    if (!starting && llmState is LlmState.Error) {
                        Text(
                            "Failed to load model: ${(llmState as LlmState.Error).message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}
