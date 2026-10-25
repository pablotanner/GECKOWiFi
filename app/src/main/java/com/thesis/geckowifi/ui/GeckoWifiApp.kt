package com.thesis.geckowifi.ui

import android.content.Intent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.thesis.geckowifi.data.model.VerificationState
import com.thesis.geckowifi.di.AppModule
import com.thesis.geckowifi.ui.evidence.EvidenceScreen
import com.thesis.geckowifi.ui.history.HistoryScreen
import com.thesis.geckowifi.ui.home.HomeScreen
import com.thesis.geckowifi.ui.onboarding.PermissionScreen
import com.thesis.geckowifi.ui.settings.SavedDataScreen
import com.thesis.geckowifi.ui.settings.SettingsScreen
import com.thesis.geckowifi.ui.warning.WarningScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

/** Three places, one job each: act (Networks), look back (History), understand and test (Settings). */
private val tabs = listOf(
    Tab("home", "Networks", Icons.Outlined.Wifi),
    Tab("history", "History", Icons.Outlined.History),
    Tab("settings", "Settings", Icons.Outlined.Settings)
)

@Composable
fun GeckoWifiApp(
    hasLocationPermission: Boolean,
    permissionDeniedBefore: Boolean,
    onRequestLocationPermission: () -> Unit
) {
    val viewModel: VerificationViewModel = viewModel(factory = AppModule.verificationViewModelFactory)
    LaunchedEffect(hasLocationPermission) { viewModel.onPermissionResult(hasLocationPermission) }

    if (!hasLocationPermission) {
        PermissionScreen(deniedBefore = permissionDeniedBefore, onAllow = onRequestLocationPermission)
        return
    }

    // While the app is visible, GECKO keeps the saved answer for this place current (D26).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, viewModel) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.keepRegistrationsFresh() }
    }

    val navController = rememberNavController()

    // A CONFLICT always interrupts, whichever tab the user is on. Nothing else does.
    LaunchedEffect(viewModel.pendingWarning) {
        val id = viewModel.pendingWarning ?: return@LaunchedEffect
        viewModel.warningShown()
        navController.navigate("warning/$id") { launchSingleTop = true }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = tabs.any { it.route == currentRoute }
    val unseenConflict = viewModel.current?.result?.state == VerificationState.CONFLICT

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { navController.switchTab(tab.route) },
                            icon = {
                                if (tab.route == "home" && unseenConflict && currentRoute != "home") {
                                    BadgedBox(badge = { Badge() }) { Icon(tab.icon, contentDescription = null) }
                                } else {
                                    Icon(tab.icon, contentDescription = null)
                                }
                            },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = if (currentRoute?.startsWith("warning") == true) Modifier else Modifier.padding(padding)
        ) {
            composable("home") {
                HomeScreen(viewModel, onOpenCheck = { navController.navigate("check/$it") })
            }
            composable("history") {
                HistoryScreen(viewModel.checks, onOpenCheck = { navController.navigate("check/$it") })
            }
            composable("settings") { SettingsScreen(viewModel, onOpenSavedData = { navController.navigate("saved") }) }
            composable("saved") { SavedDataScreen(viewModel, onBack = { navController.popBackStack() }) }
            composable("check/{id}") { entry ->
                EvidenceScreen(
                    check = entry.arguments?.getString("id")?.let(viewModel::checkById),
                    labMode = viewModel.labMode,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("warning/{id}") { entry ->
                val id = entry.arguments?.getString("id")
                val check = id?.let(viewModel::checkById)
                if (check == null) {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                } else {
                    val context = LocalContext.current
                    WarningScreen(
                        check = check,
                        onDisconnect = {
                            // The device joined it through Android (a password network): only Android can leave it.
                            val androidsOwn = !check.joined && !check.isEnterprise && !check.isDemo &&
                                check.ssid.equals(viewModel.deviceSsid, ignoreCase = true)
                            viewModel.disconnect()
                            navController.popBackStack()
                            if (androidsOwn) context.startActivity(Intent(android.provider.Settings.Panel.ACTION_WIFI))
                        },
                        onStay = { navController.popBackStack() },
                        onEvidence = { navController.navigate("check/$id") }
                    )
                }
            }
        }
    }
}

private fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
