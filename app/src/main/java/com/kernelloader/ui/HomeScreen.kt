package com.kernelloader.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kernelloader.R
import com.kernelloader.BuildConfig
import com.kernelloader.driver.DriverViewModel
import com.kernelloader.driver.SupportContact
import com.kernelloader.driver.OtaDriverStore
import com.kernelloader.root.RootChecker
import com.kernelloader.ui.theme.AccentAmber
import com.kernelloader.ui.theme.AccentBlue
import com.kernelloader.ui.theme.AccentGreen
import com.kernelloader.ui.theme.AccentRed
import com.kernelloader.ui.theme.BorderSubtle
import com.kernelloader.ui.theme.FamilyQx
import com.kernelloader.ui.theme.FamilyRt
import com.kernelloader.ui.theme.FamilyRtDeep
import com.kernelloader.ui.theme.LogCmd
import com.kernelloader.ui.theme.LogErr
import com.kernelloader.ui.theme.LogFix
import com.kernelloader.ui.theme.LogInfo
import com.kernelloader.ui.theme.LogOk
import com.kernelloader.ui.theme.LogOut
import com.kernelloader.ui.theme.LogWarn
import com.kernelloader.ui.theme.MonoSmall
import com.kernelloader.ui.theme.MonoTiny
import com.kernelloader.ui.theme.StatusIdle
import com.kernelloader.ui.theme.StatusOk
import com.kernelloader.ui.theme.Surface1
import com.kernelloader.ui.theme.Surface2
import com.kernelloader.ui.theme.Surface3
import com.kernelloader.ui.theme.SurfaceInset
import com.kernelloader.ui.theme.TextDisabled
import com.kernelloader.ui.theme.TextMuted
import com.kernelloader.ui.theme.TextPrimary
import com.kernelloader.ui.theme.TextSecondary

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
        
        viewModel.refreshDriverState(context)
    }
    LaunchedEffect(terminalLines.size) {
        if (terminalLines.isNotEmpty()) listState.animateScrollToItem(terminalLines.size - 1)
    }

    var family by remember { mutableStateOf(OtaDriverStore.RT) }
    val exact = manifest?.let { OtaDriverStore.exactFor(it, kernelRelease, family) }

    val kernelShort = RootChecker.kernelShortVersion(kernelRelease)
    val supported = manifest?.let { OtaDriverStore.supportedEntries(it, family) }
        ?.let { list ->
            val (mine, others) = list.partition {
                RootChecker.kernelShortVersion(it.version) == kernelShort
            }
            mine + others
        } ?: emptyList()
    
    val newestBuild = supported
        .dropWhile { RootChecker.kernelShortVersion(it.version) == kernelShort }
        .firstOrNull { it.buildDate.isNotBlank() }
        ?.buildDate
        .orEmpty()
    
    val newestIndex = supported.count {
        RootChecker.kernelShortVersion(it.version) == kernelShort
    }

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
                    color = TextPrimary
                )
                Text(
                    text = "OTA Kernel Module Loader · v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
        }

        Spacer(Modifier.height(10.dp))

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
                Icon(Icons.Default.Refresh, contentDescription = "Refresh driver database", tint = AccentGreen)
            }
        }

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
                        containerColor = Surface2
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, AccentGreen.copy(alpha = glow)
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.SystemUpdate,
                            contentDescription = null,
                            tint = AccentGreen,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "UPDATE v${appUpdate.versionCode} · ${appUpdate.versionName}",
                                style = MaterialTheme.typography.labelMedium,
                                color = AccentGreen,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "tap to download & install (${appUpdate.apkSize / 1024} KB)",
                                style = MonoTiny,
                                color = TextMuted
                            )
                        }
                        Text(
                            text = "INSTALL ↗",
                            style = MaterialTheme.typography.labelMedium,
                            color = AccentGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            updateStatus == "DOWNLOADING" -> {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = Surface2)
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(
                            text = "DOWNLOADING UPDATE... ${if (updateProgress >= 0) "$updateProgress%" else ""}",
                            style = MonoTiny,
                            color = AccentGreen,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { if (updateProgress >= 0) updateProgress / 100f else 0f },
                            modifier = Modifier.fillMaxWidth(),
                            color = AccentGreen,
                            trackColor = Surface3
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            
            @Composable
            fun FamilyButton(
                label: String,
                tag: String,
                accent: Color,
                tapable: Boolean,
                onPick: () -> Unit
            ) {
                val selected = family == tag
                val running = busy && selected

                val anim by rememberInfiniteTransition(label = "fam-$tag").animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(
                            durationMillis = if (running) 900 else 3200,
                            easing = LinearEasing
                        )
                    ),
                    label = "sweep-$tag"
                )
                val breathe by rememberInfiniteTransition(label = "breathe-$tag").animateFloat(
                    initialValue = 0.965f,
                    targetValue = 1.035f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1400, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "breathe-$tag"
                )

                val scale by animateFloatAsState(
                    targetValue = when {
                        running -> 1.06f
                        selected -> breathe
                        else -> 0.94f
                    },
                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                    label = "scale-$tag"
                )

                val ring = when {
                    running -> AccentAmber
                    selected -> accent
                    else -> accent.copy(alpha = 0.32f)
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(modifier = Modifier.size(148.dp), contentAlignment = Alignment.Center) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val stroke = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                            val diameter = size.minDimension - stroke.width
                            val topLeft = androidx.compose.ui.geometry.Offset(
                                (size.width - diameter) / 2f, (size.height - diameter) / 2f
                            )
                            val arcSize = androidx.compose.ui.geometry.Size(diameter, diameter)

                            drawArc(
                                color = ring.copy(alpha = if (selected || running) 0.20f else 0.10f),
                                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                                topLeft = topLeft, size = arcSize, style = stroke
                            )

                            if (selected || running) {
                                rotate(anim) {
                                    drawArc(
                                        color = ring.copy(alpha = 0.95f),
                                        startAngle = 0f, sweepAngle = if (running) 90f else 120f,
                                        useCenter = false,
                                        topLeft = topLeft, size = arcSize, style = stroke
                                    )
                                    drawArc(
                                        color = ring.copy(alpha = 0.45f),
                                        startAngle = 180f, sweepAngle = 70f, useCenter = false,
                                        topLeft = topLeft, size = arcSize, style = stroke
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = {
                                family = tag
                                onPick()
                            },
                            enabled = tapable && !busy && rootAvailable,
                            shape = CircleShape,
                            border = androidx.compose.foundation.BorderStroke(
                                width = if (selected) 2.5.dp else 1.dp,
                                color = if (selected) ring.copy(alpha = 0.9f)
                                else accent.copy(alpha = 0.35f)
                            ),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected || running) {
                                    accent.copy(alpha = 0.20f)
                                } else {
                                    Surface2
                                },
                                contentColor = TextPrimary,
                                disabledContainerColor = Surface1,
                                disabledContentColor = TextDisabled
                            ),
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier
                                .size(114.dp)
                                .scale(scale)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(horizontal = 2.dp)
                            ) {
                                if (running) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(28.dp),
                                        color = ring,
                                        strokeWidth = 2.5.dp
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Bolt,
                                        contentDescription = null,
                                        tint = if (selected || running) accent else TextMuted,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selected) TextPrimary else TextSecondary,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    Text(
                        text = when (tag) {
                            OtaDriverStore.RT -> "ioctl 801/802/803"
                            else -> "ioctl 801/802/804"
                        },
                        style = MonoTiny,
                        color = if (selected) accent else TextDisabled
                    )
                }
            }

            val loaded = viewModel.driverLoaded.value
            val toUnload by animateFloatAsState(
                targetValue = if (loaded) 1f else 0f,
                animationSpec = tween(280, easing = FastOutSlowInEasing),
                label = "swap"
            )
            
            val loadsTapable = !loaded && toUnload < 0.5f

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(1f - toUnload),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Top
                ) {
                    FamilyButton("RT", OtaDriverStore.RT, FamilyRt, loadsTapable) {
                        viewModel.autoLoadUniversal(
                            context, preferOta = true, variant = OtaDriverStore.RT
                        )
                    }
                    FamilyButton("QX", OtaDriverStore.QX, FamilyQx, loadsTapable) {
                        viewModel.autoLoadUniversal(
                            context, preferOta = true, variant = OtaDriverStore.QX
                        )
                    }
                }

                UnloadPanel(
                    visible = toUnload,
                    moduleName = viewModel.driverModule.value.ifBlank {
                        viewModel.loadedModuleName.value
                    },
                    enabled = loaded && !busy && rootAvailable,
                    onUnload = { viewModel.unloadModule(context) }
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Surface1),
                border = BorderStroke(
                    1.dp,
                    if (viewModel.driverLoaded.value) StatusOk.copy(alpha = 0.4f)
                    else BorderSubtle
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (viewModel.driverLoaded.value) StatusOk else StatusIdle
                            )
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (viewModel.driverLoaded.value) {
                                "LOADED · ${viewModel.driverModule.value.ifBlank { viewModel.loadedModuleName.value }}"
                            } else {
                                "NOT LOADED"
                            },
                            style = MonoSmall,
                            color = if (viewModel.driverLoaded.value) StatusOk else TextSecondary
                        )
                        Text(
                            text = if (viewModel.driverLoaded.value)
                                "a module stays loaded until reboot or unload"
                            else "pick RT or QX above to load it",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Surface1),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (viewModel.autoloadEnabled.value) AccentGreen.copy(alpha = 0.45f)
                    else BorderSubtle
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Load at every boot",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (viewModel.autoloadEnabled.value)
                                "ON · the driver returns by itself after a restart"
                            else "OFF · you must tap a load button after a restart",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (viewModel.autoloadEnabled.value) AccentGreen else TextMuted
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = viewModel.autoloadEnabled.value,
                        onCheckedChange = { on -> viewModel.setBootAutoload(context, on) },
                        enabled = !busy && rootAvailable
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            Text(
                text = when {
                    busyStep.isNotBlank() -> busyStep
                    autoStatus.isNotBlank() -> autoStatus
                    exact != null ->
                        "Ready: ${exact.version} ${OtaDriverStore.variantLabel(family)} loader on GitHub"
                    else -> "Tap ${OtaDriverStore.variantLabel(family)} · detect kernel + download loader"
                },
                style = MonoTiny,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceInset)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "CONSOLE",
                style = MaterialTheme.typography.titleSmall,
                color = AccentGreen,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { clipboard.setText(AnnotatedString(viewModel.getTerminalText())) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy console", tint = TextSecondary)
            }
            IconButton(onClick = { viewModel.clearTerminal() }) {
                Icon(Icons.Default.Delete, contentDescription = "Clear console", tint = TextSecondary)
            }
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
                .clip(RoundedCornerShape(12.dp)),
            
            colors = CardDefaults.cardColors(containerColor = SurfaceInset),
            border = BorderStroke(1.dp, BorderSubtle)
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
                        tint = TextDisabled
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Tap RT or QX to start",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
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

        Text(
            text = when {
                manifestStatus == "OK" -> "SUPPORTED KERNELS (${supported.size}) · NEWEST " +
                        (newestBuild.take(10).ifEmpty { "n/a" })
                manifestStatus == "LOADING" -> "FETCHING DATABASE..."
                else -> "SUPPORTED KERNELS (offline)"
            },
            style = MaterialTheme.typography.titleSmall,
            color = AccentGreen
        )
        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = Surface1),
            border = BorderStroke(1.dp, BorderSubtle)
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
                                style = MonoTiny,
                                color = if (isThis) StatusOk else TextSecondary,
                                modifier = Modifier.weight(1f)
                            )
                            if (e.buildDate.isNotBlank()) {
                                Text(
                                    text = e.buildDate.take(10),
                                    style = MonoTiny,
                                    color = TextMuted
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            if (isThis) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = StatusOk,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "THIS DEVICE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StatusOk,
                                    fontWeight = FontWeight.Bold
                                )
                            } else if (idx == newestIndex) {
                                
                                Text(
                                    text = "NEWEST",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AccentAmber,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 1.dp),
                            color = BorderSubtle
                        )
                    }
                }
                manifestStatus == "LOADING" -> Row(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    horizontalArrangement = Arrangement.Center
                ) { CircularProgressIndicator(modifier = Modifier.size(22.dp), color = AccentGreen) }
                else -> Text(
                    text = "No internet connection — the driver database is not visible.\n" +
                            "Connect, then tap the refresh button.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(18.dp),
                    color = AccentAmber
                )
            }
        }

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
            containerColor = if (loadFailed) Surface2 else Surface1
        ),
        border = BorderStroke(
            1.dp,
            if (loadFailed) AccentRed.copy(alpha = 0.45f)
            else BorderSubtle
        )
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (loadFailed) "Load failed — need a custom loader?"
                       else "No kernel match? Need a custom loader?",
                style = MaterialTheme.typography.titleSmall,
                color = if (loadFailed) AccentRed else AccentGreen,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Tap a button — device, kernel and the full log go automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                
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
                            } catch (_: Exception) {  }
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
                        color = TextSecondary,
                        fontWeight = FontWeight.Bold
                    )
                }
                
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
                            } catch (_: Exception) {  }
                        },
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(Surface3)
                            .border(1.5.dp, AccentBlue.copy(alpha = 0.6f), CircleShape)
                    ) {
                        Icon(
                            Icons.Default.BugReport,
                            contentDescription = "Send failure report to GitHub",
                            tint = AccentBlue,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Report",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                text = "Report saves on GitHub — the PC auto-checks and fixes",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun UnloadPanel(
    visible: Float,
    moduleName: String,
    enabled: Boolean,
    onUnload: () -> Unit
) {
    if (visible <= 0.001f) return

    val breathe by rememberInfiniteTransition(label = "unload").animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "unloadBreathe"
    )
    val spin by rememberInfiniteTransition(label = "unloadSpin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(5200, easing = LinearEasing)
        ),
        label = "unloadSpin"
    )
    val scale = breathe * (0.9f + 0.1f * visible)

    Column(
        modifier = Modifier.alpha(visible),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.size(148.dp), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                val diameter = size.minDimension - stroke.width
                val topLeft = androidx.compose.ui.geometry.Offset(
                    (size.width - diameter) / 2f, (size.height - diameter) / 2f
                )
                val arcSize = androidx.compose.ui.geometry.Size(diameter, diameter)
                drawArc(
                    color = AccentRed.copy(alpha = 0.20f),
                    startAngle = 0f, sweepAngle = 360f, useCenter = false,
                    topLeft = topLeft, size = arcSize, style = stroke
                )
                rotate(spin) {
                    drawArc(
                        color = AccentRed.copy(alpha = 0.9f),
                        startAngle = 0f, sweepAngle = 110f, useCenter = false,
                        topLeft = topLeft, size = arcSize, style = stroke
                    )
                }
            }

            Button(
                onClick = onUnload,
                enabled = enabled,
                shape = CircleShape,
                border = BorderStroke(2.5.dp, AccentRed.copy(alpha = 0.9f)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentRed.copy(alpha = 0.18f),
                    contentColor = TextPrimary,
                    disabledContainerColor = Surface1,
                    disabledContentColor = TextDisabled
                ),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier
                    .size(114.dp)
                    .scale(scale)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = if (enabled) AccentRed else TextDisabled,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = "UNLOAD",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (enabled) TextPrimary else TextDisabled
                    )
                }
            }
        }
        Text(
            text = if (moduleName.isNotBlank()) "removes $moduleName" else "removes the driver",
            style = MonoTiny,
            color = AccentRed
        )
    }
}

@Composable
private fun StatusChip(text: String, ok: Boolean, modifier: Modifier = Modifier) {
    
    val tint = if (ok) AccentGreen else AccentRed
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = if (ok) FamilyRtDeep.copy(alpha = 0.35f) else tint.copy(alpha = 0.13f),
        border = BorderStroke(1.dp, tint.copy(alpha = if (ok) 0.55f else 0.65f))
    ) {
        Text(
            text = text,
            style = MonoTiny,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = if (ok) StatusOk else AccentRed,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp)
        )
    }
}

private fun consoleLineColor(type: String): Color = when (type) {
    "OK" -> LogOk
    "ERR" -> LogErr
    "WARN" -> LogWarn
    "CMD" -> LogCmd
    "FIX" -> LogFix
    "OUT" -> LogOut
    else -> LogInfo
}
