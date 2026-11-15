package com.kernelloader.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    val clipboard = LocalClipboardManager.current
    val scroll = rememberScrollState()

    var rootAvailable by remember { mutableStateOf(false) }
    var kernelVersion by remember { mutableStateOf("") }
    var kernelRelease by remember { mutableStateOf("") }
    val deviceVermagic = remember { mutableStateOf("") }

    var showSplash by remember { mutableStateOf(!SplashTracker.shown) }
    var showLanguageDialog by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    val screenScope = rememberCoroutineScope()

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshDriverState(context)
                screenScope.launch(Dispatchers.IO) {
                    val root = RootChecker.isRootAvailable()
                    val kVer = RootChecker.getKernelVersion()
                    val kRel = RootChecker.getKernelRelease() ?: kVer
                    val kVm = RootChecker.getKernelVermagic()
                    withContext(Dispatchers.Main) {
                        rootAvailable = root
                        if (kVer.isNotBlank() && !kVer.startsWith("Error")) kernelVersion = kVer
                        if (kRel.isNotBlank()) kernelRelease = kRel
                        if (kVm.isNotBlank()) deviceVermagic.value = kVm
                    }
                }
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

    if (!HomeScreenReportWipe.done) {
        HomeScreenReportWipe.done = true
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) { ReportPackBuilder.wipeReportDir(context) }
        }
    }

    LaunchedEffect(Unit) {
        IpLanguageManager.init(context)
        viewModel.initLogStore(context)
        viewModel.refreshDriverState(context)
        launch(Dispatchers.IO) {
            delay(250L)
            val root = RootChecker.isRootAvailable()
            val kVer = RootChecker.getKernelVersion()
            val kRel = RootChecker.getKernelRelease() ?: kVer
            val kVm = RootChecker.getKernelVermagic()
            withContext(Dispatchers.Main) {
                rootAvailable = root
                if (kVer.isNotBlank() && !kVer.startsWith("Error")) kernelVersion = kVer
                if (kRel.isNotBlank()) kernelRelease = kRel
                if (kVm.isNotBlank()) deviceVermagic.value = kVm
            }
            viewModel.runStartupChecksOnce(context)
        }
    }

    LaunchedEffect(showSplash) {
        if (showSplash) {
            delay(1800L)
            SplashTracker.shown = true
            showSplash = false
        }
    }

    val s = IpLanguageManager.strings
    val shortVermagic = remember(deviceVermagic.value, kernelRelease, kernelVersion) {
        RootChecker.getShortVermagic(
            vermagic = deviceVermagic.value,
            kernelRelease = kernelRelease.ifBlank { kernelVersion }
        )
    }

    val exact = remember(manifest, kernelRelease, deviceVermagic.value) {
        manifest?.let {
            OtaDriverStore.exactFor(it, kernelRelease, "", deviceVermagic.value)
        }
    }

    val supportedDrivers = remember(manifest, kernelRelease, deviceVermagic.value) {
        manifest?.let {
            OtaDriverStore.supportedEntries(it, "")
        }?.let { list ->
            val (mine, others) = list.partition {
                OtaDriverStore.isExactMatch(it, kernelRelease, deviceVermagic.value)
            }
            mine + others
        } ?: emptyList()
    }

    Crossfade(
        targetState = showSplash,
        animationSpec = tween(350),
        label = "splashCrossfade"
    ) { isSplash ->
        if (isSplash) {
            AnimatedSplashOverlay(
                strings = s,
                version = "v${BuildConfig.VERSION_NAME}"
            )
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                AppBackground {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 580.dp)
                                .statusBarsPadding()
                                .navigationBarsPadding()
                                .verticalScroll(scroll)
                                .padding(horizontal = 14.dp)
                        ) {
                Spacer(Modifier.height(10.dp))

                PremiumHomeHeader(
                    strings = s,
                    version = "v${BuildConfig.VERSION_NAME}",
                    busy = busy,
                    onSelectCountryLang = { IpLanguageManager.selectCountryLanguage(context) },
                    onSelectEnglish = { IpLanguageManager.selectEnglish(context) },
                    onOpenLanguageDialog = { showLanguageDialog = true }
                )

                UpdateBannerSection(
                    updateStatus = updateStatus,
                    updateProgress = updateProgress,
                    appUpdate = appUpdate,
                    onInstall = { viewModel.installAppUpdate(context) }
                )

                Spacer(Modifier.height(10.dp))

                StatusBoxesSection(
                    strings = s,
                    rootAvailable = rootAvailable,
                    shortVermagic = shortVermagic,
                    exactMatch = exact,
                    manifestStatus = manifestStatus,
                    onCopyVermagic = {
                        clipboard.setText(AnnotatedString(shortVermagic))
                        Toast.makeText(context, "Vermagic copied: $shortVermagic", Toast.LENGTH_SHORT).show()
                    },
                    onRefreshDb = {
                        viewModel.refreshManifest(forceRefresh = true)
                        viewModel.checkForAppUpdate(manual = true)
                    },
                    onConsole = onNavigateToConsole,
                    onCredits = onNavigateToCredits
                )

                Spacer(Modifier.height(14.dp))

                val toUnload by animateFloatAsState(
                    targetValue = if (loaded) 1f else 0f,
                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                    label = "swapLoadUnload"
                )
                val loadsTapable = !loaded && toUnload < 0.5f
                val running = busy

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(1f - toUnload),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        RingButton(
                            accentStart = if (running) GradientAmberStart else LogoPink,
                            accentMid = if (running) AccentAmber else LogoMagenta,
                            accentEnd = if (running) GradientAmberEnd else LogoPurple,
                            busy = running,
                            enabled = loadsTapable && !busy && rootAvailable,
                            animated = true,
                            onClick = {
                                viewModel.autoLoadUniversal(context, preferOta = true, variant = "")
                            }
                        ) {
                            if (running) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(30.dp),
                                    color = Color.White,
                                    strokeWidth = 2.8.dp
                                )
                            } else {
                                Icon(
                                    Icons.Default.Bolt,
                                    contentDescription = null,
                                    tint = if (loadsTapable && !busy && rootAvailable) Color.White else TextDisabled,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = if (running) s.loadingBtn else s.loadBtn,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.4.sp,
                                color = if (running || (loadsTapable && !busy && rootAvailable)) Color.White else TextDisabled
                            )
                        }
                        if (running) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = busyStep.ifBlank { s.loadingBtn },
                                style = MonoTiny,
                                color = AccentAmber,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            StopLoadButton(label = s.stopBtn, onStop = { viewModel.stopLoad() })
                        }
                    }

                    UnloadPanel(
                        strings = s,
                        visible = toUnload,
                        moduleName = loadedModule,
                        enabled = loaded && !busy && rootAvailable,
                        busy = rebooting,
                        onUnload = { viewModel.rebootDevice(context) }
                    )
                }

                Spacer(Modifier.height(12.dp))

                DriverStatusSummaryCard(
                    strings = s,
                    loaded = loaded,
                    hidden = hidden,
                    loadedModule = loadedModule,
                    busy = busy,
                    rootAvailable = rootAvailable,
                    verifyStatus = viewModel.verifyStatus.value,
                    verifyDetail = viewModel.verifyDetail.value,
                    onVerify = { viewModel.verifyModule() }
                )

                Spacer(Modifier.height(10.dp))

                KernelAuditCard(
                    strings = s,
                    busy = busy,
                    rootAvailable = rootAvailable,
                    status = viewModel.kernelCheckStatus.value,
                    issuesCount = viewModel.kernelCheckIssuesCount.value,
                    hasBlockers = viewModel.kernelCheckHasBlockers.value,
                    onCheckKernel = { viewModel.checkKernelCompatibility() }
                )

                Spacer(Modifier.height(10.dp))

                CustomScriptLoadCard(
                    strings = s,
                    pickedFile = viewModel.pickedFileName.value,
                    busy = busy,
                    rootAvailable = rootAvailable,
                    onPickFile = onPickFile,
                    onRunPicked = { viewModel.loadPickedFile(context) },
                    onClearPicked = { viewModel.clearPickedFile() }
                )

                Spacer(Modifier.height(14.dp))

                VariantConsoleSection(
                    title = s.statusTerminalTitle,
                    accent = LogoPink,
                    lines = terminalLines,
                    emptyHint = s.statusTerminalHint,
                    height = 350.dp,
                    onOpenFullConsole = onNavigateToConsole,
                    onClear = { viewModel.clearTerminal() }
                )

                Spacer(Modifier.height(14.dp))

                KernelListSection(
                    title = "${s.supportedKernelTerminalTitle} (${supportedDrivers.size})",
                    subtitle = s.supportedKernelTerminalSub,
                    thisDeviceBadge = s.thisDeviceBadge,
                    scriptBadge = s.scriptBadge,
                    driverBadge = s.driverBadge,
                    accent = LogoMagenta,
                    entries = supportedDrivers,
                    status = manifestStatus,
                    kernelRelease = kernelRelease,
                    deviceVermagic = deviceVermagic.value
                )

                Spacer(Modifier.height(14.dp))

                SupportCard(
                    strings = s,
                    viewModel = viewModel,
                    loadFailed = viewModel.autoLoadOk.value == false,
                    hasExact = exact != null,
                    shortVermagic = shortVermagic,
                    appVersion = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    resultText = viewModel.lastLoadError.value.ifBlank {
                        viewModel.autoLoadStatus.value.ifBlank { "load failed / no exact loader" }
                    },
                    loadedModule = loadedModule,
                    getLog = { viewModel.getTerminalText() }
                )

                Spacer(Modifier.height(18.dp))
            }
        }
    }
}
}
}

    val errorInfo = viewModel.loadErrorDialogData.value
    if (errorInfo != null) {
        DriverErrorDialog(
            info = errorInfo,
            onDismiss = { viewModel.dismissErrorDialog() },
            onCopyLog = { viewModel.copyTerminalToClipboard(context) }
        )
    }

    if (showLanguageDialog) {
        LanguageSelectionDialog(
            strings = s,
            onDismiss = { showLanguageDialog = false },
            onSelectLanguage = { code ->
                IpLanguageManager.selectLanguage(context, code)
                showLanguageDialog = false
            },
            onSelectAutoIp = {
                IpLanguageManager.selectAutoIp(context)
                showLanguageDialog = false
            }
        )
    }
}

