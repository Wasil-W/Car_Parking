package dev.wasil.permit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wasil.permit.data.store.roster
import dev.wasil.permit.parking.PendingDecision
import dev.wasil.permit.parking.android.ParkActionReceiver
import dev.wasil.permit.parking.android.SharedSync
import dev.wasil.permit.parking.currentPendingDecision
import dev.wasil.permit.ui.DecisionActionKind
import dev.wasil.permit.ui.DecisionScreen
import dev.wasil.permit.ui.HandoffTabs
import dev.wasil.permit.ui.MainViewModel
import dev.wasil.permit.ui.SetupFlow
import dev.wasil.permit.ui.theme.HandoffTheme

class MainActivity : ComponentActivity() {

    companion object {
        /** Carries only an id (the decision's raisedAtMs) — never its facts, which the screen re-reads from ParkStateStore. */
        const val EXTRA_DECISION_ID = "dev.wasil.permit.EXTRA_DECISION_ID"
    }

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

    /**
     * A shorter check than the 15-minute heartbeat floor: whenever the app is
     * looked at, ask right away whether the other phone has taken the permit,
     * instead of waiting for the next scheduled tick.
     */
    override fun onStart() {
        super.onStart()
        SharedSync.requestImmediateHeartbeat(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as PermitApp
        enableEdgeToEdge()
        val hasDecisionExtra = intent.hasExtra(EXTRA_DECISION_ID)
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
                    // Read fresh from the persisted store, never from the
                    // intent's own extras. Age-expiry alone isn't enough — the
                    // situation a decision was about (the other car parked,
                    // holding the permit) can lapse long before 12 hours are
                    // up, so it's re-validated against live facts before ever
                    // being shown. `checked` gates the fallback screen so a
                    // still-valid decision doesn't flash the main screen first.
                    var pendingDecision by remember { mutableStateOf<PendingDecision?>(null) }
                    var checked by remember { mutableStateOf(!hasDecisionExtra) }

                    LaunchedEffect(hasDecisionExtra) {
                        if (hasDecisionExtra) {
                            val stored = app.parkStateStore.currentPendingDecision()
                            val stillValid = stored != null && app.guardedClaim().stillStands(stored)
                            if (stillValid) {
                                pendingDecision = stored
                            } else {
                                // Either never existed, expired, or its situation
                                // lapsed (e.g. the other car drove off) - clear so
                                // it can't resurface, and fall through below.
                                app.parkStateStore.pendingDecision = null
                            }
                            checked = true
                        }
                    }

                    val decision = pendingDecision

                    if (!checked) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else if (decision != null) {
                        DecisionScreen(
                            decision = decision,
                            onChoice = { kind ->
                                // Same call the notification's own action buttons
                                // make — exactly one implementation per action.
                                ParkActionReceiver.perform(this@MainActivity, actionNameFor(kind))
                                pendingDecision = null
                            },
                        )
                    } else {
                        val state by viewModel.state.collectAsStateWithLifecycle()
                        var setupDone by remember { mutableStateOf(false) }
                        // The permit is no longer a wall. It used to be:
                        // `state.needsSetup` was true whenever no credentials
                        // were stored, and the app showed a four-field form
                        // instead of itself. That called a working
                        // configuration broken — the obligation half computes
                        // what a spot costs from position alone and never
                        // consults a permit — and it put the one question that
                        // only matters to permit holders in front of everyone.
                        // The form lives in Settings now, behind "Add a
                        // permit", and the first run asks the one thing
                        // detection genuinely cannot work without.
                        val needsSetup =
                            app.parkStateStore.thisPhoneDrives == null && !setupDone

                        if (needsSetup) {
                            SetupFlow(
                                stateStore = app.parkStateStore,
                                roster = app.credentialStore.roster(),
                                onDone = { setupDone = true },
                            )
                        } else {
                            HandoffTabs(
                                app = app,
                                state = state,
                                onSwitch = viewModel::switchTo,
                                onRefresh = viewModel::refresh,
                                onMessageShown = viewModel::consumeMessage,
                                onSavePermit = viewModel::saveSetup,
                                onFindPlates = viewModel::findPlates,
                                onConfirmBlocked = viewModel::confirmBlockedSwitch,
                                onDismissBlocked = viewModel::dismissBlocked,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun actionNameFor(kind: DecisionActionKind): String = when (kind) {
    DecisionActionKind.CLAIM -> ParkActionReceiver.ACTION_CLAIM
    DecisionActionKind.CLAIM_FORCE -> ParkActionReceiver.ACTION_CLAIM_FORCE
    DecisionActionKind.GIVE_BACK -> ParkActionReceiver.ACTION_GIVE_BACK
    DecisionActionKind.IGNORE -> ParkActionReceiver.ACTION_IGNORE
    DecisionActionKind.FREE_HERE -> ParkActionReceiver.ACTION_FREE_HERE
}
