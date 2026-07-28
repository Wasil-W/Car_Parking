package dev.wasil.permit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wasil.permit.ui.MainScreen
import dev.wasil.permit.ui.MainViewModel
import dev.wasil.permit.ui.SettingsScreen
import dev.wasil.permit.ui.SetupScreen

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as PermitApp
                return MainViewModel(app.repository, app.credentialStore) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as PermitApp
        setContent {
            MaterialTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                var showSettings by remember { mutableStateOf(false) }
                when {
                    state.needsSetup -> SetupScreen(onSave = viewModel::saveSetup)
                    showSettings -> {
                        BackHandler { showSettings = false }
                        SettingsScreen(
                            stateStore = app.parkStateStore,
                            freeZoneStore = app.freeZoneStore,
                            onBack = { showSettings = false },
                        )
                    }
                    else -> MainScreen(
                        state = state,
                        onSwitch = viewModel::switchTo,
                        onRefresh = viewModel::refresh,
                        onMessageShown = viewModel::consumeMessage,
                        onOpenSettings = { showSettings = true },
                    )
                }
            }
        }
    }
}