@Composable
private fun AnimatedSplashOverlay(
    strings: UiStrings,
    version: String
) {
    val transition = rememberInfiniteTransition(label = "splashInfinite")
    val spinOuter by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = LinearEasing)
        ),
        label = "splashSpinOuter"
    )
    val spinInner by transition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(4600, easing = LinearEasing)
        ),
        label = "splashSpinInner"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "splashPulse"
    )

    val bgGradient = remember {
        Brush.verticalGradient(
            0.0f to SurfaceDeep,
            0.5f to Surface1,
            1.0f to SurfaceDeep
        )
    }
    val bgGlowBrush = remember {
        Brush.radialGradient(
            colors = listOf(
                LogoPink.copy(alpha = 0.22f),
                LogoMagenta.copy(alpha = 0.10f),
                Color.Transparent
            )
        )
    }
    val outerRingBrush = remember {
        Brush.sweepGradient(
            listOf(LogoPink, LogoMagenta, LogoPurple, Color.Transparent, LogoPink)
        )
    }
    val innerRingBrush = remember {
        Brush.sweepGradient(
            listOf(LogoPurple, LogoPink, Color.Transparent, LogoPurple)
        )
    }
    val logoBorderBrush = remember {
        Brush.linearGradient(listOf(LogoPink, LogoMagenta, LogoPurple))
    }
    val titleBrush = remember {
        Brush.linearGradient(
            listOf(Color.White, LogoPink, LogoMagenta, LogoPurple)
        )
    }
    val tagBorderBrush = remember {
        Brush.horizontalGradient(listOf(LogoPink, LogoPurple))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgGradient),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = pulse
                }
        ) {
            drawCircle(
                brush = bgGlowBrush,
                center = center,
                radius = size.minDimension * 0.55f
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Box(
                modifier = Modifier.size(152.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { rotationZ = spinOuter }
                ) {
                    val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                    val glow = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
                    val d = size.minDimension - glow.width
                    val tl = Offset((size.width - d) / 2f, (size.height - d) / 2f)
                    val arcSz = Size(d, d)
                    drawArc(
                        brush = outerRingBrush,
                        startAngle = 0f,
                        sweepAngle = 260f,
                        useCenter = false,
                        topLeft = tl,
                        size = arcSz,
                        style = glow,
                        alpha = 0.28f
                    )
                    drawArc(
                        brush = outerRingBrush,
                        startAngle = 0f,
                        sweepAngle = 260f,
                        useCenter = false,
                        topLeft = tl,
                        size = arcSz,
                        style = stroke
                    )
                }

                Canvas(
                    modifier = Modifier
                        .size(126.dp)
                        .graphicsLayer { rotationZ = spinInner }
                ) {
                    val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    val d = size.minDimension - stroke.width
                    val tl = Offset((size.width - d) / 2f, (size.height - d) / 2f)
                    drawArc(
                        brush = innerRingBrush,
                        startAngle = 40f,
                        sweepAngle = 210f,
                        useCenter = false,
                        topLeft = tl,
                        size = Size(d, d),
                        style = stroke,
                        alpha = 0.85f
                    )
                }

                Image(
                    painter = painterResource(id = R.drawable.app_logo),
                    contentDescription = "Kernel Loader Logo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(102.dp)
                        .graphicsLayer {
                            scaleX = pulse
                            scaleY = pulse
                        }
                        .clip(CircleShape)
                        .border(
                            2.dp,
                            logoBorderBrush,
                            CircleShape
                        )
                )
            }

            Spacer(Modifier.height(22.dp))

            Text(
                text = strings.appName,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp,
                    brush = titleBrush
                )
            )

            Spacer(Modifier.height(6.dp))

            Surface(
                shape = RoundedCornerShape(50),
                color = LogoPink.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, tagBorderBrush)
            ) {
                Text(
                    text = "$version · ${strings.subtitle}",
                    style = MonoTiny,
                    color = LogoSoftPink,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                )
            }

            Spacer(Modifier.height(26.dp))

            LinearProgressIndicator(
                modifier = Modifier
                    .width(180.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(50)),
                color = LogoPink,
                trackColor = Surface2
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = strings.splashInitializing,
                style = MonoTiny,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun PremiumHomeHeader(
    strings: UiStrings,
    version: String,
    busy: Boolean,
    onSelectCountryLang: () -> Unit,
    onSelectEnglish: () -> Unit,
    onOpenLanguageDialog: () -> Unit
) {
    val countryFlag = IpLanguageManager.detectedCountryFlag()
    val isEnActive = IpLanguageManager.activeLangCode == "en"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1.copy(alpha = 0.94f)),
        border = BorderStroke(1.dp, BorderSubtle.copy(alpha = 0.55f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = strings.appName,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 20.sp,
                            letterSpacing = 0.4.sp
                        ),
                        color = Color.White,
                        maxLines = 1
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = LogoPink.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, LogoPink.copy(alpha = 0.45f))
                    ) {
                        Text(
                            text = version,
                            style = MonoTiny.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            ),
                            color = LogoSoftPink,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = strings.subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 10.5.sp
                    ),
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(10.dp))

            Surface(
                shape = RoundedCornerShape(50),
                color = SurfaceInset,
                border = BorderStroke(1.dp, BorderSubtle.copy(alpha = 0.6f))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(
                                if (!isEnActive) Brush.linearGradient(listOf(LogoPink.copy(alpha = 0.7f), LogoPurple.copy(alpha = 0.7f)))
                                else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                            )
                            .clickable(onClick = onSelectCountryLang)
                            .padding(horizontal = 7.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = countryFlag,
                            fontSize = 14.sp
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(
                                if (isEnActive) Brush.linearGradient(listOf(LogoPink.copy(alpha = 0.7f), LogoPurple.copy(alpha = 0.7f)))
                                else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                            )
                            .clickable(onClick = onSelectEnglish)
                            .padding(horizontal = 7.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "🇺🇸",
                            fontSize = 14.sp
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.Transparent)
                            .clickable(onClick = onOpenLanguageDialog)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "🌐",
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimatedHeaderLogo(busy: Boolean) {
    Box(
        modifier = Modifier.size(48.dp),
        contentAlignment = Alignment.Center
    ) {
        val transition = rememberInfiniteTransition(label = "headerLogoAnim")
        val spinCw by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(if (busy) 1100 else 5500, easing = LinearEasing)
            ),
            label = "headerSpinCw"
        )
        val spinCcw by transition.animateFloat(
            initialValue = 360f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(if (busy) 1600 else 8000, easing = LinearEasing)
            ),
            label = "headerSpinCcw"
        )
        val breathe by transition.animateFloat(
            initialValue = 0.93f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(
                animation = tween(if (busy) 700 else 1900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "headerBreathe"
        )

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = spinCw }
        ) {
            val stroke = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
            val glow = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
            val diameter = size.minDimension - stroke.width
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)
            val brush = Brush.sweepGradient(
                listOf(LogoPink, LogoMagenta, LogoPurple, Color.Transparent, LogoPink)
            )
            drawArc(
                brush = brush,
                startAngle = 0f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = glow,
                alpha = 0.25f
            )
            drawArc(
                brush = brush,
                startAngle = 0f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
                alpha = 0.95f
            )
        }

        Canvas(
            modifier = Modifier
                .size(41.dp)
                .graphicsLayer { rotationZ = spinCcw }
        ) {
            val stroke = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round)
            val diameter = size.minDimension - stroke.width
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            drawArc(
                brush = Brush.sweepGradient(listOf(LogoPurple, LogoPink, Color.Transparent)),
                startAngle = 90f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = topLeft,
                size = Size(diameter, diameter),
                style = stroke,
                alpha = 0.75f
            )
        }

        Image(
            painter = painterResource(id = R.drawable.app_logo),
            contentDescription = "Animated Kernel Loader Logo",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(34.dp)
                .graphicsLayer {
                    scaleX = breathe
                    scaleY = breathe
                }
                .clip(CircleShape)
                .border(
                    1.5.dp,
                    Brush.linearGradient(listOf(LogoPink, LogoPurple)),
                    CircleShape
                )
        )
    }
}

