package com.bahm.thoth

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.bahm.thoth.ui.navigation.ThothNavGraph
import com.bahm.thoth.ui.theme.ThothTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ThothTheme {
                val navController = rememberNavController()
                ThothNavGraph(navController)
            }
        }
    }
}
