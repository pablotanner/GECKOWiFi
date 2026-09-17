package com.thesis.geckowifi.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.thesis.geckowifi.data.model.VerificationResult
import com.thesis.geckowifi.data.model.VerificationState
import com.thesis.geckowifi.di.AppModule
import com.thesis.geckowifi.network.ScannedNetwork
import com.thesis.geckowifi.ui.activity.ActivityScreen
import com.thesis.geckowifi.ui.checkdetail.CheckDetailScreen
import com.thesis.geckowifi.ui.conflict.ConflictScreen
import com.thesis.geckowifi.ui.connected.ConnectedScreen
import com.thesis.geckowifi.ui.networkdetail.NetworkDetailScreen
import com.thesis.geckowifi.ui.networks.NetworksScreen
import com.thesis.geckowifi.ui.settings.SettingsScreen
import com.thesis.geckowifi.ui.status.StatusScreen
import com.thesis.geckowifi.ui.technicaldetails.TechnicalDetailsSheet

private data class BottomDestination(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val bottomDestinations = listOf(
    BottomDestination("networks", "Networks", Icons.Outlined.Wifi),
    BottomDestination("status", "Status", Icons.Outlined.Timeline),
    BottomDestination("activity", "Activity", Icons.Outlined.History),
    BottomDestination("settings", "Settings", Icons.Outlined.Settings)
)

@Composable
fun GeckoWifiApp(hasLocationPermission: Boolean, onRequestLocationPermission: () -> Unit) {
    val viewModel: VerificationViewModel = viewModel(factory = AppModule.verificationViewModelFactory)
    val navController = rememberNavController()

    var selectedRealNetwork by remember { mutableStateOf<ScannedNetwork.Real?>(null) }
    var showTechnicalDetails by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(hasLocationPermission) {
        viewModel.onPermissionResult(hasLocationPermission)
    }

    /**
     * [isEnterpriseCheck] is passed explicitly by the caller, which already
     * knows which kind of check it just dispatched (verifyEnterprise vs.
     * verifyPresentedDomain) - deliberately not inferred from which
     * [VerificationResult] fields happen to be non-null. An earlier version
     * of this function used `matchedIdentifier != null && presentedIdentifier
     * != null` as an implicit "is this an enterprise result" signal, which
     * only worked because nothing populated those fields on PortalSession's
     * VERIFIED branch - a fragile coincidence that would have silently
     * broken (routing a successful portal check to the enterprise-only
     * "Connected" screen) the moment anything legitimately populated them
     * for a portal VERIFIED result too.
     */
    fun navigateForResult(result: VerificationResult, isEnterpriseCheck: Boolean) {
        when {
            result.state == VerificationState.CONFLICT -> navController.navigate("conflict")
            result.state == VerificationState.VERIFIED && isEnterpriseCheck -> navController.navigate("connected")
            // A VERIFIED portal result has no screen of its own (no WebView
            // integration exists - see README.md) - stays silent by design,
            // same as every other non-CONFLICT state.
            else -> Unit
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = bottomDestinations.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomDestinations.forEach { dest ->
                        NavigationBarItem(
                            selected = currentRoute == dest.route,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { androidx.compose.material3.Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "networks",
            modifier = Modifier.padding(padding)
        ) {
            composable("networks") {
                NetworksScreen(
                    viewModel = viewModel,
                    onRequestLocationPermission = onRequestLocationPermission,
                    onNetworkClick = { network ->
                        when (network) {
                            is ScannedNetwork.Real -> {
                                selectedRealNetwork = network
                                navController.navigate("networkDetail")
                            }
                            else -> viewModel.checkFake(network) { result ->
                                navigateForResult(result, isEnterpriseCheck = network is ScannedNetwork.FakeEduroam)
                            }
                        }
                    }
                )
            }
            composable("status") { StatusScreen() }
            composable("activity") { ActivityScreen(viewModel) }
            composable("settings") { SettingsScreen(viewModel) }
            composable("networkDetail") {
                val network = selectedRealNetwork
                if (network != null) {
                    NetworkDetailScreen(
                        network = network,
                        viewModel = viewModel,
                        // checkReal() only ever calls VerificationEngine.verify() (the portal/domain
                        // path) - a real network is never checked via verifyEnterprise() in this UI.
                        onCheckResult = { result -> navigateForResult(result, isEnterpriseCheck = false) },
                        onBack = { navController.popBackStack() }
                    )
                }
            }
            composable("conflict") {
                ConflictScreen(
                    viewModel,
                    onTechnicalDetails = { showTechnicalDetails = true },
                    onCheckDetails = { navController.navigate("checkDetail") },
                    onBack = { navController.popBackStack() }
                )
            }
            composable("connected") {
                ConnectedScreen(
                    viewModel,
                    onTechnicalDetails = { showTechnicalDetails = true },
                    onCheckDetails = { navController.navigate("checkDetail") },
                    onBack = { navController.popBackStack() }
                )
            }
            composable("checkDetail") {
                CheckDetailScreen(viewModel, onBack = { navController.popBackStack() })
            }
        }
    }

    if (showTechnicalDetails) {
        TechnicalDetailsSheet(viewModel, onDismiss = { showTechnicalDetails = false })
    }
}
