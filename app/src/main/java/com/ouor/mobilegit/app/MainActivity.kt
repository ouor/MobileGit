package com.ouor.mobilegit.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ouor.mobilegit.ui.AppRoot
import com.ouor.mobilegit.ui.Routes
import com.ouor.mobilegit.ui.theme.MobileGitTheme
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Single activity hosting the Compose UI (docs/ui/design-system.md).
 * Also the share-sheet target (F-06 AC6) and the notification tap target (Notifier.EXTRA_TARGET / EXTRA_REPO).
 */
class MainActivity : ComponentActivity() {
    /** Route requested by an incoming intent; consumed by [AppRoot]. */
    private val pendingRoute = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pendingRoute.value = routeFor(intent)
        setContent { MobileGitTheme { AppRoot(pendingRoute) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingRoute.value = routeFor(intent)
    }

    private fun routeFor(intent: Intent?): String? {
        if (intent == null) return null
        if (intent.action == Intent.ACTION_SEND) return intent.getStringExtra(Intent.EXTRA_TEXT)?.let { Routes.add(it) }
        val repo = intent.getStringExtra(Notifier.EXTRA_REPO)
        return when (intent.getStringExtra(Notifier.EXTRA_TARGET)) {
            "repo" -> repo?.let { Routes.repo(it) }
            "credentials" -> Routes.CREDENTIALS
            "battery" -> Routes.BATTERY
            else -> null
        }
    }
}
