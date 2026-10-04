package com.kernelloader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kernelloader.BuildConfig
import com.kernelloader.R
import com.kernelloader.ui.theme.AccentGreen
import com.kernelloader.ui.theme.BorderSubtle
import com.kernelloader.ui.theme.GradientQxEnd
import com.kernelloader.ui.theme.GradientRtStart
import com.kernelloader.ui.theme.MonoTiny
import com.kernelloader.ui.theme.Surface1
import com.kernelloader.ui.theme.TextMuted
import com.kernelloader.ui.theme.TextPrimary
import com.kernelloader.ui.theme.TextSecondary

@Composable
fun CreditsScreen(onBack: () -> Unit) {
    AppBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.app_logo),
                contentDescription = "Kernel Loader logo",
                modifier = Modifier
                    .size(92.dp)
                    .clip(CircleShape)
                    .border(
                        2.dp,
                        Brush.linearGradient(listOf(GradientRtStart, GradientQxEnd)),
                        CircleShape
                    ),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.credits),
                style = MaterialTheme.typography.headlineLarge.copy(
                    brush = Brush.linearGradient(listOf(Color.White, GradientRtStart))
                ),
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(4.dp))

            Surface(
                shape = RoundedCornerShape(6.dp),
                color = AccentGreen.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, AccentGreen.copy(alpha = 0.4f))
            ) {
                Text(
                    text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MonoTiny,
                    fontWeight = FontWeight.Bold,
                    color = AccentGreen,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Surface1),
                border = BorderStroke(1.dp, BorderSubtle)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.credits_universal),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(
                            Modifier
                                .width(18.dp)
                                .height(1.dp)
                                .border(1.dp, BorderSubtle)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "universal kernel module loader",
                            style = MonoTiny,
                            color = TextMuted
                        )
                        Spacer(Modifier.width(8.dp))
                        Spacer(
                            Modifier
                                .width(18.dp)
                                .height(1.dp)
                                .border(1.dp, BorderSubtle)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onBack,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentGreen.copy(alpha = 0.16f),
                    contentColor = AccentGreen
                ),
                border = BorderStroke(1.dp, AccentGreen.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth(0.6f)
            ) {
                Text(
                    text = stringResource(R.string.close),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "load smart · stay rooted",
                style = MonoTiny,
                color = TextSecondary
            )
        }
    }
}
