package com.bahm.thoth.knowledge

import com.bahm.thoth.knowledge.models.Article

/**
 * Read access to an offline ZIM archive, as needed by the shared pipeline. Both platforms back
 * this with the same `org.kiwix.libzim.*` bindings — they differ only in how the native libs are
 * loaded (Android: bundled in the APK; desktop: glibc `.so` on the library path).
 */
interface ZimSource {
    suspend fun searchArticles(query: String, maxResults: Int = 20): List<Article>
    suspend fun getArticleByTitle(title: String): Article?
    suspend fun getArticleByPath(path: String): Article?
}
