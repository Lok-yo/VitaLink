package com.vitalink.mobile.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitalink.core.data.BpmPoint
import com.vitalink.core.data.ConnectionInfo
import com.vitalink.core.data.LinkStatus
import com.vitalink.mobile.ui.theme.VIcons
import com.vitalink.mobile.ui.theme.Vita
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Corazón que late al ritmo real del pulso recibido. */
@Composable
fun BeatingHeart(bpm: Int?, color: Color, size: Dp, modifier: Modifier = Modifier) {
    val scale = remember { Animatable(1f) }
    val currentBpm by rememberUpdatedState(bpm)
    LaunchedEffect(Unit) {
        while (true) {
            val b = (currentBpm ?: 0).coerceIn(0, 220)
            if (b < 30) {
                delay(500)
                continue
            }
            val period = 60_000L / b
            scale.animateTo(1.18f, tween((period * 0.18).toInt().coerceAtLeast(60), easing = FastOutSlowInEasing))
            scale.animateTo(0.96f, tween((period * 0.22).toInt().coerceAtLeast(60)))
            scale.animateTo(1.08f, tween((period * 0.12).toInt().coerceAtLeast(40)))
            scale.animateTo(1f, tween((period * 0.48).toInt().coerceAtLeast(80)))
        }
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size)
                .scale(scale.value)
                .background(color.copy(alpha = 0.12f), CircleShape),
        )
        Icon(VIcons.Heart, null, tint = color, modifier = Modifier.size(size * 0.62f).scale(scale.value))
    }
}

/** Gráfica de pulso en vivo con banda normal sombreada. */
@Composable
fun BpmChart(points: List<BpmPoint>, color: Color, modifier: Modifier = Modifier) {
    val band = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val pulse = rememberInfiniteTransition(label = "dot")
    val dot by pulse.animateFloat(0.6f, 1.6f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot")
    Canvas(modifier) {
        val minY = 35f
        val maxY = 165f
        fun y(v: Float) = size.height - (v - minY) / (maxY - minY) * size.height
        drawRect(band, topLeft = Offset(0f, y(105f)), size = Size(size.width, y(50f) - y(105f)))
        listOf(60f, 100f, 140f).forEach { g ->
            drawLine(grid, Offset(0f, y(g)), Offset(size.width, y(g)), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        }
        if (points.size < 2) return@Canvas
        val data = points.takeLast(60)
        val step = size.width / (data.size - 1).coerceAtLeast(1)
        val path = Path()
        data.forEachIndexed { i, p ->
            val x = i * step
            val yy = y(p.bpm.toFloat().coerceIn(minY, maxY))
            if (i == 0) path.moveTo(x, yy) else {
                val px = (i - 1) * step
                val py = y(data[i - 1].bpm.toFloat().coerceIn(minY, maxY))
                path.cubicTo(px + step / 2, py, x - step / 2, yy, x, yy)
            }
        }
        val fill = Path().apply {
            addPath(path)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), Color.Transparent)))
        drawPath(path, color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        val last = data.last()
        val c = Offset(size.width, y(last.bpm.toFloat().coerceIn(minY, maxY)))
        drawCircle(color.copy(alpha = 0.25f), radius = 7.dp.toPx() * dot, center = c)
        drawCircle(color, radius = 4.dp.toPx(), center = c)
    }
}

@Composable
fun ProgressRing(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    stroke: Dp = 8.dp,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(900), label = "ring")
    val track = color.copy(alpha = 0.15f)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx()
            val arcSize = Size(size.width - s, size.height - s)
            drawArc(track, -90f, 360f, false, Offset(s / 2, s / 2), arcSize, style = Stroke(s, cap = StrokeCap.Round))
            drawArc(color, -90f, 360f * animated, false, Offset(s / 2, s / 2), arcSize, style = Stroke(s, cap = StrokeCap.Round))
        }
        content()
    }
}

/**
 * Mini mapa tipo radar (sin API de mapas): casa al centro, zona segura y posición del paciente.
 */
