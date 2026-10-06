package com.yuejian.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import com.yuejian.designsystem.YuejianTheme
import com.yuejian.library.LibraryScreen
import com.yuejian.reader.ReaderScreen
import com.yuejian.settings.SettingsScreen
import com.yuejian.model.ReadingPreferences
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint class MainActivity : ComponentActivity() {
    @Inject lateinit var preferences: ReadingPreferences
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark by preferences.darkTheme.collectAsStateWithLifecycle(false)
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            YuejianTheme(dark) {
                Surface {
                    val nav = rememberNavController()
                    NavHost(navController = nav, startDestination = "library") {
                        composable("library") { LibraryScreen(onOpen = { nav.navigate("reader/$it") }, onSettings = { nav.navigate("settings") }) }
                        composable("reader/{documentId}") { ReaderScreen(onBack = { nav.popBackStack() }, onSettings = { nav.navigate("settings") }) }
                        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
                    }
                }
            }
        }
    }
}