@Composable
private fun StatusBoxesSection(
    strings: UiStrings,
    rootAvailable: Boolean,
    shortVermagic: String,
    exactMatch: OtaDriverStore.DriverEntry?,
    manifestStatus: String,
    onCopyVermagic: () -> Unit,
    onRefreshDb: () -> Unit,
    onConsole: () -> Unit,
    onCredits: () -> Unit
) {
    val isSupported = exactMatch != null
    val supportMainText = when {
        isSupported -> "✓ ${strings.supportedYes}"
        manifestStatus == "LOADING" -> strings.supportedChecking
        else -> "✕ ${strings.supportedNo}"
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                InfoStatusBox(
                    icon = if (rootAvailable) Icons.Default.VerifiedUser else Icons.Default.Warning,
                    title = strings.rootTitle,
                    mainValue = if (rootAvailable) "✓ ${strings.rootGranted}" else "✕ ${strings.rootDenied}",
                    accentColor = if (rootAvailable) StatusOk else AccentRed,
                    isPositive = rootAvailable
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                InfoStatusBox(
                    icon = if (isSupported) Icons.Default.CheckCircle else Icons.Default.Storage,
                    title = strings.supportedTitle,
                    mainValue = supportMainText,
                    accentColor = if (isSupported) StatusOk else AccentRed,
                    isPositive = isSupported,
                    trailingAction = {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(Color(0xFF8B5CF6).copy(alpha = 0.20f))
                                .border(1.dp, Color(0xFF8B5CF6).copy(alpha = 0.45f), RoundedCornerShape(7.dp))
                                .clickable(onClick = onRefreshDb),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                tint = Color(0xFFC4B5FD),
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Surface1.copy(alpha = 0.94f)),
            border = BorderStroke(1.dp, BorderSubtle.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                LogoPink.copy(alpha = 0.08f),
                                LogoPurple.copy(alpha = 0.06f)
                            )
                        )
                    )
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(LogoPink.copy(alpha = 0.16f))
                        .border(1.dp, LogoPink.copy(alpha = 0.45f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = strings.vermagicTitle,
                        tint = LogoPink,
                        modifier = Modifier.size(19.dp)
                    )
                }

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.vermagicTitle,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            letterSpacing = 0.5.sp
                        ),
                        color = LogoSoftPink
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = shortVermagic.ifBlank { strings.vermagicDetecting },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp
                        ),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.width(6.dp))

                MiniAction(
                    icon = Icons.Default.ContentCopy,
                    description = "Copy Vermagic",
                    tint = Color(0xFFC4B5FD),
                    gradientColors = listOf(Color(0xFF3B1F6E), Color(0xFF28154D)),
                    borderTint = Color(0xFF8B5CF6).copy(alpha = 0.6f),
                    onClick = onCopyVermagic
                )
                Spacer(Modifier.width(5.dp))
                MiniAction(
                    icon = Icons.Default.Terminal,
                    description = "Open Console",
                    tint = LogoSoftPink,
                    gradientColors = listOf(Color(0xFF4A1B66), Color(0xFF2E124A)),
                    borderTint = LogoPink.copy(alpha = 0.6f),
                    onClick = onConsole
                )
                Spacer(Modifier.width(5.dp))
                MiniAction(
                    icon = Icons.Default.Info,
                    description = "Credits",
                    tint = Color(0xFF67E8F9),
                    gradientColors = listOf(Color(0xFF213163), Color(0xFF1A2049)),
                    borderTint = Color(0xFF38BDF8).copy(alpha = 0.6f),
                    onClick = onCredits
                )
            }
        }
    }
}

