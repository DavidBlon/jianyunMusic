package com.ncm.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ncm.app.ui.theme.AccentSecondary
import com.ncm.app.ui.theme.Green500
import com.ncm.app.ui.theme.TextPrimary

@Composable
fun LinglanSourceBadge(
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(7.dp)
    Row(
        modifier = modifier
            .clearAndSetSemantics {
                contentDescription = "聆澜音源"
            }
            .background(
                brush = Brush.horizontalGradient(
                    listOf(
                        Green500.copy(alpha = 0.22f),
                        AccentSecondary.copy(alpha = 0.16f)
                    )
                ),
                shape = shape
            )
            .border(
                width = 0.5.dp,
                brush = Brush.horizontalGradient(
                    listOf(
                        Green500.copy(alpha = 0.62f),
                        AccentSecondary.copy(alpha = 0.42f)
                    )
                ),
                shape = shape
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Hearing,
            contentDescription = null,
            tint = Green500,
            modifier = Modifier.size(12.dp)
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = "聆澜",
            style = MaterialTheme.typography.labelSmall,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold
        )
    }
}
