package com.vitalink.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Patient(
    val id: String,
    @SerialName("full_name") val fullName: String,
    val nickname: String,
    val age: Int? = null,
    @SerialName("blood_type") val bloodType: String? = null,
    val conditions: String? = null,
    @SerialName("emergency_phone") val emergencyPhone: String? = null,
    val timezone: String = "America/Hermosillo",
    @SerialName("home_lat") val homeLat: Double,
    @SerialName("home_lng") val homeLng: Double,
    @SerialName("home_label") val homeLabel: String = "Casa",
    @SerialName("safe_radius_m") val safeRadiusM: Int = 250,
    @SerialName("step_goal") val stepGoal: Int = 4000,
    @SerialName("invite_code") val inviteCode: String? = null,
)

object MemberRole {
    const val OWNER = "owner"
    const val CAREGIVER = "caregiver"
    const val DEVICE = "device"
}

/** Miembro del círculo de cuidado: un familiar con cuenta o el reloj vinculado. */
@Serializable
data class Caregiver(
    val id: String? = null,
    @SerialName("patient_id") val patientId: String,
    @SerialName("user_id") val userId: String? = null,
    val name: String,
    val relation: String,
    val color: String = "#4F7CFF",
    val phone: String? = null,
    val role: String = MemberRole.CAREGIVER,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
)

/** Una membresía con los datos de su paciente (para el selector de círculos). */
@Serializable
data class Membership(
    @SerialName("patient_id") val patientId: String,
    val name: String,
    val relation: String,
    val color: String = "#4F7CFF",
    val role: String = MemberRole.CAREGIVER,
    val patients: Patient? = null,
)

object VitalStatus {
    const val OK = "ok"
    const val ATTENTION = "attention"
    const val EMERGENCY = "emergency"
}

@Serializable
data class VitalsLive(
    @SerialName("patient_id") val patientId: String,
    val bpm: Int? = null,
    val steps: Int = 0,
    val battery: Int? = null,
    val charging: Boolean = false,
    val lat: Double? = null,
    val lng: Double? = null,
    @SerialName("distance_home_m") val distanceHomeM: Int? = null,
    @SerialName("in_safe_zone") val inSafeZone: Boolean = true,
    val status: String = VitalStatus.OK,
    val activity: String? = null,
    val source: String = "watch",
    @SerialName("sensor_mode") val sensorMode: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class VitalsSample(
    @SerialName("patient_id") val patientId: String,
    val bpm: Int? = null,
    val steps: Int? = null,
    val battery: Int? = null,
    @SerialName("recorded_at") val recordedAt: String? = null,
)

@Serializable
data class Medication(
    val id: String? = null,
    @SerialName("patient_id") val patientId: String,
    val name: String,
    val dosage: String,
    val instructions: String? = null,
    @SerialName("schedule_times") val scheduleTimes: List<String> = emptyList(),
    val color: String = "#7C5CFF",
    val active: Boolean = true,
    @SerialName("created_by") val createdBy: String? = null,
)

object DoseStatus {
    const val PENDING = "pending"
    const val SNOOZED = "snoozed"
    const val TAKEN = "taken"
    const val SKIPPED = "skipped"
    const val MISSED = "missed"
}

@Serializable
data class MedicationDose(
    val id: String? = null,
    @SerialName("medication_id") val medicationId: String,
    @SerialName("patient_id") val patientId: String,
    @SerialName("scheduled_for") val scheduledFor: String,
    val status: String = DoseStatus.PENDING,
    @SerialName("snoozed_until") val snoozedUntil: String? = null,
    @SerialName("snooze_count") val snoozeCount: Int = 0,
    @SerialName("confirmed_by") val confirmedBy: String? = null,
    @SerialName("confirmed_device") val confirmedDevice: String? = null,
    @SerialName("confirmed_at") val confirmedAt: String? = null,
) {
    val isOpen: Boolean get() = status == DoseStatus.PENDING || status == DoseStatus.SNOOZED
}

object AlertType {
    const val SOS = "sos"
    const val FALL = "fall"
    const val TACHYCARDIA = "tachycardia"
    const val BRADYCARDIA = "bradycardia"
    const val GEOFENCE = "geofence"
    const val MEDICATION = "medication"
    const val BATTERY = "battery"
}

object AlertStatus {
    const val ACTIVE = "active"
    const val ACKNOWLEDGED = "acknowledged"
    const val RESOLVED = "resolved"
    const val CANCELLED = "cancelled"
}

@Serializable
data class Alert(
    val id: String? = null,
    @SerialName("patient_id") val patientId: String,
    val type: String,
    val severity: String = "critical",
    val status: String = AlertStatus.ACTIVE,
    val title: String,
    val message: String? = null,
    val bpm: Int? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val source: String = "watch",
    @SerialName("acknowledged_by") val acknowledgedBy: String? = null,
    @SerialName("acknowledged_at") val acknowledgedAt: String? = null,
    @SerialName("eta_minutes") val etaMinutes: Int? = null,
    @SerialName("resolved_by") val resolvedBy: String? = null,
    @SerialName("resolved_at") val resolvedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    val isOpen: Boolean get() = status == AlertStatus.ACTIVE || status == AlertStatus.ACKNOWLEDGED
}

@Serializable
data class CareLogEntry(
    val id: Long? = null,
    @SerialName("patient_id") val patientId: String,
    val author: String,
    val device: String,
    val kind: String,
    val message: String,
    @SerialName("created_at") val createdAt: String? = null,
)

/** Ráfaga de signos vitales enviada por Broadcast (1 Hz, sin tocar la base de datos). */
@Serializable
data class VitalsPulse(
    val bpm: Int,
    val steps: Int,
    val battery: Int,
    val lat: Double,
    val lng: Double,
    val distance: Int,
    val inZone: Boolean,
    val status: String,
    val activity: String,
    val sensorMode: String,
    val sentAt: Long,
)

/** Cuenta regresiva del reloj antes de enviar una alerta: la familia la ve en vivo. */
@Serializable
data class CountdownSignal(
    val type: String,
    val phase: String,
    val secondsLeft: Int,
    val total: Int,
    val sentAt: Long,
) {
    companion object {
        const val STARTED = "started"
        const val TICK = "tick"
        const val CANCELLED = "cancelled"
        const val SENT = "sent"
    }
}

/** Orden del panel de simulación (de cualquier dispositivo hacia el reloj). */
@Serializable
data class SimCommand(
    val action: String,
    val from: String,
    val sentAt: Long,
    val medicationId: String? = null,
) {
    companion object {
        const val FALL = "fall"
        const val SOS = "sos"
        const val TACHYCARDIA = "tachycardia"
        const val BRADYCARDIA = "bradycardia"
        const val LEAVE_ZONE = "leave_zone"
        const val WALK = "walk"
        const val NORMAL = "normal"
        const val LOW_BATTERY = "low_battery"
    }
}

/** Latido de presencia de cada dispositivo conectado al círculo de cuidado. */
@Serializable
data class DevicePing(
    val deviceId: String,
    val name: String,
    val kind: String,
    val color: String,
    val sentAt: Long,
)
