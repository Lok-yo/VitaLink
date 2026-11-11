package com.vitalink.core.engine

import com.vitalink.core.model.SimCommand
import com.vitalink.core.model.VitalStatus
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

enum class Scenario { NORMAL, WALKING, TACHYCARDIA, BRADYCARDIA, WANDERING, RETURNING }

data class VitalsReading(
    val bpm: Int,
    val steps: Int,
    val battery: Int,
    val charging: Boolean,
    val lat: Double,
    val lng: Double,
    val distanceM: Int,
    val inZone: Boolean,
    val activity: String,
    val sensorMode: String,
    val status: String,
)

/**
 * Modelo fisiológico simple que combina sensores reales (si existen) con escenarios simulados.
 * Los escenarios cambian el pulso y la posición de forma gradual para que la demo se vea natural.
 */
class VitalsEngine(
    private val homeLat: Double,
    private val homeLng: Double,
    private val safeRadiusM: Int,
    initialSteps: Int = 1_240,
    initialBattery: Int = 86,
    private val random: Random = Random.Default,
) {
    var scenario: Scenario = Scenario.NORMAL
        private set

    /** Lectura del sensor de pulso real; null cuando el dispositivo no tiene sensor. */
    var sensorBpm: Int? = null

    /** Batería real del dispositivo; null para simularla. */
    var deviceBattery: Int? = null
    var deviceCharging: Boolean = false

    private var bpm = 72.0
    private var steps = initialSteps.toDouble()
    private var battery = initialBattery.toDouble()
    private var batteryOverride: Int? = null
    private var lat = homeLat
    private var lng = homeLng
    private var heading = random.nextDouble(0.0, 360.0)

    /** Suma pasos medidos por el podómetro real del dispositivo. */
    fun addSteps(delta: Int) {
        if (delta > 0) steps += delta
    }

    fun apply(action: String) {
        when (action) {
            SimCommand.TACHYCARDIA -> scenario = Scenario.TACHYCARDIA
            SimCommand.BRADYCARDIA -> scenario = Scenario.BRADYCARDIA
            SimCommand.WALK -> scenario = Scenario.WALKING
            SimCommand.LEAVE_ZONE -> {
                scenario = Scenario.WANDERING
                heading = random.nextDouble(0.0, 360.0)
                val start = Geo.offset(homeLat, homeLng, safeRadiusM * 0.8, heading)
                lat = start.first
                lng = start.second
            }
            SimCommand.LOW_BATTERY -> batteryOverride = 9
            SimCommand.NORMAL -> {
                scenario = if (distanceHome() > safeRadiusM * 0.5) Scenario.RETURNING else Scenario.NORMAL
                batteryOverride = null
            }
        }
    }

    fun tick(dtSec: Double): VitalsReading {
        val dt = dtSec.coerceIn(0.05, 5.0)
        val real = sensorBpm?.takeIf { it in 30..220 }
        val useReal = real != null && scenario == Scenario.NORMAL

        val (target, rate) = when (scenario) {
            Scenario.NORMAL -> 72.0 to 3.0
            Scenario.WALKING -> 96.0 to 4.0
            Scenario.TACHYCARDIA -> 148.0 to 11.0
            Scenario.BRADYCARDIA -> 40.0 to 6.0
            Scenario.WANDERING -> 104.0 to 4.0
            Scenario.RETURNING -> 88.0 to 4.0
        }
        if (useReal) {
            bpm = real!!.toDouble()
        } else {
            val delta = (target - bpm).coerceIn(-rate * dt, rate * dt)
            val noise = (random.nextDouble() - 0.5) * if (abs(target - bpm) < 3) 2.4 else 1.0
            bpm = (bpm + delta + noise).coerceIn(30.0, 210.0)
        }

        val stepRate = when (scenario) {
            Scenario.WALKING, Scenario.WANDERING, Scenario.RETURNING -> 1.7
            else -> if (random.nextDouble() < 0.08) 2.0 else 0.0
        }
        steps += stepRate * dt

        moveLocation(dt)

        val currentBattery = batteryOverride ?: deviceBattery ?: run {
            battery = (battery - 0.004 * dt).coerceAtLeast(5.0)
            battery.roundToInt()
        }
        val distance = distanceHome()
        val inZone = distance <= safeRadiusM
        val bpmInt = bpm.roundToInt()
        val status = when {
            bpmInt >= 130 || bpmInt <= 42 -> VitalStatus.EMERGENCY
            bpmInt >= 105 || bpmInt <= 50 || !inZone || currentBattery <= 15 -> VitalStatus.ATTENTION
            else -> VitalStatus.OK
        }
        val activity = when (scenario) {
            Scenario.WALKING, Scenario.WANDERING, Scenario.RETURNING -> "caminando"
            else -> if (stepRate > 0) "en movimiento" else "en reposo"
        }
        return VitalsReading(
            bpm = bpmInt,
            steps = steps.toInt(),
            battery = currentBattery,
            charging = deviceCharging,
            lat = lat,
            lng = lng,
            distanceM = distance.roundToInt(),
            inZone = inZone,
            activity = activity,
            sensorMode = if (useReal) "sensor" else "simulado",
            status = status,
        )
    }

    private fun distanceHome() = Geo.distanceMeters(homeLat, homeLng, lat, lng)

    private fun moveLocation(dt: Double) {
        when (scenario) {
            Scenario.WANDERING -> {
                if (distanceHome() < safeRadiusM * 1.9) {
                    heading += (random.nextDouble() - 0.5) * 6
                    val next = Geo.offset(lat, lng, 11.0 * dt, heading)
                    lat = next.first; lng = next.second
                }
            }
            Scenario.RETURNING -> {
                val d = distanceHome()
                if (d < 15) {
                    scenario = Scenario.NORMAL
                } else {
                    val toHome = Geo.bearing(lat, lng, homeLat, homeLng)
                    val next = Geo.offset(lat, lng, minOf(d, 16.0 * dt), toHome)
                    lat = next.first; lng = next.second
                }
            }
            Scenario.WALKING -> {
                heading += (random.nextDouble() - 0.5) * 20
                if (distanceHome() > safeRadiusM * 0.6) heading = Geo.bearing(lat, lng, homeLat, homeLng)
                val next = Geo.offset(lat, lng, 1.3 * dt, heading)
                lat = next.first; lng = next.second
            }
            else -> {
                val d = distanceHome()
                if (d > 25) {
                    val next = Geo.offset(lat, lng, minOf(d - 20, 3.0 * dt), Geo.bearing(lat, lng, homeLat, homeLng))
                    lat = next.first; lng = next.second
                } else {
                    val next = Geo.offset(lat, lng, random.nextDouble() * 0.4 * dt, random.nextDouble(0.0, 360.0))
                    lat = next.first; lng = next.second
                }
            }
        }
    }
}
