package com.bahm.thoth.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.bahm.thoth.ui.article.ArticleScreen
import com.bahm.thoth.ui.chat.ChatScreen
import com.bahm.thoth.ui.home.HomeScreen
import com.bahm.thoth.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val CHAT = "chat?q={q}"
    const val SETTINGS = "settings"
    const val ARTICLE = "article/{zimPath}?a={anchor}&h={heading}"

    fun article(zimEntryPath: String, anchor: String? = null, heading: String? = null): String =
        "article/${Uri.encode(zimEntryPath)}" +
            "?a=${Uri.encode(anchor.orEmpty())}&h=${Uri.encode(heading.orEmpty())}"

    /** Open the detailed chat, optionally pre-running a question handed off from Home. */
    fun chat(query: String? = null): String =
        if (query.isNullOrBlank()) "chat" else "chat?q=${Uri.encode(query)}"
}

@Composable
fun ThothNavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onSearchDetail = { query -> navController.navigate(Routes.chat(query)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenArticle = { zimEntryPath, anchor, heading ->
                    navController.navigate(Routes.article(zimEntryPath, anchor, heading))
                },
            )
        }
        composable(
            route = Routes.CHAT,
            arguments = listOf(
                navArgument("q") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            ChatScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenArticle = { zimEntryPath, anchor, heading ->
                    navController.navigate(Routes.article(zimEntryPath, anchor, heading))
                },
                initialQuery = backStackEntry.arguments?.getString("q"),
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.ARTICLE,
            arguments = listOf(
                navArgument("zimPath") { type = NavType.StringType },
                navArgument("anchor") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("heading") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val zimPath = backStackEntry.arguments?.getString("zimPath").orEmpty()
            ArticleScreen(
                zimEntryPath = zimPath,
                anchor = backStackEntry.arguments?.getString("anchor")?.ifBlank { null },
                heading = backStackEntry.arguments?.getString("heading")?.ifBlank { null },
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}
