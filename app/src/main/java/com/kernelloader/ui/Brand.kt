package com.kernelloader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.kernelloader.R
import com.kernelloader.ui.theme.GridLine
import com.kernelloader.ui.theme.Surface0
import com.kernelloader.ui.theme.Surface1
import com.kernelloader.ui.theme.Surface2
import com.kernelloader.ui.theme.SurfaceDeep
import com.kernelloader.ui.theme.TopGlow

val BrandBgTop = Surface0
val BrandBgMid = Surface1
val BrandBgBottom = Surface2
val BrandAccent = TopGlow

@Composable
fun AppBackground(
    watermarkAlpha: Float = 0.035f,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceDeep)
            .background(
                Brush.verticalGradient(
                    0.0f to Surface0,
                    0.5f to Surface1.copy(alpha = 0.55f),
                    1.0f to Surface0
                )
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(
                    Brush.verticalGradient(
                        0.0f to TopGlow.copy(alpha = 0.10f),
                        1.0f to Color.Transparent
                    )
                )
        )

        Canvas(Modifier.fillMaxSize()) {
            val step = 30.dp.toPx()
            var x = 0f
            while (x < size.width) {
                drawLine(GridLine, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                x += step
            }
            var y = 0f
            while (y < size.height) {
                drawLine(GridLine, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                y += step
            }
        }

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