@Composable
private fun InfoStatusBox(
    icon: ImageVector,
    title: String,
    mainValue: String,
    accentColor: Color,
    isPositive: Boolean,
    trailingAction: (@Composable () -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1.copy(alpha = 0.95f)),
        border = BorderStroke(
            1.dp,
            if (isPositive) StatusOk.copy(alpha = 0.40f) else AccentRed.copy(alpha = 0.40f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            accentColor.copy(alpha = if (isPositive) 0.10f else 0.12f),
                            Surface1.copy(alpha = 0.95f)
                        )
                    )
                )
                .padding(horizontal = 11.dp, vertical = 9.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(accentColor.copy(alpha = 0.18f))
                        .border(1.dp, accentColor.copy(alpha = 0.45f), RoundedCornerShape(7.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = accentColor,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Spacer(Modifier.width(7.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.5.sp,
                        letterSpacing = 0.3.sp
                    ),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (trailingAction != null) {
                    trailingAction()
                } else {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = accentColor.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = mainValue,
                    style = MonoTiny.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp
                    ),
                    color = accentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp)
                )
            }
        }
    }
}

@Composable
private fun PremiumActionButton(
    text: String,
    icon: ImageVector,
    enabled: Boolean,
    loading: Boolean = false,
    gradientColors: List<Color>,
    borderColors: List<Color>,
    iconTint: Color = Color.White,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(
                if (enabled) Brush.horizontalGradient(gradientColors)
                else Brush.horizontalGradient(listOf(Surface2, Surface3.copy(alpha = 0.65f)))
            )
            .border(
                1.1.dp,
                if (enabled) Brush.horizontalGradient(borderColors)
                else Brush.horizontalGradient(listOf(BorderSubtle, BorderSubtle)),
                RoundedCornerShape(13.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 11.dp, horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (enabled) Color.Black.copy(alpha = 0.24f) else Surface3.copy(alpha = 0.45f))
                    .border(
                        1.dp,
                        if (enabled) iconTint.copy(alpha = 0.42f) else BorderSubtle,
                        RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = iconTint,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (enabled) iconTint else TextDisabled,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.ExtraBold,
                color = if (enabled) Color.White else TextDisabled,
                letterSpacing = 0.7.sp
            )
        }
    }
}

@Composable
private fun DriverStatusSummaryCard(
    strings: UiStrings,
    loaded: Boolean,
    hidden: Boolean,
    loadedModule: String,
    busy: Boolean,
    rootAvailable: Boolean,
    verifyStatus: String,
    verifyDetail: String,
    onVerify: () -> Unit
) {
    val activeDisplay = loaded || verifyStatus == "PASS"
    val stateColor = if (activeDisplay) StatusOk else AccentRed
    val isChecking = verifyStatus == "CHECKING"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1),
        border = BorderStroke(
            1.dp,
            if (activeDisplay) StatusOk.copy(alpha = 0.45f)
            else BorderSubtle.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (activeDisplay) StatusOk.copy(alpha = 0.08f)
                    else Surface1
                )
                .padding(horizontal = 14.dp, vertical = 11.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusDot(loaded = activeDisplay)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (activeDisplay) {
                            if (hidden) strings.driverStatusHidden
                            else "${strings.driverStatusLoaded} · $loadedModule"
                        } else strings.driverStatusNotLoaded,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.5.sp
                        ),
                        color = if (activeDisplay) StatusOk else TextPrimary
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = if (activeDisplay) strings.driverStatusLoadedHint else strings.driverStatusNotLoadedHint,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (activeDisplay) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = StatusOk,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            if (verifyStatus == "PASS" || verifyStatus == "FAIL") {
                val pass = verifyStatus == "PASS"
                val vColor = if (pass) Color(0xFF00E676) else Color(0xFFFF2E54)
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = vColor.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, vColor.copy(alpha = 0.55f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (pass) Icons.Default.CheckCircle else Icons.Default.Close,
                            contentDescription = null,
                            tint = vColor,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (pass) {
                                if (verifyDetail.isNotBlank()) "${strings.verifyPassText} ($verifyDetail)"
                                else strings.verifyPassText
                            } else {
                                if (verifyDetail.isNotBlank() && verifyDetail != "NOT LOADED") "${strings.verifyFailText} ($verifyDetail)"
                                else strings.verifyFailText
                            },
                            style = MonoTiny,
                            fontWeight = FontWeight.ExtraBold,
                            color = vColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (activeDisplay) {
                Spacer(Modifier.height(10.dp))
                PremiumActionButton(
                    text = if (isChecking) strings.verifyingBtn else strings.verifyBtn,
                    icon = Icons.Default.VerifiedUser,
                    enabled = !busy && rootAvailable,
                    loading = isChecking,
                    gradientColors = listOf(Color(0xFF0B5345), Color(0xFF0E6655), Color(0xFF154360)),
                    borderColors = listOf(Color(0xFF2DD4BF).copy(alpha = 0.65f), Color(0xFF38BDF8).copy(alpha = 0.60f)),
                    iconTint = Color(0xFF5EEAD4),
                    onClick = onVerify
                )
            }
        }
    }
}

