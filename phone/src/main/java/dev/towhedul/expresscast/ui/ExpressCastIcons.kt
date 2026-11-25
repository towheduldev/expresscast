package dev.towhedul.expresscast.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.towhedul.expresscast.settings.AspectCropMode
import dev.towhedul.expresscast.ui.theme.CardBorder
import dev.towhedul.expresscast.ui.theme.EmeraldPrimary
import dev.towhedul.expresscast.ui.theme.InkPrimary
import dev.towhedul.expresscast.ui.theme.InkSecondary
import dev.towhedul.expresscast.ui.theme.MintBorder
import dev.towhedul.expresscast.ui.theme.MintTint
import dev.towhedul.expresscast.ui.theme.MintWaveLight
import dev.towhedul.expresscast.ui.theme.MintWaveSoft
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun MintWaveBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val backWave = Path().apply {
            moveTo(0f, h * 0.42f)
            cubicTo(w * 0.28f, h * 0.14f, w * 0.65f, h * 0.62f, w, h * 0.28f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(backWave, color = MintWaveLight)

        val frontWave = Path().apply {
            moveTo(0f, h * 0.66f)
            cubicTo(w * 0.34f, h * 0.38f, w * 0.68f, h * 0.85f, w, h * 0.52f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(frontWave, color = MintWaveSoft.copy(alpha = 0.72f))
    }
}

@Composable
fun PowerIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.12f
        val inset = stroke * 1.4f
        drawArc(
            color = color,
            startAngle = -52f,
            sweepAngle = 284f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(size.width - inset * 2f, size.height - inset * 2f),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        drawLine(
            color = color,
            start = Offset(size.width / 2f, inset * 0.45f),
            end = Offset(size.width / 2f, size.height * 0.48f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun SettingsGearIcon(color: Color = InkPrimary, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val stroke = size.minDimension * 0.10f
        val innerR = size.minDimension * 0.16f
        val outerR = size.minDimension * 0.31f
        val toothOuter = size.minDimension * 0.42f

        drawCircle(
            color = color,
            radius = innerR,
            center = Offset(cx, cy),
            style = Stroke(width = stroke)
        )
        drawCircle(
            color = color,
            radius = outerR,
            center = Offset(cx, cy),
            style = Stroke(width = stroke)
        )
        for (i in 0 until 8) {
            val angle = (i * 45.0) * PI / 180.0
            val sx = cx + (outerR * 0.92f) * cos(angle).toFloat()
            val sy = cy + (outerR * 0.92f) * sin(angle).toFloat()
            val ex = cx + toothOuter * cos(angle).toFloat()
            val ey = cy + toothOuter * sin(angle).toFloat()
            drawLine(
                color = color,
                start = Offset(sx, sy),
                end = Offset(ex, ey),
                strokeWidth = stroke * 1.25f,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
fun BackArrowIcon(color: Color = InkPrimary, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.12f
        val path = Path().apply {
            moveTo(size.width * 0.64f, size.height * 0.22f)
            lineTo(size.width * 0.34f, size.height * 0.50f)
            lineTo(size.width * 0.64f, size.height * 0.78f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
fun ChevronRightIcon(color: Color = InkSecondary, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.12f
        val path = Path().apply {
            moveTo(size.width * 0.36f, size.height * 0.22f)
            lineTo(size.width * 0.66f, size.height * 0.50f)
            lineTo(size.width * 0.36f, size.height * 0.78f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
fun MonitorIcon(color: Color = EmeraldPrimary, modifier: Modifier = Modifier) {
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
fun CopyIcon(color: Color = InkPrimary, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.10f
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.32f, h * 0.14f),
            size = Size(w * 0.52f, h * 0.56f),
            cornerRadius = CornerRadius(w * 0.09f, w * 0.09f),
            style = Stroke(width = stroke)
        )
        val backPath = Path().apply {
            moveTo(w * 0.64f, h * 0.76f)
            lineTo(w * 0.64f, h * 0.84f)
            lineTo(w * 0.18f, h * 0.84f)
            lineTo(w * 0.18f, h * 0.30f)
            lineTo(w * 0.26f, h * 0.30f)
        }
        drawPath(
            path = backPath,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
fun PlayTriangleIcon(color: Color = EmeraldPrimary, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.22f, h * 0.14f)
            lineTo(w * 0.86f, h * 0.50f)
            lineTo(w * 0.22f, h * 0.86f)
            close()
        }
        drawPath(path = path, color = color)
    }
}

@Composable
fun GaugeIcon(color: Color = InkPrimary, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.10f
        val w = size.width
        val h = size.height
        drawArc(
            color = color,
            startAngle = 150f,
            sweepAngle = 240f,
            useCenter = false,
            topLeft = Offset(w * 0.14f, h * 0.16f),
            size = Size(w * 0.72f, h * 0.72f),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        drawLine(
            color = color,
            start = Offset(w * 0.50f, h * 0.56f),
            end = Offset(w * 0.68f, h * 0.34f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawCircle(color = color, radius = stroke * 0.85f, center = Offset(w * 0.50f, h * 0.58f))
    }
}

@Composable
fun SignalBarsIcon(color: Color = EmeraldPrimary, modifier: Modifier = Modifier) {
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
fun HdBadgeIcon(color: Color = EmeraldPrimary, modifier: Modifier = Modifier) {
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.8.dp, color),
        modifier = modifier
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                text = "HD",
                color = color,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

@Composable
fun LightningIcon(color: Color = EmeraldPrimary, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.58f, h * 0.10f)
            lineTo(w * 0.22f, h * 0.54f)
            lineTo(w * 0.48f, h * 0.54f)
            lineTo(w * 0.42f, h * 0.90f)
            lineTo(w * 0.78f, h * 0.46f)
            lineTo(w * 0.52f, h * 0.46f)
            close()
        }
        drawPath(path = path, color = color)
    }
}

@Composable
fun InfoCircleIcon(color: Color = InkSecondary, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.10f
        drawCircle(
            color = color,
            radius = size.minDimension * 0.42f,
            style = Stroke(width = stroke)
        )
        drawCircle(
            color = color,
            radius = stroke * 0.75f,
            center = Offset(size.width / 2f, size.height * 0.34f)
        )
        drawLine(
            color = color,
            start = Offset(size.width / 2f, size.height * 0.48f),
            end = Offset(size.width / 2f, size.height * 0.72f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun SpeakerIcon(color: Color = EmeraldPrimary, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val body = Path().apply {
            moveTo(w * 0.16f, h * 0.38f)
            lineTo(w * 0.34f, h * 0.38f)
            lineTo(w * 0.56f, h * 0.20f)
            lineTo(w * 0.56f, h * 0.80f)
            lineTo(w * 0.34f, h * 0.62f)
            lineTo(w * 0.16f, h * 0.62f)
            close()
        }
        drawPath(path = body, color = color)
        val stroke = size.minDimension * 0.10f
        drawArc(
            color = color,
            startAngle = -45f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(w * 0.52f, h * 0.28f),
            size = Size(w * 0.28f, h * 0.44f),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}

@Composable
fun ShieldCheckIcon(color: Color = EmeraldPrimary, modifier: Modifier = Modifier) {
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
fun CheckCircleBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(EmeraldPrimary),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(10.dp)) {
            val path = Path().apply {
                moveTo(size.width * 0.15f, size.height * 0.52f)
                lineTo(size.width * 0.42f, size.height * 0.78f)
                lineTo(size.width * 0.86f, size.height * 0.25f)
            }
            drawPath(
                path = path,
                color = Color.White,
                style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}

@Composable
fun AspectPreviewThumbnail(
    mode: AspectCropMode,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val label = when (mode) {
        AspectCropMode.NATIVE -> "Native"
        AspectCropMode.RATIO_16_9 -> "16:9\n(Fill TV)"
        AspectCropMode.RATIO_16_10 -> "16:10"
        AspectCropMode.RATIO_4_3 -> "4:3"
    }

    val skyBrush = if (selected) {
        Brush.verticalGradient(listOf(Color(0xFFD9F7E8), Color(0xFFBCEBD4)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFFE9EFF6), Color(0xFFD9E3EE)))
    }
    val hillBack = if (selected) Color(0xFF9CDCB9) else Color(0xFFB5C4D6)
    val hillFront = if (selected) Color(0xFF7FD0A5) else Color(0xFF9FB2C9)

    Column(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.5f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(skyBrush)
                    .border(
                        width = if (selected) 1.5.dp else 1.dp,
                        color = if (selected) MintBorder else CardBorder,
                        shape = RoundedCornerShape(12.dp)
                    )
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val leftMountain = Path().apply {
                        moveTo(0f, h)
                        lineTo(w * 0.34f, h * 0.42f)
                        lineTo(w * 0.72f, h)
                        close()
                    }
                    drawPath(leftMountain, color = hillBack)

                    val rightMountain = Path().apply {
                        moveTo(w * 0.38f, h)
                        lineTo(w * 0.76f, h * 0.34f)
                        lineTo(w, h * 0.72f)
                        lineTo(w, h)
                        close()
                    }
                    drawPath(rightMountain, color = hillFront)

                    // Subtle side-bar hint on narrower modes
                    if (mode == AspectCropMode.RATIO_4_3) {
                        drawRect(color = Color.Black.copy(alpha = 0.08f), size = Size(w * 0.14f, h))
                        drawRect(
                            color = Color.Black.copy(alpha = 0.08f),
                            topLeft = Offset(w * 0.86f, 0f),
                            size = Size(w * 0.14f, h)
                        )
                    } else if (mode == AspectCropMode.RATIO_16_10) {
                        drawRect(color = Color.Black.copy(alpha = 0.05f), size = Size(w * 0.07f, h))
                        drawRect(
                            color = Color.Black.copy(alpha = 0.05f),
                            topLeft = Offset(w * 0.93f, 0f),
                            size = Size(w * 0.07f, h)
                        )
                    }
                }
            }
            if (selected) {
                CheckCircleBadge(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-4).dp)
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) InkPrimary else InkSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 14.sp
        )
    }
}
