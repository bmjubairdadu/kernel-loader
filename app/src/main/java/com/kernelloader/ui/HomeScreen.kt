package com.kernelloader.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kernelloader.BuildConfig
import com.kernelloader.R
import com.kernelloader.driver.DeviceReportCollector
import com.kernelloader.driver.DriverViewModel
import com.kernelloader.driver.OtaDriverStore
import com.kernelloader.driver.SupportContact
import com.kernelloader.driver.TerminalLine
import com.kernelloader.root.RootChecker
import com.kernelloader.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    viewModel: DriverViewModel,
    onNavigateToConsole: () -> Unit,
    onNavigateToCredits: () -> Unit,
    onPickFile: () -> Unit
) {
    val context = LocalContext.current
    val scroll = rememberScrollState()

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
    val loaded = viewModel.driverLoaded.value
    val loadedModule = viewModel.driverModule.value.ifBlank { viewModel.loadedModuleName.value }

    LaunchedEffect(Unit) {
        viewModel.refreshManifest()
        viewModel.checkForAppUpdate()

        viewModel.refreshDriverState(context)
    }

    var family by remember { mutableStateOf("") }
    val exact = manifest?.let {
        if (family.isBlank()) {
            OtaDriverStore.exactFor(it, kernelRelease, OtaDriverStore.RT)
                ?: OtaDriverStore.exactFor(it, kernelRelease, OtaDriverStore.QX)
        } else {
            OtaDriverStore.exactFor(it, kernelRelease, family)
        }
    }

    val kernelShort = RootChecker.kernelShortVersion(kernelRelease)

    val rtSupported = manifest?.let {
        OtaDriverStore.supportedEntries(it, OtaDriverStore.RT)
    }?.let { list ->
        val (mine, others) = list.partition {
            RootChecker.kernelShortVersion(it.version) == kernelShort
        }
        mine + others
    } ?: emptyList()

    val qxSupported = manifest?.let {
        OtaDriverStore.supportedEntries(it, OtaDriverStore.QX)
    }?.let { list ->
        val (mine, others) = list.partition {
            RootChecker.kernelShortVersion(it.version) == kernelShort
        }
        mine + others
    } ?: emptyList()

    AppBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(14.dp))

            HomeHeader(
                version = "v${BuildConfig.VERSION_NAME}",
                onConsole = onNavigateToConsole,
                onCredits = onNavigateToCredits
            )

            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(
                    text = if (rootAvailable) "ROOT OK" else "ROOT MISSING",
                    ok = rootAvailable,
                    modifier = Modifier.weight(1f)
                )
                StatusChip(
                    text = "KERNEL $kernelVersion",
                    ok = true,
                    modifier = Modifier.weight(1.6f)
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
                    modifier = Modifier.weight(1f)
                )
                MiniAction(
                    icon = Icons.Default.Refresh,
                    description = "Refresh driver database",
                    tint = AccentGreen,
                    onClick = { viewModel.refreshManifest() }
                )
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
                        colors = CardDefaults.cardColors(containerColor = Surface2),
                        border = BorderStroke(1.dp, AccentGreen.copy(alpha = glow))
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

            Spacer(Modifier.height(14.dp))

            val toUnload by animateFloatAsState(
                targetValue = if (loaded) 1f else 0f,
                animationSpec = tween(280, easing = FastOutSlowInEasing),
                label = "swap"
            )
            val loadsTapable = !loaded && toUnload < 0.5f
            val running = busy

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(268.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(1f - toUnload),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    RingButton(
                        accentStart = if (running) GradientAmberStart else GradientRtStart,
                        accentEnd = if (running) GradientAmberEnd else GradientRtEnd,
                        busy = running,
                        enabled = loadsTapable && !busy && rootAvailable,
                        onClick = {
                            family = ""
                            viewModel.autoLoadUniversal(context, preferOta = true, variant = "")
                        }
                    ) {
                        if (running) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(26.dp),
                                color = GradientAmberStart,
                                strokeWidth = 2.5.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.Bolt,
                                contentDescription = null,
                                tint = if (loadsTapable && !busy) GradientRtStart else TextDisabled,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = if (running) "LOADING" else "LOAD",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (loadsTapable && !busy) TextPrimary else TextDisabled
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (running) busyStep.ifBlank { "working..." }
                        else "auto pipeline · RT → QX → built-in",
                        style = MonoTiny,
                        color = if (running) AccentAmber else FamilyRt,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                    if (running) {
                        Spacer(Modifier.height(8.dp))
                        StopLoadButton(onStop = { viewModel.stopLoad() })
                    }
                    Spacer(Modifier.height(8.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "FORCE:",
                            style = MonoTiny,
                            color = TextDisabled
                        )
                        listOf(
                            "RT" to OtaDriverStore.RT,
                            "QX" to OtaDriverStore.QX
                        ).forEach { (label, tag) ->
                            val selected = family == tag
                            val accent = if (tag == OtaDriverStore.RT) FamilyRt else FamilyQx
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (selected) accent.copy(alpha = 0.22f) else Surface2,
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) accent.copy(alpha = 0.8f) else BorderSubtle
                                ),
                                modifier = Modifier.clickable(
                                    enabled = !busy && rootAvailable && loadsTapable
                                ) { family = tag }
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) accent else TextSecondary,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                )
                            }
                        }
                        if (family.isNotBlank()) {
                            Surface(
                                shape = CircleShape,
                                color = (if (family == OtaDriverStore.RT) FamilyRt else FamilyQx)
                                    .copy(alpha = 0.85f),
                                modifier = Modifier.clickable(
                                    enabled = !busy && rootAvailable && loadsTapable
                                ) {
                                    viewModel.autoLoadUniversal(
                                        context, preferOta = true, variant = family
                                    )
                                }
                            ) {
                                Icon(
                                    Icons.Default.Bolt,
                                    contentDescription = "Load forced variant",
                                    tint = TextPrimary,
                                    modifier = Modifier
                                        .padding(6.dp)
                                        .size(18.dp)
                                )
                            }
                        }
                    }
                }

                UnloadPanel(
                    visible = toUnload,
                    moduleName = loadedModule,
                    enabled = loaded && !busy && rootAvailable,
                    onUnload = { viewModel.unloadModule(context) }
                )
            }

            Spacer(Modifier.height(12.dp))

            val loadedGlow by rememberInfiniteTransition(label = "glow").animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "glowA"
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Surface1),
                border = BorderStroke(
                    1.dp,
                    if (loaded) Brush.horizontalGradient(
                        listOf(
                            GradientRtStart.copy(alpha = 0.65f),
                            GradientQxStart.copy(alpha = 0.65f)
                        )
                    ) else Brush.horizontalGradient(listOf(BorderSubtle, BorderSubtle))
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(if (loaded) StatusOk.copy(alpha = loadedGlow) else StatusIdle)
                            .border(
                                2.dp,
                                if (loaded) StatusOk.copy(alpha = loadedGlow * 0.35f) else Color.Transparent,
                                CircleShape
                            )
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (loaded) "LOADED · $loadedModule" else "NOT LOADED",
                            style = MonoSmall,
                            color = if (loaded) StatusOk else TextSecondary
                        )
                        Text(
                            text = if (loaded)
                                "a module stays loaded until reboot or unload"
                            else "tap LOAD above to detect and load the driver",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                    if (loaded) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = StatusOk,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Surface1),
                border = BorderStroke(
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
                        enabled = !busy && rootAvailable,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TextOnAccent,
                            checkedTrackColor = AccentGreen,
                            uncheckedThumbColor = TextMuted,
                            uncheckedTrackColor = Surface3,
                            uncheckedBorderColor = BorderSubtle
                        )
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = SurfaceInset,
                border = BorderStroke(1.dp, BorderSubtle)
            ) {
                Text(
                    text = when {
                        busyStep.isNotBlank() -> busyStep
                        autoStatus.isNotBlank() -> autoStatus
                        exact != null ->
                            "Ready: ${exact.version} ${OtaDriverStore.variantLabel(exact.variant)} loader on GitHub"
                        else -> "Tap LOAD · detect kernel + download loader"
                    },
                    style = MonoTiny,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 9.dp)
                )
            }

            Spacer(Modifier.height(14.dp))

            VariantConsoleSection(
                title = "STATUS CONSOLE",
                accent = AccentGreen,
                lines = terminalLines,
                emptyHint = "No output yet — tap LOAD",
                height = 260.dp,
                onClear = { viewModel.clearTerminal() }
            )

            Spacer(Modifier.height(14.dp))

            KernelListSection(
                title = "RT KERNELS (${rtSupported.size})",
                accent = FamilyRt,
                entries = rtSupported,
                status = manifestStatus,
                kernelRelease = kernelRelease
            )
            Spacer(Modifier.height(10.dp))
            KernelListSection(
                title = "QX KERNELS (${qxSupported.size})",
                accent = FamilyQx,
                entries = qxSupported,
                status = manifestStatus,
                kernelRelease = kernelRelease
            )

            SupportCard(
                kernelRelease = kernelRelease,
                loadFailed = autoOk == false,
                hasExact = exact != null,
                appVersion = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                resultText = autoStatus.ifBlank { "load failed / no exact loader" },
                loadedModule = loadedModule,
                getLog = { viewModel.getTerminalText() }
            )
        }
    }
}

