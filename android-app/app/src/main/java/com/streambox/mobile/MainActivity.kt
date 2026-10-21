package com.streambox.mobile

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.streambox.mobile.ui.navigation.StreamBoxNavHost
import com.streambox.mobile.ui.theme.StreamBoxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is dark-only regardless of the system light/dark setting, so
        // force light system-bar icons instead of letting auto-detection pick
        // dark icons against our obsidian background.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            StreamBoxTheme {
                StreamBoxNavHost(
                    navController = rememberNavController(),
                    container = (application as StreamBoxApplication).container,
                )
            }
        }
    }
}
