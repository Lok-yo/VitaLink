package com.vitalink.wear.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import com.vitalink.core.data.CareState
import com.vitalink.core.model.Alert
import com.vitalink.core.model.AlertStatus
import com.vitalink.core.model.AlertType
import com.vitalink.core.model.MedicationDose
import com.vitalink.wear.Countdown
import com.vitalink.wear.WatchToast

/** Bloquea los toques hacia la pantalla de abajo. */
@Composable
private fun blockTouches(): Modifier =
    Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}

@Composable
private fun BigAction(text: String, color: Color, textColor: Color, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    Box(
        modifier.height(46.dp).clip(RoundedCornerShape(23.dp)).background(color).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = textColor, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(4.dp))
            }
            Text(text, color = textColor, fontSize = 15.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
fun CountdownOverlay(c: Countdown, onCancel: () -> Unit, onSend: () -> Unit) {
    val progress by animateFloatAsState(c.left / c.total.toFloat(), tween(900), label = "cd")
    val pulse = rememberInfiniteTransition(label = "cd")
    val glow by pulse.animateFloat(0.55f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "glow")
    val fall = c.type == AlertType.FALL
    Box(
        Modifier.fillMaxSize().background(Color.Black).background(Brush.radialGradient(listOf(W.DeepRed.copy(alpha = glow), Color.Black))).then(blockTouches()).testTag("watch_countdown"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(4.dp)) {
            val s = 7.dp.toPx()
            val arc = Size(size.width - s, size.height - s)
            drawArc(Color.White.copy(alpha = 0.15f), -90f, 360f, false, Offset(s / 2, s / 2), arc, style = Stroke(s))
            drawArc(W.Red, -90f, 360f * progress, false, Offset(s / 2, s / 2), arc, style = Stroke(s, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 26.dp)) {
            Text(if (fall) "¿Te caíste?" else "Enviando SOS", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("${c.left}", fontSize = 44.sp, fontWeight = FontWeight.Black, color = Color.White, modifier = Modifier.testTag("watch_countdown_value"))
            Spacer(Modifier.height(4.dp))
            BigAction("ESTOY BIEN", W.Green, Color.Black, Modifier.fillMaxWidth().testTag("watch_cancel"), WIcons.Check, onCancel)
            Text(
                "Pedir ayuda ya", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 2.dp).clickable(onClick = onSend).padding(6.dp),
            )
        }
    }
}

@Composable
fun AlertOverlay(alert: Alert, st: CareState, onOkay: () -> Unit, onHide: () -> Unit) {
    val acknowledged = alert.status == AlertStatus.ACKNOWLEDGED
    val pulse = rememberInfiniteTransition(label = "alert")
    val wave by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1600)), label = "wave")
    val base = if (acknowledged) W.DeepGreen else W.DeepRed
    val accent = if (acknowledged) W.Green else W.Red
    Box(
        Modifier.fillMaxSize().background(Color.Black).background(Brush.radialGradient(listOf(base, Color.Black))).then(blockTouches()).testTag("watch_alert"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            for (i in 0..1) {
                val p = (wave + i / 2f) % 1f
                drawCircle(accent.copy(alpha = (1 - p) * 0.45f), radius = size.minDimension / 2 * (0.35f + p * 0.65f), style = Stroke(3.dp.toPx()))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.padding(horizontal = 24.dp)) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                Icon(if (acknowledged) WIcons.Shield else alertIcon(alert.type), null, tint = if (acknowledged) W.DeepGreen else W.DeepRed, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(4.dp))
            if (acknowledged) {
                Text(
                    "${alert.acknowledgedBy ?: "Tu familia"} " + if (alert.etaMinutes == 0) "está contigo" else "va en camino", fontSize = 15.sp, fontWeight = FontWeight.Black, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.testTag("watch_help_coming"))
                Text(
                    if (alert.etaMinutes == 0) "Ya está contigo" else "Llega en ~${alert.etaMinutes ?: "?"} min",
                    fontSize = 12.sp, color = W.Green, fontWeight = FontWeight.Bold,
                )
            } else {
                Text("Avisamos a tu familia", fontSize = 15.sp, fontWeight = FontWeight.Black, color = Color.White, textAlign = TextAlign.Center)
                Text(alert.title, fontSize = 12.sp, color = W.Amber, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1)
            }
            Spacer(Modifier.height(5.dp))
            BigAction("ESTOY BIEN", Color.White, Color.Black, Modifier.fillMaxWidth().testTag("watch_im_ok"), WIcons.Check, onOkay)
            if (acknowledged) {
                Text("Ocultar", fontSize = 11.sp, color = W.Muted, modifier = Modifier.padding(top = 2.dp).clickable(onClick = onHide).padding(4.dp))
            }
        }
    }
}

private fun alertIcon(type: String) = when (type) {
    AlertType.FALL -> WIcons.Fall
    AlertType.GEOFENCE -> WIcons.Place
    AlertType.TACHYCARDIA, AlertType.BRADYCARDIA -> WIcons.Heart
    else -> WIcons.Sos
}

@Composable
fun DoseOverlay(dose: MedicationDose, st: CareState, onTake: () -> Unit, onSnooze: () -> Unit) {
    val med = st.medication(dose.medicationId)
    val color = W.parse(med?.color)
    val pulse = rememberInfiniteTransition(label = "dose")
    val scale by pulse.animateFloat(0.92f, 1.08f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "s")
    Box(
        Modifier.fillMaxSize().background(Color.Black).background(Brush.radialGradient(listOf(color.copy(alpha = 0.45f), Color.Black))).then(blockTouches()).testTag("watch_dose"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 24.dp)) {
            Box(Modifier.size((30 * scale).dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                Icon(WIcons.Pill, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Text("Hora de tu medicina", fontSize = 11.sp, color = W.Muted)
            Text(med?.name ?: "Medicina", fontSize = 18.sp, fontWeight = FontWeight.Black, color = Color.White, maxLines = 1)
            Text(med?.dosage ?: "", fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center, maxLines = 1)
            Spacer(Modifier.height(5.dp))
            BigAction("YA LA TOMÉ", W.Green, Color.Black, Modifier.fillMaxWidth().testTag("watch_take"), WIcons.Check, onTake)
            Text(
                "Recordar en 10 min", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 2.dp).clickable(onClick = onSnooze).padding(6.dp).testTag("watch_snooze"),
            )
        }
    }
}

@Composable
fun ToastOverlay(t: WatchToast) {
    Box(Modifier.fillMaxSize().padding(top = 30.dp, start = 26.dp, end = 26.dp), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(if (t.positive) Color(0xFF14532D) else W.DeepRed).padding(horizontal = 14.dp, vertical = 8.dp)
                .testTag("watch_toast"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(t.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
            if (t.text.isNotBlank()) Text(t.text, fontSize = 11.sp, color = Color.White.copy(alpha = 0.9f), textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}
