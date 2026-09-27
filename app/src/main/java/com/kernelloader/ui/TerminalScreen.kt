package com.kernelloader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kernelloader.driver.DriverViewModel
import com.kernelloader.driver.TerminalLine
import com.kernelloader.root.RootChecker

private val TERM_BG = Color(0xFF0D1117)
private val colorsByType = mapOf(
    "INFO" to Color(0xFF8AB4F8),
    "CMD"  to Color(0xFFFFD54F),
    "OUT"  to Color(0xFFE0E0E0),
    "OK"   to Color(0xFF69F0AE),
    "ERR"  to Color(0xFFFF5252),
    "FIX"  to Color(0xFFFFAB40),
    "WARN" to Color(0xFFFFD54F)
)

@Composable
fun ConsoleScreen(
    viewModel: DriverViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val rootAvailable = remember { RootChecker.isRootAvailable() }
    val kernelVersion = remember { RootChecker.getKernelVersion() }

    // auto-scroll to bottom when new lines arrive
    LaunchedEffect(viewModel.terminalLines.size) {
        if (viewModel.terminalLines.isNotEmpty()) {
            listState.animateScrollToItem(viewModel.terminalLines.size - 1)
        }
    }

    AppBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(12.dp)
        ) {
            // ---- Header ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color(0xFFE0E0E0))
                }
                Text(
                    text = "Kernel Loder Console",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = { clipboardManager.setText(AnnotatedString(viewModel.getTerminalText())) }) {
                    Icon(Icons.Default.Bolt, contentDescription = "Copy terminal", tint = Color(0xFF69F0AE))
                }
                IconButton(onClick = { viewModel.clearTerminal() }) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear terminal", tint = Color(0xFFE0E0E0))
                }
            }

            // ---- Device status chips ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (rootAvailable) "ROOT: OK" else "ROOT: MISSING",
                    color = if (rootAvailable) Color(0xFF69F0AE) else Color(0xFFFF5252),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
                Text(
                    text = "KERNEL: $kernelVersion",
                    color = Color(0xFFB0BEC5),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            }

            // ---- Busy indicator / current step ----
            if (viewModel.isBusy.value) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = ">> ${viewModel.busyStep.value}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = Color(0xFFFFAB40)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ---- Terminal body ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(TERM_BG, RoundedCornerShape(10.dp))
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp)
                ) {
                    items(viewModel.terminalLines) { line ->
                        TerminalLineRow(line)
                    }
                }
            }

            // ---- Final status ----
            if (viewModel.autoLoadStatus.value.isNotEmpty()) {
                Text(
                    text = viewModel.autoLoadStatus.value,
                    color = if (viewModel.autoLoadOk.value == true) Color(0xFF69F0AE) else Color(0xFFFF5252),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    ),
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }

            // ---- Quick actions ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = { viewModel.autoLoadUniversal(context) },
                    enabled = !viewModel.isBusy.value && rootAvailable,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = Color(0xFF69F0AE),
                        disabledContentColor = Color(0xFF69F0AE).copy(alpha = 0.4f)
                    )
                ) {
                    Icon(Icons.Default.Bolt, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("AUTO LOAD")
                }
                TextButton(
                    onClick = { viewModel.verifyModule() },
                    enabled = !viewModel.isBusy.value && rootAvailable,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = Color(0xFF8AB4F8),
                        disabledContentColor = Color(0xFF8AB4F8).copy(alpha = 0.4f)
                    )
                ) {
                    Text("VERIFY")
                }
                TextButton(
                    onClick = { viewModel.unloadModule(context) },
                    enabled = !viewModel.isBusy.value && rootAvailable,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = Color(0xFFFF8A65),
                        disabledContentColor = Color(0xFFFF8A65).copy(alpha = 0.4f)
                    )
                ) {
                    Text("UNLOAD")
                }
                TextButton(
                    onClick = { viewModel.memTest() },
                    enabled = !viewModel.isBusy.value && rootAvailable,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = Color(0xFF4DD0E1),
                        disabledContentColor = Color(0xFF4DD0E1).copy(alpha = 0.4f)
                    )
                ) {
                    Text("MEM TEST")
                }
            }

            // ---- Command input ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("root command (e.g. dmesg | tail -n 30)", color = Color(0xFF78909C)) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = Color.White
                    )
                )
                IconButton(
                    onClick = {
                        viewModel.runUserCommand(input)
                        input = ""
                    }
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Run", tint = Color(0xFF69F0AE))
                }
            }
        }
    }
}

@Composable
private fun TerminalLineRow(line: TerminalLine) {
    Text(
        text = "[${line.time}] ${line.text}",
        color = colorsByType[line.type] ?: Color(0xFFE0E0E0),
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        ),
        modifier = Modifier.padding(vertical = 1.dp)
    )
}

