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
import androidx.compose.material.icons.filled.FolderOpen
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import com.kernelloader.driver.ReportPackBuilder
import com.kernelloader.driver.SupportContact
import com.kernelloader.driver.TerminalLine
import com.kernelloader.root.RootChecker
import com.kernelloader.ui.theme.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    var rootAvailable by remember { mutableStateOf(false) }
    var kernelVersion by remember { mutableStateOf("") }
    var kernelRelease by remember { mutableStateOf("") }
    val lifecycleOwner = LocalLifecycleOwner.current
    val screenScope = rememberCoroutineScope()
    DisposableEffect(lifecycleOwner) {
        fun resync() {
            screenScope.launch(Dispatchers.IO) {
                val root = RootChecker.isRootAvailable()
                val kVer = RootChecker.getKernelVersion()
                val kRel = RootChecker.getKernelRelease() ?: kVer
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    rootAvailable = root
                    if (kVer.isNotBlank() && !kVer.startsWith("Error")) kernelVersion = kVer
                    if (kRel.isNotBlank()) kernelRelease = kRel
                }
            }
        }
        resync()
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resync()
                viewModel.checkRevocationOnResume(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val manifest = viewModel.remoteManifest.value
    val manifestStatus = viewModel.manifestStatus.value
    val busy = viewModel.isBusy.value
    val busyStep = viewModel.busyStep.value
    val rebooting = viewModel.rebooting.value
    val terminalLines = viewModel.terminalLines
    val updateStatus = viewModel.updateStatus.value
    val updateProgress = viewModel.updateProgress.value
    val appUpdate = viewModel.appUpdate.value
    val loaded = viewModel.driverLoaded.value
    val hidden = viewModel.hiddenLoaded.value
    val loadedModule = viewModel.driverModule.value.ifBlank { viewModel.loadedModuleName.value }

    LaunchedEffect(Unit) {
        viewModel.initLogStore(context)

        withContext(Dispatchers.IO) { ReportPackBuilder.wipeReportDir(context) }
        viewModel.refreshManifest()
        viewModel.checkForAppUpdate()

        viewModel.refreshDriverState(context)
    }

    val kernelShort = remember(kernelRelease) {
        RootChecker.kernelShortVersion(kernelRelease)
    }

    val exact = remember(manifest, kernelRelease) {
        manifest?.let {
            OtaDriverStore.exactFor(it, kernelRelease, OtaDriverStore.RT)
        }
    }

    val rtSupported = remember(manifest, kernelShort) {
        manifest?.let {
            OtaDriverStore.supportedEntries(it, OtaDriverStore.RT)
        }?.let { list ->
            val (mine, others) = list.partition {
                RootChecker.kernelShortVersion(it.version) == kernelShort
            }
            mine + others
        } ?: emptyList()
    }

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
                busy = busy,
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
                    onClick = {
                        viewModel.refreshManifest()
                        viewModel.checkForAppUpdate()
                    }
                )
            }

            when {
                (updateStatus == "READY_TO_INSTALL" || updateStatus == "AVAILABLE") && appUpdate != null -> {
                    val isReady = updateStatus == "READY_TO_INSTALL"
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
                        border = BorderStroke(1.dp, (if (isReady) AccentGreen else AccentAmber).copy(alpha = glow))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (isReady) Icons.Default.CheckCircle else Icons.Default.SystemUpdate,
                                contentDescription = null,
                                tint = if (isReady) AccentGreen else AccentAmber,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                val displayVer = if (appUpdate.versionName.startsWith("v", ignoreCase = true)) appUpdate.versionName else "v${appUpdate.versionName}"
                                Text(
                                    text = if (isReady) "UPDATE $displayVer READY"
                                           else "UPDATE $displayVer AVAILABLE",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isReady) AccentGreen else AccentAmber,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (isReady) {
                                        "Downloaded in background" +
                                                (if (appUpdate.apkSize > 0) " (${appUpdate.apkSize / 1024} KB)" else "") +
                                                " · tap to install"
                                    } else {
                                        "Download failed or pending · tap to retry"
                                    },
                                    style = MonoTiny,
                                    color = TextMuted
                                )
                            }
                            Text(
                                text = if (isReady) "INSTALL NOW ↗" else "RETRY ⟳",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isReady) AccentGreen else AccentAmber,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                updateStatus == "DOWNLOADING" -> {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = Surface2),
                        border = BorderStroke(1.dp, AccentGreen.copy(alpha = 0.4f))
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.SystemUpdate,
                                    contentDescription = null,
                                    tint = AccentGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "AUTO-DOWNLOADING v${appUpdate?.versionName ?: ""} IN BACKGROUND... ${if (updateProgress >= 0) "$updateProgress%" else ""}",
                                    style = MonoTiny,
                                    color = AccentGreen,
                                    fontWeight = FontWeight.Bold
                                )
                            }
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
                        enabled = loadsTapable && !busy && rootAvailable && !viewModel.isDriverRevoked.value,
                        animated = true,
                        onClick = {
                            viewModel.autoLoadUniversal(context, preferOta = true, variant = OtaDriverStore.RT)
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
                                tint = if (loadsTapable && !busy && !viewModel.isDriverRevoked.value) GradientRtStart else TextDisabled,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = if (running) "LOADING" else if (viewModel.isDriverRevoked.value) "REVOKED" else "LOAD",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (loadsTapable && !busy && !viewModel.isDriverRevoked.value) TextPrimary else TextDisabled
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    if (viewModel.isDriverRevoked.value) {
                        Text(
                            text = "⛔ ACCESS REVOKED: Driver removed from DB by admin",
                            style = MonoTiny,
                            color = AccentRed,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    } else if (viewModel.isDriverUpgraded.value && loaded) {
                        Text(
                            text = "⚡ Upgraded driver available in DB (loads on next restart)",
                            style = MonoTiny,
                            color = AccentGreen,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    } else {
                        Text(
                            text = if (running) busyStep.ifBlank { "working..." }
                            else "Modern RT Stealth Engine",
                            style = MonoTiny,
                            color = if (running) AccentAmber else FamilyRt,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                    if (running) {
                        Spacer(Modifier.height(8.dp))
                        StopLoadButton(onStop = { viewModel.stopLoad() })
                    }
                }

                UnloadPanel(
                    visible = toUnload,
                    moduleName = loadedModule,
                    enabled = loaded && !busy && rootAvailable,
                    busy = rebooting,
                    onUnload = { viewModel.rebootDevice(context) }
                )
            }

            Spacer(Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Surface1),
                border = BorderStroke(
                    1.dp,
                    if (loaded) Brush.horizontalGradient(
                        listOf(
                            GradientRtStart.copy(alpha = 0.65f),
                            GradientRtEnd.copy(alpha = 0.65f)
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
                    StatusDot(loaded = loaded)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (loaded) {
                                if (hidden) "LOADED BUT HIDDEN" else "LOADED · $loadedModule"
                            } else "NOT LOADED",
                            style = MonoSmall,
                            color = if (loaded) StatusOk else TextSecondary
                        )
                        Text(
                            text = if (loaded)
                                "a module stays loaded until reboot"
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

            val pickedFile = viewModel.pickedFileName.value
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Surface1),
                border = BorderStroke(
                    1.dp,
                    if (pickedFile != null) AccentBlue.copy(alpha = 0.5f) else BorderSubtle
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Custom Driver (.ko)",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = if (pickedFile != null)
                                    "Selected: $pickedFile"
                                else "Pick your own custom .ko driver from storage",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (pickedFile != null) AccentBlue else TextMuted
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = onPickFile,
                            enabled = !busy && rootAvailable,
                            border = BorderStroke(1.dp, if (pickedFile != null) AccentBlue else BorderSubtle),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = if (pickedFile != null) AccentBlue else TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (pickedFile != null) "CHANGE" else "BROWSE",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (pickedFile != null) AccentBlue else TextPrimary
                            )
                        }
                    }
                    if (pickedFile != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { viewModel.loadPickedFile(context) },
                                enabled = !busy && rootAvailable,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                            ) {
                                Icon(Icons.Default.Bolt, contentDescription = null, tint = TextOnAccent, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("LOAD CUSTOM", color = TextOnAccent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { viewModel.clearPickedFile() },
                                enabled = !busy,
                                border = BorderStroke(1.dp, BorderSubtle),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                            ) {
                                Text("CLEAR", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
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
                        viewModel.autoLoadStatus.value.isNotBlank() -> viewModel.autoLoadStatus.value
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

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Surface1),
                border = BorderStroke(1.dp, AccentGreen.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Driver R/W Status",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "read/write check (0x801/0x802/0x803) & clients",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { viewModel.checkDriverActivity(context) },
                        enabled = !busy && rootAvailable,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            Icons.Default.BugReport,
                            contentDescription = null,
                            tint = TextOnAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "CHECK R/W",
                            color = TextOnAccent,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            var switchSlotState by remember { mutableStateOf(false) }
            val isBootPatching = viewModel.isBootPatching.value
            val bootResult = viewModel.bootPatchResult.value
            val bootStatus = viewModel.bootPatchStatus.value
            val bootBackup = viewModel.bootBackupPath.value

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Surface1),
                border = BorderStroke(1.dp, if (bootResult == true) AccentGreen.copy(alpha = 0.5f) else AccentAmber.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Kernel Signature Bypass (Boot Patcher)",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Unpack · HexPatch · AVB re-sign · Anti-bootloop",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { viewModel.patchBootImage(context, switchSlotState) },
                            enabled = !busy && rootAvailable && !isBootPatching,
                            colors = ButtonDefaults.buttonColors(containerColor = AccentAmber),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            if (isBootPatching) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = TextOnAccent,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    Icons.Default.SystemUpdate,
                                    contentDescription = null,
                                    tint = TextOnAccent,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (isBootPatching) "PATCHING..." else "PATCH BOOT",
                                color = TextOnAccent,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (bootStatus.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = bootStatus,
                            style = MonoTiny,
                            color = if (bootResult == true) AccentGreen else if (bootResult == false) AccentRed else AccentAmber
                        )
                    }

                    if (bootBackup.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Backup: $bootBackup",
                            style = MonoTiny,
                            color = TextMuted
                        )
                    }

                    if (bootResult == true) {
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.rebootDevice(context) },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                tint = TextOnAccent,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "REBOOT TO APPLY BYPASS",
                                color = TextOnAccent,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
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

            SupportCard(
                viewModel = viewModel,
                loadFailed = viewModel.autoLoadOk.value == false,
                hasExact = exact != null,
                appVersion = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                resultText = viewModel.lastLoadError.value.ifBlank {
                    viewModel.autoLoadStatus.value.ifBlank { "load failed / no exact loader" }
                },
                loadedModule = loadedModule,
                getLog = { viewModel.getTerminalText() }
            )
        }
    }
}

@Composable
private fun HomeHeader(
    version: String,
    busy: Boolean,
    onConsole: () -> Unit,
    onCredits: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(50.dp), contentAlignment = Alignment.Center) {
            val transition = rememberInfiniteTransition(label = "logoAnim")
            val spin by transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(if (busy) 1200 else 7000, easing = LinearEasing)
                ),
                label = "logoSpin"
            )
            val breathe by transition.animateFloat(
                initialValue = 0.95f,
                targetValue = 1.05f,
                animationSpec = infiniteRepeatable(
                    animation = tween(if (busy) 800 else 2200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "logoBreathe"
            )
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationZ = spin }
            ) {
                val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                val diameter = size.minDimension - stroke.width
                val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
                val arcSize = Size(diameter, diameter)
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(GradientRtStart, GradientQxEnd, GradientRtStart)
                    ),
                    startAngle = 0f, sweepAngle = 280f, useCenter = false,
                    topLeft = topLeft, size = arcSize, style = stroke, alpha = 0.9f
                )
            }
            Image(
                painter = painterResource(id = R.drawable.app_logo),
                contentDescription = "Kernel Loader logo",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(42.dp)
                    .graphicsLayer {
                        scaleX = breathe
                        scaleY = breathe
                    }
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
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (lines.size - 1).coerceAtLeast(0)
    )
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.scrollToItem(lines.size - 1)
        }
    }
    LaunchedEffect(Unit) {
        if (lines.isNotEmpty()) {
            listState.scrollToItem(lines.size - 1)
        }
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
                        modifier = Modifier.padding(vertical = 1.dp),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
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
private fun StatusDot(loaded: Boolean) {
    if (loaded) {
        val transition = rememberInfiniteTransition(label = "statusDotPulse")
        val glow by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "statusDotGlow"
        )
        val scale by transition.animateFloat(
            initialValue = 0.9f,
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "statusDotScale"
        )
        Box(
            modifier = Modifier.size(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        alpha = glow * 0.45f
                    }
                    .clip(CircleShape)
                    .background(StatusOk)
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .graphicsLayer {
                        alpha = glow
                    }
                    .clip(CircleShape)
                    .background(StatusOk)
                    .border(1.5.dp, StatusOk.copy(alpha = glow * 0.7f), CircleShape)
            )
        }
    } else {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(StatusIdle)
                .border(2.dp, Color.Transparent, CircleShape)
        )
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
    animated: Boolean = true,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val transition = rememberInfiniteTransition(label = "ringTransition")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (busy) 900 else 5200, easing = LinearEasing)
        ),
        label = "ringSpin"
    )
    val breathe by transition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (busy) 1600 else 3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringBreathe"
    )
    val scaleSpec = if (busy) 1.05f else 1f
    val ringBrush = Brush.sweepGradient(
        listOf(accentEnd, accentStart, accentEnd.copy(alpha = 0.2f), accentEnd)
    )
    val hot = enabled || busy

    Box(modifier = Modifier.size(148.dp), contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = if (animated || busy) spin else 0f }
        ) {
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
                .graphicsLayer {
                    val b = if (animated || busy) breathe else 1f
                    val s = scaleSpec * b
                    scaleX = s
                    scaleY = s
                }
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, content = content)
        }
    }
}

