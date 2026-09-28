package com.kernelloader.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

private val trim = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None
)

private fun style(
    size: Int,
    lineHeight: Int,
    weight: FontWeight,
    letter: Double = 0.0
) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letter.sp,
    lineHeightStyle = trim
)

val Typography = Typography(
    displaySmall = style(34, 40, FontWeight.Bold, (-0.5)),

    headlineMedium = style(27, 34, FontWeight.Bold),
    headlineSmall = style(23, 30, FontWeight.Bold),

    titleLarge = style(20, 26, FontWeight.Bold),
    titleMedium = style(17, 23, FontWeight.SemiBold, 0.1),
    titleSmall = style(15, 20, FontWeight.SemiBold, 0.1),

    bodyLarge = style(16, 24, FontWeight.Normal, 0.15),
    bodyMedium = style(14, 20, FontWeight.Normal, 0.2),
    bodySmall = style(12, 17, FontWeight.Normal, 0.3),

    labelLarge = style(14, 20, FontWeight.SemiBold, 0.1),
    labelMedium = style(12, 16, FontWeight.Medium, 0.4),
    labelSmall = style(11, 15, FontWeight.Medium, 0.5)
)

val MonoSmall = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 17.sp,
    letterSpacing = 0.sp
)

val MonoTiny = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 11.sp,
    lineHeight = 15.sp,
    letterSpacing = 0.sp
)
