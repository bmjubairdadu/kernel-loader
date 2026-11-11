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

    val lifecycleOwner = LocalLifecycleOwner.current
    val screenScope = rememberCoroutineScope()

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
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
                    viewModel.refreshDriverState(context)
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
    val sigPatchStatus = viewModel.sigPatchStatus.value

    if (!HomeScreenReportWipe.done) {
        HomeScreenReportWipe.done = true
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) { ReportPackBuilder.wipeReportDir(context) }
        }
    }

    LaunchedEffect(Unit) {
        IpLanguageManager.init(context)
        viewModel.initLogStore(context)
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
            viewModel.refreshManifest()
            viewModel.checkForAppUpdate()
            viewModel.refreshDriverState(context)
            IpLanguageManager.detectIpAndLanguage(context)
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
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
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
                    onSelectCountryLang = { IpLanguageManager.selectCountryLanguage() },
                    onSelectEnglish = { IpLanguageManager.selectEnglish() }
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
                        viewModel.refreshManifest()
                        viewModel.checkForAppUpdate()
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
                                    color = GradientAmberStart,
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
                                color = if (loadsTapable && !busy && rootAvailable) Color.White else TextDisabled
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

                CustomScriptLoadCard(
                    strings = s,
                    pickedFile = viewModel.pickedFileName.value,
                    busy = busy,
                    rootAvailable = rootAvailable,
                    onPickFile = onPickFile,
                    onRunPicked = { viewModel.loadPickedFile(context) },
                    onClearPicked = { viewModel.clearPickedFile() }
                )

                Spacer(Modifier.height(10.dp))

                SignatureVerificationOffCard(
                    strings = s,
                    busy = busy,
                    busyStep = busyStep,
                    rootAvailable = rootAvailable,
                    sigPatchStatus = sigPatchStatus,
                    onPatchSignature = { viewModel.patchKernelSignature(context) }
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

    val errorInfo = viewModel.loadErrorDialogData.value
    if (errorInfo != null) {
        DriverErrorDialog(
            info = errorInfo,
            onDismiss = { viewModel.dismissErrorDialog() },
            onCopyLog = { viewModel.copyTerminalToClipboard(context) }
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
    onSelectEnglish: () -> Unit
) {
    val countryFlag = IpLanguageManager.detectedCountryFlag()
    val isEnActive = IpLanguageManager.activeLangCode == "en"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1.copy(alpha = 0.94f)),
        border = BorderStroke(
            1.2.dp,
            Brush.linearGradient(
                listOf(
                    LogoPink.copy(alpha = 0.8f),
                    LogoMagenta.copy(alpha = 0.5f),
                    LogoPurple.copy(alpha = 0.8f)
                )
            )
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            LogoPink.copy(alpha = 0.12f),
                            LogoMagenta.copy(alpha = 0.06f),
                            LogoPurple.copy(alpha = 0.14f)
                        )
                    )
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = strings.appName,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            brush = Brush.linearGradient(
                                listOf(Color.White, LogoPink, LogoMagenta, LogoPurple)
                            )
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(7.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = LogoPink.copy(alpha = 0.18f),
                        border = BorderStroke(1.dp, LogoPink.copy(alpha = 0.6f))
                    ) {
                        Text(
                            text = version,
                            style = MonoTiny,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = LogoSoftPink,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.5.dp)
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = strings.subtitle,
                    style = MonoTiny,
                    fontSize = 9.5.sp,
                    color = TextSecondary,
                    letterSpacing = 0.6.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(8.dp))

            Surface(
                shape = RoundedCornerShape(50),
                color = SurfaceInset,
                border = BorderStroke(
                    1.dp,
                    Brush.horizontalGradient(listOf(LogoPink.copy(alpha = 0.65f), LogoPurple.copy(alpha = 0.65f)))
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(
                                if (!isEnActive) Brush.linearGradient(listOf(LogoPink, LogoPurple))
                                else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                            )
                            .clickable(onClick = onSelectCountryLang)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = countryFlag,
                            fontSize = 15.sp
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(
                                if (isEnActive) Brush.linearGradient(listOf(LogoPink, LogoPurple))
                                else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                            )
                            .clickable(onClick = onSelectEnglish)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "🇺🇸",
                            fontSize = 15.sp
                        )
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            AnimatedHeaderLogo(busy = busy)
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
                                .background(Surface2)
                                .border(0.8.dp, BorderSubtle, RoundedCornerShape(7.dp))
                                .clickable(onClick = onRefreshDb),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                tint = LogoSoftPink,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Surface1.copy(alpha = 0.94f)),
            border = BorderStroke(
                1.dp,
                Brush.horizontalGradient(
                    listOf(
                        LogoPink.copy(alpha = 0.7f),
                        LogoMagenta.copy(alpha = 0.45f),
                        LogoPurple.copy(alpha = 0.7f)
                    )
                )
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                LogoPink.copy(alpha = 0.10f),
                                LogoPurple.copy(alpha = 0.08f)
                            )
                        )
                    )
                    .padding(horizontal = 11.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(LogoPink.copy(alpha = 0.16f))
                        .border(1.dp, LogoPink.copy(alpha = 0.5f), RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = strings.vermagicTitle,
                        tint = LogoPink,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.vermagicTitle,
                        style = MonoTiny,
                        fontSize = 9.5.sp,
                        color = LogoSoftPink,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp
                    )
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
                    tint = LogoSoftPink,
                    onClick = onCopyVermagic
                )
                Spacer(Modifier.width(5.dp))
                MiniAction(
                    icon = Icons.Default.Terminal,
                    description = "Open Console",
                    tint = LogoPink,
                    onClick = onConsole
                )
                Spacer(Modifier.width(5.dp))
                MiniAction(
                    icon = Icons.Default.Info,
                    description = "Credits",
                    tint = LogoSoftPink,
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
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1.copy(alpha = 0.95f)),
        border = BorderStroke(
            1.3.dp,
            Brush.linearGradient(
                listOf(
                    accentColor.copy(alpha = 0.90f),
                    accentColor.copy(alpha = 0.45f)
                )
            )
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            accentColor.copy(alpha = if (isPositive) 0.14f else 0.16f),
                            Surface1.copy(alpha = 0.95f)
                        )
                    )
                )
                .padding(horizontal = 10.dp, vertical = 9.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(accentColor.copy(alpha = 0.20f))
                        .border(1.dp, accentColor.copy(alpha = 0.65f), RoundedCornerShape(7.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = accentColor,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Spacer(Modifier.width(7.dp))
                Text(
                    text = title,
                    style = MonoTiny,
                    fontSize = 10.5.sp,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (trailingAction != null) {
                    trailingAction()
                } else {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                }
            }

            Spacer(Modifier.height(7.dp))

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = accentColor.copy(alpha = 0.20f),
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.70f)),
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
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)
                )
            }
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
    val stateColor = if (loaded) StatusOk else AccentRed
    val isChecking = verifyStatus == "CHECKING"
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1),
        border = BorderStroke(
            1.2.dp,
            if (loaded) Brush.horizontalGradient(listOf(StatusOk, LogoPink))
            else Brush.horizontalGradient(listOf(AccentRed.copy(alpha = 0.65f), LogoPurple.copy(alpha = 0.55f)))
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(stateColor.copy(alpha = 0.08f))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusDot(loaded = loaded)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (loaded) {
                            if (hidden) strings.driverStatusHidden
                            else "${strings.driverStatusLoaded} · $loadedModule"
                        } else strings.driverStatusNotLoaded,
                        style = MonoSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (loaded) StatusOk else AccentRed
                    )
                    Text(
                        text = if (loaded) strings.driverStatusLoadedHint else strings.driverStatusNotLoadedHint,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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

            if (verifyStatus == "PASS" || verifyStatus == "FAIL") {
                val pass = verifyStatus == "PASS"
                val vColor = if (pass) Color(0xFF00E676) else Color(0xFFFF2E54)
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = vColor.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, vColor.copy(alpha = 0.70f)),
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

            Spacer(Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        if (!busy && rootAvailable) {
                            Brush.horizontalGradient(
                                listOf(
                                    Color(0xFF2B1045),
                                    Color(0xFF45145E),
                                    Color(0xFF261042)
                                )
                            )
                        } else {
                            Brush.horizontalGradient(listOf(Surface2, Surface2))
                        }
                    )
                    .border(
                        1.4.dp,
                        if (!busy && rootAvailable) {
                            Brush.horizontalGradient(listOf(LogoPink, LogoMagenta, LogoPurple))
                        } else {
                            Brush.horizontalGradient(listOf(BorderSubtle, BorderSubtle))
                        },
                        RoundedCornerShape(11.dp)
                    )
                    .clickable(enabled = !busy && rootAvailable, onClick = onVerify)
                    .padding(vertical = 10.dp, horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            color = LogoSoftPink,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Default.VerifiedUser,
                            contentDescription = null,
                            tint = if (!busy && rootAvailable) LogoSoftPink else TextDisabled,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (isChecking) strings.verifyingBtn else strings.verifyBtn,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (!busy && rootAvailable) Color.White else TextDisabled,
                        letterSpacing = 0.7.sp
                    )
                }
            }
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
        border = BorderStroke(
            1.2.dp,
            Brush.horizontalGradient(
                listOf(
                    LogoPink.copy(alpha = 0.7f),
                    LogoMagenta.copy(alpha = 0.5f),
                    LogoPurple.copy(alpha = 0.7f)
                )
            )
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            LogoPink.copy(alpha = 0.08f),
                            LogoPurple.copy(alpha = 0.08f)
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
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = if (hasFile) "${strings.customScriptSelected} $pickedFile"
                        else strings.customScriptSub,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = if (hasFile) StatusOk else TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(9.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        if (!busy && rootAvailable) {
                            Brush.horizontalGradient(
                                listOf(
                                    Color(0xFF2E1049),
                                    Color(0xFF4A145E),
                                    Color(0xFF281045)
                                )
                            )
                        } else {
                            Brush.horizontalGradient(listOf(Surface2, Surface2))
                        }
                    )
                    .border(
                        1.4.dp,
                        if (!busy && rootAvailable) {
                            Brush.horizontalGradient(listOf(LogoPink, LogoMagenta, LogoPurple))
                        } else {
                            Brush.horizontalGradient(listOf(BorderSubtle, BorderSubtle))
                        },
                        RoundedCornerShape(11.dp)
                    )
                    .clickable(enabled = !busy && rootAvailable, onClick = onPickFile)
                    .padding(vertical = 10.dp, horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.FolderOpen,
                        contentDescription = null,
                        tint = if (!busy && rootAvailable) LogoSoftPink else TextDisabled,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = strings.customScriptPickBtn,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (!busy && rootAvailable) Color.White else TextDisabled,
                        letterSpacing = 0.8.sp
                    )
                }
            }

            if (hasFile) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onRunPicked,
                        enabled = !busy && rootAvailable,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.3.dp, StatusOk),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A3323))
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = StatusOk,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = strings.customScriptLoadBtn,
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    OutlinedButton(
                        onClick = onClearPicked,
                        enabled = !busy,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, BorderStrong),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                    ) {
                        Text(strings.customScriptClearBtn, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun SignatureVerificationOffCard(
    strings: UiStrings,
    busy: Boolean,
    busyStep: String,
    rootAvailable: Boolean,
    sigPatchStatus: String,
    onPatchSignature: () -> Unit
) {
    val isPatchingSig = busy && (busyStep.contains("signature", true) || busyStep.contains("boot", true))
    val isSigDone = sigPatchStatus == "DONE"
    val subText = when (sigPatchStatus) {
        "DONE" -> "✓ Signature verification disabled in boot.img"
        "PATCHING" -> strings.sigOffPatchingBtn
        "FAILED" -> "✕ Signature bypass failed — check terminal"
        else -> strings.sigOffSub
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1),
        border = BorderStroke(
            1.2.dp,
            Brush.horizontalGradient(
                listOf(
                    if (isSigDone) StatusOk else LogoPurple.copy(alpha = 0.75f),
                    LogoMagenta.copy(alpha = 0.55f),
                    AccentAmber.copy(alpha = 0.65f)
                )
            )
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            LogoPurple.copy(alpha = 0.10f),
                            AccentAmber.copy(alpha = 0.06f)
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
                        .background(LogoPurple.copy(alpha = 0.18f))
                        .border(1.dp, LogoPurple.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Security,
                        contentDescription = null,
                        tint = if (isSigDone) StatusOk else LogoSoftPink,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = strings.sigOffTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = subText,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = when (sigPatchStatus) {
                            "DONE" -> StatusOk
                            "FAILED" -> AccentRed
                            else -> TextMuted
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(9.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        if (!busy && rootAvailable) {
                            Brush.horizontalGradient(
                                listOf(
                                    Color(0xFF281046),
                                    Color(0xFF45135C),
                                    Color(0xFF31114C)
                                )
                            )
                        } else {
                            Brush.horizontalGradient(listOf(Surface2, Surface2))
                        }
                    )
                    .border(
                        1.4.dp,
                        if (!busy && rootAvailable) {
                            Brush.horizontalGradient(listOf(LogoPurple, LogoMagenta, LogoPink))
                        } else {
                            Brush.horizontalGradient(listOf(BorderSubtle, BorderSubtle))
                        },
                        RoundedCornerShape(11.dp)
                    )
                    .clickable(enabled = !busy && rootAvailable, onClick = onPatchSignature)
                    .padding(vertical = 10.dp, horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Shield,
                        contentDescription = null,
                        tint = if (!busy && rootAvailable) LogoSoftPink else TextDisabled,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (isPatchingSig) strings.sigOffPatchingBtn else strings.sigOffBtn,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (!busy && rootAvailable) Color.White else TextDisabled,
                        letterSpacing = 0.8.sp
                    )
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
                onClick = onOpenFullConsole
            )
            Spacer(Modifier.width(6.dp))
        }
        MiniAction(
            icon = Icons.Default.ContentCopy,
            description = "Copy $title",
            tint = TextSecondary
        ) {
            clipboard.setText(
                AnnotatedString(lines.joinToString("\n") { "- [${it.type}] ${it.text}" })
            )
            Toast.makeText(context, "Status terminal logs copied!", Toast.LENGTH_SHORT).show()
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
        border = BorderStroke(
            1.2.dp,
            Brush.horizontalGradient(
                listOf(
                    LogoPink.copy(alpha = 0.55f),
                    LogoMagenta.copy(alpha = 0.35f),
                    LogoPurple.copy(alpha = 0.55f)
                )
            )
        )
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
            HorizontalDivider(color = BorderSubtle.copy(alpha = 0.7f))

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
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    items(lines) { line ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "- [${line.type}]",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = consoleLineColor(line.type),
                                modifier = Modifier.padding(end = 6.dp, top = 0.5.dp)
                            )
                            Text(
                                text = line.text,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                ),
                                color = if (line.type == "OK" || line.type == "ERR" || line.type == "WARN" || line.type == "CMD") {
                                    consoleLineColor(line.type)
                                } else {
                                    TextPrimary
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    item {
                        Spacer(Modifier.height(2.dp))
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
        border = BorderStroke(
            1.2.dp,
            Brush.horizontalGradient(
                listOf(
                    LogoPurple.copy(alpha = 0.6f),
                    LogoMagenta.copy(alpha = 0.45f),
                    LogoPink.copy(alpha = 0.6f)
                )
            )
        )
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
            .background(Surface2.copy(alpha = 0.85f))
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
        color = color.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = color,
            fontWeight = FontWeight.Bold,
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
                Brush.linearGradient(listOf(accentStart, accentMid, accentEnd))
            ),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = TextPrimary,
                disabledContainerColor = Surface1.copy(alpha = 0.6f),
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
                        Brush.linearGradient(
                            listOf(
                                accentStart.copy(alpha = 0.38f),
                                accentMid.copy(alpha = 0.28f),
                                accentEnd.copy(alpha = 0.38f)
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
            1.2.dp,
            if (loadFailed) {
                Brush.horizontalGradient(listOf(AccentRed, LogoPink))
            } else {
                Brush.horizontalGradient(listOf(LogoPink, LogoMagenta, LogoPurple))
            }
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

                Button(
                    onClick = {
                        if (preparing) return@Button
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
                    },
                    enabled = !preparing,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.3.dp, Color(0xFF29B6F6)),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0C2D48),
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(vertical = 12.dp, horizontal = 10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_telegram),
                        contentDescription = "Telegram",
                        tint = Color(0xFF29B6F6),
                        modifier = Modifier.size(20.dp)
                    )
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

                Button(
                    onClick = {
                        if (preparing) return@Button
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
                    },
                    enabled = !preparing,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.3.dp, Color(0xFF00E676)),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0A3322),
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(vertical = 12.dp, horizontal = 10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_whatsapp),
                        contentDescription = "WhatsApp",
                        tint = Color(0xFF00E676),
                        modifier = Modifier.size(20.dp)
                    )
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

            Spacer(Modifier.height(6.dp))

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
                enabled = !preparing,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = null,
                    tint = LogoSoftPink,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (preparing) strings.collectingReport else strings.copyReportBtn,
                    style = MonoTiny,
                    color = LogoSoftPink,
                    fontWeight = FontWeight.Bold
                )
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
    Surface(
        shape = RoundedCornerShape(50),
        color = AccentRed.copy(alpha = 0.08f + 0.14f * blink),
        border = BorderStroke(1.5.dp, AccentRed.copy(alpha = 0.3f + 0.55f * blink)),
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
                text = label,
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
