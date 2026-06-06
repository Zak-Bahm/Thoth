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
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val ARTICLE = "article/{zimPath}"

    fun article(zimEntryPath: String): String = "article/${Uri.encode(zimEntryPath)}"
}

@Composable
fun ThothNavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onStartChat = { navController.navigate(Routes.CHAT) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.CHAT) {
            ChatScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenArticle = { zimEntryPath ->
                    navController.navigate(Routes.article(zimEntryPath))
                },
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
