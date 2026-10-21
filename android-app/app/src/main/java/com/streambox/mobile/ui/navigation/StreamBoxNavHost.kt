package com.streambox.mobile.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.streambox.mobile.AppContainer
import com.streambox.mobile.ui.player.PlayerScreen
import com.streambox.mobile.ui.player.PlayerViewModel
import com.streambox.mobile.ui.screens.detail.DetailScreen
import com.streambox.mobile.ui.screens.detail.DetailViewModel
import com.streambox.mobile.ui.screens.home.HomeScreen
import com.streambox.mobile.ui.screens.home.HomeViewModel
import com.streambox.mobile.ui.screens.search.SearchScreen
import com.streambox.mobile.ui.screens.search.SearchViewModel
import com.streambox.mobile.ui.screens.splash.SplashScreen
import com.streambox.mobile.ui.screens.watchlist.WatchlistScreen
import com.streambox.mobile.ui.screens.watchlist.WatchlistViewModel

object Routes {
    const val SPLASH = "splash"
    const val HOME = "home"
    const val SEARCH = "search"
    const val WATCHLIST = "watchlist"
    const val DETAIL_ARG = "subjectId"
    const val DETAIL = "detail/{$DETAIL_ARG}"
    const val PLAYER_ARG = "playbackId"
    const val PLAYER =
        "player/{$PLAYER_ARG}?season={season}&episode={episode}" +
            "&title={title}&poster={poster}&isSeries={isSeries}&resourceId={resourceId}"
}

private data class TopTab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TopTab(Routes.HOME, "Home", Icons.Filled.Home),
    TopTab(Routes.SEARCH, "Search", Icons.Filled.Search),
    TopTab(Routes.WATCHLIST, "Watchlist", Icons.AutoMirrored.Filled.List),
)

@Composable
fun StreamBoxNavHost(
    navController: NavHostController,
    container: AppContainer,
    modifier: Modifier = Modifier,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in tabs.map { it.route }

    Scaffold(
        modifier = modifier,
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
                ) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.SPLASH,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.SPLASH) {
                SplashScreen(onReady = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                })
            }

            composable(Routes.HOME) {
                val vm: HomeViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { HomeViewModel(container.repository) }
                    },
                )
                HomeScreen(viewModel = vm, onOpenDetail = { item ->
                    navController.navigate("detail/${item.subjectId}")
                })
            }

            composable(Routes.SEARCH) {
                val vm: SearchViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { SearchViewModel(container.repository) }
                    },
                )
                SearchScreen(viewModel = vm, onOpenDetail = { item ->
                    navController.navigate("detail/${item.subjectId}")
                })
            }

            composable(Routes.WATCHLIST) {
                val vm: WatchlistViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { WatchlistViewModel(container.watchlist) }
                    },
                )
                WatchlistScreen(
                    viewModel = vm,
                    onOpenDetail = { subjectId -> navController.navigate("detail/$subjectId") },
                )
            }

            composable(
                route = Routes.DETAIL,
                arguments = listOf(
                    navArgument(Routes.DETAIL_ARG) { type = NavType.StringType },
                ),
            ) { entry ->
                val subjectId = entry.arguments?.getString(Routes.DETAIL_ARG).orEmpty()
                val vm: DetailViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { DetailViewModel(container.repository, container.watchlist) }
                    },
                )
                val detailState by vm.state.collectAsState()
                DetailScreen(
                    subjectId = subjectId,
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onOpenDetail = { dubSubjectId ->
                        navController.navigate("detail/$dubSubjectId") {
                            popUpTo(navController.graph.findStartDestination().id) {
                                inclusive = false
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onPlay = { season, episode, resourceId ->
                        val item = detailState.item
                        val title = item?.title ?: ""
                        val poster = item?.imageUrl ?: ""
                        val isSeries = item?.subjectType == 2
                        navController.navigate(
                            "player/$subjectId" +
                                "?season=$season&episode=$episode" +
                                "&title=${android.net.Uri.encode(title)}" +
                                "&poster=${android.net.Uri.encode(poster)}" +
                                "&isSeries=$isSeries" +
                                (resourceId?.let { "&resourceId=$it" } ?: ""),
                        )
                    },
                )
            }

            composable(
                route = Routes.PLAYER,
                arguments = listOf(
                    navArgument(Routes.PLAYER_ARG) { type = NavType.StringType },
                    navArgument("season") { type = NavType.IntType; defaultValue = 1 },
                    navArgument("episode") { type = NavType.IntType; defaultValue = 1 },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                    navArgument("poster") { type = NavType.StringType; defaultValue = "" },
                    navArgument("isSeries") { type = NavType.BoolType; defaultValue = false },
                    navArgument("resourceId") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val subjectId = entry.arguments?.getString(Routes.PLAYER_ARG).orEmpty()
                val season = entry.arguments?.getInt("season") ?: 1
                val episode = entry.arguments?.getInt("episode") ?: 1
                val title = entry.arguments?.getString("title").orEmpty()
                val poster = entry.arguments?.getString("poster")?.takeIf { it.isNotBlank() }
                val isSeries = entry.arguments?.getBoolean("isSeries") ?: false
                val resourceId = entry.arguments?.getString("resourceId")?.takeIf { it.isNotBlank() }
                val vm: PlayerViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            PlayerViewModel(
                                repo = container.repository,
                                progressStore = container.progress,
                                appContext = container.appContext,
                            )
                        }
                    },
                )
                PlayerScreen(
                    subjectId = subjectId,
                    initialSeason = season,
                    initialEpisode = episode,
                    displayTitle = title,
                    posterUrl = poster,
                    isSeries = isSeries,
                    resourceId = resourceId,
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
