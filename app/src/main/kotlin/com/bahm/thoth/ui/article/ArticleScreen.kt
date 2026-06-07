package com.bahm.thoth.ui.article

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleScreen(
    zimEntryPath: String,
    onNavigateBack: () -> Unit,
    anchor: String? = null,
    heading: String? = null,
    viewModel: ArticleViewModel = hiltViewModel(),
) {
    val article by viewModel.article.collectAsState()
    val loading by viewModel.loading.collectAsState()

    LaunchedEffect(zimEntryPath) { viewModel.load(zimEntryPath) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        article?.title ?: "Article",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                article == null -> Text(
                    "Article not found.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> ArticleWebView(
                    html = article!!.htmlContent,
                    anchor = anchor,
                    heading = heading,
                )
            }
        }
    }
}

/**
 * Sandboxed WebView for the full ZIM article HTML: no file/content access, no network. The
 * page's own `<script>` tags are stripped, then JS is enabled solely so we can inject one
 * [scrollIntoView] call to deep-link to the cited section ([anchor], with [heading] as a
 * text-match fallback). With no anchor the article opens at the top, as before.
 */
@Composable
private fun ArticleWebView(html: String, anchor: String?, heading: String?) {
    val safeHtml = remember(html) {
        org.jsoup.Jsoup.parse(html).apply { select("script").remove() }.html()
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        val js = scrollScript(anchor, heading) ?: return
                        view.evaluateJavascript(js, null)
                    }
                }
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(null, safeHtml, "text/html", "utf-8", null)
        },
    )
}

/**
 * JS that scrolls to the cited section: by element id first (authoritative — section ids are
 * unique even when heading text repeats), falling back to matching heading text. Once found, it
 * briefly flashes a highlight on the heading so the reader sees exactly where the citation lands.
 * Returns null when there's nothing to scroll to.
 */
private fun scrollScript(anchor: String?, heading: String?): String? {
    if (anchor.isNullOrBlank() && heading.isNullOrBlank()) return null
    val idLit = org.json.JSONObject.quote(anchor.orEmpty())
    val hLit = org.json.JSONObject.quote(heading.orEmpty())
    return """
        (function(){
          var id=$idLit, h=$hLit;
          var e=id?document.getElementById(id):null;
          if(!e&&h){
            var hs=document.querySelectorAll('h1,h2,h3,h4');
            for(var i=0;i<hs.length;i++){
              if(hs[i].textContent.trim()===h){e=hs[i];break;}
            }
          }
          if(e){
            e.scrollIntoView();
            var prevBg=e.style.backgroundColor, prevT=e.style.transition;
            e.style.transition='background-color 0.4s ease';
            e.style.backgroundColor='#fff59d';
            setTimeout(function(){
              e.style.backgroundColor=prevBg;
              setTimeout(function(){ e.style.transition=prevT; }, 400);
            }, 1500);
          }
        })();
    """.trimIndent()
}
