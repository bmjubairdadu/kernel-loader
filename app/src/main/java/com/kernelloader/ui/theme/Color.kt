package com.kernelloader.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val SurfaceDeep = Color(0xFF080411)
val Surface0 = Color(0xFF100821)
val Surface1 = Color(0xFF190E30)
val Surface2 = Color(0xFF241543)
val Surface3 = Color(0xFF321E5B)
val SurfaceInset = Color(0xFF0A0517)

val BorderSubtle = Color(0xFF3D236E)
val BorderStrong = Color(0xFF6E3FB8)

val TextPrimary = Color(0xFFFDF7FF)
val TextSecondary = Color(0xFFE2C9F5)
val TextMuted = Color(0xFFA98EC4)
val TextDisabled = Color(0xFF6B5385)
val TextOnAccent = Color(0xFF180326)

val LogoPink = Color(0xFFFF62CA)
val LogoHotPink = Color(0xFFFF47B8)
val LogoMagenta = Color(0xFFD946EF)
val LogoPurple = Color(0xFFA855F7)
val LogoDeepViolet = Color(0xFF7E22CE)
val LogoDeepMagenta = Color(0xFFC2009C)
val LogoSoftPink = Color(0xFFFFA6E2)

val AccentGreen = Color(0xFF00E676)
val AccentBlue = Color(0xFFC084FC)
val AccentAmber = Color(0xFFFFBE0B)
val AccentRed = Color(0xFFFF2E54)
val AccentViolet = Color(0xFFA855F7)
val AccentEmerald = Color(0xFF00E676)

val StatusOk = Color(0xFF00E676)
val StatusWarn = AccentAmber
val StatusError = AccentRed
val StatusIdle = TextMuted

val FamilyRt = LogoPink
val FamilyRtDeep = Color(0xFF59125E)
val FamilyQx = LogoPurple
val FamilyQxDeep = Color(0xFF3B1266)

val GridLine = Color(0x0EFF62CA)
val TopGlow = Color(0xFFE040FB)

val GradientGreenStart = Color(0xFFFF62CA)
val GradientGreenEnd = Color(0xFFA855F7)
val GradientRtStart = Color(0xFFFF62CA)
val GradientRtEnd = Color(0xFFD946EF)
val GradientQxStart = Color(0xFFD946EF)
val GradientQxEnd = Color(0xFF9333EA)
val GradientRedStart = Color(0xFFFF4D6D)
val GradientRedEnd = Color(0xFFD90429)
val GradientAmberStart = Color(0xFFFFD479)
val GradientAmberEnd = Color(0xFFFF7A45)

val LogoBrushHorizontal = Brush.horizontalGradient(
    listOf(LogoPink, LogoMagenta, LogoPurple)
)

val LogoBrushLinear = Brush.linearGradient(
    listOf(LogoPink, LogoMagenta, LogoPurple)
)

val LogoBrushSubtle = Brush.linearGradient(
    listOf(
        LogoPink.copy(alpha = 0.22f),
        LogoMagenta.copy(alpha = 0.14f),
        LogoPurple.copy(alpha = 0.22f)
    )
)

val LogCmd = Color(0xFFFF85DC)
val LogOut = Color(0xFFEDE0F7)
val LogOk = Color(0xFF00E676)
val LogWarn = Color(0xFFFFD166)
val LogErr = Color(0xFFFF4D6D)
val LogInfo = Color(0xFFD1B3EE)
val LogFix = Color(0xFFE879F9)
val LogTimestamp = Color(0xFF8E71AC)

val Color_White = Color(0xFFFFFFFF)
val Color_LightPrimary = Color(0xFFB81D93)
val Color_LightPrimaryContainer = Color(0xFFFFD6F1)
val Color_LightOnPrimaryContainer = Color(0xFF3B002C)
val Color_LightSecondary = Color(0xFF7E22CE)
val Color_LightSecondaryContainer = Color(0xFFF3E8FF)
val Color_LightOnSecondaryContainer = Color(0xFF2B004D)
val Color_LightBackground = Color(0xFFFDF7FF)
val Color_LightSurface = Color(0xFFFFFFFF)
val Color_LightSurfaceVariant = Color(0xFFF3E5F8)
val Color_LightOnBackground = Color(0xFF1D102B)
val Color_LightMuted = Color(0xFF6B5385)
val Color_LightError = Color(0xFFB3261E)
