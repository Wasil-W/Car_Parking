package dev.wasil.permit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wasil.permit.ui.HandoffTabs
import dev.wasil.permit.ui.MainViewModel
import dev.wasil.permit.ui.SetupFlow
import dev.wasil.permit.ui.theme.HandoffTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as PermitApp
                return MainViewModel(
                    app.repository, app.credentialStore, app.parkStateStore,
                    guardedClaim = { app.guardedClaim() },
                    sharedStore = { app.sharedStateStore() },
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as PermitApp
        enableEdgeToEdge()
        setContent {
            HandoffTheme {
                // Every screen must sit inside a Surface. Without one,
                // LocalContentColor defaults to Color.Black, so any Text that
                // doesn't set its own colour renders black — invisible on the
                // dark background. This is what made Settings, the map and the
                // setup screens unreadable before v0.3.2; fixing it here rather
                // than per-screen also covers every screen added later.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    var setupDone by remember { mutableStateOf(false) }
                    val needsSetup =
                        (state.needsSetup || app.parkStateStore.myCar == null) && !setupDone

                    if (needsSetup) {
                        SetupFlow(
                            stateStore = app.parkStateStore,
                            onSaveCredentials = viewModel::saveSetup,
                            onDone = { setupDone = true },
                        )
                    } else {
                        HandoffTabs(
                            app = app,
                            state = state,
                            onSwitch = viewModel::switchTo,
                            onRefresh = viewModel::refresh,
                            onMessageShown = viewModel::consumeMessage,
                            onConfirmBlocked = viewModel::confirmBlockedSwitch,
                            onDismissBlocked = viewModel::dismissBlocked,
                        )
                    }
                }
            }
        }
    }
}
