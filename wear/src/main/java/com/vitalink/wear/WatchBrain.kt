package com.vitalink.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.vitalink.core.data.CareEvent
import com.vitalink.core.data.CareRepository
import com.vitalink.core.engine.AnomalyDetector
import com.vitalink.core.engine.FallDetector
import com.vitalink.core.engine.VitalsEngine
import com.vitalink.core.engine.VitalsReading
import com.vitalink.core.model.Alert
import com.vitalink.core.model.AlertStatus
import com.vitalink.core.model.AlertType
import com.vitalink.core.model.CountdownSignal
import com.vitalink.core.model.DoseStatus
import com.vitalink.core.model.MedicationDose
import com.vitalink.core.model.SimCommand
import com.vitalink.core.util.Times
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class Countdown(val type: String, val total: Int, val left: Int, val startedAt: Long)

data class WatchToast(val title: String, val text: String, val positive: Boolean, val at: Long = System.currentTimeMillis())

/**
 * Cerebro del reloj: sensores (pulso, pasos, acelerómetro), motor de signos vitales,
 * detección de caídas/anomalías, cuenta regresiva de emergencias y recordatorios de medicinas.
 */
class WatchBrain(private val context: Context, private val repo: CareRepository) : SensorEventListener {
    private val tag = "VitaLink-Watch"
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val battery = context.getSystemService(BatteryManager::class.java)

    private val _reading = MutableStateFlow<VitalsReading?>(null)
    val reading: StateFlow<VitalsReading?> = _reading.asStateFlow()

    private val _countdown = MutableStateFlow<Countdown?>(null)
    val countdown: StateFlow<Countdown?> = _countdown.asStateFlow()

    private val _dueDose = MutableStateFlow<MedicationDose?>(null)
    val dueDose: StateFlow<MedicationDose?> = _dueDose.asStateFlow()

    private val _toast = MutableStateFlow<WatchToast?>(null)
    val toast: StateFlow<WatchToast?> = _toast.asStateFlow()

    private val _sensorInfo = MutableStateFlow("Sensores simulados")
    val sensorInfo: StateFlow<String> = _sensorInfo.asStateFlow()

    private var engine: VitalsEngine? = null
    private val anomalies = AnomalyDetector()
    private val fallDetector = FallDetector()
    private var countdownJob: Job? = null
    private val alertMutex = Mutex()
    private var lastStepCounter = -1f
    private val remindedDoses = mutableMapOf<String, String>()
    private var started = false

    fun start() {
        if (started) return
        started = true
        registerSensors()
        repo.launch { vitalsLoop() }
        repo.launch { doseLoop() }
        repo.launch { repo.events.collect { handle(it) } }
    }

    fun stop() {
        sensors?.unregisterListener(this)
        countdownJob?.cancel()
    }

