package com.vitalink.mobile.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vitalink.core.data.CareState
import com.vitalink.core.model.DoseStatus
import com.vitalink.core.model.MedicationDose
import com.vitalink.core.util.Times
import com.vitalink.mobile.VitaLinkApp
import com.vitalink.mobile.ui.theme.VIcons
import com.vitalink.mobile.ui.theme.Vita
import kotlinx.coroutines.launch

@Composable
fun MedsScreen(app: VitaLinkApp, state: CareState, now: Long) {
    val tz = state.patient?.timezone
    val today = state.doses.filter { Times.isToday(it.scheduledFor, tz) }.sortedBy { Times.millis(it.scheduledFor) }
    val taken = today.count { it.status == DoseStatus.TAKEN }
    var adding by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LazyColumn(
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.testTag("meds_screen"),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Medicinas", style = MaterialTheme.typography.headlineSmall)
                    Text(Times.today(tz), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ProgressRing(if (today.isEmpty()) 0f else taken / today.size.toFloat(), Vita.Teal, Modifier.size(64.dp)) {
                    Text("$taken/${today.size}", fontWeight = FontWeight.Bold)
                }
            }
        }
        message?.let { m ->
            item {
                Surface(shape = RoundedCornerShape(16.dp), color = Vita.Amber.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth().clickable { message = null }) {
                    Text(m, modifier = Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        item { SectionTitle("Hoy") }
        if (today.isEmpty()) item { Text("No hay tomas programadas para hoy.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(today, key = { it.id ?: it.scheduledFor }) { dose ->
            DoseRow(
                state, dose, now,
                onConfirm = {
                    scope.launch {
                        if (!app.repository.confirmDose(dose)) message = "Alguien más ya registró esta toma. La lista ya muestra quién fue."
                    }
                },
                onSnooze = { scope.launch { app.repository.snoozeDose(dose) } },
                onSkip = { scope.launch { app.repository.skipDose(dose) } },
            )
        }
        item {
            SectionTitle("Tratamientos") {
                FilledTonalButton(onClick = { adding = true }, modifier = Modifier.testTag("add_med")) {
                    Icon(VIcons.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Agregar")
                }
            }
        }
        items(state.medications.sortedBy { it.scheduleTimes.firstOrNull() ?: "99" }, key = { it.id ?: it.name }) { med ->
            VitaCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(VIcons.Pill, Vita.parse(med.color, Vita.Violet))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${med.name} ${med.dosage}", fontWeight = FontWeight.Bold)
                        med.instructions?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Switch(checked = med.active, onCheckedChange = { checked -> scope.launch { app.repository.setMedicationActive(med, checked) } })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    med.scheduleTimes.forEach { Chip(it, Vita.parse(med.color, Vita.Violet)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { med.id?.let { id -> scope.launch { app.repository.triggerDoseNow(id) } } }) {
                        Text("Recordar ahora")
                    }
                }
            }
        }
    }

    if (adding) AddMedicationDialog(onDismiss = { adding = false }) { name, dosage, times, color, instructions ->
        adding = false
        scope.launch { app.repository.addMedication(name, dosage, times, color, instructions) }
    }
}

@Composable
private fun DoseRow(state: CareState, dose: MedicationDose, now: Long, onConfirm: () -> Unit, onSnooze: () -> Unit, onSkip: () -> Unit) {
    val med = state.medication(dose.medicationId)
    val color = Vita.parse(med?.color, Vita.Violet)
    val (label, statusColor) = doseLook(dose)
    val due = dose.isOpen && Times.millis(dose.snoozedUntil ?: dose.scheduledFor) <= now
    Row(Modifier.fillMaxWidth().animateContentSize(), verticalAlignment = Alignment.Top) {
        Column(Modifier.width(56.dp).padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Times.hour(dose.scheduledFor, state.patient?.timezone), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.size(12.dp).clip(CircleShape).background(if (dose.status == DoseStatus.TAKEN) Vita.Ok else color))
        }
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (due) Vita.Coral.copy(alpha = 0.08f).compositeOver(MaterialTheme.colorScheme.surface) else MaterialTheme.colorScheme.surface,
            shadowElevation = 1.dp,
            modifier = Modifier.weight(1f),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${med?.name ?: "Medicina"} ${med?.dosage ?: ""}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Chip(if (due && dose.status == DoseStatus.PENDING) "Le toca ahora" else label, if (due) Vita.Coral else statusColor)
                }
                val detail = when (dose.status) {
                    DoseStatus.TAKEN -> "Confirmó ${dose.confirmedBy ?: "alguien"} ${deviceLabel(dose.confirmedDevice)} · ${Times.hour(dose.confirmedAt, state.patient?.timezone)}"
                    DoseStatus.SNOOZED -> "${dose.confirmedBy ?: "Alguien"} la pospuso hasta las ${Times.hour(dose.snoozedUntil, state.patient?.timezone)}"
                    DoseStatus.SKIPPED -> "${dose.confirmedBy ?: "Alguien"} la omitió"
                    DoseStatus.MISSED -> "Pasó una hora sin confirmación"
                    else -> med?.instructions ?: ""
                }
                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (dose.isOpen) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = onConfirm,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Vita.Teal),
                            modifier = Modifier.testTag("confirm_dose"),
                        ) {
                            Icon(VIcons.Check, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Confirmar toma")
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onSnooze) { Icon(VIcons.Snooze, "Posponer 10 min", tint = Vita.Amber) }
                        IconButton(onClick = onSkip) { Icon(VIcons.Close, "Omitir", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

private val presetTimes = listOf("07:00", "08:00", "09:00", "12:00", "13:00", "14:00", "15:00", "18:00", "20:00", "21:00", "22:00")
private val pillColors = listOf("#7C5CFF", "#FF8A3D", "#F5B700", "#18A0FB", "#E8618C", "#0E9F8E")

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AddMedicationDialog(onDismiss: () -> Unit, onSave: (String, String, List<String>, String, String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var dosage by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    var times by remember { mutableStateOf(setOf("09:00")) }
    var color by remember { mutableStateOf(pillColors.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nueva medicina") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(dosage, { dosage = it }, label = { Text("Dosis (ej. 500 mg)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(instructions, { instructions = it }, label = { Text("Indicaciones (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Horarios", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    presetTimes.forEach { t ->
                        FilterChip(selected = t in times, onClick = { times = if (t in times) times - t else times + t }, label = { Text(t) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pillColors.forEach { c ->
                        Box(
                            Modifier.size(if (c == color) 34.dp else 28.dp).clip(CircleShape).background(Vita.parse(c)).clickable { color = c },
                            contentAlignment = Alignment.Center,
                        ) { if (c == color) Icon(VIcons.Check, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && dosage.isNotBlank() && times.isNotEmpty(),
                onClick = { onSave(name.trim(), dosage.trim(), times.toList(), color, instructions.trim().ifBlank { null }) },
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
