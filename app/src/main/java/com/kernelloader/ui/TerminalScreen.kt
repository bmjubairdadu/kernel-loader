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
import com.kernelloader.ui.theme.AccentAmber
import com.kernelloader.ui.theme.AccentBlue
import com.kernelloader.ui.theme.AccentGreen
import com.kernelloader.ui.theme.AccentRed
import com.kernelloader.ui.theme.AccentViolet
import com.kernelloader.ui.theme.BorderSubtle
import com.kernelloader.ui.theme.LogCmd
import com.kernelloader.ui.theme.LogErr
import com.kernelloader.ui.theme.LogFix
import com.kernelloader.ui.theme.LogInfo
import com.kernelloader.ui.theme.LogOk
import com.kernelloader.ui.theme.LogOut
import com.kernelloader.ui.theme.LogWarn
import com.kernelloader.ui.theme.MonoSmall
import com.kernelloader.ui.theme.MonoTiny
import com.kernelloader.ui.theme.Surface1
import com.kernelloader.ui.theme.Surface2
import com.kernelloader.ui.theme.SurfaceInset
import com.kernelloader.ui.theme.TextMuted
import com.kernelloader.ui.theme.TextPrimary
import com.kernelloader.ui.theme.TextSecondary

private val TERM_BG = SurfaceInset
private val colorsByType = mapOf(
    "INFO" to LogInfo,
    "CMD"  to LogCmd,
    "OUT"  to LogOut,
    "OK"   to LogOk,
    "ERR"  to LogErr,
    "FIX"  to LogFix,
    "WARN" to LogWarn
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
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
                Text(
                    text = "Kernel Loder Console",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = { clipboardManager.setText(AnnotatedString(viewModel.getTerminalText())) }) {
                    Icon(Icons.Default.Bolt, contentDescription = "Copy terminal", tint = AccentGreen)
                }
                IconButton(onClick = { viewModel.clearTerminal() }) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear terminal", tint = TextPrimary)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (rootAvailable) "ROOT: OK" else "ROOT: MISSING",
                    color = if (rootAvailable) AccentGreen else AccentRed,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
                Text(
                    text = "KERNEL: $kernelVersion",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            }

            if (viewModel.isBusy.value) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = ">> ${viewModel.busyStep.value}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = LogWarn
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

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

            if (viewModel.autoLoadStatus.value.isNotEmpty()) {
                Text(
                    text = viewModel.autoLoadStatus.value,
                    color = if (viewModel.autoLoadOk.value == true) AccentGreen else AccentRed,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    ),
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }

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
                        contentColor = AccentGreen,
                        disabledContentColor = AccentGreen.copy(alpha = 0.4f)
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
                        contentColor = AccentBlue,
                        disabledContentColor = AccentBlue.copy(alpha = 0.4f)
                    )
                ) {
                    Text("VERIFY")
                }
                TextButton(
                    onClick = { viewModel.unloadModule(context) },
                    enabled = !viewModel.isBusy.value && rootAvailable,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = AccentRed,
                        disabledContentColor = AccentRed.copy(alpha = 0.4f)
                    )
                ) {
                    Text("UNLOAD")
                }
                TextButton(
                    onClick = { viewModel.memTest() },
                    enabled = !viewModel.isBusy.value && rootAvailable,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = AccentViolet,
                        disabledContentColor = AccentViolet.copy(alpha = 0.4f)
                    )
                ) {
                    Text("MEM TEST")
                }
            }

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
                    placeholder = { Text("root command (e.g. dmesg | tail -n 30)", color = TextMuted) },
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
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Run", tint = AccentGreen)
                }
            }
        }
    }
}

@Composable
private fun TerminalLineRow(line: TerminalLine) {
    Text(
        text = "[${line.time}] ${line.text}",
        color = colorsByType[line.type] ?: TextPrimary,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        ),
        modifier = Modifier.padding(vertical = 1.dp)
    )
}
