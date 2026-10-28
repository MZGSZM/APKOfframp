package com.mzgszm.apkofframp.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mzgszm.apkofframp.R

object Routes {
    const val APPS = "apps"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val ADD = "add"
    const val DETAIL = "detail/{pkg}"
    const val DEST = "destination/{id}"
    fun detail(pkg: String) = "detail/$pkg"
    fun destination(id: Long) = "destination/$id"
}

private data class Tab(val route: String, val label: String, val icon: Int)

private val tabs = listOf(
    Tab(Routes.APPS, "Apps", R.drawable.ic_apps),
    Tab(Routes.HISTORY, "History", R.drawable.ic_history),
    Tab(Routes.SETTINGS, "Settings", R.drawable.ic_settings),
)

@Composable
fun OfframpNav(openPkg: String?, onOpened: () -> Unit) {
    val nav = rememberNavController()
    LaunchedEffect(openPkg) {
        if (openPkg != null) {
            nav.navigate(Routes.detail(openPkg)) { launchSingleTop = true }
            onOpened()
        }
    }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = tabs.any { it.route == route }

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = { nav.goTab(t.route) },
                            icon = { Icon(painterResource(t.icon), null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { inner ->
        NavHost(nav, startDestination = Routes.APPS, modifier = Modifier) {
            composable(Routes.APPS) {
                AppsScreen(inner, onAdd = { nav.navigate(Routes.ADD) }, onOpen = { nav.navigate(Routes.detail(it)) })
            }
            composable(Routes.HISTORY) {
                HistoryScreen(inner, onOpen = { nav.navigate(Routes.detail(it)) })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(inner, onEditDestination = { nav.navigate(Routes.destination(it)) })
            }
            composable(Routes.ADD) {
                AddAppScreen(onBack = { nav.popBackStack() }, onAdded = { pkg ->
                    nav.popBackStack()
                    nav.navigate(Routes.detail(pkg))
                })
            }
            composable(Routes.DETAIL, arguments = listOf(navArgument("pkg") { type = NavType.StringType })) { e ->
                DetailScreen(e.arguments?.getString("pkg").orEmpty(), onBack = { nav.popBackStack() })
            }
            composable(Routes.DEST, arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                DestinationScreen(e.arguments?.getLong("id") ?: 0L, onBack = { nav.popBackStack() })
            }
        }
    }
}

private fun NavHostController.goTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
