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
    const val ARTICLE = "article/{zimPath}"

    fun article(zimEntryPath: String): String = "article/${Uri.encode(zimEntryPath)}"

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
                onOpenArticle = { zimEntryPath ->
                    navController.navigate(Routes.article(zimEntryPath))
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
                onOpenArticle = { zimEntryPath ->
                    navController.navigate(Routes.article(zimEntryPath))
                },
                initialQuery = backStackEntry.arguments?.getString("q"),
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.ARTICLE,
            arguments = listOf(navArgument("zimPath") { type = NavType.StringType }),
        ) { backStackEntry ->
            val zimPath = backStackEntry.arguments?.getString("zimPath").orEmpty()
            ArticleScreen(
                zimEntryPath = zimPath,
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}