@Composable
private fun KernelAuditCard(
    strings: UiStrings,
    busy: Boolean,
    rootAvailable: Boolean,
    status: String,
    issuesCount: Int,
    hasBlockers: Boolean,
    onCheckKernel: () -> Unit
) {
    val isScanning = status == "SCANNING"
    val isDone = status == "DONE"
    val cardBorder = if (isDone) {
        if (hasBlockers) AccentRed.copy(alpha = 0.55f)
        else StatusOk.copy(alpha = 0.45f)
    } else {
        Color(0xFF0284C7).copy(alpha = 0.45f)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1),
        border = BorderStroke(1.dp, cardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xFF0284C7).copy(alpha = 0.07f),
                            LogoPurple.copy(alpha = 0.05f)
                        )
                    )
                )
                .padding(horizontal = 14.dp, vertical = 11.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0284C7).copy(alpha = 0.16f))
                        .border(1.dp, Color(0xFF0284C7).copy(alpha = 0.45f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Security,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(19.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = strings.checkKernelTitle,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp
                        ),
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = strings.checkKernelSub,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (isDone) {
                Spacer(Modifier.height(8.dp))
                val bannerColor = if (hasBlockers) AccentRed else StatusOk
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = bannerColor.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, bannerColor.copy(alpha = 0.55f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (hasBlockers) Icons.Default.Warning else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = bannerColor,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (hasBlockers) "${strings.checkKernelIssuesBanner} ($issuesCount)"
                            else strings.checkKernelPassBanner,
                            style = MonoTiny,
                            fontWeight = FontWeight.ExtraBold,
                            color = bannerColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            PremiumActionButton(
                text = if (isScanning) strings.checkKernelCheckingBtn else strings.checkKernelBtn,
                icon = Icons.Default.Bolt,
                enabled = !busy && rootAvailable,
                loading = isScanning,
                gradientColors = listOf(Color(0xFF0369A1), Color(0xFF0284C7), Color(0xFF4338CA)),
                borderColors = listOf(Color(0xFF38BDF8).copy(alpha = 0.70f), LogoPurple.copy(alpha = 0.65f)),
                iconTint = Color(0xFFBAE6FD),
                onClick = onCheckKernel
            )
        }
    }
}

@Composable
private fun CustomScriptLoadCard(
    strings: UiStrings,
    pickedFile: String?,
    busy: Boolean,
    rootAvailable: Boolean,
    onPickFile: () -> Unit,
    onRunPicked: () -> Unit,
    onClearPicked: () -> Unit
) {
    val hasFile = !pickedFile.isNullOrBlank()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1),
        border = BorderStroke(1.dp, LogoPurple.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            LogoPink.copy(alpha = 0.07f),
                            LogoPurple.copy(alpha = 0.05f)
                        )
                    )
                )
                .padding(horizontal = 14.dp, vertical = 11.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(LogoPink.copy(alpha = 0.16f))
                        .border(1.dp, LogoPink.copy(alpha = 0.45f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Code,
                        contentDescription = null,
                        tint = LogoPink,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = strings.customScriptTitle,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp
                        ),
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = if (hasFile) "${strings.customScriptSelected} $pickedFile"
                        else strings.customScriptSub,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = if (hasFile) StatusOk else TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            PremiumActionButton(
                text = strings.customScriptPickBtn,
                icon = Icons.Default.FolderOpen,
                enabled = !busy && rootAvailable,
                gradientColors = listOf(Color(0xFF6B1D6B), Color(0xFF88207A), Color(0xFF4C1D73)),
                borderColors = listOf(LogoPink.copy(alpha = 0.70f), LogoPurple.copy(alpha = 0.65f)),
                iconTint = LogoSoftPink,
                onClick = onPickFile
            )

            if (hasFile) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (!busy && rootAvailable) {
                                    Brush.horizontalGradient(listOf(Color(0xFF0E5E3E), Color(0xFF117A4B)))
                                } else {
                                    Brush.horizontalGradient(listOf(Surface2, Surface2))
                                }
                            )
                            .border(
                                1.1.dp,
                                if (!busy && rootAvailable) Color(0xFF34D399).copy(alpha = 0.68f) else BorderSubtle,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable(enabled = !busy && rootAvailable, onClick = onRunPicked)
                            .padding(vertical = 11.dp, horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = if (!busy && rootAvailable) Color(0xFF6EE7B7) else TextDisabled,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = strings.customScriptLoadBtn,
                                color = if (!busy && rootAvailable) Color.White else TextDisabled,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Surface2)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                            .clickable(enabled = !busy, onClick = onClearPicked)
                            .padding(vertical = 11.dp, horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Clear",
                            tint = Color(0xFFFDA4AF),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateBannerSection(
    updateStatus: String,
    updateProgress: Int,
    appUpdate: com.kernelloader.update.AppUpdateChecker.UpdateInfo?,
    onInstall: () -> Unit
) {
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
                    .padding(top = 10.dp)
                    .clickable(onClick = onInstall),
                colors = CardDefaults.cardColors(containerColor = Surface2),
                border = BorderStroke(1.dp, (if (isReady) StatusOk else AccentAmber).copy(alpha = glow))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (isReady) Icons.Default.CheckCircle else Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = if (isReady) StatusOk else AccentAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        val displayVer = if (appUpdate.versionName.startsWith("v", ignoreCase = true)) {
                            appUpdate.versionName
                        } else {
                            "v${appUpdate.versionName}"
                        }
                        Text(
                            text = if (isReady) "UPDATE $displayVer READY" else "UPDATE $displayVer AVAILABLE",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isReady) StatusOk else AccentAmber,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isReady) {
                                "Downloaded in background" +
                                    (if (appUpdate.apkSize > 0) " (${appUpdate.apkSize / 1024} KB)" else "") +
                                    " · tap to install"
                            } else {
                                "Download pending · tap to retry"
                            },
                            style = MonoTiny,
                            color = TextMuted
                        )
                    }
                    Text(
                        text = if (isReady) "INSTALL ↗" else "RETRY ⟳",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isReady) StatusOk else AccentAmber,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        updateStatus == "DOWNLOADING" -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                colors = CardDefaults.cardColors(containerColor = Surface2),
                border = BorderStroke(1.dp, LogoPink.copy(alpha = 0.5f))
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.SystemUpdate,
                            contentDescription = null,
                            tint = LogoPink,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "AUTO-DOWNLOADING v${appUpdate?.versionName ?: ""}... ${if (updateProgress >= 0) "$updateProgress%" else ""}",
                            style = MonoTiny,
                            color = LogoSoftPink,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { if (updateProgress >= 0) updateProgress / 100f else 0f },
                        modifier = Modifier.fillMaxWidth(),
                        color = LogoPink,
                        trackColor = Surface3
                    )
                }
            }
        }
    }
}

