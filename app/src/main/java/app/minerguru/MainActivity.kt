package app.minerguru

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.minerguru.service.MinerStore
import app.minerguru.ui.MinerGuruMainScreen
import app.minerguru.ui.theme.MinerGuruTheme

class MainActivity : ComponentActivity() {
    private val store: MinerStore by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store.syncLanguage()
        // Poll the miners only while the app is visible; re-check the network permission when coming back.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                store.refreshLocalNetworkPermission()
                store.setForeground(true)
            }
            override fun onStop(owner: LifecycleOwner) = store.setForeground(false)
        })
        store.pendingRoute = intent?.getStringExtra(EXTRA_ROUTE)
        setContent {
            MinerGuruTheme {
                MinerGuruMainScreen(store)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_ROUTE)?.let { store.pendingRoute = it }
    }

    companion object {
        /** Screen a widget opens, as [app.minerguru.ui.Route.encode] writes it. */
        const val EXTRA_ROUTE = "app.minerguru.route"
    }
}
