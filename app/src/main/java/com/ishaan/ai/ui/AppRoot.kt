package com.ishaan.ai.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ishaan.ai.ui.assistant.AssistantScreen
import com.ishaan.ai.ui.assistant.HealthReportScreen
import com.ishaan.ai.ui.history.HistoryScreen
import com.ishaan.ai.ui.home.DashboardScreen
import com.ishaan.ai.ui.meds.MedicationDetailScreen
import com.ishaan.ai.ui.meds.MedicationsScreen
import com.ishaan.ai.ui.monitoring.*
import com.ishaan.ai.ui.reports.KidneyScreen
import com.ishaan.ai.ui.reports.LabTrendScreen
import com.ishaan.ai.ui.reports.ReportDetailScreen
import com.ishaan.ai.ui.reports.ReportsScreen
import com.ishaan.ai.ui.settings.ProfileEditScreen
import com.ishaan.ai.ui.settings.SettingsScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selected: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Outlined.Home, Icons.Filled.Home),
    Tab("history", "History", Icons.Outlined.History, Icons.Filled.History),
    Tab("reports", "Reports", Icons.Outlined.Description, Icons.Filled.Description),
    Tab("monitoring", "Monitoring", Icons.Outlined.MonitorHeart, Icons.Filled.MonitorHeart),
    Tab("assistant", "AI Assistant", Icons.Outlined.AutoAwesome, Icons.Filled.AutoAwesome),
)

@Composable
fun AppRoot(onLock: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    Scaffold(
        bottomBar = {
            if (route in tabs.map { it.route }) NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = { nav.navigate(t.route) { popUpTo("home") { saveState = true }; launchSingleTop = true; restoreState = true } },
                        icon = { Icon(if (route == t.route) t.selected else t.icon, t.label) },
                        label = { Text(t.label, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(pad)) {
            composable("home") { DashboardScreen(nav, onLock) }
            composable("history") { HistoryScreen(nav) }
            composable("reports") { ReportsScreen(nav) }
            composable("monitoring") { MonitoringHub(nav) }
            composable("assistant") { AssistantScreen(nav) }
            composable("settings") { SettingsScreen(nav, onLock) }
            composable("profile") { ProfileEditScreen(nav) }
            composable("kidney") { KidneyScreen(nav) }
            composable("report/{id}", listOf(navArgument("id") { type = NavType.LongType })) { ReportDetailScreen(nav, it.arguments!!.getLong("id")) }
            composable("lab/{code}") { LabTrendScreen(nav, it.arguments!!.getString("code")!!) }
            composable("healthreport/{kind}") { HealthReportScreen(nav, it.arguments!!.getString("kind")!!) }
            composable("urine") { UrineScreen(nav) }
            composable("stool") { StoolScreen(nav) }
            composable("growth") { GrowthScreen(nav) }
            composable("feeding") { FeedingScreen(nav) }
            composable("sleep") { SleepScreen(nav) }
            composable("milestones") { MilestoneScreen(nav) }
            composable("symptoms") { SymptomScreen(nav) }
            composable("appointments") { AppointmentScreen(nav) }
            composable("meds") { MedicationsScreen(nav) }
            composable("med/{id}", listOf(navArgument("id") { type = NavType.LongType })) { MedicationDetailScreen(nav, it.arguments!!.getLong("id")) }
        }
    }
}

fun NavHostController.back() { if (!popBackStack()) navigate("home") }