@Composable
private fun VariantConsoleSection(
    title: String,
    accent: Color,
    lines: List<TerminalLine>,
    emptyHint: String,
    height: Dp = 250.dp,
    onOpenFullConsole: (() -> Unit)? = null,
    onClear: (() -> Unit)? = null
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    SectionTitle(title = title, accent = accent) {
        if (onOpenFullConsole != null) {
            MiniAction(
                icon = Icons.Default.Terminal,
                description = "Full Console",
                tint = LogoSoftPink,
                gradientColors = listOf(Color(0xFF4A1B66), Color(0xFF2E124A)),
                borderTint = LogoPink,
                onClick = onOpenFullConsole
            )
            Spacer(Modifier.width(6.dp))
        }
        MiniAction(
            icon = Icons.Default.ContentCopy,
            description = "Copy $title",
            tint = Color(0xFFC4B5FD),
            gradientColors = listOf(Color(0xFF3B1F6E), Color(0xFF28154D)),
            borderTint = Color(0xFF8B5CF6)
        ) {
            clipboard.setText(
                AnnotatedString(lines.joinToString("\n") { it.text })
            )
            Toast.makeText(context, "Status terminal logs copied!", Toast.LENGTH_SHORT).show()
        }
        if (onClear != null) {
            Spacer(Modifier.width(6.dp))
            MiniAction(
                icon = Icons.Default.Delete,
                description = "Clear console",
                tint = Color(0xFFFDA4AF),
                gradientColors = listOf(Color(0xFF5E1730), Color(0xFF3E1022)),
                borderTint = Color(0xFFFB7185),
                onClick = onClear
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    VariantConsoleCard(
        lines = lines,
        accent = accent,
        emptyHint = emptyHint,
        height = height
    )
}

@Composable
private fun VariantConsoleCard(
    lines: List<TerminalLine>,
    accent: Color,
    emptyHint: String,
    height: Dp = 250.dp
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = lines.size.coerceAtLeast(0)
    )
    LaunchedEffect(lines.size, lines.lastOrNull()) {
        if (lines.isNotEmpty()) {
            listState.scrollToItem(lines.size)
        }
    }
    LaunchedEffect(Unit) {
        if (lines.isNotEmpty()) {
            listState.scrollToItem(lines.size)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceInset),
        border = BorderStroke(1.dp, BorderSubtle.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface1.copy(alpha = 0.9f))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFF5F56)))
                Spacer(Modifier.width(5.dp))
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFFBD2E)))
                Spacer(Modifier.width(5.dp))
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF27C93F)))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "root@kernel-loader:~# status_stream",
                    style = MonoTiny,
                    color = TextSecondary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${lines.size} lines",
                    style = MonoTiny,
                    color = LogoSoftPink
                )
            }
            HorizontalDivider(color = BorderSubtle.copy(alpha = 0.6f))

            if (lines.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(30.dp),
                        tint = LogoPink.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = emptyHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(lines) { line ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.5.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            val startsWithDash = line.text.startsWith("-") || line.text.startsWith("  -")
                            if (!startsWithDash) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 6.dp, end = 7.dp)
                                        .size(5.dp)
                                        .clip(CircleShape)
                                        .background(consoleLineColor(line.type))
                                )
                            }
                            Text(
                                text = line.text,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                ),
                                color = if (line.type == "OK" || line.type == "ERR" || line.type == "WARN" || line.type == "CMD" || line.type == "FIX") {
                                    consoleLineColor(line.type)
                                } else {
                                    TextPrimary
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    item {
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun KernelListSection(
    title: String,
    subtitle: String,
    thisDeviceBadge: String,
    scriptBadge: String,
    driverBadge: String,
    accent: Color,
    entries: List<OtaDriverStore.DriverEntry>,
    status: String,
    kernelRelease: String,
    deviceVermagic: String = ""
) {
    val scriptCount = entries.count { it.isScript() }
    val driverCount = entries.size - scriptCount

    SectionTitle(title = title, accent = accent) {
        if (status == "OK" && entries.isNotEmpty()) {
            Text(
                text = "${scriptCount} .sh · ${driverCount} .ko",
                style = MonoTiny,
                color = LogoSoftPink,
                fontWeight = FontWeight.Bold
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = TextMuted
    )
    Spacer(Modifier.height(8.dp))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceInset),
        border = BorderStroke(1.dp, BorderSubtle.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface1.copy(alpha = 0.9f))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(LogoPink))
                Spacer(Modifier.width(5.dp))
                Box(Modifier.size(8.dp).clip(CircleShape).background(LogoMagenta))
                Spacer(Modifier.width(5.dp))
                Box(Modifier.size(8.dp).clip(CircleShape).background(LogoPurple))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "root@kernel-loader:~# ls /db/drivers_and_scripts",
                    style = MonoTiny,
                    color = TextSecondary,
                    modifier = Modifier.weight(1f)
                )
            }
            HorizontalDivider(color = BorderSubtle.copy(alpha = 0.7f))

            when {
                status == "OK" && entries.isNotEmpty() -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        itemsIndexed(entries) { idx, e ->
                            val shortVm = e.shortVermagic()
                            val isThis = OtaDriverStore.isExactMatch(e, kernelRelease, deviceVermagic)
                            val isSh = e.isScript()

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isThis) StatusOk.copy(alpha = 0.12f)
                                        else Color.Transparent
                                    )
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "%02d.".format(idx + 1),
                                        style = MonoTiny,
                                        color = LogTimestamp,
                                        modifier = Modifier.width(26.dp)
                                    )
                                    Text(
                                        text = shortVm,
                                        style = MonoSmall.copy(fontWeight = FontWeight.Bold),
                                        color = if (isThis) StatusOk else TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    DeviceBadge(
                                        text = if (isSh) scriptBadge else driverBadge,
                                        color = if (isSh) LogoPink else LogoPurple
                                    )
                                    if (isThis) {
                                        Spacer(Modifier.width(5.dp))
                                        DeviceBadge(text = thisDeviceBadge, color = StatusOk)
                                    }
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 26.dp, top = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val metaParts = buildList {
                                        add("file: ${e.file}")
                                        if (e.model.isNotBlank()) add("device: ${e.model}")
                                        else if (e.device.isNotBlank()) add("device: ${e.device}")
                                    }.joinToString(" · ")
                                    Text(
                                        text = metaParts,
                                        style = MonoTiny,
                                        fontSize = 10.sp,
                                        color = TextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 2.dp),
                                color = BorderSubtle.copy(alpha = 0.45f)
                            )
                        }
                    }
                }
                status == "OK" -> Text(
                    text = "No kernels in the database yet",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                    color = TextMuted
                )
                status == "LOADING" -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = accent
                    )
                }
                else -> Text(
                    text = "Database status: $status — tap Refresh above.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                    color = AccentAmber
                )
            }
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
                    .graphicsLayer { alpha = glow }
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
                .background(LogoPink.copy(alpha = 0.7f))
        )
    }
}

