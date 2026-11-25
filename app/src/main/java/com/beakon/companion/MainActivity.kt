package com.beakon.companion

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.beakon.companion.data.ThemeMode
import com.beakon.companion.ui.AppViewModel
import com.beakon.companion.ui.BeakonApp
import com.beakon.companion.ui.theme.BeakonTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A widget tap arrives as an ACTION_VIEW intent carrying the person's map
        // URL; AppViewModel only accepts it for the configured instance.
        viewModel.openMapFromWidget(intent?.data?.toString())
        setContent {
            val themeMode by viewModel.themeMode.collectAsState()
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            BeakonTheme(darkTheme = darkTheme) {
                BeakonApp(viewModel)
            }
        }
    }

    /** The app is singleTask, so a widget tap while running lands here, not onCreate. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.openMapFromWidget(intent.data?.toString())
    }
}
