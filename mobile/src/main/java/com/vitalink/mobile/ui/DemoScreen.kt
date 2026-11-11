package com.vitalink.mobile.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vitalink.core.data.CareState
import com.vitalink.core.model.SimCommand
import com.vitalink.mobile.VitaLinkApp
import com.vitalink.mobile.ui.theme.VIcons
import com.vitalink.mobile.ui.theme.Vita
import kotlinx.coroutines.launch

private data class DemoAction(val id: String, val label: String, val hint: String, val icon: ImageVector, val color: Color)

private val actions = listOf(
    DemoAction(SimCommand.FALL, "Caída", "Cuenta regresiva en el reloj", VIcons.Fall, Vita.Coral),
    DemoAction(SimCommand.SOS, "SOS", "Pánico desde el reloj", VIcons.Sos, Vita.CoralDark),
    DemoAction(SimCommand.TACHYCARDIA, "Taquicardia", "75 → 145 BPM en vivo", VIcons.Heart, Vita.Coral),
    DemoAction(SimCommand.BRADYCARDIA, "Bradicardia", "Pulso baja a ~40", VIcons.Heart, Vita.Violet),
    DemoAction(SimCommand.LEAVE_ZONE, "Sale de la zona", "Rompe la geocerca", VIcons.Place, Vita.Amber),
    DemoAction(SimCommand.WALK, "Paseo", "Camina dentro de casa", VIcons.Walk, Vita.Indigo),
    DemoAction(SimCommand.LOW_BATTERY, "Batería baja", "Reloj al 9 %", VIcons.Battery, Vita.Amber),
    DemoAction(SimCommand.NORMAL, "Normalizar", "Vuelve a casa y a 72 BPM", VIcons.Check, Vita.Ok),
)

@Composable
fun DemoScreen(app: VitaLinkApp, state: CareState, now: Long) {
    val scope = rememberCoroutineScope()
    val virtual by app.simulator.virtualWatch.collectAsState()
    var result by remember { mutableStateOf<String?>(null) }
    val watchOnline = state.realWatchOnline(now)

    LazyColumn(
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.testTag("demo_screen"),
    ) {
        item {
            Text("Modo simulación", style = MaterialTheme.typography.headlineSmall)
            Text("Dispara escenarios y mira cómo reaccionan el reloj y todos los teléfonos al mismo tiempo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            VitaCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(VIcons.Watch, if (watchOnline) Vita.Ok else Vita.Muted)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (watchOnline) "Reloj conectado" else "Reloj no detectado", fontWeight = FontWeight.Bold)
                        Text(
                            if (watchOnline) "Las órdenes viajan al reloj por Realtime Broadcast" else "El teléfono puede emular al reloj",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Reloj virtual en este teléfono", fontWeight = FontWeight.SemiBold)
                        Text("Publica signos simulados si no hay reloj", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = virtual, onCheckedChange = { app.simulator.setVirtualWatch(it) }, enabled = !watchOnline || virtual)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Metric("Latencia", state.connection.lastLatencyMs?.let { "$it ms" } ?: "—")
                    Metric("Dispositivos", "${state.devices.size}")
                    Metric("En cola", "${state.connection.pendingWrites}")
                }
            }
        }
        result?.let {
            item {
                Surface(shape = RoundedCornerShape(16.dp), color = Vita.Teal.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
                    Text(it, modifier = Modifier.padding(14.dp), color = Vita.TealDark, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        item { SectionTitle("Escenarios") }
        actions.chunked(2).forEach { row ->
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { a ->
                        Surface(
                            onClick = { scope.launch { result = "${a.label}: " + app.simulator.run(a.id) } },
                            shape = RoundedCornerShape(22.dp),
                            color = a.color.copy(alpha = 0.12f),
                            modifier = Modifier.weight(1f).height(104.dp).testTag("sim_${a.id}"),
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                Icon(a.icon, null, tint = a.color, modifier = Modifier.size(28.dp))
                                Column {
                                    Text(a.label, fontWeight = FontWeight.Bold, color = a.color)
                                    Text(a.hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
        item { SectionTitle("Hora de medicamento") }
        item {
            VitaCard {
                Text("Crea una toma para este instante: el reloj la muestra con vibración y cualquiera puede confirmarla.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.medications.filter { it.active }.forEach { med ->
                    Surface(
                        onClick = { med.id?.let { id -> scope.launch { result = "${med.name}: " + app.simulator.run("medication", id) } } },
                        shape = RoundedCornerShape(16.dp),
                        color = Vita.parse(med.color, Vita.Violet).copy(alpha = 0.12f),
                        modifier = Modifier.fillMaxWidth().testTag("sim_med_${med.name}"),
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(VIcons.Pill, null, tint = Vita.parse(med.color, Vita.Violet))
                            Spacer(Modifier.width(10.dp))
                            Text("${med.name} ${med.dosage}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Icon(VIcons.Play, null, tint = Vita.parse(med.color, Vita.Violet))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
