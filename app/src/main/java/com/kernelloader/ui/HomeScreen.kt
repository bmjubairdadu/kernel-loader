package com.kernelloader.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kernelloader.R
import com.kernelloader.BuildConfig
import com.kernelloader.driver.DriverViewModel
import com.kernelloader.driver.SupportContact
import com.kernelloader.driver.OtaDriverStore
import com.kernelloader.root.RootChecker

// ---------------------------------------------------------------------------
// HOME - lightweight OTA loader screen
//   logo + name -> status chips -> animated circle button -> console
//   -> supported kernels (live from the GitHub driver database)
// ---------------------------------------------------------------------------
@Composable
fun HomeScreen(
    viewModel: DriverViewModel,
    onNavigateToConsole: () -> Unit,
    onNavigateToCredits: () -> Unit,
    onPickFile: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scroll = rememberScrollState()
    val listState = rememberLazyListState()

    val rootAvailable = remember { RootChecker.isRootAvailable() }
    val kernelVersion = remember { RootChecker.getKernelVersion() }
    val kernelRelease = remember { RootChecker.getKernelRelease() ?: kernelVersion }

    val manifest = viewModel.remoteManifest.value
    val manifestStatus = viewModel.manifestStatus.value
    val busy = viewModel.isBusy.value
    val busyStep = viewModel.busyStep.value
    val autoOk = viewModel.autoLoadOk.value
    val autoStatus = viewModel.autoLoadStatus.value
    val terminalLines = viewModel.terminalLines
    val updateStatus = viewModel.updateStatus.value
    val updateProgress = viewModel.updateProgress.value
    val appUpdate = viewModel.appUpdate.value

    LaunchedEffect(Unit) {
        viewModel.refreshManifest()
        viewModel.checkForAppUpdate()
        // A module loaded by the boot script (or left over from last session)
        // must be reflected in the UI without the user pressing anything.
        viewModel.refreshDriverState(context)
    }
    LaunchedEffect(terminalLines.size) {
        if (terminalLines.isNotEmpty()) listState.animateScrollToItem(terminalLines.size - 1)
    }

    // Which ABI family the RT / QX button last targeted. The game-mod clients
    // (Aincrad, Angry Mod) speak two DIFFERENT driver ABIs, so the whole screen -
    // the driver list and the "Ready:" line - follows the selected family.
    var family by remember { mutableStateOf(OtaDriverStore.RT) }
    val exact = manifest?.let { OtaDriverStore.exactFor(it, kernelRelease, family) }
    val supported = manifest?.let { OtaDriverStore.supportedEntries(it, family) } ?: emptyList()

    AppBackground {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(scroll)
            .padding(horizontal = 14.dp)
    ) {
        Spacer(Modifier.height(10.dp))

        // ---------------- header: logo + name + quick actions ----------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(id = R.drawable.app_logo),
                contentDescription = "Kernel Loder logo",
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .border(
                        1.5.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                        CircleShape
                    ),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Kernel Loder",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "OTA Kernel Module Loader · v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB0BEC5)
                )
            }
            IconButton(onClick = onPickFile) {
                Icon(Icons.Default.FolderOpen, contentDescription = "Pick .ko file", tint = Color(0xFFE0E0E0))
            }
            IconButton(onClick = onNavigateToConsole) {
                Icon(Icons.Default.Terminal, contentDescription = "Full console", tint = Color(0xFFE0E0E0))
            }
            IconButton(onClick = onNavigateToCredits) {
                Icon(Icons.Default.Info, contentDescription = "Credits", tint = Color(0xFFE0E0E0))
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---------------- status chips ----------------
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(
                text = if (rootAvailable) "ROOT OK" else "ROOT MISSING",
                ok = rootAvailable,
                modifier = Modifier.weight(1f)
            )
            StatusChip(
                text = "KERNEL $kernelVersion",
                ok = true,
                modifier = Modifier.weight(2f)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusChip(
                text = when {
                    exact != null -> "SUPPORTED: YES"
                    manifestStatus == "LOADING" -> "CHECKING DB..."
                    manifestStatus == "OK" -> "SUPPORTED: NO"
                    else -> "DB: $manifestStatus"
                },
                ok = exact != null,
                modifier = Modifier.weight(2f)
            )
            IconButton(onClick = { viewModel.refreshManifest() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh driver database", tint = Color(0xFF69F0AE))
            }
        }

        // ---------------- auto-update banner (GitHub Releases) ----------------
        when {
            updateStatus == "AVAILABLE" && appUpdate != null -> {
                val pulse = rememberInfiniteTransition(label = "updatePulse")
                val glow by pulse.animateFloat(
                    initialValue = 0.55f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(700, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "updateGlow"
                )
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clickable { viewModel.installAppUpdate(context) },
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF12271A)
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, Color(0xFF4CAF50).copy(alpha = glow)
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.SystemUpdate,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "UPDATE v${appUpdate.versionCode} · ${appUpdate.versionName}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF69F0AE),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "tap to download & install (${appUpdate.apkSize / 1024} KB)",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = Color(0xFFB0BEC5)
                            )
                        }
                        Text(
                            text = "INSTALL ↗",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF4CAF50),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            updateStatus == "DOWNLOADING" -> {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2A1F))
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(
                            text = "DOWNLOADING UPDATE... ${if (updateProgress >= 0) "$updateProgress%" else ""}",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = Color(0xFF4CAF50),
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { if (updateProgress >= 0) updateProgress / 100f else 0f },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF4CAF50)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---------------- animated circle button ----------------
        val transition = rememberInfiniteTransition(label = "loadBtn")
        val angle by transition.animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
            label = "angle"
        )
        val pulse by transition.animateFloat(
            initialValue = 0.95f, targetValue = 1.05f,
            animationSpec = infiniteRepeatable(tween(750, easing = LinearEasing), RepeatMode.Reverse),
            label = "pulse"
        )
        val ringColor = when {
            busy -> Color(0xFFFFB74D)
            autoOk == true -> Color(0xFF4CAF50)
            autoOk == false -> Color(0xFFEF5350)
            else -> Color(0xFF69F0AE)
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Two separate load buttons: RT and QX are different ioctl ABIs, so
            // the user must pick the one their game-mod client expects.
            @Composable
            fun FamilyButton(
                label: String,
                tag: String,
                container: Color,
                onPick: () -> Unit
            ) {
                val selected = family == tag
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(modifier = Modifier.size(150.dp), contentAlignment = Alignment.Center) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val stroke = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                            val diameter = size.minDimension - stroke.width
                            val topLeft = androidx.compose.ui.geometry.Offset(
                                (size.width - diameter) / 2f, (size.height - diameter) / 2f
                            )
                            val arcSize = androidx.compose.ui.geometry.Size(diameter, diameter)
                            rotate(if (selected) angle else angle + 180f) {
                                drawArc(
                                    color = ringColor.copy(alpha = 0.9f),
                                    startAngle = 0f, sweepAngle = 130f, useCenter = false,
                                    topLeft = topLeft, size = arcSize, style = stroke
                                )
                                drawArc(
                                    color = ringColor.copy(alpha = 0.35f),
                                    startAngle = 180f, sweepAngle = 100f, useCenter = false,
                                    topLeft = topLeft, size = arcSize, style = stroke
                                )
                            }
                        }
                        Button(
                            onClick = {
                                family = tag
                                onPick()
                            },
                            enabled = !busy && rootAvailable,
                            shape = CircleShape,
                            border = androidx.compose.foundation.BorderStroke(
                                if (selected) 3.dp else 1.5.dp,
                                if (selected) Color.White.copy(alpha = 0.9f)
                                else Color.White.copy(alpha = 0.3f)
                            ),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = container,
                                contentColor = Color.White,
                                disabledContainerColor = container.copy(alpha = 0.3f),
                                disabledContentColor = Color.White.copy(alpha = 0.55f)
                            ),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                            modifier = Modifier
                                .size(112.dp)
                                .scale(if (selected) pulse else 0.97f)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(horizontal = 2.dp)
                            ) {
                                if (busy && selected) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(30.dp),
                                        color = Color.White,
                                        strokeWidth = 2.5.dp
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Bolt,
                                        contentDescription = null,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = when (tag) {
                            OtaDriverStore.RT -> "ioctl 801/802/803"
                            else -> "ioctl 801/802/804"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) Color(0xFF69F0AE) else Color(0xFF78909C),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FamilyButton("RT", OtaDriverStore.RT, Color(0xFF1E7A46)) {
                    viewModel.autoLoadUniversal(context, preferOta = true, variant = OtaDriverStore.RT)
                }
                FamilyButton("QX", OtaDriverStore.QX, Color(0xFF8A4B1E)) {
                    viewModel.autoLoadUniversal(context, preferOta = true, variant = OtaDriverStore.QX)
                }
            }
            Spacer(Modifier.height(6.dp))

            // ---------------- loaded state + unload ----------------
            // A module stays in kernel memory until reboot or rmmod, so this
            // row is normally just a status readout; UNLOAD is the one action
            // that removes it and nothing else.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (viewModel.driverLoaded.value) {
                            "LOADED: ${viewModel.driverModule.value.ifBlank { viewModel.loadedModuleName.value }}"
                        } else {
                            "NOT LOADED"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (viewModel.driverLoaded.value) Color(0xFF4CAF50) else Color(0xFFEF9A9A)
                    )
                    Text(
                        text = "survives until reboot or UNLOAD",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF78909C)
                    )
                }
                OutlinedButton(
                    onClick = { viewModel.unloadModule(context) },
                    enabled = !busy && rootAvailable && viewModel.driverLoaded.value,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFFF8A65),
                        disabledContentColor = Color(0xFFFF8A65).copy(alpha = 0.35f)
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, Color(0xFFFF8A65).copy(alpha = if (viewModel.driverLoaded.value) 0.7f else 0.25f)
                    )
                ) {
                    Text("UNLOAD", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(8.dp))

            // ---------------- boot auto-load ----------------
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF16202A)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Load at every boot",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFE0E0E0)
                        )
                        Text(
                            text = if (viewModel.autoloadEnabled.value)
                                "ON - after a restart the driver loads by itself"
                            else "OFF - you must tap a load button after a restart",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (viewModel.autoloadEnabled.value) Color(0xFF69F0AE) else Color(0xFF78909C)
                        )
                    }
                    Switch(
                        checked = viewModel.autoloadEnabled.value,
                        onCheckedChange = { on -> viewModel.setBootAutoload(context, on) },
                        enabled = !busy && rootAvailable
                    )
                }
            }
            Spacer(Modifier.height(6.dp))

            Text(
                text = when {
                    busyStep.isNotBlank() -> busyStep
                    autoStatus.isNotBlank() -> autoStatus
                    exact != null ->
                        "Ready: ${exact.version} ${OtaDriverStore.variantLabel(family)} loader on GitHub"
                    else -> "Tap ${OtaDriverStore.variantLabel(family)} - detect kernel + download loader"
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFE0E0E0),
                textAlign = TextAlign.Center,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(12.dp))

        // ---------------- console ----------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "CONSOLE",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF69F0AE),
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { clipboard.setText(AnnotatedString(viewModel.getTerminalText())) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy console", tint = Color(0xFFE0E0E0))
            }
            IconButton(onClick = { viewModel.clearTerminal() }) {
                Icon(Icons.Default.Delete, contentDescription = "Clear console", tint = Color(0xFFE0E0E0))
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth().height(250.dp),
            // Fixed dark terminal background - log colors are tuned for dark,
            // so the console stays readable in every theme.
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1117))
        ) {
            if (terminalLines.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(38.dp),
                        tint = Color(0xFF90A4AE)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Tap the circle to start",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB0BEC5)
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(10.dp)
                ) {
                    items(terminalLines) { line ->
                        Text(
                            text = "[${line.time}] ${line.text}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            ),
                            color = consoleLineColor(line.type),
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---------------- supported kernels (GitHub database) ----------------
        Text(
            text = when {
                manifestStatus == "OK" -> "SUPPORTED KERNELS (${supported.size}) · LATEST " +
                        (supported.firstOrNull()?.buildDate?.take(10) ?: "n/a")
                manifestStatus == "LOADING" -> "FETCHING DATABASE..."
                else -> "SUPPORTED KERNELS (offline)"
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF69F0AE)
        )
        Text(
            text = "DB updated: ${manifest?.updated?.take(10) ?: "-"}  ·  " +
                    "github.com/bmjubairdadu/kernel-loder (drivers branch)",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = Color(0xFF90A4AE),
            modifier = Modifier.padding(top = 2.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(OtaDriverStore.manifestUrl))
                        )
                    } catch (_: Exception) { /* no browser installed */ }
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "open driver database ↗",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF64B5F6),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "raw drivers.json",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            when {
                manifestStatus == "OK" -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(190.dp).padding(10.dp)
                ) {
                    itemsIndexed(supported) { idx, e ->
                        val v = e.version
                        val isThis = RootChecker.kernelShortVersion(v) ==
                                RootChecker.kernelShortVersion(kernelRelease)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "kernel $v",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                ),
                                color = if (isThis) Color(0xFF4CAF50)
                                        else Color(0xFFE0E0E0),
                                modifier = Modifier.weight(1f)
                            )
                            if (e.buildDate.isNotBlank()) {
                                Text(
                                    text = e.buildDate.take(10),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    color = Color(0xFF90A4AE)
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            if (isThis) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF4CAF50),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "THIS DEVICE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF4CAF50),
                                    fontWeight = FontWeight.Bold
                                )
                            } else if (idx == 0) {
                                Text(
                                    text = "LATEST",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFFFB74D),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 1.dp),
                            color = Color(0xFF37474F)
                        )
                    }
                }
                manifestStatus == "LOADING" -> Row(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    horizontalArrangement = Arrangement.Center
                ) { CircularProgressIndicator(modifier = Modifier.size(22.dp)) }
                else -> Text(
                    text = "No internet connection - driver database is not visible.\n" +
                            "Connect, then tap the refresh button.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(18.dp),
                    color = Color(0xFFFFD54F)
                )
            }
        }

        // ---------------- support: custom loader via WhatsApp / GitHub ----------------
        SupportCard(
            kernelRelease = kernelRelease,
            loadFailed = autoOk == false,
            appVersion = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            resultText = autoStatus.ifBlank { "load failed / no exact loader" },
            getLog = { viewModel.getTerminalText() }
        )
    }
    }
}