@Composable
private fun HomeHeader(
    version: String,
    onConsole: () -> Unit,
    onCredits: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(50.dp), contentAlignment = Alignment.Center) {
            val spin by rememberInfiniteTransition(label = "logoSpin").animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(9000, easing = LinearEasing)
                ),
                label = "logoSpinA"
            )
            val breathe by rememberInfiniteTransition(label = "logoBreathe").animateFloat(
                initialValue = 0.96f,
                targetValue = 1.04f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1800, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "logoBreatheA"
            )
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                val diameter = size.minDimension - stroke.width
                val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
                val arcSize = Size(diameter, diameter)
                rotate(spin) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            listOf(GradientRtStart, GradientQxEnd, GradientRtStart)
                        ),
                        startAngle = 0f, sweepAngle = 280f, useCenter = false,
                        topLeft = topLeft, size = arcSize, style = stroke, alpha = 0.9f
                    )
                }
            }
            Image(
                painter = painterResource(id = R.drawable.app_logo),
                contentDescription = "Kernel Loader logo",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(42.dp)
                    .scale(breathe)
                    .clip(CircleShape)
                    .border(1.dp, BorderSubtle, CircleShape)
            )
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "Kernel Loader",
                style = MaterialTheme.typography.headlineSmall.copy(
                    brush = Brush.linearGradient(listOf(Color.White, GradientRtStart))
                )
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = AccentGreen.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, AccentGreen.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = version,
                        style = MonoTiny,
                        fontWeight = FontWeight.Bold,
                        color = AccentGreen,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "OTA MODULE LOADER",
                    style = MonoTiny,
                    color = TextMuted,
                    letterSpacing = 1.sp
                )
            }
        }
        MiniAction(
            icon = Icons.Default.Terminal,
            description = "Open console",
            tint = AccentGreen,
            onClick = onConsole
        )
        Spacer(Modifier.width(6.dp))
        MiniAction(
            icon = Icons.Default.Info,
            description = "Credits",
            tint = TextSecondary,
            onClick = onCredits
        )
    }
}

