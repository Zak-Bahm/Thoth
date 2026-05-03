package com.bahm.thoth.ui.demo

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZimDemoScreen(viewModel: ZimDemoViewModel = hiltViewModel()) {
    val downloadStatus by viewModel.downloadStatus.collectAsState()
    val archiveInfo by viewModel.archiveInfo.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val selectedArticle by viewModel.selectedArticle.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

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
                title = { Text("Thoth - ZIM Demo") },
                navigationIcon = {
                    if (selectedArticle != null) {
                        IconButton(onClick = { viewModel.clearSelectedArticle() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
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
            DemoContent(
                downloadStatus = downloadStatus,
                archiveInfo = archiveInfo,
                searchQuery = searchQuery,
                searchResults = searchResults,
                onDownloadMini = viewModel::downloadMini,
                onDownloadNopic = viewModel::downloadNopic,
                onCancelDownload = viewModel::cancelDownload,
                onSearchQueryChange = viewModel::updateSearchQuery,
                onSearch = viewModel::search,
                onArticleClick = viewModel::selectArticle,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }
}

@Composable
private fun DemoContent(
    downloadStatus: DownloadStatus,
    archiveInfo: ArchiveInfo?,
    searchQuery: String,
    searchResults: List<SearchResultItem>,
    onDownloadMini: () -> Unit,
    onDownloadNopic: () -> Unit,
    onCancelDownload: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onArticleClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.padding(horizontal = 16.dp)) {
        // Download section
        item {
            Text("Download", style = MaterialTheme.typography.titleMedium)
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

        // Search section
        item {
            Text("Search", style = MaterialTheme.typography.titleMedium)
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
                Text("Search")
            }
            Spacer(Modifier.height(8.dp))
        }

        // Search results
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
