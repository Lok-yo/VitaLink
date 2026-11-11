package com.vitalink.wear.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.vitalink.core.data.CareState
import com.vitalink.core.data.LinkStatus
import com.vitalink.core.engine.VitalsReading
import com.vitalink.core.model.AlertType
import com.vitalink.core.model.DoseStatus
import com.vitalink.core.model.SimCommand
import com.vitalink.core.model.VitalStatus
import com.vitalink.core.util.Times
import com.vitalink.wear.WatchApp
import com.vitalink.wear.WatchBrain
import kotlinx.coroutines.delay

object W {
    val Red = Color(0xFFFF4D5A)
    val DeepRed = Color(0xFFB0121D)
    val Green = Color(0xFF3DDC84)
    val DeepGreen = Color(0xFF0B5D34)
    val Amber = Color(0xFFFFB020)
    val Blue = Color(0xFF6E95FF)
    val Violet = Color(0xFFA48BFF)
    val Gray = Color(0xFF2A2F36)
    val Muted = Color(0xFFB8C0CC)

    fun status(s: String?) = when (s) {
        VitalStatus.EMERGENCY -> Red
        VitalStatus.ATTENTION -> Amber
        else -> Green
    }

    fun parse(hex: String?, fallback: Color = Violet) = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)
}

@Composable
fun WatchRoot(app: WatchApp) {
    val repo = app.repository
    val brain = app.brain
    val st by repo.state.collectAsState()
    val reading by brain.reading.collectAsState()
    val countdown by brain.countdown.collectAsState()
    val dose by brain.dueDose.collectAsState()
    val toast by brain.toast.collectAsState()
    var hidden by remember { mutableStateOf(setOf<String>()) }
    val nav = rememberSwipeDismissableNavController()

    val alert = st.openAlerts
        .filter { it.type != AlertType.BATTERY && it.type != AlertType.MEDICATION && "${it.id}:${it.status}" !in hidden }
        .maxByOrNull { Times.millis(it.createdAt) }

    val overlayActive = countdown != null || alert != null || dose != null
    MaterialTheme {
        AppScaffold(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            timeText = { if (!overlayActive) TimeText() },
        ) {
            SwipeDismissableNavHost(navController = nav, startDestination = "home") {
                composable("home") {
                    HomeScreen(st, reading, brain, openDemo = { nav.navigate("demo") }, openMeds = { nav.navigate("meds") })
                }
                composable("demo") { DemoScreen(brain, done = { nav.popBackStack() }) }
                composable("meds") { MedsScreen(st, brain) }
            }
            val c = countdown
            val d = dose
            when {
                c != null -> CountdownOverlay(c, onCancel = brain::cancelCountdown, onSend = brain::sendNow)
                alert != null -> AlertOverlay(alert, st, onOkay = { brain.imOkay(alert) }, onHide = { hidden = hidden + "${alert.id}:${alert.status}" })
                d != null -> DoseOverlay(d, st, onTake = { brain.confirmDose(d) }, onSnooze = { brain.snoozeDose(d) })
            }
            toast?.let { ToastOverlay(it) }
        }
    }
}

@Composable
fun BeatingHeart(bpm: Int?, color: Color, size: Dp) {
    val scale = remember { Animatable(1f) }
    val current by rememberUpdatedState(bpm)
    LaunchedEffect(Unit) {
        while (true) {
            val b = (current ?: 0).coerceIn(0, 220)
            if (b < 30) {
                delay(500)
                continue
            }
            val period = 60_000L / b
            scale.animateTo(1.2f, tween((period * 0.2).toInt().coerceAtLeast(60), easing = FastOutSlowInEasing))
            scale.animateTo(0.95f, tween((period * 0.25).toInt().coerceAtLeast(60)))
            scale.animateTo(1f, tween((period * 0.55).toInt().coerceAtLeast(80)))
        }
    }
    Icon(WIcons.Heart, null, tint = color, modifier = Modifier.size(size).scale(scale.value))
}

