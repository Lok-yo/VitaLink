package com.vitalink.mobile.sim

import com.vitalink.core.data.CareRepository
import com.vitalink.core.engine.AnomalyDetector
import com.vitalink.core.engine.VitalsEngine
import com.vitalink.core.model.AlertType
import com.vitalink.core.model.SimCommand
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Panel de simulación del teléfono. Si el reloj está conectado, las órdenes viajan por
 * Realtime Broadcast y el reloj reacciona. Si no, el teléfono hace de "reloj virtual"
 * con el mismo motor de signos vitales que usa el reloj.
 */
class PhoneSimulator(private val repository: CareRepository) {
    private val _virtualWatch = MutableStateFlow(false)
    val virtualWatch: StateFlow<Boolean> = _virtualWatch.asStateFlow()

    private val _lastAction = MutableStateFlow<String?>(null)
    val lastAction: StateFlow<String?> = _lastAction.asStateFlow()

    private var engine: VitalsEngine? = null
    private val detector = AnomalyDetector()

    fun setVirtualWatch(enabled: Boolean) {
        _virtualWatch.value = enabled
        if (!enabled) detector.reset()
    }

    fun start() {
        repository.launch {
            var last = System.currentTimeMillis()
            while (true) {
                delay(1_000)
                val now = System.currentTimeMillis()
                val dt = (now - last) / 1000.0
                last = now
                if (!_virtualWatch.value) continue
                val st = repository.state.value
                if (st.realWatchOnline(now)) {
                    setVirtualWatch(false)
                    engine = null
                    continue
                }
                val patient = st.patient ?: continue
                val e = engine ?: VitalsEngine(
                    patient.homeLat, patient.homeLng, patient.safeRadiusM,
                    initialSteps = st.vitals?.steps ?: 1_200, initialBattery = st.vitals?.battery ?: 80,
                ).also { engine = it }
                val reading = e.tick(dt)
                repository.publishVitals(reading, source = "phone-sim")
                detector.evaluate(reading, now, patient.nickname).forEach { a ->
                    repository.raiseAlert(a.type, a.severity, a.title, a.message, reading.bpm, reading.lat, reading.lng, source = "phone-sim")
                }
            }
        }
    }

    /**
     * Ejecuta una acción del simulador. Devuelve una descripción de cómo se entregó.
     */
    suspend fun run(action: String, medicationId: String? = null): String {
        val st = repository.state.value
        _lastAction.value = action
        if (action == "medication" && medicationId != null) {
            repository.triggerDoseNow(medicationId)
            return "Toma creada en Supabase: todos los dispositivos la reciben por Postgres Changes"
        }
        if (st.realWatchOnline() && repository.sendSim(action)) {
            return "Orden enviada al reloj por Realtime Broadcast"
        }
        // Sin reloj: el teléfono emula al reloj
        if (!_virtualWatch.value) setVirtualWatch(true)
        val patient = st.patient
        val vitals = st.vitals
        when (action) {
            SimCommand.FALL -> repository.raiseAlert(
                AlertType.FALL, "critical", "Caída detectada",
                "Se detectó una caída fuerte y ${patient?.nickname ?: "el paciente"} no canceló la alerta.",
                vitals?.bpm, vitals?.lat, vitals?.lng, source = "phone-sim",
            )
            SimCommand.SOS -> repository.raiseAlert(
                AlertType.SOS, "critical", "Botón SOS presionado",
                "${patient?.nickname ?: "El paciente"} pidió ayuda desde su reloj.",
                vitals?.bpm, vitals?.lat, vitals?.lng, source = "phone-sim",
            )
            else -> engine?.apply(action) ?: run {
                // El motor se crea en el siguiente tick; aplicamos la orden en cuanto exista
                repository.launch {
                    repeat(20) {
                        engine?.let { it.apply(action); return@launch }
                        delay(250)
                    }
                }
            }
        }
        return "Reloj sin conexión: el teléfono simula al reloj (reloj virtual)"
    }
}