@Composable
private fun SectionTitle(
    title: String,
    accent: Color = LogoPink,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(17.dp)
                .background(
                    Brush.verticalGradient(listOf(LogoPink, LogoPurple)),
                    RoundedCornerShape(2.dp)
                )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.ExtraBold,
            color = accent,
            letterSpacing = 1.1.sp,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

@Composable
private fun MiniAction(
    icon: ImageVector,
    description: String,
    tint: Color = LogoSoftPink,
    gradientColors: List<Color> = listOf(Color(0xFF3B1F6E), Color(0xFF28154D)),
    borderTint: Color = LogoPurple,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.linearGradient(gradientColors))
            .border(1.dp, borderTint.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(17.dp)
        )
    }
}

@Composable
private fun DeviceBadge(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = color,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun RingButton(
    accentStart: Color,
    accentMid: Color = LogoMagenta,
    accentEnd: Color,
    busy: Boolean,
    enabled: Boolean,
    animated: Boolean = true,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val transition = rememberInfiniteTransition(label = "ringTransition")
    val spinCw by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (busy) 850 else 4400, easing = LinearEasing)
        ),
        label = "ringSpinCw"
    )
    val spinCcw by transition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (busy) 1200 else 6200, easing = LinearEasing)
        ),
        label = "ringSpinCcw"
    )
    val breathe by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (busy) 900 else 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringBreathe"
    )
    val scaleSpec = if (busy) 1.05f else 1f
    val ringBrush = Brush.sweepGradient(
        listOf(accentStart, accentMid, accentEnd, accentStart.copy(alpha = 0.15f), accentStart)
    )
    val hot = enabled || busy

    Box(
        modifier = Modifier.size(164.dp),
        contentAlignment = Alignment.Center
    ) {

        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accentStart.copy(alpha = if (hot) 0.24f * breathe else 0.08f),
                        accentEnd.copy(alpha = if (hot) 0.10f else 0.03f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = size.minDimension / 2f
                ),
                center = center,
                radius = size.minDimension / 2f
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = if (animated || busy) spinCw else 0f }
        ) {
            val stroke = Stroke(width = 5.5.dp.toPx(), cap = StrokeCap.Round)
            val glow = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round)
            val diameter = size.minDimension - glow.width
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)
            drawArc(
                color = accentStart.copy(alpha = if (hot) 0.18f else 0.08f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke
            )
            if (hot) {
                drawArc(
                    brush = ringBrush,
                    startAngle = 0f,
                    sweepAngle = 145f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = glow,
                    alpha = 0.25f
                )
            }
            drawArc(
                brush = ringBrush,
                startAngle = 0f,
                sweepAngle = 145f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
                alpha = if (hot) 0.98f else 0.4f
            )
        }

        Canvas(
            modifier = Modifier
                .size(140.dp)
                .graphicsLayer { rotationZ = if (animated || busy) spinCcw else 0f }
        ) {
            val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
            val diameter = size.minDimension - stroke.width
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(accentEnd, accentStart, Color.Transparent, accentEnd)
                ),
                startAngle = 180f,
                sweepAngle = 130f,
                useCenter = false,
                topLeft = topLeft,
                size = Size(diameter, diameter),
                style = stroke,
                alpha = if (hot) 0.85f else 0.3f
            )
        }

        Button(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            border = BorderStroke(
                2.2.dp,
                if (hot) {
                    Brush.linearGradient(
                        listOf(accentStart, accentMid, accentEnd)
                    )
                } else {
                    Brush.linearGradient(listOf(BorderSubtle, BorderStrong))
                }
            ),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = TextPrimary,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = TextDisabled
            ),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
                .size(120.dp)
                .graphicsLayer {
                    val b = if (animated || busy) breathe else 1f
                    val s = scaleSpec * b
                    scaleX = s
                    scaleY = s
                }
                .clip(CircleShape)
                .background(
                    if (hot) {
                        Brush.radialGradient(
                            colors = listOf(
                                accentStart.copy(alpha = 0.72f),
                                accentMid.copy(alpha = 0.44f),
                                Color(0xFF280B47)
                            )
                        )
                    } else {
                        Brush.linearGradient(listOf(Surface1, Surface2))
                    }
                )
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                content = content
            )
        }
    }
}

