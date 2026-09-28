package com.kernelloader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import com.kernelloader.ui.SafetyWarningDialog
import com.kernelloader.ui.WarningRoute
import com.kernelloader.ui.theme.KernelLoderTheme
import com.topjohnwu.superuser.Shell

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(10)
        )
        enableEdgeToEdge()
        setContent {
            
            KernelLoderTheme(darkTheme = true, dynamicColor = false) {
                val backStack = remember { NavBackStack<NavKey>(MainRoute) }
                NavDisplay(
                    backStack = backStack,
                    onBack = { backStack.removeLastOrNull() },
                    entryProvider = { key ->
                        when (key) {
                            is WarningRoute -> NavEntry<NavKey>(key) {
                                SafetyWarningDialog(
                                    onDismiss = { finish() },
                                    onConfirm = { backStack.add(MainRoute) }
                                )
                            }
                            is MainRoute -> NavEntry<NavKey>(key) {
                                MainScreen(
                                    onNavigateToCredits = { backStack.add(CreditsRoute) },
                                    onNavigateToConsole = { backStack.add(ConsoleRoute) }
                                )
                            }
                            is CreditsRoute -> NavEntry<NavKey>(key) {
                                CreditsScreen(onBack = { backStack.removeLastOrNull() })
                            }
                            is ConsoleRoute -> NavEntry<NavKey>(key) {
                                ConsoleScreen(
                                    viewModel = viewModel(),
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
