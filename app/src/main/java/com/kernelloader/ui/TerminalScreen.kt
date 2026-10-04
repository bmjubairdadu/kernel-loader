package com.kernelloader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kernelloader.driver.DriverViewModel
import com.kernelloader.driver.OtaDriverStore
import com.kernelloader.driver.TerminalLine
import com.kernelloader.root.RootChecker
import com.kernelloader.ui.theme.AccentAmber
import com.kernelloader.ui.theme.AccentBlue
import com.kernelloader.ui.theme.AccentGreen
import com.kernelloader.ui.theme.AccentRed
import com.kernelloader.ui.theme.AccentViolet
import com.kernelloader.ui.theme.BorderSubtle
import com.kernelloader.ui.theme.GradientRtStart
import com.kernelloader.ui.theme.FamilyQx
import com.kernelloader.ui.theme.FamilyRt
import com.kernelloader.ui.theme.LogCmd
import com.kernelloader.ui.theme.LogErr
import com.kernelloader.ui.theme.LogFix
import com.kernelloader.ui.theme.LogInfo
import com.kernelloader.ui.theme.LogOk
import com.kernelloader.ui.theme.LogOut
import com.kernelloader.ui.theme.LogTimestamp
import com.kernelloader.ui.theme.LogWarn
import com.kernelloader.ui.theme.MonoSmall
import com.kernelloader.ui.theme.MonoTiny
import com.kernelloader.ui.theme.Surface2
import com.kernelloader.ui.theme.Surface3
import com.kernelloader.ui.theme.SurfaceInset
import com.kernelloader.ui.theme.TextMuted
import com.kernelloader.ui.theme.TextOnAccent
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
    var filter by remember { mutableStateOf("ALL") }
    val listState = rememberLazyListState()

    val rootAvailable = remember { RootChecker.isRootAvailable() }
    val kernelVersion = remember { RootChecker.getKernelVersion() }

    val shown = when (filter) {
        "RT" -> viewModel.terminalLines.filter { it.variant == OtaDriverStore.RT }
        "QX" -> viewModel.terminalLines.filter { it.variant == OtaDriverStore.QX }
        "STATUS" -> viewModel.terminalLines.filter { it.variant.isBlank() }
        else -> viewModel.terminalLines
    }

    LaunchedEffect(shown.size, filter) {
        if (shown.isNotEmpty()) {
            listState.animateScrollToItem(shown.size - 1)
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
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPrimary
                    )
                }
                Text(
                    text = "Kernel Loader Console",
                    style = MaterialTheme.typography.titleLarge.copy(
                        brush = Brush.linearGradient(listOf(Color.White, GradientRtStart))
                    )
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = { clipboardManager.setText(AnnotatedString(viewModel.getTerminalText())) }) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy terminal",
                        tint = AccentGreen
                    )
                }
                IconButton(onClick = { viewModel.clearTerminal() }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Clear terminal",
                        tint = TextSecondary
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusPill(label = "ROOT", value = if (rootAvailable) "OK" else "MISSING", ok = rootAvailable)
                StatusPill(label = "KERNEL", value = kernelVersion, ok = true)
            }

            if (viewModel.isBusy.value) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    color = AccentAmber,
                    trackColor = Surface2
                )
                Text(
                    text = ">> ${viewModel.busyStep.value}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = AccentAmber
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    "ALL" to TextSecondary,
                    "STATUS" to AccentGreen,
                    "RT" to FamilyRt,
                    "QX" to FamilyQx
                ).forEach { (label, tint) ->
                    val selected = filter == label
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (selected) tint.copy(alpha = 0.18f) else Surface2,
                        border = BorderStroke(
                            1.dp,
                            if (selected) tint.copy(alpha = 0.7f) else BorderSubtle
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { filter = label }
                    ) {
                        Text(
                            text = label,
                            color = if (selected) tint else TextMuted,
                            style = MonoTiny,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(TERM_BG, RoundedCornerShape(12.dp))
                    .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
            ) {
                if (shown.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = when (filter) {
                                "ALL" -> "No console output yet"
                                "STATUS" -> "No status yet"
                                else -> "No $filter lines yet"
                            },
                            color = TextMuted,
                            style = MonoSmall.copy(fontFamily = FontFamily.Monospace)
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp)
                    ) {
                        items(shown) { line ->
                            TerminalLineRow(line)
                        }
                    }
                }
            }

            if (viewModel.autoLoadStatus.value.isNotEmpty()) {
                Text(
                    text = viewModel.autoLoadStatus.value,
                    color = if (viewModel.autoLoadOk.value == true) AccentGreen else AccentRed,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ActionChip(
                    label = "AUTO LOAD",
                    tint = AccentGreen,
                    enabled = !viewModel.isBusy.value && rootAvailable,
                    modifier = Modifier.weight(1f)
                ) { viewModel.autoLoadUniversal(context) }
                ActionChip(
                    label = "VERIFY",
                    tint = AccentBlue,
                    enabled = !viewModel.isBusy.value && rootAvailable,
                    modifier = Modifier.weight(1f)
                ) { viewModel.verifyModule() }
                ActionChip(
                    label = "UNLOAD",
                    tint = AccentRed,
                    enabled = !viewModel.isBusy.value && rootAvailable,
                    modifier = Modifier.weight(1f)
                ) { viewModel.unloadModule(context) }
                ActionChip(
                    label = "MEM TEST",
                    tint = AccentViolet,
                    enabled = !viewModel.isBusy.value && rootAvailable,
                    modifier = Modifier.weight(1f)
                ) { viewModel.memTest() }
                if (viewModel.isBusy.value) {
                    ActionChip(
                        label = "STOP",
                        tint = AccentRed,
                        enabled = true,
                        modifier = Modifier.weight(1f)
                    ) { viewModel.stopLoad() }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text("root command (e.g. dmesg | tail -n 30)", color = TextMuted)
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentGreen.copy(alpha = 0.6f),
                        unfocusedBorderColor = BorderSubtle,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = AccentGreen
                    ),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = Color.White
                    )
                )
                val canRun = input.isNotBlank() && rootAvailable && !viewModel.isBusy.value
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (canRun) AccentGreen else Surface3)
                        .clickable(enabled = canRun) {
                            viewModel.runUserCommand(input)
                            input = ""
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Run",
                        tint = if (canRun) TextOnAccent else TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, value: String, ok: Boolean) {
    val tint = if (ok) AccentGreen else AccentRed
    Surface(
        shape = RoundedCornerShape(50),
        color = tint.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.45f))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(tint)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "$label: $value",
                color = tint,
                style = MonoTiny.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ActionChip(
    label: String,
    tint: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (enabled) tint.copy(alpha = 0.12f) else Surface2,
        border = BorderStroke(1.dp, if (enabled) tint.copy(alpha = 0.5f) else BorderSubtle),
        modifier = modifier
    ) {
        Text(
            text = label,
            color = if (enabled) tint else tint.copy(alpha = 0.35f),
            style = MonoTiny,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 9.dp)
        )
    }
}

@Composable
private fun TerminalLineRow(line: TerminalLine) {
    val typeColor = colorsByType[line.type] ?: TextPrimary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .width(3.dp)
                .height(12.dp)
                .background(typeColor, RoundedCornerShape(2.dp))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = line.time,
            color = LogTimestamp,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            ),
            modifier = Modifier.padding(top = 1.dp, end = 8.dp)
        )
        Text(
            text = line.text,
            color = typeColor,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
                fontWeight = if (line.type == "OK" || line.type == "ERR")
                    FontWeight.SemiBold
                else FontWeight.Normal
            ),
            modifier = Modifier.weight(1f)
        )
    }
}
