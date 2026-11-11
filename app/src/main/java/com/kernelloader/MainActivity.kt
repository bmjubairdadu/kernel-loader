package com.kernelloader

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.NavDisplay
import com.kernelloader.driver.DriverViewModel
import com.kernelloader.ui.ConsoleRoute
import com.kernelloader.ui.ConsoleScreen
import com.kernelloader.ui.CreditsRoute
import com.kernelloader.ui.CreditsScreen
import com.kernelloader.ui.HomeScreen
import com.kernelloader.ui.MainRoute
import com.kernelloader.ui.theme.KernelLoderTheme
import com.topjohnwu.superuser.Shell

class TaskClearService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        DriverViewModel.performStealthCleanupAndExit(applicationContext)
        stopSelf()
    }
}

class MainActivity : ComponentActivity() {

    private val viewModel: DriverViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.kernelloader.security.SecurityShield.protect(this)
        runCatching { startService(Intent(this, TaskClearService::class.java)) }
        if (!shellConfigured) {
            shellConfigured = true
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_REDIRECT_STDERR)
                    .setTimeout(60)
            )
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
        enableEdgeToEdge()
        setContent {
            KernelLoderTheme(darkTheme = true, dynamicColor = false) {
                val backStack = remember { NavBackStack<NavKey>(MainRoute) }
                NavDisplay(
                    backStack = backStack,
                    onBack = { backStack.removeLastOrNull() },
                    entryProvider = { key ->
                        when (key) {
                            is MainRoute -> NavEntry<NavKey>(key) {
                                MainScreen(
                                    viewModel = viewModel,
                                    onNavigateToCredits = { backStack.add(CreditsRoute) },
                                    onNavigateToConsole = { backStack.add(ConsoleRoute) }
                                )
                            }
                            is CreditsRoute -> NavEntry<NavKey>(key) {
                                CreditsScreen(onBack = { backStack.removeLastOrNull() })
                            }
                            is ConsoleRoute -> NavEntry<NavKey>(key) {
                                ConsoleScreen(
                                    viewModel = viewModel,
                                    onBack = { backStack.removeLastOrNull() }
                                )
                            }
                            else -> error("Unknown key: $key")
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        com.kernelloader.security.SecurityShield.protect(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) {
            viewModel.autoCleanupAndExit(this)
        }
    }

    companion object {
        @Volatile
        private var shellConfigured = false
    }
}

@Composable
fun MainScreen(
    viewModel: DriverViewModel = viewModel(),
    onNavigateToCredits: () -> Unit,
    onNavigateToConsole: () -> Unit
) {
    val context = LocalContext.current
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let { viewModel.onFilePicked(context, it) } }
    )
    HomeScreen(
        viewModel = viewModel,
        onNavigateToConsole = onNavigateToConsole,
        onNavigateToCredits = onNavigateToCredits,
        onPickFile = { pickerLauncher.launch(arrayOf("*/*")) }
    )
}
