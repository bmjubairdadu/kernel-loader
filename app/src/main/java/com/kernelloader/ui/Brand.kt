package com.kernelloader.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.kernelloader.R
import com.kernelloader.ui.theme.AccentGreen
import com.kernelloader.ui.theme.Surface0
import com.kernelloader.ui.theme.Surface1
import com.kernelloader.ui.theme.Surface2

val BrandBgTop = Surface0
val BrandBgMid = Surface1
val BrandBgBottom = Surface2
val BrandAccent = AccentGreen

@Composable
fun AppBackground(
    watermarkAlpha: Float = 0.035f,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0.0f to Surface0,
                    0.55f to Surface1,
                    1.0f to Surface0
                )
            )
    ) {
        Image(
            painter = painterResource(id = R.drawable.app_logo),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .align(Alignment.Center)
                .size(320.dp)
                .alpha(watermarkAlpha)
        )
        content()
    }
}