@Composable
private fun VitalsDial(reading: VitalsReading?, stepGoal: Int, statusColor: Color) {
    val progress by animateFloatAsState(((reading?.steps ?: 0) / stepGoal.toFloat()).coerceIn(0f, 1f), tween(800), label = "steps")
    Box(Modifier.size(132.dp).testTag("vitals_dial"), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = 9.dp.toPx()
            val arc = Size(size.width - s, size.height - s)
            drawArc(W.Blue.copy(alpha = 0.22f), 135f, 270f, false, Offset(s / 2, s / 2), arc, style = Stroke(s, cap = StrokeCap.Round))
            drawArc(W.Blue, 135f, 270f * progress, false, Offset(s / 2, s / 2), arc, style = Stroke(s, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BeatingHeart(reading?.bpm, statusColor, 30.dp)
            Text(
                "${reading?.bpm ?: "--"}", fontSize = 44.sp, fontWeight = FontWeight.Black, color = Color.White,
                modifier = Modifier.testTag("watch_bpm"),
            )
            Text("BPM", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = W.Muted)
        }
        Row(Modifier.align(Alignment.BottomCenter), verticalAlignment = Alignment.CenterVertically) {
            Icon(WIcons.Walk, null, tint = W.Blue, modifier = Modifier.size(14.dp))
            Text("${reading?.steps ?: 0}", fontSize = 13.sp, color = W.Blue, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun HomeScreen(st: CareState, reading: VitalsReading?, brain: WatchBrain, openDemo: () -> Unit, openMeds: () -> Unit) {
    val list = rememberTransformingLazyColumnState()
    val patient = st.patient
    val status = reading?.status ?: VitalStatus.OK
    val statusColor = W.status(status)
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(5_000); value = System.currentTimeMillis() } }
    val sensorInfo by brain.sensorInfo.collectAsState()

    ScreenScaffold(scrollState = list) { padding ->
        TransformingLazyColumn(state = list, contentPadding = padding, modifier = Modifier.testTag("watch_home")) {
            item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { VitalsDial(reading, patient?.stepGoal ?: 4000, statusColor) } }
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Hola, ${patient?.nickname?.substringAfterLast(' ') ?: "Rosa"}",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    )
                    Text(
                        when {
                            reading == null -> "Conectando…"
                            !reading.inZone -> "Estás lejos de casa"
                            status == VitalStatus.EMERGENCY -> "Tu pulso no está bien"
                            status == VitalStatus.ATTENTION -> "Descansa un momento"
                            else -> "Todo bien hoy"
                        },
                        fontSize = 15.sp, color = statusColor, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    )
                }
            }
            item {
                Button(
                    onClick = { brain.startCountdown(AlertType.SOS) },
                    modifier = Modifier.fillMaxWidth().height(68.dp).testTag("watch_sos"),
                    colors = ButtonDefaults.buttonColors(containerColor = W.Red, contentColor = Color.White, secondaryContentColor = Color.White, iconColor = Color.White),
                    icon = { Icon(WIcons.Sos, null, modifier = Modifier.size(30.dp)) },
                    label = { Text("SOS", fontSize = 24.sp, fontWeight = FontWeight.Black) },
                    secondaryLabel = { Text("Pedir ayuda", fontSize = 13.sp) },
                )
            }
            item {
                val next = st.doses.filter { it.isOpen && Times.isToday(it.scheduledFor, patient?.timezone) }.minByOrNull { Times.millis(it.scheduledFor) }
                val med = next?.let { st.medication(it.medicationId) }
                Button(
                    onClick = openMeds,
                    modifier = Modifier.fillMaxWidth().testTag("watch_meds"),
                    colors = ButtonDefaults.buttonColors(containerColor = W.Gray, contentColor = Color.White, secondaryContentColor = W.Muted, iconColor = W.parse(med?.color)),
                    icon = { Icon(WIcons.Pill, null) },
                    label = { Text(med?.let { "${it.name} ${it.dosage}" } ?: "Sin medicinas", fontSize = 15.sp, maxLines = 1) },
                    secondaryLabel = { Text(next?.let { "${Times.hour(it.scheduledFor, patient?.timezone)} · ${Times.untilLabel(it.scheduledFor, now)}" } ?: "Al día", fontSize = 13.sp) },
                )
            }
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    MiniInfo(WIcons.Battery, "${reading?.battery ?: "--"}%", if ((reading?.battery ?: 100) <= 15) W.Red else W.Green)
                    MiniInfo(WIcons.Place, "${reading?.distanceM ?: 0} m", if (reading?.inZone == false) W.Amber else W.Green)
                }
            }
            item {
                val family = st.devices.values.count { it.ping.kind == "phone" && System.currentTimeMillis() - it.lastSeen < 30_000 }
                val (dot, label) = when (st.connection.status) {
                    LinkStatus.LIVE -> W.Green to if (family > 0) "$family familiar(es) conectados" else "Conectado a la nube"
                    LinkStatus.CONNECTING -> W.Amber to "Conectando…"
                    LinkStatus.OFFLINE -> W.Red to "Sin conexión"
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                    Spacer(Modifier.width(6.dp))
                    Text(label, fontSize = 12.sp, color = W.Muted, modifier = Modifier.testTag("watch_connection"))
                }
            }
            item {
                Button(
                    onClick = openDemo,
                    modifier = Modifier.fillMaxWidth().testTag("watch_demo"),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F2A44), contentColor = Color.White, iconColor = W.Amber),
                    icon = { Icon(WIcons.Bolt, null) },
                    label = { Text("Simulación", fontSize = 15.sp) },
                )
            }
            item { Text(sensorInfo, fontSize = 10.sp, color = W.Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun MiniInfo(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

private data class SimItem(val action: String, val label: String, val color: Color, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val simItems = listOf(
    SimItem(SimCommand.FALL, "Caída", W.Red, WIcons.Fall),
    SimItem(SimCommand.TACHYCARDIA, "Taquicardia", W.Red, WIcons.Heart),
    SimItem(SimCommand.BRADYCARDIA, "Pulso bajo", W.Violet, WIcons.Heart),
    SimItem(SimCommand.LEAVE_ZONE, "Salir de zona", W.Amber, WIcons.Place),
    SimItem("medication", "Medicina ahora", W.Violet, WIcons.Pill),
    SimItem(SimCommand.WALK, "Paseo", W.Blue, WIcons.Walk),
    SimItem(SimCommand.LOW_BATTERY, "Batería baja", W.Amber, WIcons.Battery),
    SimItem(SimCommand.NORMAL, "Normalizar", W.Green, WIcons.Check),
)

@Composable
private fun DemoScreen(brain: WatchBrain, done: () -> Unit) {
    val list = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = list) { padding ->
        TransformingLazyColumn(state = list, contentPadding = padding, modifier = Modifier.testTag("watch_demo_list")) {
            item { ListHeader { Text("Simulación", fontWeight = FontWeight.Bold) } }
            simItems.forEach { s ->
                item(key = s.action) {
                    Button(
                        onClick = {
                            brain.simulate(s.action)
                            done()
                        },
                        modifier = Modifier.fillMaxWidth().testTag("watch_sim_${s.action}"),
                        colors = ButtonDefaults.buttonColors(containerColor = s.color.copy(alpha = 0.22f), contentColor = Color.White, iconColor = s.color),
                        icon = { Icon(s.icon, null) },
                        label = { Text(s.label, fontSize = 15.sp) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MedsScreen(st: CareState, brain: WatchBrain) {
    val list = rememberTransformingLazyColumnState()
    val tz = st.patient?.timezone
    val today = st.doses.filter { Times.isToday(it.scheduledFor, tz) }.sortedBy { Times.millis(it.scheduledFor) }
    ScreenScaffold(scrollState = list) { padding ->
        TransformingLazyColumn(state = list, contentPadding = padding) {
            item { ListHeader { Text("Mis medicinas", fontWeight = FontWeight.Bold) } }
            today.forEach { d ->
                item(key = d.id ?: d.scheduledFor) {
                    val med = st.medication(d.medicationId)
                    val taken = d.status == DoseStatus.TAKEN
                    Button(
                        onClick = { if (d.isOpen) brain.confirmDose(d) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (taken) W.DeepGreen else W.Gray, contentColor = Color.White,
                            secondaryContentColor = if (taken) W.Green else W.Muted, iconColor = if (taken) W.Green else W.parse(med?.color),
                        ),
                        icon = { Icon(if (taken) WIcons.Check else WIcons.Pill, null) },
                        label = { Text("${Times.hour(d.scheduledFor, tz)} ${med?.name ?: ""}", fontSize = 15.sp, maxLines = 1) },
                        secondaryLabel = {
                            Text(
                                when (d.status) {
                                    DoseStatus.TAKEN -> "Tomada · ${d.confirmedBy ?: ""}"
                                    DoseStatus.SNOOZED -> "Pospuesta"
                                    DoseStatus.MISSED -> "No confirmada"
                                    DoseStatus.SKIPPED -> "Omitida"
                                    else -> "Toca para confirmar"
                                },
                                fontSize = 12.sp, maxLines = 1,
                            )
                        },
                    )
                }
            }
        }
    }
}
