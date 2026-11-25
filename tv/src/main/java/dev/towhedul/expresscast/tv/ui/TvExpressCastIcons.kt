package dev.towhedul.expresscast.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.towhedul.expresscast.tv.ui.theme.TvEmerald
import dev.towhedul.expresscast.tv.ui.theme.TvMintWaveLight
import dev.towhedul.expresscast.tv.ui.theme.TvMintWaveSoft

@Composable
fun TvMintWaveBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val backWave = Path().apply {
            moveTo(0f, h * 0.44f)
            cubicTo(w * 0.28f, h * 0.14f, w * 0.65f, h * 0.62f, w, h * 0.28f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(backWave, color = TvMintWaveLight)

        val frontWave = Path().apply {
            moveTo(0f, h * 0.68f)
            cubicTo(w * 0.34f, h * 0.40f, w * 0.68f, h * 0.86f, w, h * 0.54f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(frontWave, color = TvMintWaveSoft.copy(alpha = 0.72f))
    }
}

@Composable
fun TvMonitorIcon(color: Color = TvEmerald, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.095f
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.10f, h * 0.16f),
            size = Size(w * 0.80f, h * 0.52f),
            cornerRadius = CornerRadius(w * 0.10f, w * 0.10f),
            style = Stroke(width = stroke)
        )
        drawLine(
            color = color,
            start = Offset(w * 0.50f, h * 0.68f),
            end = Offset(w * 0.50f, h * 0.84f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(w * 0.30f, h * 0.84f),
            end = Offset(w * 0.70f, h * 0.84f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun TvSignalIcon(color: Color = TvEmerald, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val barW = w * 0.18f
        val heights = listOf(0.28f, 0.52f, 0.76f)
        heights.forEachIndexed { idx, frac ->
            val x = w * (0.14f + idx * 0.28f)
            val barH = h * frac
            drawRoundRect(
                color = color,
                topLeft = Offset(x, h * 0.86f - barH),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f)
            )
        }
    }
}

@Composable
fun TvShieldCheckIcon(color: Color = TvEmerald, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val shield = Path().apply {
            moveTo(w * 0.50f, h * 0.10f)
            lineTo(w * 0.84f, h * 0.24f)
            lineTo(w * 0.84f, h * 0.52f)
            quadraticTo(w * 0.84f, h * 0.76f, w * 0.50f, h * 0.90f)
            quadraticTo(w * 0.16f, h * 0.76f, w * 0.16f, h * 0.52f)
            lineTo(w * 0.16f, h * 0.24f)
            close()
        }
        drawPath(path = shield, color = color)
        val check = Path().apply {
            moveTo(w * 0.34f, h * 0.52f)
            lineTo(w * 0.46f, h * 0.64f)
            lineTo(w * 0.68f, h * 0.39f)
        }
        drawPath(
            path = check,
            color = Color.White,
            style = Stroke(width = size.minDimension * 0.10f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
fun TvCheckCircleBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(TvEmerald),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(11.dp)) {
            val path = Path().apply {
                moveTo(size.width * 0.15f, size.height * 0.52f)
                lineTo(size.width * 0.42f, size.height * 0.78f)
                lineTo(size.width * 0.86f, size.height * 0.25f)
            }
            drawPath(
                path = path,
                color = Color.White,
                style = Stroke(width = 2.3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}