@Composable
private fun SupportCard(
    strings: UiStrings,
    viewModel: DriverViewModel,
    loadFailed: Boolean,
    hasExact: Boolean,
    shortVermagic: String,
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
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (loadFailed) Surface2 else Surface1
        ),
        border = BorderStroke(
            1.dp,
            if (loadFailed) AccentRed.copy(alpha = 0.5f) else BorderSubtle.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            LogoPink.copy(alpha = 0.09f),
                            LogoPurple.copy(alpha = 0.06f),
                            Color.Transparent
                        )
                    )
                )
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(18.dp)
                        .background(
                            Brush.verticalGradient(listOf(LogoPink, LogoPurple)),
                            RoundedCornerShape(2.dp)
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = strings.reportSectionTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (loadFailed) AccentRed else LogoPink,
                    letterSpacing = 1.sp
                )
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = strings.reportSectionSub,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            Spacer(Modifier.height(10.dp))

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = SurfaceInset.copy(alpha = 0.85f),
                border = BorderStroke(1.dp, BorderSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = strings.reportHowToTitle,
                        style = MaterialTheme.typography.labelLarge,
                        color = LogoSoftPink,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = strings.reportStep1,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = strings.reportStep2,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = strings.reportStep3,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = BorderSubtle.copy(alpha = 0.6f))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "ZIP: ${shortVermagic.ifBlank { "device_dump" }}.zip · Telegram: ${SupportContact.TELEGRAM_HANDLE} · WhatsApp: ${SupportContact.WHATSAPP_DISPLAY}",
                        style = MonoTiny,
                        fontSize = 10.5.sp,
                        color = LogoSoftPink
                    )
                }
            }

            if (preparing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    color = LogoPink,
                    trackColor = Surface3
                )
                if (stepText.isNotBlank()) {
                    Text(
                        text = stepText,
                        style = MonoTiny,
                        color = LogoSoftPink,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF10497E), Color(0xFF165B9E))
                            )
                        )
                        .border(1.1.dp, Color(0xFF38BDF8).copy(alpha = 0.65f), RoundedCornerShape(14.dp))
                        .clickable(enabled = !preparing) {
                            scope.launch {
                                preparing = true
                                stepText = "Collecting device info..."
                                viewModel.tlog("Building report package (${shortVermagic.ifBlank { "device_dump" }}.zip)...", "CMD")
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
                                    viewModel.tlog("Report ZIP ready: ${pack.zipName}", "OK")
                                    clipboard.setText(AnnotatedString(SupportContact.TELEGRAM_HANDLE))
                                    val sent = pack.zipFile?.let {
                                        SupportContact.sendZipViaTelegram(context, it, pack.caption)
                                    } ?: false
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
                                            "Sending ${pack.zipName} to @Jubairdadubm",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                } catch (e: Exception) {
                                    viewModel.tlog("Report failed: ${e.message}", "ERR")
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
                        }
                        .padding(vertical = 12.dp, horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.24f))
                                .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.55f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_telegram),
                                contentDescription = "Telegram",
                                tint = Color(0xFF7DD3FC),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.Start) {
                            Text(
                                text = "Telegram",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                            Text(
                                text = SupportContact.TELEGRAM_HANDLE,
                                style = MonoTiny,
                                fontSize = 9.5.sp,
                                color = Color.White.copy(alpha = 0.88f)
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF0E5A38), Color(0xFF147245))
                            )
                        )
                        .border(1.1.dp, Color(0xFF4ADE80).copy(alpha = 0.65f), RoundedCornerShape(14.dp))
                        .clickable(enabled = !preparing) {
                            scope.launch {
                                preparing = true
                                stepText = "Collecting device info..."
                                viewModel.tlog("Building report package (${shortVermagic.ifBlank { "device_dump" }}.zip)...", "CMD")
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
                                    viewModel.tlog("Report ZIP ready: ${pack.zipName}", "OK")
                                    val sent = pack.zipFile?.let {
                                        SupportContact.sendZipViaWhatsApp(context, it, pack.caption)
                                    } ?: false
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
                                    viewModel.tlog("Report failed: ${e.message}", "ERR")
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
                        }
                        .padding(vertical = 12.dp, horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.24f))
                                .border(1.dp, Color(0xFF4ADE80).copy(alpha = 0.55f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_whatsapp),
                                contentDescription = "WhatsApp",
                                tint = Color(0xFF6EE7B7),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.Start) {
                            Text(
                                text = "WhatsApp",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                            Text(
                                text = SupportContact.WHATSAPP_DISPLAY,
                                style = MonoTiny,
                                fontSize = 9.5.sp,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (!preparing) {
                            Brush.horizontalGradient(
                                listOf(Color(0xFF3B1A66), Color(0xFF551F75))
                            )
                        } else {
                            Brush.horizontalGradient(listOf(Surface2, Surface3))
                        }
                    )
                    .border(
                        1.dp,
                        if (!preparing) LogoPurple.copy(alpha = 0.60f) else BorderSubtle,
                        RoundedCornerShape(12.dp)
                    )
                    .clickable(enabled = !preparing) {
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
                    }
                    .padding(vertical = 10.dp, horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = if (preparing) strings.collectingReport else strings.copyReportBtn,
                        style = MonoTiny,
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}

@Composable
private fun StopLoadButton(label: String, onStop: () -> Unit) {
    val blink by rememberInfiniteTransition(label = "stop").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "stopA"
    )
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color(0xFF881337).copy(alpha = 0.90f + 0.10f * blink),
                        Color(0xFFBE123C).copy(alpha = 0.90f + 0.10f * blink)
                    )
                )
            )
            .border(1.3.dp, Color(0xFFFB7185).copy(alpha = 0.45f + 0.50f * blink), RoundedCornerShape(50))
            .clickable(onClick = onStop)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 9.dp)
        ) {
            Icon(
                Icons.Default.Stop,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                letterSpacing = 1.4.sp
            )
        }
    }
}

@Composable
private fun UnloadPanel(
    strings: UiStrings,
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
            accentMid = if (hot) LogoPink else TextDisabled,
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
                    tint = if (enabled) Color.White else TextDisabled,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.height(5.dp))
            Text(
                text = if (busy) strings.rebootingBtn else strings.restartBtn,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (hot) TextPrimary else TextDisabled
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = when {
                busy -> "Restarting phone to unload kernel module..."
                moduleName.isNotBlank() -> "Reboots device to unload $moduleName"
                else -> "Reboots device to unload active driver"
            },
            style = MonoTiny,
            color = AccentRed,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
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

private object HomeScreenReportWipe {
    @Volatile var done: Boolean = false
}

private object SplashTracker {
    @Volatile var shown: Boolean = false
}

@Composable
private fun LanguageSelectionDialog(
    strings: UiStrings,
    onDismiss: () -> Unit,
    onSelectLanguage: (String) -> Unit,
    onSelectAutoIp: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.80f),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Surface1),
            border = BorderStroke(
                1.2.dp,
                Brush.linearGradient(listOf(LogoPink, LogoMagenta, LogoPurple))
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = "🌐", fontSize = 20.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = strings.languageDialogTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                val isAutoIp = IpLanguageManager.manualOverrideLang == null
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isAutoIp) LogoPurple.copy(alpha = 0.22f) else SurfaceInset,
                    border = BorderStroke(
                        1.dp,
                        if (isAutoIp) LogoPink.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.08f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onSelectAutoIp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "🌐", fontSize = 18.sp)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = strings.autoIpDetectOption,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "${IpLanguageManager.detectedCountryFlag()} ${IpLanguageManager.detectedCountryCode} (Auto)",
                                style = MonoTiny,
                                fontSize = 10.5.sp,
                                color = AccentAmber
                            )
                        }
                        if (isAutoIp) {
                            Text(text = "✓", color = StatusOk, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(IpLanguageManager.SupportedLanguages) { item ->
                        val isSelected = !isAutoIp && IpLanguageManager.activeLangCode == item.code
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) LogoPurple.copy(alpha = 0.24f) else SurfaceInset.copy(alpha = 0.7f),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) LogoPink.copy(alpha = 0.8f) else Color.Transparent
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectLanguage(item.code) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = item.flag, fontSize = 20.sp)
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.nativeName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color.White else TextPrimary
                                    )
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                }
                                if (isSelected) {
                                    Text(text = "✓", color = StatusOk, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
