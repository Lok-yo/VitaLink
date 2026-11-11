package com.vitalink.mobile.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitalink.core.data.CareState
import com.vitalink.core.data.LinkStatus
import com.vitalink.core.data.Profile
import com.vitalink.core.engine.Geo
import com.vitalink.core.model.VitalStatus
import com.vitalink.core.util.Times
import com.vitalink.mobile.VitaLinkApp
import com.vitalink.mobile.ui.theme.NumberStyle
import com.vitalink.mobile.ui.theme.VIcons
import com.vitalink.mobile.ui.theme.Vita
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(
    app: VitaLinkApp,
    state: CareState,
    profile: Profile,
    now: Long,
    openTab: (Tab) -> Unit,
    openAlert: (String) -> Unit,
) {
    val patient = state.patient
    val vitals = state.vitals
    val emergency = state.openAlerts.any { isCriticalType(it.type) && it.status == "active" }
    val status = if (emergency) VitalStatus.EMERGENCY else vitals?.status ?: VitalStatus.OK
    val statusColor by animateColorAsState(Vita.status(status), tween(600), label = "status")
    val scope = rememberCoroutineScope()

    LazyColumn(
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.testTag("dashboard"),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Hola, ${profile.name}", style = MaterialTheme.typography.headlineSmall)
                    Text(Times.today(patient?.timezone), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ConnectionPill(state.connection, Modifier.testTag("connection_pill"))
            }
        }

        val open = state.openAlerts
        if (open.isNotEmpty()) {
            item {
                val a = open.first()
                val look = alertLook(a.type)
                val (statusText, sc) = alertStatusLabel(a.status)
                Surface(
                    shape = RoundedCornerShape(20.dp), color = look.color.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth().clickable { a.id?.let(openAlert) },
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(look.icon, look.color)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.title, fontWeight = FontWeight.Bold, color = look.color)
                            Text(
                                if (a.acknowledgedBy != null) "${a.acknowledgedBy} la atiende · ${Times.relative(a.createdAt, now)}" else "Sin atender · ${Times.relative(a.createdAt, now)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Chip(statusText, sc)
                    }
                }
            }
        }

        item {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(listOf(statusColor, statusColor.copy(alpha = 0.75f), Vita.TealDark.copy(alpha = 0.9f))))
                    .testTag("hero_card"),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(patient?.nickname ?: "Paciente", Color.White.copy(alpha = 0.25f), 52.dp, online = state.watchOnline(now))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(patient?.nickname ?: "Cargando…", color = Color.White, style = MaterialTheme.typography.titleLarge)
                            Text(
                                listOfNotNull(patient?.age?.let { "$it años" }, patient?.bloodType, patient?.conditions).joinToString(" · "),
                                color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            )
                        }
                        Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.2f)) {
                            Text(
                                vitalStatusLabel(status), color = Color.White, fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BeatingHeart(vitals?.bpm, Color.White, 74.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    "${vitals?.bpm ?: "--"}", color = Color.White,
                                    style = NumberStyle.copy(fontSize = 64.sp), modifier = Modifier.testTag("bpm_value"),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("BPM", color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
                            }
                            Text(
                                buildString {
                                    append(vitals?.activity?.replaceFirstChar { it.uppercase() } ?: "Sin datos")
                                    if (state.vitalsReceivedAt > 0) append(" · ${Times.relativeMillis(now - state.vitalsReceivedAt)}")
                                },
                                color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val watchLive = state.watchOnline(now)
                        HeroTag(if (watchLive) VIcons.Watch else VIcons.Cloud, if (watchLive) "Reloj conectado" else "Reloj sin señal")
                        HeroTag(VIcons.Bolt, if (vitals?.sensorMode == "sensor") "Sensor real" else if (vitals?.source == "phone-sim") "Reloj virtual" else "Sensor simulado")
                    }
                }
            }
        }

        item {
            VitaCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ritmo cardíaco en vivo", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Chip("Normal 50–105", Vita.Ok)
                }
                BpmChart(state.bpmHistory, statusColor, Modifier.fillMaxWidth().height(130.dp))
                val bpms = state.bpmHistory.takeLast(60).map { it.bpm }
                if (bpms.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        MiniStat("Mín", "${bpms.min()}")
                        MiniStat("Prom", "${bpms.average().toInt()}")
                        MiniStat("Máx", "${bpms.max()}")
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val goal = patient?.stepGoal ?: 4000
                val steps = vitals?.steps ?: 0
                StatCard("Pasos", "$steps", "meta $goal", Modifier.weight(1f)) {
                    ProgressRing(steps / goal.toFloat(), Vita.Indigo, Modifier.size(46.dp), stroke = 6.dp) {
                        Icon(VIcons.Walk, null, tint = Vita.Indigo, modifier = Modifier.size(20.dp))
                    }
                }
                val battery = vitals?.battery ?: 0
                val bColor = if (battery <= 15) Vita.Coral else if (battery <= 30) Vita.Amber else Vita.Ok
                StatCard("Batería", "$battery%", if (vitals?.charging == true) "cargando" else "reloj", Modifier.weight(1f)) {
                    ProgressRing(battery / 100f, bColor, Modifier.size(46.dp), stroke = 6.dp) {
                        Icon(VIcons.Battery, null, tint = bColor, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        item {
            val inZone = vitals?.inSafeZone ?: true
            val distance = vitals?.distanceHomeM ?: 0
            val bearing = if (patient != null && vitals?.lat != null && vitals.lng != null) {
                Geo.bearing(patient.homeLat, patient.homeLng, vitals.lat!!, vitals.lng!!)
            } else 0.0
            VitaCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Ubicación y zona segura", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (inZone) "En ${patient?.homeLabel ?: "casa"} · a $distance m" else "Fuera de la zona · a $distance m de casa",
                            color = if (inZone) Vita.Ok else Vita.Coral, fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Chip("Radio ${patient?.safeRadiusM ?: 250} m", MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SafeZoneRadar(distance, patient?.safeRadiusM ?: 250, bearing, inZone, Modifier.fillMaxWidth().height(190.dp))
            }
        }

        item {
            val next = state.doses.filter { it.isOpen && Times.isToday(it.scheduledFor, patient?.timezone) }
                .minByOrNull { Times.millis(it.scheduledFor) }
            VitaCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Próxima medicina", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("Ver todas", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { openTab(Tab.Meds) })
                }
                if (next == null) {
                    Text("No quedan tomas pendientes hoy.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val med = state.medication(next.medicationId)
                    val due = Times.millis(next.scheduledFor) <= now
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(VIcons.Pill, Vita.parse(med?.color, Vita.Violet), 48.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${med?.name ?: "Medicina"} ${med?.dosage ?: ""}", fontWeight = FontWeight.Bold)
                            Text(
                                "${Times.hour(next.scheduledFor, patient?.timezone)} · " + if (due) "le toca ahora" else Times.untilLabel(next.scheduledFor, now),
                                color = if (due) Vita.Coral else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Button(
                            onClick = { scope.launch { app.repository.confirmDose(next) } },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Vita.Teal),
                        ) { Text("Se la di") }
                    }
                }
            }
        }

        item {
            VitaCard {
                Text("Círculo de cuidado", style = MaterialTheme.typography.titleMedium)
                val onlineNames = state.devices.values.filter { now - it.lastSeen < 30_000 }.map { it.ping.name }.toSet()
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(state.caregivers.sortedBy { it.name }) { c ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
                            Avatar(c.name, Vita.parse(c.color), 48.dp, online = c.name in onlineNames)
                            Text(c.name, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            Text(c.relation, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
                Text(
                    if (state.connection.status == LinkStatus.LIVE) "${state.devices.size} dispositivo(s) conectados ahora por Realtime"
                    else "Sin conexión: mostrando la última información guardada",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HeroTag(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.18f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun StatCard(label: String, value: String, sub: String, modifier: Modifier, icon: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp, modifier = modifier) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(12.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.titleLarge)
                Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
