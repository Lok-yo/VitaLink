package com.vitalink.mobile.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.vitalink.core.model.AlertStatus
import com.vitalink.core.model.AlertType
import com.vitalink.core.model.DoseStatus
import com.vitalink.core.model.MedicationDose
import com.vitalink.core.model.VitalStatus
import com.vitalink.mobile.ui.theme.VIcons
import com.vitalink.mobile.ui.theme.Vita

data class AlertLook(val label: String, val icon: ImageVector, val color: Color)

fun alertLook(type: String): AlertLook = when (type) {
    AlertType.SOS -> AlertLook("SOS", VIcons.Sos, Vita.Coral)
    AlertType.FALL -> AlertLook("Caída", VIcons.Fall, Vita.Coral)
    AlertType.TACHYCARDIA -> AlertLook("Taquicardia", VIcons.Heart, Vita.Coral)
    AlertType.BRADYCARDIA -> AlertLook("Bradicardia", VIcons.Heart, Vita.Violet)
    AlertType.GEOFENCE -> AlertLook("Zona segura", VIcons.Place, Vita.Amber)
    AlertType.BATTERY -> AlertLook("Batería", VIcons.Battery, Vita.Amber)
    AlertType.MEDICATION -> AlertLook("Medicina", VIcons.Pill, Vita.Violet)
    else -> AlertLook("Alerta", VIcons.Warning, Vita.Coral)
}

fun alertStatusLabel(status: String): Pair<String, Color> = when (status) {
    AlertStatus.ACTIVE -> "Sin atender" to Vita.Coral
    AlertStatus.ACKNOWLEDGED -> "En atención" to Vita.Indigo
    AlertStatus.RESOLVED -> "Resuelta" to Vita.Ok
    AlertStatus.CANCELLED -> "Falsa alarma" to Vita.Muted
    else -> status to Vita.Muted
}

fun vitalStatusLabel(status: String?): String = when (status) {
    VitalStatus.EMERGENCY -> "Emergencia"
    VitalStatus.ATTENTION -> "Requiere atención"
    else -> "Todo en orden"
}

fun deviceLabel(device: String?): String = when (device) {
    "watch" -> "desde el reloj"
    "phone" -> "desde el teléfono"
    "phone-sim" -> "reloj virtual"
    "cloud" -> "automático"
    else -> ""
}

fun doseLook(dose: MedicationDose): Pair<String, Color> = when (dose.status) {
    DoseStatus.TAKEN -> "Tomada" to Vita.Ok
    DoseStatus.SNOOZED -> "Pospuesta" to Vita.Amber
    DoseStatus.SKIPPED -> "Omitida" to Vita.Muted
    DoseStatus.MISSED -> "No confirmada" to Vita.Coral
    else -> "Pendiente" to Vita.Indigo
}

/** Tipos que abren la pantalla completa de emergencia. */
fun isCriticalType(type: String) = type != AlertType.BATTERY && type != AlertType.MEDICATION
