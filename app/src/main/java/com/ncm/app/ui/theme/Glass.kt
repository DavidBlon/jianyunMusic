package com.ncm.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val GlassSurface: Color
    @Composable get() = LocalAppPalette.current.glass
val GlassSurfaceStrong: Color
    @Composable get() = LocalAppPalette.current.glassStrong
val GlassSurfaceSoft: Color
    @Composable get() = LocalAppPalette.current.glassSoft

@Composable
fun miniPlayerSafeBottomPadding(): Dp {
    val navigationBarPadding = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()
    return 140.dp + navigationBarPadding
}

/**
 * Shared ambient background. Dark: near-black with a subtle falloff.
 * Light: layered system grays with a soft white top.
 */
@Composable
fun Modifier.appBackground(
    accent: Color = Color.Unspecified,
    secondary: Color = Color.Unspecified,
    highlight: Color = Color.Unspecified
): Modifier {
    val palette = LocalAppPalette.current
    val brush = if (palette.isLight) {
        Brush.verticalGradient(
            listOf(
                Color(0xFFFFFFFF),
                Color(0xFFF7F7F9),
                Color(0xFFF2F2F7)
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color(0xFF161618),
                Color(0xFF0E0E10),
                Color(0xFF0A0A0B)
            )
        )
    }
    return background(brush)
}

@Composable
fun Modifier.accentSurface(shape: Shape): Modifier = this
    .clip(shape)
    .background(accentBrush())

/**
 * Apple-style frosted surface: a calm translucent fill with a hairline border
 * and a barely-there top sheen. Subtle depth without loud gradients.
 */
@Composable
fun Modifier.glassSurface(
    shape: Shape = RoundedCornerShape(16.dp),
    tint: Color = Green500,
    elevation: Dp = 10.dp,
    strong: Boolean = false
): Modifier {
    val palette = LocalAppPalette.current
    val fill = if (strong) palette.glassStrong else palette.glass
    return this
        .shadowIos(elevation, shape, palette)
        .clip(shape)
        .background(fill)
        .border(
            width = 1.dp,
            brush = Brush.verticalGradient(
                colors = listOf(
                    if (strong) palette.glassBorderTopStrong else palette.glassBorderTop,
                    palette.glassBorderBottom
                )
            ),
            shape = shape
        )
}

private fun Modifier.shadowIos(elevation: Dp, shape: Shape, palette: AppPalette): Modifier =
    if (elevation.value <= 0f) this
    else shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = Color.Black.copy(alpha = palette.shadowAmbient),
        spotColor = Color.Black.copy(alpha = palette.shadowSpot)
    )

fun Modifier.glassDivider(): Modifier = drawWithCache {
    onDrawBehind {
        drawRect(
            color = Color.White.copy(alpha = 0.10f),
            topLeft = Offset(0f, size.height - 1f),
            size = Size(size.width, 1f)
        )
    }
}
