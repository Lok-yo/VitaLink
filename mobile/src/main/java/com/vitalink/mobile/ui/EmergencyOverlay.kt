package com.vitalink.mobile.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vitalink.core.data.CareState
import com.vitalink.core.model.Alert
import com.vitalink.core.model.AlertStatus
import com.vitalink.core.util.Times
import com.vitalink.mobile.VitaLinkApp
import com.vitalink.mobile.ui.theme.NumberStyle
import com.vitalink.mobile.ui.theme.VIcons
import com.vitalink.mobile.ui.theme.Vita
import kotlinx.coroutines.launch

@Composable
fun EmergencyOverlay(app: VitaLinkApp, alert: Alert, state: CareState, me: String, now: Long, onMinimize: () -> Unit) {
    val look = alertLook(alert.type)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val acknowledged = alert.status == AlertStatus.ACKNOWLEDGED
    val mine = acknowledged && alert.acknowledgedBy == me
    var eta by remember { mutableStateOf(10) }
    var busy by remember { mutableStateOf(false) }
    var lostRace by remember { mutableStateOf<String?>(null) }
    val patient = state.patient
    val vitals = state.vitals

    val pulse = rememberInfiniteTransition(label = "emergency")
    val wave by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1600)), label = "wave")
    val glow by pulse.animateFloat(0.75f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "glow")
    val top by animateColorAsState(if (acknowledged) Color(0xFF2B3FD6) else Color(0xFFE5484D).copy(alpha = glow), label = "top")
    val bottom by animateColorAsState(if (acknowledged) Color(0xFF151C5C) else Color(0xFF7A0E14), label = "bottom")

    Box(
        Modifier
            .fillMaxSize()
            .background(bottom)
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .testTag("emergency_overlay"),
    ) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.18f)) {
                    Text(
                        if (acknowledged) "EN ATENCIÓN" else "EMERGENCIA ACTIVA",
                        color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onMinimize) { Text("Ver panel", color = Color.White) }
            }

            Box(Modifier.size(170.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    for (i in 0..2) {
                        val p = (wave + i / 3f) % 1f
                        drawCircle(Color.White.copy(alpha = (1 - p) * 0.35f), radius = size.minDimension / 2 * (0.45f + p * 0.55f), style = Stroke(3.dp.toPx()))
                    }
                }
                Box(Modifier.size(92.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Icon(if (acknowledged) VIcons.Shield else look.icon, null, tint = if (acknowledged) Vita.Indigo else Vita.Coral, modifier = Modifier.size(52.dp))
                }
            }

            Text(alert.title, color = Color.White, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Text(
                (alert.message ?: "") + "\n${Times.relative(alert.createdAt, now).replaceFirstChar { it.uppercase() }} · ${deviceLabel(alert.source)}",
                color = Color.White.copy(alpha = 0.88f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                LiveTile("Pulso ahora", "${vitals?.bpm ?: "--"}", "BPM", Modifier.weight(1f)) {
                    BeatingHeart(vitals?.bpm, Color.White, 34.dp)
                }
                LiveTile(
                    "Ubicación", "${vitals?.distanceHomeM ?: 0}", "m de casa", Modifier.weight(1f),
                ) { Icon(VIcons.Place, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
            }

            if (!acknowledged) {
                Text("¿En cuánto llegas?", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(0, 5, 10, 20).forEach { m ->
                        val selected = eta == m
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (selected) Color.White else Color.White.copy(alpha = 0.16f),
                            modifier = Modifier.weight(1f).clickable { eta = m },
                        ) {
                            Text(
                                if (m == 0) "Estoy ahí" else "$m min",
                                color = if (selected) Vita.CoralDark else Color.White, fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center, maxLines = 1,
                                modifier = Modifier.padding(vertical = 9.dp),
                            )
                        }
                    }
                }
                Button(
                    onClick = {
                        busy = true
                        scope.launch {
                            val ok = app.repository.acknowledgeAlert(alert, eta)
                            if (!ok) lostRace = "Otro familiar se adelantó y ya la está atendiendo."
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(68.dp).testTag("ack_button"),
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Vita.CoralDark),
                ) {
                    Icon(VIcons.Shield, null)
                    Spacer(Modifier.width(10.dp))
                    Text("YO ATIENDO LA EMERGENCIA", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "Al tocarlo, la familia y el reloj de ${patient?.nickname ?: "tu familiar"} sabrán al instante que vas en camino.",
                    color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                )
            } else {
                Surface(shape = RoundedCornerShape(22.dp), color = Color.White.copy(alpha = 0.14f), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (mine) "Tú atiendes esta emergencia" else "${alert.acknowledgedBy} va en camino",
                            color = Color.White, style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            buildString {
                                append(if (alert.etaMinutes == 0) "Ya está con ${patient?.nickname ?: "el paciente"}" else "Llega en ~${alert.etaMinutes ?: "?"} min")
                                append(" · ${Times.relative(alert.acknowledgedAt, now)}")
                                append("\nEl reloj ya muestra que la ayuda va en camino.")
                            },
                            color = Color.White.copy(alpha = 0.85f),
                        )
                    }
                }
                if (mine) {
                    Button(
                        onClick = { scope.launch { app.repository.resolveAlert(alert) } },
                        modifier = Modifier.fillMaxWidth().height(60.dp).testTag("resolve_button"),
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Vita.Indigo),
                    ) {
                        Icon(VIcons.Check, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Todo bien: marcar como resuelta", fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = onMinimize,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Vita.Indigo),
                    ) { Text("Entendido", fontWeight = FontWeight.Bold) }
                }
            }

            lostRace?.let {
                Text(it, color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        patient?.emergencyPhone?.let { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$it")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                ) {
                    Icon(VIcons.Call, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Llamar")
                }
                OutlinedButton(
                    onClick = {
                        val lat = alert.lat ?: vitals?.lat
                        val lng = alert.lng ?: vitals?.lng
                        if (lat != null && lng != null) {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                ) {
                    Icon(VIcons.Place, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Ubicación")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun LiveTile(label: String, value: String, unit: String, modifier: Modifier, icon: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = Color.White.copy(alpha = 0.14f), modifier = modifier) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(10.dp))
            Column {
                Text(label, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(value, color = Color.White, style = NumberStyle.copy(fontSize = MaterialTheme.typography.headlineMedium.fontSize))
                    Spacer(Modifier.width(4.dp))
                    Text(unit, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 4.dp))
                }
            }
        }
    }
}