@Composable
private fun SupportCard(
    viewModel: DriverViewModel,
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
    var stepText by remember { mutableStateOf("") }

    val matchStatus = if (hasExact) "EXACT MATCH FOUND" else "NO MATCH IN DB"

    var wipeOnResume by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && wipeOnResume) {
                wipeOnResume = false
                scope.launch(Dispatchers.IO) { ReportPackBuilder.wipeReportDir(context) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    fun collectReport(): DeviceReportCollector.DeviceReport =
        DeviceReportCollector.collect(
            rootAvailable = RootChecker.isRootAvailable(),
            loadedModules = listOf(loadedModule),
            bundleTried = viewModel.lastBundleTried.value,
            lastError = resultText
        )

    fun buildReportText(report: DeviceReportCollector.DeviceReport): String =
        report.buildText(
            appName = SupportContact.APP_NAME,
            appVersion = appVersion,
            matchStatus = matchStatus,
            resultText = resultText,
            logText = getLog()
        )

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
                text = "One tap packs report.txt, Module.symvers.txt (CRC table), " +
                        "config.txt, dmesg.txt & applog.txt into a ZIP — you just hit send.",
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
                if (stepText.isNotBlank()) {
                    Text(
                        text = stepText,
                        style = MonoTiny,
                        color = AccentAmber,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF25D366))
                            .clickable(enabled = !preparing) {
                                scope.launch {
                                    preparing = true
                                    stepText = "Collecting device info..."
                                    try {
                                        val pack = withContext(Dispatchers.IO) {
                                            ReportPackBuilder.buildPack(
                                                context = context,
                                                rootAvailable = RootChecker.isRootAvailable(),
                                                loadedModule = loadedModule,
                                                bundleTried = viewModel.lastBundleTried.value,
                                                matchStatus = matchStatus,
                                                resultText = resultText,
                                                logText = getLog(),
                                                onStep = { stepText = it }
                                            )
                                        }
                                        val sent = pack.zipFile?.let {
                                            SupportContact.sendZipViaWhatsApp(context, it, pack.caption)
                                        } ?: false

                                        wipeOnResume = true
                                        if (!sent) {
                                            context.startActivity(
                                                Intent(
                                                    Intent.ACTION_VIEW,

                                                    Uri.parse(
                                                        SupportContact.waLinkReport(
                                                            "${pack.caption}\n\n${pack.fullText.take(1300)}"
                                                        )
                                                    )
                                                )
                                            )
                                            Toast.makeText(
                                                context,
                                                "ZIP share unavailable — text report opened instead",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    } catch (e: Exception) {
                                        Toast.makeText(
                                            context,
                                            "Report failed: ${e.message}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } finally {
                                        preparing = false
                                        stepText = ""
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_whatsapp),
                            contentDescription = "Send report ZIP on WhatsApp",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "WhatsApp",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF24A1DE))
                            .clickable(enabled = !preparing) {
                                scope.launch {
                                    preparing = true
                                    stepText = "Collecting device info..."
                                    try {
                                        val pack = withContext(Dispatchers.IO) {
                                            ReportPackBuilder.buildPack(
                                                context = context,
                                                rootAvailable = RootChecker.isRootAvailable(),
                                                loadedModule = loadedModule,
                                                bundleTried = viewModel.lastBundleTried.value,
                                                matchStatus = matchStatus,
                                                resultText = resultText,
                                                logText = getLog(),
                                                onStep = { stepText = it }
                                            )
                                        }
                                        clipboard.setText(AnnotatedString(SupportContact.TELEGRAM_HANDLE))
                                        val sent = pack.zipFile?.let {
                                            SupportContact.sendZipViaTelegram(context, it, pack.caption)
                                        } ?: false
                                        wipeOnResume = true
                                        if (!sent) {
                                            context.startActivity(
                                                Intent(
                                                    Intent.ACTION_VIEW,
                                                    Uri.parse(SupportContact.telegramDirectUrl())
                                                )
                                            )
                                            Toast.makeText(
                                                context,
                                                "Telegram opened (@Jubairdadubm copied)",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        } else {
                                            Toast.makeText(
                                                context,
                                                "Sending report to @Jubairdadubm",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    } catch (e: Exception) {
                                        Toast.makeText(
                                            context,
                                            "Report failed: ${e.message}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } finally {
                                        preparing = false
                                        stepText = ""
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_telegram),
                            contentDescription = "Send report ZIP on Telegram (@Jubairdadubm)",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Telegram",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            TextButton(
                onClick = {
                    if (preparing) return@TextButton
                    scope.launch {
                        preparing = true
                        try {
                            val report = withContext(Dispatchers.IO) { collectReport() }
                            val text = buildReportText(report)
                            clipboard.setText(AnnotatedString(text))
                            Toast.makeText(context, "Full build report copied", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(
                                context,
                                "Report failed: ${e.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        } finally {
                            preparing = false
                            stepText = ""
                        }
                    }
                },
                enabled = !preparing
            ) {
                Text(
                    text = if (preparing) "COLLECTING REPORT..." else "COPY FULL REPORT",
                    style = MonoTiny,
                    color = TextSecondary,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "ZIP goes to dev via WhatsApp or Telegram (@Jubairdadubm) — everything needed to build your .ko is inside",
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
    busy: Boolean,
    onUnload: () -> Unit
) {
    if (visible <= 0.001f) return

    val hot = enabled || busy
    Column(
        modifier = Modifier.alpha(visible),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        RingButton(
            accentStart = if (hot) GradientRedStart else TextDisabled,
            accentEnd = if (hot) GradientRedEnd else TextDisabled,
            busy = busy,
            enabled = enabled,
            animated = true,
            onClick = onUnload
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    color = GradientRedStart,
                    strokeWidth = 2.5.dp
                )
            } else {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    tint = if (enabled) GradientRedStart else TextDisabled,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.height(5.dp))
            Text(
                text = if (busy) "REBOOTING" else "RESTART",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (hot) TextPrimary else TextDisabled
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = when {
                busy -> "the phone restarts now - the driver goes with it"
                moduleName.isNotBlank() -> "reboots to unload $moduleName"
                else -> "reboots to unload the driver"
            },
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
