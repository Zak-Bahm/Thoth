package com.bahm.thoth.ui.article

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bahm.thoth.knowledge.ZimRepository
import com.bahm.thoth.knowledge.models.Article
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ArticleViewModel @Inject constructor(
    private val zimRepository: ZimRepository,
) : ViewModel() {

    companion object {
        private const val TAG = "ArticleViewModel"
    }

    private val _article = MutableStateFlow<Article?>(null)
    val article: StateFlow<Article?> = _article.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    fun load(zimEntryPath: String) {
        if (zimEntryPath.isBlank()) return
        viewModelScope.launch {
            _loading.value = true
            val result = zimRepository.getArticleByPath(zimEntryPath)
            Log.d(TAG, "load(\"$zimEntryPath\") | found=${result != null} | title=${result?.title}")
            _article.value = result
            _loading.value = false
        }
    }
}
