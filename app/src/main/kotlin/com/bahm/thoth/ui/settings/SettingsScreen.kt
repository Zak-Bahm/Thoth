package com.bahm.thoth.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bahm.thoth.inference.LlmState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val downloadStatus by viewModel.downloadStatus.collectAsState()
    val archiveInfo by viewModel.archiveInfo.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val selectedArticle by viewModel.selectedArticle.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val pipelineState by viewModel.pipelineState.collectAsState()
    val modelDownloadStatus by viewModel.modelDownloadStatus.collectAsState()
    val modelFileExists by viewModel.modelFileExists.collectAsState()
    val llmState by viewModel.llmState.collectAsState()
    val chatMessages by viewModel.chatMessages.collectAsState()
    val chatInput by viewModel.chatInput.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()

    // Request notification permission on Android 13+
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { /* no-op — download works either way, notification is just nice to have */ }

        LaunchedEffect(Unit) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selectedArticle != null) "Article" else "Settings") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (selectedArticle != null) {
                                viewModel.clearSelectedArticle()
                            } else {
                                onNavigateBack()
                            }
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (selectedArticle != null) {
            ArticleView(
                title = selectedArticle!!.title,
                htmlContent = selectedArticle!!.htmlContent,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        } else {
            SettingsContent(
                downloadStatus = downloadStatus,
                archiveInfo = archiveInfo,
                searchQuery = searchQuery,
                searchResults = searchResults,
                pipelineState = pipelineState,
                modelDownloadStatus = modelDownloadStatus,
                modelFileExists = modelFileExists,
                llmState = llmState,
                chatMessages = chatMessages,
                chatInput = chatInput,
                isGenerating = isGenerating,
                onDownloadMini = viewModel::downloadMini,
                onDownloadNopic = viewModel::downloadNopic,
                onCancelDownload = viewModel::cancelDownload,
                onSearchQueryChange = viewModel::updateSearchQuery,
                onSearch = viewModel::search,
                onSearchPipeline = viewModel::searchPipeline,
                onArticleClick = viewModel::selectArticle,
                onDownloadModel = viewModel::downloadModel,
                onCancelModelDownload = viewModel::cancelModelDownload,
                onLoadModel = viewModel::loadModel,
                onReleaseModel = viewModel::releaseModel,
                onChatInputChange = viewModel::updateChatInput,
                onSendChat = viewModel::sendChatMessage,
                onResetChat = viewModel::resetChat,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }
}

@Composable
private fun SettingsContent(
    downloadStatus: DownloadStatus,
    archiveInfo: ArchiveInfo?,
    searchQuery: String,
    searchResults: List<SearchResultItem>,
    pipelineState: PipelineSearchState,
    modelDownloadStatus: DownloadStatus,
    modelFileExists: Boolean,
    llmState: LlmState,
    chatMessages: List<ChatMessage>,
    chatInput: String,
    isGenerating: Boolean,
    onDownloadMini: () -> Unit,
    onDownloadNopic: () -> Unit,
    onCancelDownload: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSearchPipeline: () -> Unit,
    onArticleClick: (String) -> Unit,
    onDownloadModel: () -> Unit,
    onCancelModelDownload: () -> Unit,
    onLoadModel: () -> Unit,
    onReleaseModel: () -> Unit,
    onChatInputChange: (String) -> Unit,
    onSendChat: () -> Unit,
    onResetChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.padding(horizontal = 16.dp)) {
        // Knowledge (ZIM) section
        item {
            Text("Knowledge Pack", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            when (downloadStatus) {
                is DownloadStatus.Idle -> {
                    Button(
                        onClick = onDownloadMini,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Download Wikipedia Mini (~12 GB)")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onDownloadNopic,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Download Wikipedia Nopic (~48 GB)")
                    }
                }

                is DownloadStatus.Downloading -> {
                    val progress = downloadStatus
                    if (progress.totalBytes > 0) {
                        val fraction = progress.bytesDownloaded.toFloat() / progress.totalBytes
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "%.1f / %.1f GB".format(
                                progress.bytesDownloaded / 1_000_000_000.0,
                                progress.totalBytes / 1_000_000_000.0,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "%.1f GB downloaded".format(
                                progress.bytesDownloaded / 1_000_000_000.0,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onCancelDownload) {
                        Text("Cancel")
                    }
                }

                is DownloadStatus.Complete -> {
                    Text("Download complete", color = MaterialTheme.colorScheme.primary)
                }

                is DownloadStatus.Error -> {
                    Text(
                        "Error: ${downloadStatus.message}",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onDownloadMini) {
                        Text("Retry")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
        }

        // Archive info section
        item {
            Text("Archive Info", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            if (archiveInfo != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Filename: ${archiveInfo.filename}", style = MaterialTheme.typography.bodyMedium)
                        Text("Articles: ${archiveInfo.articleCount}", style = MaterialTheme.typography.bodyMedium)
                        Text("Total entries: ${archiveInfo.allEntryCount}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Fulltext index: ${if (archiveInfo.hasFulltextIndex) "Yes" else "No"}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                Text("No archive loaded", style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
        }

        // Search testing section
        item {
            Text("Search Testing", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                label = { Text("Search query") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onSearch,
                enabled = archiveInfo != null && searchQuery.isNotBlank(),
            ) {
                Text("Search (Article-level)")
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = onSearchPipeline,
                enabled = archiveInfo != null && searchQuery.isNotBlank() && !pipelineState.isSearching,
            ) {
                Text("Search Pipeline (Passage-level)")
            }
            Spacer(Modifier.height(8.dp))
        }

        // Article-level search results
        if (searchResults.isNotEmpty()) {
            item {
                Text(
                    "Article Results (${searchResults.size})",
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.height(4.dp))
            }
        }
        items(searchResults, key = { it.path }) { result ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onArticleClick(result.path) },
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        result.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        result.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Pipeline search results
        if (pipelineState.isSearching) {
            item {
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text("Searching pipeline...", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (pipelineState.results.isNotEmpty()) {
            item {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    "Pipeline Results (${pipelineState.results.size} passages, ${pipelineState.searchTimeMs}ms)",
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.height(4.dp))
            }
            items(pipelineState.results, key = { it.passage.id }) { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            item.passage.articleTitle,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (item.passage.sectionHeading != null) {
                            Text(
                                item.passage.sectionHeading,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            item.passage.text.take(200) + if (item.passage.text.length > 200) "..." else "",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // Model section
        item {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("Model", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            when (modelDownloadStatus) {
                is DownloadStatus.Idle -> {
                    Button(
                        onClick = onDownloadModel,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Download Gemma 4 E4B (~3.6 GB)")
                    }
                }

                is DownloadStatus.Downloading -> {
                    val progress = modelDownloadStatus
                    if (progress.totalBytes > 0) {
                        val fraction = progress.bytesDownloaded.toFloat() / progress.totalBytes
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "%.1f / %.1f GB".format(
                                progress.bytesDownloaded / 1_000_000_000.0,
                                progress.totalBytes / 1_000_000_000.0,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "%.1f GB downloaded".format(
                                progress.bytesDownloaded / 1_000_000_000.0,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onCancelModelDownload) {
                        Text("Cancel")
                    }
                }

                is DownloadStatus.Complete -> {
                    Text("Model downloaded", color = MaterialTheme.colorScheme.primary)
                }

                is DownloadStatus.Error -> {
                    Text(
                        "Error: ${modelDownloadStatus.message}",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onDownloadModel) {
                        Text("Retry")
                    }
                }
            }
        }

        // Model loading section
        if (modelFileExists) {
            item {
                Spacer(Modifier.height(16.dp))
                Text("Model Loading", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                when (llmState) {
                    is LlmState.Uninitialized -> {
                        Button(
                            onClick = onLoadModel,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Load Model")
                        }
                    }

                    is LlmState.Initializing -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator()
                            Spacer(Modifier.width(12.dp))
                            Text("Loading model...", style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    is LlmState.Ready -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Model ready",
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(onClick = onReleaseModel) {
                                Text("Release")
                            }
                        }
                    }

                    is LlmState.Error -> {
                        Text(
                            "Error: ${llmState.message}",
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onLoadModel) {
                            Text("Retry")
                        }
                    }
                }
            }
        }

        // Chat testing section
        if (llmState is LlmState.Ready) {
            item {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Chat Testing",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (chatMessages.isNotEmpty()) {
                        OutlinedButton(onClick = onResetChat) {
                            Text("Reset")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            items(chatMessages.size, key = { it }) { index ->
                val message = chatMessages[index]
                val isUser = message.role == "user"
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isUser) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            if (isUser) "You" else "Thoth",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isUser) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            message.text.ifEmpty { "..." },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isUser) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            item {
                if (isGenerating) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = chatInput,
                        onValueChange = onChatInputChange,
                        label = { Text("Ask a question") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        enabled = !isGenerating,
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = onSendChat,
                        enabled = chatInput.isNotBlank() && !isGenerating,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun ArticleView(
    title: String,
    htmlContent: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            htmlContent,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        )
    }
}
