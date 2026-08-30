package com.example.checkin.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckInApp(viewModel: CheckInViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            // 记录维护页为独立全屏页，不显示底部导航
            if (currentRoute != "record_maintenance") {
                NavigationBar {
                NavigationBarItem(
                    selected = currentRoute == "home",
                    onClick = {
                        navController.navigate("home") {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(Icons.Filled.CheckCircle, contentDescription = "打卡") },
                    label = { Text("打卡") }
                )
                NavigationBarItem(
                    selected = currentRoute == "rules",
                    onClick = {
                        navController.navigate("rules") {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(Icons.AutoMirrored.Filled.Rule, contentDescription = "规则") },
                    label = { Text("规则") }
                )
                NavigationBarItem(
                    selected = currentRoute == "calendar",
                    onClick = {
                        navController.navigate("calendar") {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(Icons.Filled.CalendarMonth, contentDescription = "日历") },
                    label = { Text("日历") }
                )
                NavigationBarItem(
                    selected = currentRoute == "settings",
                    onClick = {
                        navController.navigate("settings") {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = "设置") },
                    label = { Text("设置") }
                )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("home") { HomeScreen(viewModel) }
            composable("rules") { RulesScreen(viewModel) }
            composable("calendar") { CalendarScreen(viewModel) }
            composable("settings") {
                SettingsScreen(
                    viewModel = viewModel,
                    onOpenMaintenance = { navController.navigate("record_maintenance") }
                )
            }
            composable("record_maintenance") {
                RecordMaintenanceScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