@Composable
fun SafeZoneRadar(distanceM: Int, radiusM: Int, bearingDeg: Double, inZone: Boolean, modifier: Modifier = Modifier) {
    val zoneColor by animateColorAsState(if (inZone) Vita.Ok else Vita.Coral, label = "zone")
    val sweep = rememberInfiniteTransition(label = "radar")
    val angle by sweep.animateFloat(0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "sweep")
    val ping by sweep.animateFloat(0f, 1f, infiniteRepeatable(tween(1400)), label = "ping")
    val grid = MaterialTheme.colorScheme.outlineVariant
    val home = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val maxR = size.minDimension / 2 * 0.95f
        val scaleM = max(radiusM * 1.6f, distanceM * 1.15f)
        val zoneR = maxR * radiusM / scaleM
        for (i in 1..3) drawCircle(grid, maxR * i / 3, center, style = Stroke(1.dp.toPx()))
        drawCircle(zoneColor.copy(alpha = 0.10f), zoneR, center)
        drawCircle(zoneColor.copy(alpha = 0.7f), zoneR, center, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
        val rad = Math.toRadians(angle.toDouble() - 90)
        drawLine(
            Brush.linearGradient(listOf(zoneColor.copy(alpha = 0.0f), zoneColor.copy(alpha = 0.5f)), center, Offset(center.x + maxR * cos(rad).toFloat(), center.y + maxR * sin(rad).toFloat())),
            center, Offset(center.x + maxR * cos(rad).toFloat(), center.y + maxR * sin(rad).toFloat()), strokeWidth = 2.dp.toPx(),
        )
        drawRect(home, topLeft = Offset(center.x - 5.dp.toPx(), center.y - 3.dp.toPx()), size = Size(10.dp.toPx(), 8.dp.toPx()))
        val roof = Path().apply {
            moveTo(center.x - 7.dp.toPx(), center.y - 3.dp.toPx())
            lineTo(center.x, center.y - 9.dp.toPx())
            lineTo(center.x + 7.dp.toPx(), center.y - 3.dp.toPx())
            close()
        }
        drawPath(roof, home)
        val pr = maxR * distanceM / scaleM
        val b = Math.toRadians(bearingDeg - 90)
        val p = Offset(center.x + pr * cos(b).toFloat(), center.y + pr * sin(b).toFloat())
        drawCircle(zoneColor.copy(alpha = (1 - ping) * 0.5f), 6.dp.toPx() + 14.dp.toPx() * ping, p)
        drawCircle(Color.White, 8.dp.toPx(), p)
        drawCircle(zoneColor, 6.dp.toPx(), p)
    }
}

@Composable
fun ConnectionPill(connection: ConnectionInfo, modifier: Modifier = Modifier) {
    val (color, label) = when (connection.status) {
        LinkStatus.LIVE -> Vita.Ok to ("En vivo" + (connection.lastLatencyMs?.let { " · $it ms" } ?: ""))
        LinkStatus.CONNECTING -> Vita.Amber to "Conectando…"
        LinkStatus.OFFLINE -> Vita.Coral to "Sin conexión"
    }
    val blink = rememberInfiniteTransition(label = "blink")
    val alpha by blink.animateFloat(0.35f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.13f), modifier = modifier) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(color.copy(alpha = if (connection.status == LinkStatus.LIVE) alpha else 1f), CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(label, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            if (connection.pendingWrites > 0) {
                Text(" · ${connection.pendingWrites} en cola", color = color, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
fun Chip(text: String, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.13f), modifier = modifier) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(text, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun Avatar(name: String, color: Color, size: Dp = 40.dp, online: Boolean = false, modifier: Modifier = Modifier) {
    Box(modifier.size(size)) {
        Box(
            Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.7f)))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.trim().split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifBlank { "?" },
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.36f).sp,
            )
        }
        if (online) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(size * 0.3f).clip(CircleShape)
                    .background(Vita.Ok).border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
            )
        }
    }
}

@Composable
fun VitaCard(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surface, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = color,
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun IconBadge(icon: ImageVector, color: Color, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = color, modifier = Modifier.size(size * 0.55f))
    }
}
