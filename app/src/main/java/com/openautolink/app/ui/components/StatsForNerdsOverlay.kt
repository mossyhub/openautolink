package com.openautolink.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Original AA Stats for nerds panel, shared without inventing protocol metrics. */
@Composable
fun StatsForNerdsOverlay(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(14.dp)
            .testTag("videoStatsOverlay")
    ) {
        Column {
            StatLine("Stats for nerds", "", header = true)
            Spacer(modifier = Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
fun StatLine(
    label: String,
    value: String,
    header: Boolean = false,
    valueColor: Color = Color.White,
) {
    Row(modifier = Modifier.padding(vertical = 1.dp)) {
        Text(
            text = label,
            color = if (header) Color(0xFF64FFDA) else Color(0xFFB0BEC5),
            fontSize = if (header) 16.sp else 13.sp,
            fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace,
            lineHeight = if (header) 20.sp else 16.sp,
            modifier = Modifier.width(if (header) 200.dp else 100.dp),
        )
        if (value.isNotEmpty()) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = value,
                color = valueColor,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp,
            )
        }
    }
}
