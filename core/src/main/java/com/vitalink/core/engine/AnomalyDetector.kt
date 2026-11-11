package com.vitalink.core.engine

import com.vitalink.core.model.AlertType

data class Anomaly(val type: String, val severity: String, val title: String, val message: String)

/**
 * Convierte lecturas continuas en alertas discretas: exige que la condición se sostenga
 * [sustainMs] y no vuelve a disparar el mismo tipo hasta que la lectura se normaliza.
 */
class AnomalyDetector(
    private val tachyBpm: Int = 120,
    private val bradyBpm: Int = 45,
    private val lowBattery: Int = 10,
    private val sustainMs: Long = 4_000,
) {
    private val since = mutableMapOf<String, Long>()
    private val fired = mutableSetOf<String>()

    fun evaluate(reading: VitalsReading, nowMs: Long, nickname: String): List<Anomaly> {
        val result = mutableListOf<Anomaly>()
        check(AlertType.TACHYCARDIA, reading.bpm >= tachyBpm, reading.bpm < tachyBpm - 15, nowMs, sustainMs)?.let {
            result += Anomaly(
                it, "critical", "Taquicardia: ${reading.bpm} BPM",
                "El pulso de $nickname se mantiene por encima de $tachyBpm BPM.",
            )
        }
        check(AlertType.BRADYCARDIA, reading.bpm <= bradyBpm, reading.bpm > bradyBpm + 10, nowMs, sustainMs)?.let {
            result += Anomaly(
                it, "critical", "Pulso muy bajo: ${reading.bpm} BPM",
                "El pulso de $nickname bajó de $bradyBpm BPM.",
            )
        }
        check(AlertType.GEOFENCE, !reading.inZone, reading.inZone, nowMs, 1_500)?.let {
            result += Anomaly(
                it, "warning", "Salió de la zona segura",
                "$nickname está a ${reading.distanceM} m de casa.",
            )
        }
        check(AlertType.BATTERY, reading.battery <= lowBattery, reading.battery > lowBattery + 5, nowMs, 0)?.let {
            result += Anomaly(
                it, "warning", "Batería del reloj al ${reading.battery}%",
                "Pide a $nickname que cargue su reloj para no perder el monitoreo.",
            )
        }
        return result
    }

    fun reset() {
        since.clear()
        fired.clear()
    }

    private fun check(type: String, active: Boolean, recovered: Boolean, now: Long, sustain: Long): String? {
        if (recovered) {
            fired.remove(type)
            since.remove(type)
            return null
        }
        if (!active) {
            since.remove(type)
            return null
        }
        val start = since.getOrPut(type) { now }
        if (type !in fired && now - start >= sustain) {
            fired += type
            return type
        }
        return null
    }
}
