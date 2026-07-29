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

val GlassSurface = Color(0x941A2027)
val GlassSurfaceStrong = Color(0xD91A2027)
val GlassSurfaceSoft = Color(0x661A2027)
val GlassHighlight = Color(0x38FFFFFF)
val GlassBorder = Color(0x24FFFFFF)

@Composable
fun miniPlayerSafeBottomPadding(): Dp {
    val navigationBarPadding = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()
    // 68dp bottom navigation offset + 56dp mini-player height + 16dp breathing room.
    return 140.dp + navigationBarPadding
}

/**
 * A shared ambient background. Radial light fields create depth behind translucent
 * surfaces without relying on API-specific backdrop blur behavior.
 */
fun Modifier.appBackground(
    accent: Color,
    secondary: Color = accent,
    highlight: Color = secondary
): Modifier = drawWithCache {
    val base = Brush.verticalGradient(
        listOf(
            Color(0xFF0C1015),
            DarkBg,
            Color(0xFF07090C)
        )
    )
    val topGlow = Brush.radialGradient(
        colors = listOf(accent.copy(alpha = 0.18f), Color.Transparent),
        center = Offset(size.width * 0.82f, size.height * 0.04f),
        radius = size.width * 0.78f
    )
    val lowerGlow = Brush.radialGradient(
        colors = listOf(secondary.copy(alpha = 0.13f), Color.Transparent),
        center = Offset(size.width * 0.08f, size.height * 0.72f),
        radius = size.width * 0.92f
    )
    val edgeGlow = Brush.radialGradient(
        colors = listOf(highlight.copy(alpha = 0.09f), Color.Transparent),
        center = Offset(size.width * 0.94f, size.height * 0.88f),
        radius = size.width * 0.58f
    )
    onDrawBehind {
        drawRect(base)
        drawRect(topGlow)
        drawRect(lowerGlow)
        drawRect(edgeGlow)
    }
}

@Composable
fun Modifier.accentSurface(shape: Shape): Modifier = this
    .clip(shape)
    .background(accentBrush())
    .border(
        width = 1.dp,
        color = Color.White.copy(alpha = 0.20f),
        shape = shape
    )

/**
 * Consistent dark glass: tinted shadow, translucent layers, and a directional
 * edge highlight that reads like refracted light.
 */
@Composable
fun Modifier.glassSurface(
    shape: Shape = RoundedCornerShape(22.dp),
    tint: Color = Green500,
    elevation: Dp = 18.dp,
    strong: Boolean = false
): Modifier {
    val fill = if (strong) GlassSurfaceStrong else GlassSurface
    return this
        .shadow(
            elevation = elevation,
            shape = shape,
            clip = false,
            ambientColor = tint.copy(alpha = 0.10f),
            spotColor = Color.Black.copy(alpha = 0.48f)
        )
        .clip(shape)
        .background(
            Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.White.copy(alpha = if (strong) 0.12f else 0.09f),
                    0.18f to Color.White.copy(alpha = if (strong) 0.055f else 0.035f),
                    0.58f to fill,
                    1f to tint.copy(alpha = if (strong) 0.07f else 0.035f)
                )
            )
        )
        .drawWithCache {
            val cornerSheen = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (strong) 0.075f else 0.05f),
                    Color.Transparent
                ),
                center = Offset(size.width * 0.12f, 0f),
                radius = maxOf(size.width, size.height) * 0.82f
            )
            onDrawBehind {
                drawRect(cornerSheen)
            }
        }
        .border(
            width = 1.dp,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.30f),
                    GlassBorder,
                    tint.copy(alpha = 0.18f)
                ),
                start = Offset.Zero,
                end = Offset(720f, 720f)
            ),
            shape = shape
        )
}

fun Modifier.glassDivider(): Modifier = drawWithCache {
    val line = Brush.horizontalGradient(
        listOf(Color.Transparent, Color.White.copy(alpha = 0.16f), Color.Transparent)
    )
    onDrawBehind {
        drawRect(
            brush = line,
            topLeft = Offset(0f, size.height - 1f),
            size = Size(size.width, 1f)
        )
    }
}
