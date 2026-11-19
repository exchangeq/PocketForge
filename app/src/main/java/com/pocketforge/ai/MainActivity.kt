package com.pocketforge.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.pocketforge.ai.ui.AppRoot
import com.pocketforge.ai.ui.PocketForgeTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PocketForgeTheme {
                AppRoot()
            }
        }
    }
}
