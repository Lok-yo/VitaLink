package com.vitalink.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vitalink.core.data.CareState
import com.vitalink.core.data.Profile
import com.vitalink.core.model.AlertStatus
import com.vitalink.core.util.Times
import com.vitalink.mobile.VitaLinkApp
import com.vitalink.mobile.ui.theme.Vita
import kotlinx.coroutines.launch

@Composable
fun AlertsScreen(app: VitaLinkApp, state: CareState, profile: Profile, now: Long, open: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val tz = state.patient?.timezone
    LazyColumn(
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.testTag("alerts_screen"),
    ) {
        item {
            Text("Alertas", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (state.openAlerts.isEmpty()) "Sin emergencias activas" else "${state.openAlerts.size} alerta(s) abiertas",
                color = if (state.openAlerts.isEmpty()) Vita.Ok else Vita.Coral,
            )
        }
        if (state.alerts.isEmpty()) {
            item { Text("Aquí aparecerán las caídas, SOS y anomalías detectadas por el reloj.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(state.alerts, key = { it.id ?: it.createdAt.orEmpty() }) { a ->
            val look = alertLook(a.type)
            val (statusText, statusColor) = alertStatusLabel(a.status)
            VitaCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(look.icon, look.color)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.title, fontWeight = FontWeight.Bold)
                        Text(
                            "${Times.hour(a.createdAt, tz)} · ${Times.relative(a.createdAt, now)} · ${deviceLabel(a.source)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Chip(statusText, statusColor)
                }
                a.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                val trail = buildList {
                    a.acknowledgedBy?.let { add("Atendió: $it (${Times.hour(a.acknowledgedAt, tz)})" + (a.etaMinutes?.let { m -> " · ETA $m min" } ?: "")) }
                    a.resolvedBy?.let { add("${if (a.status == AlertStatus.CANCELLED) "Canceló" else "Resolvió"}: $it (${Times.hour(a.resolvedAt, tz)})") }
                }
                if (trail.isNotEmpty()) Text(trail.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (a.isOpen) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (a.status == AlertStatus.ACTIVE) {
                            Button(
                                onClick = { if (isCriticalType(a.type)) a.id?.let(open) else scope.launch { app.repository.acknowledgeAlert(a, null) } },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Vita.Coral),
                            ) { Text("Yo atiendo") }
                        }
                        OutlinedButton(onClick = { scope.launch { app.repository.resolveAlert(a) } }, shape = RoundedCornerShape(12.dp)) {
                            Text("Marcar resuelta")
                        }
                    }
                }
            }
        }
    }
}
