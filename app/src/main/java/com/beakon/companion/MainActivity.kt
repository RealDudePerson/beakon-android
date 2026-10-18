package com.beakon.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.beakon.companion.ui.BeakonApp
import com.beakon.companion.ui.theme.BeakonTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BeakonTheme {
                BeakonApp()
            }
        }
    }
}
