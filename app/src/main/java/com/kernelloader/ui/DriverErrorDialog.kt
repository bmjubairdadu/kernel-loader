package com.kernelloader.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kernelloader.driver.DriverErrorInfo
import com.kernelloader.ui.theme.AccentRed
import com.kernelloader.ui.theme.BorderStrong
import com.kernelloader.ui.theme.BorderSubtle
import com.kernelloader.ui.theme.FamilyRt
import com.kernelloader.ui.theme.Surface0
import com.kernelloader.ui.theme.Surface1
import com.kernelloader.ui.theme.Surface2
import com.kernelloader.ui.theme.Surface3
import com.kernelloader.ui.theme.TextMuted
import com.kernelloader.ui.theme.TextPrimary
import com.kernelloader.ui.theme.TextSecondary

@Composable
fun DriverErrorDialog(
    info: DriverErrorInfo,
    onDismiss: () -> Unit,
    onCopyLog: () -> Unit
) {
    var isBengali by remember { mutableStateOf(true) }
    val scrollState = rememberScrollState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 20.dp),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = Surface1),
            border = BorderStroke(
                1.5.dp,
                Brush.verticalGradient(
                    listOf(
                        AccentRed.copy(alpha = 0.5f),
                        BorderStrong,
                        BorderSubtle
                    )
                )
            )
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Header with Warning Icon Badge & Language Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Warning Icon Badge
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(AccentRed.copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "⚠️",
                                fontSize = 22.sp
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = if (isBengali) "ত্রুটি সমাধান" else "Error Guide",
                            color = AccentRed,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }

                    // Language Switcher Tabs (Bengali / English)
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Surface0)
                            .padding(3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LanguageTabButton(
                            label = "বাংলা",
                            selected = isBengali,
                            onClick = { isBengali = true }
                        )
                        LanguageTabButton(
                            label = "EN",
                            selected = !isBengali,
                            onClick = { isBengali = false }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Title
                Text(
                    text = if (isBengali) info.titleBn else info.titleEn,
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                // Raw Error Pill
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Surface0)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(AccentRed, CircleShape)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = info.rawError,
                            color = AccentRed.copy(alpha = 0.9f),
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Dynamic Animated Description & Steps
                AnimatedContent(
                    targetState = isBengali,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "langTransition"
                ) { bengaliActive ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Explanation
                        Text(
                            text = if (bengaliActive) info.descriptionBn else info.descriptionEn,
                            color = TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )

                        Spacer(Modifier.height(14.dp))

                        // Solution Steps Card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Surface2),
                            border = BorderStroke(1.dp, BorderSubtle)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = if (bengaliActive) "🛠️ সমাধানের ধাপসমূহ:" else "🛠️ Step-by-Step Fix:",
                                    color = TextPrimary,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                Spacer(Modifier.height(10.dp))

                                val steps = if (bengaliActive) info.solutionStepsBn else info.solutionStepsEn
                                steps.forEachIndexed { idx, step ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(20.dp)
                                                .background(FamilyRt.copy(alpha = 0.2f), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "${idx + 1}",
                                                color = FamilyRt,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            text = step,
                                            color = TextPrimary,
                                            fontSize = 12.5.sp,
                                            lineHeight = 17.5.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                // Action Buttons (Copy Log & Close)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Copy Log Button
                    Button(
                        onClick = onCopyLog,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Surface3,
                            contentColor = TextPrimary
                        ),
                        border = BorderStroke(1.dp, BorderStrong)
                    ) {
                        Text(
                            text = if (isBengali) "📋 লগ কপি" else "📋 Copy Log",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Confirm / Dismiss or Reboot Button
                    if (info.canReboot) {
                        Button(
                            onClick = {
                                onDismiss()
                                com.topjohnwu.superuser.Shell.cmd("svc power reboot || reboot").exec()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentRed,
                                contentColor = Color.White
                            )
                        ) {
                            Text(
                                text = if (isBengali) "🔄 রিস্টার্ট দিন" else "🔄 Reboot Now",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = FamilyRt,
                                contentColor = Color.White
                            )
                        ) {
                            Text(
                                text = if (isBengali) "ঠিক আছে" else "Got It",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageTabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) FamilyRt else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else TextMuted,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

