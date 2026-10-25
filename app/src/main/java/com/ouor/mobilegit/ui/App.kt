package com.ouor.mobilegit.ui

import com.ouor.mobilegit.ui.screens.ArchiveScreen
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ouor.mobilegit.app.Core
import com.ouor.mobilegit.app.MobileGitApp
import com.ouor.mobilegit.ui.components.LocalSnack
import com.ouor.mobilegit.ui.screens.AccessLogScreen
import com.ouor.mobilegit.ui.screens.AddMirrorScreen
import com.ouor.mobilegit.ui.screens.BackupScreen
import com.ouor.mobilegit.ui.screens.BatteryScreen
import com.ouor.mobilegit.ui.screens.BulkMirrorScreen
import com.ouor.mobilegit.ui.screens.BulkProgressScreen
import com.ouor.mobilegit.ui.screens.CreateRepoScreen
import com.ouor.mobilegit.ui.screens.CredentialsScreen
import com.ouor.mobilegit.ui.screens.MainScreen
import com.ouor.mobilegit.ui.screens.OnboardingScreen
import com.ouor.mobilegit.ui.screens.QrScreen
import com.ouor.mobilegit.ui.screens.RepoDetailScreen
import com.ouor.mobilegit.ui.screens.SettingsScreen
import com.ouor.mobilegit.ui.screens.StorageScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Route table. Screen IDs follow PRD 4장 (A-01..A-14). */
object Routes {
    const val ONBOARDING = "onboarding"          // A-01
    const val MAIN = "main"                      // A-02
    const val ADD = "add?text={text}"            // A-03
    const val BULK = "bulk"                      // A-04
    const val BULK_PROGRESS = "bulk/{id}"        // A-05
    const val CREATE = "create"                  // A-06
    const val REPO = "repo/{name}"               // A-07
    const val QR = "qr?repo={repo}"              // A-08
    const val STORAGE = "storage"                // A-09
    const val SETTINGS = "settings"              // A-10
    const val CREDENTIALS = "credentials"        // A-11
    const val CREDENTIALS_FOR = "credentials?host={host}"
    const val BATTERY = "battery"                // A-12
    const val ACCESS_LOG = "access-log"          // A-13
    const val BACKUP = "backup"                  // A-14
    const val ARCHIVES = "archives/{name}?group={group}" // A-15

    fun add(text: String? = null) = if (text == null) "add" else "add?text=${Uri.encode(text)}"
    fun bulkProgress(id: String) = "bulk/${Uri.encode(id)}"
    fun repo(name: String) = "repo/${Uri.encode(name)}"
    fun archives(name: String, group: String? = null) = "archives/${Uri.encode(name)}" + (group?.let { "?group=${Uri.encode(it)}" } ?: "")
    fun qr(repo: String?) = if (repo == null) "qr" else "qr?repo=${Uri.encode(repo)}"
    fun credentials(host: String?) = if (host == null) CREDENTIALS else "credentials?host=${Uri.encode(host)}"
}

/** Thin navigation facade passed to screens so they never touch NavController directly. */
class Nav(private val c: NavHostController) {
    fun go(route: String) = c.navigate(route) { launchSingleTop = true }
    fun back() { if (!c.popBackStack()) go(Routes.MAIN) }
    /** Replace the current screen (e.g. "add" → the created repo). */
    fun replace(route: String) { c.popBackStack(); go(route) }
    fun home() = c.navigate(Routes.MAIN) { popUpTo(0) { inclusive = true }; launchSingleTop = true }
}

@Composable
fun core(): Core { val ctx = LocalContext.current; return remember { MobileGitApp.core(ctx) } }

@Composable
fun AppRoot(pendingRoute: MutableStateFlow<String?>) {
    val core = core()
    val controller = rememberNavController()
    val nav = remember(controller) { Nav(controller) }
    val snackState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val snack: (String) -> Unit = remember { { msg -> scope.launch { snackState.currentSnackbarData?.dismiss(); snackState.showSnackbar(msg) } } }
    val start = remember { if (core.settings.current.onboardingCompleted) Routes.MAIN else Routes.ONBOARDING }

    LaunchedEffect(Unit) {
        pendingRoute.collect { r -> if (r != null) { pendingRoute.value = null; nav.go(r) } }
    }

    CompositionLocalProvider(LocalSnack provides snack) {
        // Surface sets LocalContentColor = onBackground so bare Text is readable in dark mode.
        androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = androidx.compose.material3.MaterialTheme.colorScheme.background) { Box(Modifier.fillMaxSize()) {
            NavHost(controller, startDestination = start) {
                composable(Routes.ONBOARDING) { OnboardingScreen(nav) }
                composable(Routes.MAIN) { MainScreen(nav) }
                composable(Routes.ADD, arguments = listOf(navArgument("text") { type = NavType.StringType; nullable = true })) {
                    AddMirrorScreen(it.arguments?.getString("text"), nav)
                }
                composable(Routes.BULK) { BulkMirrorScreen(nav) }
                composable(Routes.BULK_PROGRESS) { BulkProgressScreen(it.arguments?.getString("id").orEmpty(), nav) }
                composable(Routes.CREATE) { CreateRepoScreen(nav) }
                composable(Routes.REPO) { RepoDetailScreen(it.arguments?.getString("name").orEmpty(), nav) }
                composable(Routes.ARCHIVES, arguments = listOf(navArgument("group") { type = NavType.StringType; nullable = true })) {
                    ArchiveScreen(it.arguments?.getString("name").orEmpty(), it.arguments?.getString("group"), nav)
                }
                composable(Routes.QR, arguments = listOf(navArgument("repo") { type = NavType.StringType; nullable = true })) {
                    QrScreen(it.arguments?.getString("repo"), nav)
                }
                composable(Routes.STORAGE) { StorageScreen(nav) }
                composable(Routes.SETTINGS) { SettingsScreen(nav) }
                composable(Routes.CREDENTIALS_FOR, arguments = listOf(navArgument("host") { type = NavType.StringType; nullable = true })) {
                    CredentialsScreen(it.arguments?.getString("host"), nav)
                }
                composable(Routes.BATTERY) { BatteryScreen(nav) }
                composable(Routes.ACCESS_LOG) { AccessLogScreen(nav) }
                composable(Routes.BACKUP) { BackupScreen(nav) }
            }
            SnackbarHost(snackState, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp))
        } }
    }
}