/**
 * Contact card: WhatsApp (logo-only circle button, full log attached
 * automatically) + GitHub failure report (one tap opens a pre-filled
 * issue - the PC auto-triage watches these). No custom textbox.
 */
@Composable
private fun SupportCard(
    kernelRelease: String,
    loadFailed: Boolean,
    appVersion: String,
    resultText: String,
    getLog: () -> String
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (loadFailed) Color(0xFF3E2723)
                             else Color(0xFF131E2C)
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (loadFailed) Color(0xFFEF5350).copy(alpha = 0.5f)
            else Color(0xFF4CAF50).copy(alpha = 0.35f)
        )
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (loadFailed) "LOAD FAILED - need a custom loader?"
                       else "No kernel match? Need a custom loader?",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (loadFailed) Color(0xFFFF8A65) else Color(0xFF69F0AE),
                textAlign = TextAlign.Center
            )
            Text(
                text = "Tap a button - device, kernel and full log go automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB0BEC5),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // WhatsApp: logo-only circle
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = {
                            try {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse(
                                            SupportContact.waLinkFull(
                                                kernelRelease, appVersion,
                                                resultText, getLog()
                                            )
                                        )
                                    )
                                )
                            } catch (_: Exception) { /* no WhatsApp/browser */ }
                        },
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF25D366))
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_whatsapp),
                            contentDescription = "WhatsApp support",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "WhatsApp",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFB0BEC5),
                        fontWeight = FontWeight.Bold
                    )
                }
                // GitHub failure report: logo-only circle
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = {
                            try {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse(
                                            SupportContact.issueUrl(
                                                kernelRelease, appVersion,
                                                resultText, getLog()
                                            )
                                        )
                                    )
                                )
                            } catch (_: Exception) { /* no browser */ }
                        },
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1F2A37))
                            .border(1.5.dp, Color(0xFF8AB4F8).copy(alpha = 0.6f), CircleShape)
                    ) {
                        Icon(
                            Icons.Default.BugReport,
                            contentDescription = "Send failure report to GitHub",
                            tint = Color(0xFF8AB4F8),
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Report",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFB0BEC5),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                text = "Report saves on GitHub - PC auto-checks and fixes",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF90A4AE),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------
@Composable
private fun StatusChip(text: String, ok: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = if (ok) Color(0xFF134E2E)
                else Color(0xFF5D1A14),
        tonalElevation = 2.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (ok) Color(0xFF4CAF50).copy(alpha = 0.7f)
            else Color(0xFFEF5350).copy(alpha = 0.7f)
        )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = if (ok) Color(0xFFB9F5D0) else Color(0xFFFFB4AB),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

// High-contrast log colors, tuned for the fixed dark console background.
private fun consoleLineColor(type: String): Color = when (type) {
    "OK" -> Color(0xFF69F0AE)
    "ERR" -> Color(0xFFFF6E6E)
    "WARN" -> Color(0xFFFFD54F)
    "CMD" -> Color(0xFF8AB4F8)
    "FIX" -> Color(0xFF4DD0E1)
    "OUT" -> Color(0xFFF1F3F4)
    else -> Color(0xFFCFD8DC)
}