@Composable
private fun VariantConsoleSection(
    title: String,
    accent: Color,
    lines: List<TerminalLine>,
    emptyHint: String,
    height: Dp = 175.dp,
    onClear: (() -> Unit)? = null
) {
    val clipboard = LocalClipboardManager.current
    SectionTitle(title = title, accent = accent) {
        MiniAction(
            icon = Icons.Default.ContentCopy,
            description = "Copy $title",
            tint = TextSecondary
        ) {
            clipboard.setText(
                AnnotatedString(lines.joinToString("\n") { "- ${it.text}" })
            )
        }
        if (onClear != null) {
            Spacer(Modifier.width(6.dp))
            MiniAction(
                icon = Icons.Default.Delete,
                description = "Clear console",
                tint = TextSecondary,
                onClick = onClear
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    VariantConsoleCard(lines = lines, accent = accent, emptyHint = emptyHint, height = height)
}

@Composable
private fun VariantConsoleCard(
    lines: List<TerminalLine>,
    accent: Color,
    emptyHint: String,
    height: Dp = 175.dp
) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceInset),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        if (lines.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                    tint = TextDisabled
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = emptyHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(10.dp)
            ) {
                items(lines) { line ->
                    Text(
                        text = "- ${line.text}",
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
}

@Composable
private fun KernelListSection(
    title: String,
    accent: Color,
    entries: List<OtaDriverStore.DriverEntry>,
    status: String,
    kernelRelease: String
) {
    val deviceShort = RootChecker.kernelShortVersion(kernelRelease)
    SectionTitle(title = title, accent = accent) {
        if (status == "OK" && entries.isNotEmpty()) {
            val newest = entries
                .dropWhile { RootChecker.kernelShortVersion(it.version) == deviceShort }
                .firstOrNull { it.buildDate.isNotBlank() }?.buildDate.orEmpty()
            Text(
                text = "NEWEST ${newest.take(10).ifEmpty { "n/a" }}",
                style = MonoTiny,
                color = TextMuted
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface1),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        when {
            status == "OK" && entries.isNotEmpty() -> {
                val newestIndex = entries.count {
                    RootChecker.kernelShortVersion(it.version) == deviceShort
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(170.dp).padding(10.dp)
                ) {
                    itemsIndexed(entries) { idx, e ->
                        val v = e.version
                        val isThis = RootChecker.kernelShortVersion(v) == deviceShort
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(7.dp))
                                .background(
                                    if (isThis) accent.copy(alpha = 0.10f)
                                    else Color.Transparent
                                )
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "kernel $v",
                                style = MonoTiny,
                                color = if (isThis) accent else TextSecondary,
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
                                DeviceBadge(text = "THIS DEVICE", color = accent)
                            } else if (idx == newestIndex) {
                                DeviceBadge(text = "NEWEST", color = AccentAmber)
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 1.dp),
                            color = BorderSubtle
                        )
                    }
                }
            }
            status == "OK" -> Text(
                text = "No kernels in the database for this series yet",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
                color = TextMuted
            )
            status == "LOADING" -> Row(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = accent
                )
            }
            else -> Text(
                text = "No internet — the driver database is not visible. " +
                        "Connect, then tap the refresh button.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
                color = AccentAmber
            )
        }
    }
}

@Composable
private fun SectionTitle(
    title: String,
    accent: Color = AccentGreen,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(15.dp)
                .background(accent, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = accent,
            letterSpacing = 1.2.sp
        )
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun MiniAction(
    icon: ImageVector,
    description: String,
    tint: Color = TextSecondary,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(Surface2.copy(alpha = 0.7f))
            .border(1.dp, BorderSubtle, RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun DeviceBadge(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = color,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun RingButton(
    accentStart: Color,
    accentEnd: Color,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val breathe by rememberInfiniteTransition(label = "ring-breathe").animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe"
    )
    val spin by rememberInfiniteTransition(label = "ring-spin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (busy) 900 else 5200, easing = LinearEasing)
        ),
        label = "spin"
    )
    val scale = breathe * (if (busy) 1.05f else 1f)
    val ringBrush = Brush.sweepGradient(
        listOf(accentEnd, accentStart, accentEnd.copy(alpha = 0.2f), accentEnd)
    )
    val hot = enabled || busy

    Box(modifier = Modifier.size(148.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
            val glow = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
            val diameter = size.minDimension - stroke.width
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)
            drawArc(
                color = accentStart.copy(alpha = if (hot) 0.20f else 0.10f),
                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = topLeft, size = arcSize, style = stroke
            )
            rotate(spin) {
                if (hot) {
                    drawArc(
                        brush = ringBrush,
                        startAngle = 0f, sweepAngle = 110f, useCenter = false,
                        topLeft = topLeft, size = arcSize, style = glow, alpha = 0.16f
                    )
                }
                drawArc(
                    brush = ringBrush,
                    startAngle = 0f, sweepAngle = 110f, useCenter = false,
                    topLeft = topLeft, size = arcSize, style = stroke,
                    alpha = if (hot) 0.95f else 0.4f
                )
            }
        }

        Button(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            border = BorderStroke(
                2.dp,
                Brush.linearGradient(listOf(accentStart, accentEnd))
            ),
            colors = ButtonDefaults.buttonColors(
                containerColor = accentStart.copy(alpha = if (hot) 0.16f else 0.08f),
                contentColor = TextPrimary,
                disabledContainerColor = Surface1,
                disabledContentColor = TextDisabled
            ),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
                .size(114.dp)
                .scale(scale)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, content = content)
        }
    }
}

@Composable
private fun SupportCard(
    kernelRelease: String,
    loadFailed: Boolean,
    hasExact: Boolean,
    appVersion: String,
    resultText: String,
    loadedModule: String,
    getLog: () -> String
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var preparing by remember { mutableStateOf(false) }

    fun gatherAndSend(open: (DeviceReportCollector.DeviceReport, String) -> Unit) {
        scope.launch {
            preparing = true
            val report = withContext(Dispatchers.IO) {
                DeviceReportCollector.collect(
                    rootAvailable = RootChecker.isRootAvailable(),
                    loadedModules = listOf(loadedModule)
                )
            }
            preparing = false
            val text = report.buildText(
                appName = SupportContact.APP_NAME,
                appVersion = appVersion,
                matchStatus = if (hasExact) "EXACT MATCH FOUND" else "NO MATCH IN DB",
                resultText = resultText,
                logText = getLog()
            )
            open(report, text)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (loadFailed) Surface2 else Surface1
        ),
        border = BorderStroke(
            1.dp,
            if (loadFailed) AccentRed.copy(alpha = 0.45f) else BorderSubtle
        )
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = when {
                    loadFailed -> "Load failed — auto build report is ready"
                    !hasExact -> "No module for this kernel — build report ready"
                    else -> "No kernel match? Need a custom loader?"
                },
                style = MaterialTheme.typography.titleSmall,
                color = if (loadFailed) AccentRed else AccentGreen,
                textAlign = TextAlign.Center
            )
            Text(
                text = "The app auto-collects kernel vermagic, config flags & device info — you just hit send.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (preparing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    color = AccentAmber,
                    trackColor = Surface3
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = {
                            gatherAndSend { _, text ->
                                try {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse(SupportContact.waLinkReport(text))
                                        )
                                    )
                                } catch (_: Exception) {
                                }
                            }
                        },
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF25D366))
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_whatsapp),
                            contentDescription = "Send auto build report on WhatsApp",
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
                            gatherAndSend { report, text ->
                                try {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse(
                                                SupportContact.issueUrlFromReport(
                                                    text, report.issueTitle
                                                )
                                            )
                                        )
                                    )
                                } catch (_: Exception) {
                                }
                            }
                        },
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(Surface3)
                            .border(1.5.dp, AccentBlue.copy(alpha = 0.6f), CircleShape)
                    ) {
                        Icon(
                            Icons.Default.BugReport,
                            contentDescription = "Post auto build report to GitHub",
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

            TextButton(
                onClick = {
                    gatherAndSend { _, text ->
                        clipboard.setText(AnnotatedString(text))
                        Toast.makeText(context, "Full build report copied", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = !preparing
            ) {
                Text(
                    text = if (preparing) "COLLECTING DEVICE INFO..." else "COPY FULL REPORT",
                    style = MonoTiny,
                    color = TextSecondary,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "Report saves on GitHub — the dev sees everything needed to build your .ko",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
            )
        }
    }
}

@Composable
private fun StopLoadButton(onStop: () -> Unit) {
    val blink by rememberInfiniteTransition(label = "stop").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "stopA"
    )
    Surface(
        shape = RoundedCornerShape(50),
        color = AccentRed.copy(alpha = 0.06f + 0.14f * blink),
        border = BorderStroke(1.5.dp, AccentRed.copy(alpha = 0.25f + 0.55f * blink)),
        modifier = Modifier.clickable(onClick = onStop)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Icon(
                Icons.Default.Stop,
                contentDescription = null,
                tint = AccentRed,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "STOP",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = AccentRed,
                letterSpacing = 1.5.sp
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

    Column(
        modifier = Modifier.alpha(visible),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        RingButton(
            accentStart = if (enabled) GradientRedStart else TextDisabled,
            accentEnd = if (enabled) GradientRedEnd else TextDisabled,
            busy = false,
            enabled = enabled,
            onClick = onUnload
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = null,
                tint = if (enabled) GradientRedStart else TextDisabled,
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
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (moduleName.isNotBlank()) "removes $moduleName" else "removes the driver",
            style = MonoTiny,
            color = AccentRed,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }
}

@Composable
private fun StatusChip(text: String, ok: Boolean, modifier: Modifier = Modifier) {
    val tint = if (ok) AccentGreen else AccentRed
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = tint.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.45f))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(tint)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = text,
                style = MonoTiny,
                fontWeight = FontWeight.Bold,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
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