    fun registerSensors() {
        sensors?.unregisterListener(this)
        val found = mutableListOf<String>()
        if (granted(Manifest.permission.BODY_SENSORS)) {
            sensors?.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.let {
                sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                found += "pulso"
            }
        }
        if (granted(Manifest.permission.ACTIVITY_RECOGNITION)) {
            sensors?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
                sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                found += "pasos"
            }
        }
        sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            found += "caídas"
        }
        _sensorInfo.value = if (found.isEmpty()) "Sensores simulados" else "Sensores: ${found.joinToString(", ")}"
        Log.i(tag, _sensorInfo.value)
    }

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_HEART_RATE -> {
                val bpm = event.values.firstOrNull()?.toInt() ?: 0
                engine?.sensorBpm = bpm.takeIf { it in 30..220 }
            }
            Sensor.TYPE_STEP_COUNTER -> {
                val total = event.values.firstOrNull() ?: return
                if (lastStepCounter >= 0) engine?.addSteps((total - lastStepCounter).toInt())
                lastStepCounter = total
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
                if (fallDetector.onSample(x, y, z, event.timestamp / 1_000_000) && _countdown.value == null) {
                    Log.w(tag, "Caída detectada por acelerómetro")
                    startCountdown(AlertType.FALL)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private suspend fun vitalsLoop() {
        var last = System.currentTimeMillis()
        while (true) {
            delay(1_000)
            val st = repo.state.value
            val patient = st.patient ?: continue
            val e = engine ?: VitalsEngine(
                patient.homeLat, patient.homeLng, patient.safeRadiusM,
                initialSteps = st.vitals?.steps?.takeIf { st.vitals?.source == "watch" } ?: 1_240,
            ).also { engine = it }
            e.deviceBattery = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 1..100 }
            e.deviceCharging = battery?.isCharging == true
            val now = System.currentTimeMillis()
            val r = e.tick((now - last) / 1000.0)
            last = now
            _reading.value = r
            repo.publishVitals(r, source = "watch")
            for (a in anomalies.evaluate(r, now, patient.nickname)) {
                raiseOnce(a.type, a.severity, a.title, a.message, r)
            }
        }
    }

    private suspend fun raiseOnce(type: String, severity: String, title: String, message: String, r: VitalsReading?) {
        alertMutex.withLock {
            if (repo.state.value.openAlerts.any { it.type == type }) return
            Log.w(tag, "Alerta $type: $title")
            repo.raiseAlert(type, severity, title, message, r?.bpm, r?.lat, r?.lng, source = "watch")
        }
        if (severity == "critical") vibrate(longArrayOf(0, 400, 150, 400, 150, 600))
    }

    // region Cuenta regresiva (caída / SOS)

    fun startCountdown(type: String) {
        if (_countdown.value != null) return
        val total = if (type == AlertType.FALL) FALL_SECONDS else SOS_SECONDS
        val startedAt = System.currentTimeMillis()
        countdownJob = repo.launch {
            for (left in total downTo 1) {
                _countdown.value = Countdown(type, total, left, startedAt)
                repo.broadcastCountdown(
                    CountdownSignal(type, if (left == total) CountdownSignal.STARTED else CountdownSignal.TICK, left, total, System.currentTimeMillis()),
                )
                vibrate(if (left <= 3) longArrayOf(0, 300) else longArrayOf(0, 120))
                delay(1_000)
            }
            sendEmergency(type)
        }
    }

    fun cancelCountdown() {
        val c = _countdown.value ?: return
        countdownJob?.cancel()
        _countdown.value = null
        repo.launch {
            repo.broadcastCountdown(CountdownSignal(c.type, CountdownSignal.CANCELLED, 0, c.total, System.currentTimeMillis()))
            repo.addLog("alert", if (c.type == AlertType.FALL) "Canceló una posible caída: estoy bien" else "Canceló el SOS antes de enviarlo")
        }
        showToast("Cancelado", "Qué bueno que estás bien", positive = true)
    }

    fun sendNow() {
        val c = _countdown.value ?: return
        countdownJob?.cancel()
        repo.launch { sendEmergency(c.type) }
    }

    private suspend fun sendEmergency(type: String) {
        _countdown.value = null
        val r = _reading.value
        val nickname = repo.state.value.patient?.nickname ?: "El paciente"
        repo.broadcastCountdown(CountdownSignal(type, CountdownSignal.SENT, 0, 0, System.currentTimeMillis()))
        if (type == AlertType.FALL) {
            raiseOnce(AlertType.FALL, "critical", "Caída detectada", "$nickname sufrió una posible caída y no canceló la alerta.", r)
        } else {
            raiseOnce(AlertType.SOS, "critical", "Botón SOS presionado", "$nickname pidió ayuda desde su reloj.", r)
        }
    }

    // endregion

    fun imOkay(alert: Alert) {
        repo.launch { repo.resolveAlert(alert, cancelled = alert.status == AlertStatus.ACTIVE) }
        if (alert.type in setOf(AlertType.TACHYCARDIA, AlertType.BRADYCARDIA, AlertType.GEOFENCE)) engine?.apply(SimCommand.NORMAL)
        showToast("Gracias", "Avisamos a tu familia que estás bien", positive = true)
    }

    // region Medicinas

    private suspend fun doseLoop() {
        while (true) {
            val now = System.currentTimeMillis()
            val st = repo.state.value
            val due = st.doses.filter { d ->
                when (d.status) {
                    DoseStatus.PENDING -> Times.millis(d.scheduledFor) in (now - 60 * 60_000)..now
                    DoseStatus.SNOOZED -> Times.millis(d.snoozedUntil) in (now - 60 * 60_000)..now
                    else -> false
                }
            }.minByOrNull { Times.millis(it.scheduledFor) }
            if (due?.id != _dueDose.value?.id || due?.status != _dueDose.value?.status) _dueDose.value = due
            val dueId = due?.id
            if (due != null && dueId != null) {
                val key = due.status + (due.snoozedUntil ?: "")
                if (remindedDoses[dueId] != key) {
                    remindedDoses[dueId] = key
                    vibrate(longArrayOf(0, 250, 120, 250, 120, 500))
                    Log.i(tag, "Recordatorio de medicina: ${st.medication(due.medicationId)?.name}")
                }
            }
            delay(1_000)
        }
    }

    fun confirmDose(dose: MedicationDose) {
        _dueDose.value = null
        repo.launch {
            val ok = repo.confirmDose(dose)
            val med = repo.state.value.medication(dose.medicationId)
            if (ok) showToast("¡Muy bien!", "${med?.name ?: "Medicina"} registrada", positive = true)
        }
    }

    fun snoozeDose(dose: MedicationDose) {
        _dueDose.value = null
        repo.launch { repo.snoozeDose(dose, 10) }
        showToast("Te recuerdo en 10 min", "", positive = true)
    }

    // endregion

    /** Acciones del panel de simulación (del propio reloj o recibidas por Broadcast). */
    fun simulate(action: String) {
        Log.i(tag, "Simulación: $action")
        when (action) {
            SimCommand.FALL -> startCountdown(AlertType.FALL)
            SimCommand.SOS -> startCountdown(AlertType.SOS)
            "medication" -> {
                val st = repo.state.value
                val med = st.medications.filter { it.active }.minByOrNull { m ->
                    st.doses.filter { it.medicationId == m.id && it.isOpen }.minOfOrNull { Times.millis(it.scheduledFor) } ?: Long.MAX_VALUE
                } ?: return
                med.id?.let { id -> repo.launch { repo.triggerDoseNow(id) } }
            }
            else -> engine?.apply(action)
        }
    }

    private fun handle(event: CareEvent) {
        val me = repo.state.value.patient?.nickname
        when (event) {
            is CareEvent.Sim -> {
                showToast("Simulación", "${event.command.from}: ${simLabel(event.command.action)}", positive = true)
                simulate(event.command.action)
            }
            is CareEvent.AlertChanged -> {
                val a = event.alert
                if (a.status == AlertStatus.ACKNOWLEDGED && event.previous?.status == AlertStatus.ACTIVE) {
                    vibrate(longArrayOf(0, 120, 80, 120))
                    Log.i(tag, "Ayuda en camino: ${a.acknowledgedBy}")
                }
            }
            is CareEvent.DoseChanged -> {
                val d = event.dose
                if (d.status == DoseStatus.TAKEN && event.previous?.isOpen == true && d.confirmedDevice != "watch") {
                    val med = repo.state.value.medication(d.medicationId)
                    showToast("Registrado", "${d.confirmedBy} confirmó tu ${med?.name ?: "medicina"}", positive = true)
                    vibrate(longArrayOf(0, 80))
                }
            }
            is CareEvent.NewLog -> {
                val e = event.entry
                if (e.kind == "note" && e.device == "phone" && e.author != me) {
                    showToast("Mensaje de ${e.author}", e.message, positive = true)
                    vibrate(longArrayOf(0, 60, 60, 60))
                }
            }
            else -> Unit
        }
    }

    private fun simLabel(action: String) = when (action) {
        SimCommand.FALL -> "caída"
        SimCommand.SOS -> "SOS"
        SimCommand.TACHYCARDIA -> "taquicardia"
        SimCommand.BRADYCARDIA -> "pulso bajo"
        SimCommand.LEAVE_ZONE -> "salir de la zona"
        SimCommand.WALK -> "paseo"
        SimCommand.LOW_BATTERY -> "batería baja"
        SimCommand.NORMAL -> "normalizar"
        else -> action
    }

    fun showToast(title: String, text: String, positive: Boolean) {
        val t = WatchToast(title, text, positive)
        _toast.value = t
        repo.launch {
            delay(3_500)
            if (_toast.value == t) _toast.value = null
        }
    }

    fun vibrate(pattern: LongArray) {
        if (vibrator?.hasVibrator() == true) vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    companion object {
        const val FALL_SECONDS = 12
        const val SOS_SECONDS = 5
    }
}
