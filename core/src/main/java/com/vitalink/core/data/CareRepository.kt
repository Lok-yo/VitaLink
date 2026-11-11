package com.vitalink.core.data

import android.content.Context
import android.util.Log
import com.vitalink.core.engine.VitalsReading
import com.vitalink.core.model.Alert
import com.vitalink.core.model.AlertStatus
import com.vitalink.core.model.CareLogEntry
import com.vitalink.core.model.Caregiver
import com.vitalink.core.model.CountdownSignal
import com.vitalink.core.model.DevicePing
import com.vitalink.core.model.DoseStatus
import com.vitalink.core.model.Medication
import com.vitalink.core.model.MedicationDose
import com.vitalink.core.model.Patient
import com.vitalink.core.model.SimCommand
import com.vitalink.core.model.VitalsLive
import com.vitalink.core.model.VitalsPulse
import com.vitalink.core.model.VitalsSample
import com.vitalink.core.util.Times
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeOldRecordOrNull
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class LinkStatus { OFFLINE, CONNECTING, LIVE }

data class ConnectionInfo(
    val status: LinkStatus = LinkStatus.CONNECTING,
    val since: Long = System.currentTimeMillis(),
    val lastEventAt: Long? = null,
    val lastLatencyMs: Long? = null,
    val pendingWrites: Int = 0,
    val error: String? = null,
)

data class OnlineDevice(val ping: DevicePing, val lastSeen: Long)

data class CareState(
    val patient: Patient? = null,
    val caregivers: List<Caregiver> = emptyList(),
    val vitals: VitalsLive? = null,
    val vitalsReceivedAt: Long = 0,
    val bpmHistory: List<BpmPoint> = emptyList(),
    val medications: List<Medication> = emptyList(),
    val doses: List<MedicationDose> = emptyList(),
    val alerts: List<Alert> = emptyList(),
    val log: List<CareLogEntry> = emptyList(),
    val countdown: CountdownSignal? = null,
    val devices: Map<String, OnlineDevice> = emptyMap(),
    val connection: ConnectionInfo = ConnectionInfo(),
    val loadedFromCache: Boolean = false,
) {
    val openAlerts: List<Alert> get() = alerts.filter { it.isOpen }
    val activeAlert: Alert? get() = openAlerts.maxByOrNull { Times.millis(it.createdAt) }
    fun medication(id: String) = medications.firstOrNull { it.id == id }

    /** El reloj se considera en línea si envió signos o un ping recientemente. */
    fun watchOnline(now: Long = System.currentTimeMillis()): Boolean =
        now - vitalsReceivedAt < 15_000 || realWatchOnline(now)

    /** Sólo cuenta el reloj físico (sus pings de presencia), no el reloj virtual del teléfono. */
    fun realWatchOnline(now: Long = System.currentTimeMillis()): Boolean =
        devices.values.any { it.ping.kind == "watch" && now - it.lastSeen < 25_000 }
}

sealed interface CareEvent {
    data class AlertRaised(val alert: Alert) : CareEvent
    data class AlertChanged(val alert: Alert, val previous: Alert?) : CareEvent
    data class DoseChanged(val dose: MedicationDose, val previous: MedicationDose?) : CareEvent
    data class Sim(val command: SimCommand) : CareEvent
    data class Countdown(val signal: CountdownSignal) : CareEvent
    data class NewLog(val entry: CareLogEntry) : CareEvent
}

/**
 * Sincronización en vivo con Supabase compartida por el teléfono y el reloj.
 *
 * - Postgres Changes para el estado durable (alertas, tomas, bitácora, medicinas, signos persistidos).
 * - Broadcast para lo efímero y de alta frecuencia (pulso a 1 Hz, cuenta regresiva, simulador, presencia).
 * - Caché local + bandeja de salida para que todo siga funcionando sin red.
 */
class CareRepository(
    context: Context,
    private val identity: DeviceIdentity,
    private val deviceKind: String,
    val patientId: String,
) {
    private val tag = "VitaLink-Sync"
    private val crashGuard = CoroutineExceptionHandler { _, e -> Log.e(tag, "Error no controlado en sincronización", e) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + crashGuard)

    // supabase-kt elimina callbacks con índice-y-borrado no atómico: cancelar los colectores
    // del canal desde varios hilos a la vez provoca IndexOutOfBounds. Un solo hilo lo evita.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val channelDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val store = LocalStore(context.applicationContext, patientId)
    private val client get() = Cloud.client
    private val outboxMutex = Mutex()

    private val _state = MutableStateFlow(CareState())
    val state: StateFlow<CareState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<CareEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<CareEvent> = _events.asSharedFlow()

    @Volatile private var channel: RealtimeChannel? = null
    private var started = false
    private var lastPersistVitals = 0L
    private var lastHistoryAt = 0L
    private var lastPersistedStatus: String? = null

    private val me get() = identity.profile.value

    fun start() {
        if (started) return
        started = true
        store.loadSnapshot()?.let { snap ->
            _state.update {
                it.copy(
                    patient = snap.patient, caregivers = snap.caregivers, vitals = snap.vitals,
                    bpmHistory = snap.bpmHistory, medications = snap.medications, doses = snap.doses,
                    alerts = snap.alerts, log = snap.log, loadedFromCache = true,
                )
            }
        }
        _state.update { it.copy(connection = it.connection.copy(pendingWrites = store.loadOutbox().size)) }
        if (!Cloud.isConfigured) {
            setConnection(LinkStatus.OFFLINE, "Faltan credenciales de Supabase")
            return
        }
        scope.launch { superviseRealtime() }
        scope.launch { persistCacheLoop() }
        scope.launch { presenceLoop() }
        scope.launch { outboxLoop() }
    }

    // region Conexión

    private suspend fun superviseRealtime() {
        var backoff = 2_000L
        while (scope.isActive) {
            val channelScope = CoroutineScope(SupervisorJob() + channelDispatcher + crashGuard)
            var ch: RealtimeChannel? = null
            try {
                setConnection(LinkStatus.CONNECTING)
                runCatching { refreshAll() }.onFailure { Log.w(tag, "Carga inicial falló: ${it.message}") }
                ch = client.channel("care-$patientId") {
                    isPrivate = true
                    broadcast { receiveOwnBroadcasts = false }
                }
                listen(ch, channelScope)
                ch.subscribe(blockUntilSubscribed = false)
                withTimeout(20_000) { ch.status.first { it == RealtimeChannel.Status.SUBSCRIBED } }
                channel = ch
                setConnection(LinkStatus.LIVE)
                Log.i(tag, "Realtime SUSCRITO al canal care-$patientId ($deviceKind)")
                backoff = 2_000L
                sendPing()
                runCatching { refreshAll() }
                flushOutbox()

                var degradedSince = 0L
                while (scope.isActive) {
                    delay(1_500)
                    val healthy = ch.status.value == RealtimeChannel.Status.SUBSCRIBED &&
                        client.realtime.status.value == Realtime.Status.CONNECTED
                    if (healthy) {
                        if (degradedSince != 0L) {
                            Log.i(tag, "Realtime recuperado, sincronizando cambios perdidos")
                            degradedSince = 0L
                            setConnection(LinkStatus.LIVE)
                            runCatching { refreshAll() }
                            flushOutbox()
                        }
                    } else {
                        if (degradedSince == 0L) {
                            degradedSince = System.currentTimeMillis()
                            setConnection(LinkStatus.CONNECTING)
                        }
                        if (System.currentTimeMillis() - degradedSince > 12_000) {
                            Log.w(tag, "Realtime sin recuperarse; reiniciando canal")
                            break
                        }
                    }
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException && !scope.isActive) throw e
                Log.w(tag, "Realtime caído: ${e.message}")
                setConnection(LinkStatus.OFFLINE, e.message)
            } finally {
                channelScope.cancel()
            }
            channel = null
            runCatching { ch?.let { client.realtime.removeChannel(it) } }
            runCatching { client.realtime.disconnect() }
            setConnection(LinkStatus.OFFLINE)
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(20_000)
        }
    }

    private fun setConnection(status: LinkStatus, error: String? = null) {
        _state.update {
            if (it.connection.status == status && error == null) it
            else it.copy(connection = it.connection.copy(status = status, since = System.currentTimeMillis(), error = error))
        }
    }

    private fun markEvent(latencyMs: Long?) {
        _state.update {
            it.copy(
                connection = it.connection.copy(
                    lastEventAt = System.currentTimeMillis(),
                    lastLatencyMs = latencyMs?.takeIf { l -> l in 0..60_000 } ?: it.connection.lastLatencyMs,
                ),
            )
        }
    }

    private fun latency(action: PostgresAction): Long =
        System.currentTimeMillis() - action.commitTimestamp.toEpochMilliseconds()

    private fun listen(ch: RealtimeChannel, s: CoroutineScope) {
        fun changes(table: String, column: String = "patient_id"): Flow<PostgresAction> =
            ch.postgresChangeFlow<PostgresAction>(schema = "public") {
                this.table = table
                filter(column, FilterOperator.EQ, patientId)
            }

        val alerts = changes("alerts")
        val doses = changes("medication_doses")
        val meds = changes("medications")
        val log = changes("care_log")
        val vitals = changes("vitals_live")
        val caregivers = changes("caregivers")
        val patient = changes("patients", "id")

        s.launch {
            alerts.collect { a ->
                markEvent(latency(a))
                when (a) {
                    is PostgresAction.Insert, is PostgresAction.Update -> applyAlert(a.decodeRecordSafe<Alert>() ?: return@collect, fromRemote = true)
                    is PostgresAction.Delete -> a.decodeOldRecordOrNull<IdOnly>()?.let { old ->
                        _state.update { st -> st.copy(alerts = st.alerts.filterNot { it.id == old.id }) }
                    }
                    else -> Unit
                }
            }
        }
        s.launch {
            doses.collect { a ->
                markEvent(latency(a))
                when (a) {
                    is PostgresAction.Insert, is PostgresAction.Update -> applyDose(a.decodeRecordSafe<MedicationDose>() ?: return@collect)
                    is PostgresAction.Delete -> a.decodeOldRecordOrNull<IdOnly>()?.let { old ->
                        _state.update { st -> st.copy(doses = st.doses.filterNot { it.id == old.id }) }
                    }
                    else -> Unit
                }
            }
        }
        s.launch {
            meds.collect { a ->
                markEvent(latency(a))
                when (a) {
                    is PostgresAction.Insert, is PostgresAction.Update -> a.decodeRecordSafe<Medication>()?.let { applyMedication(it) }
                    is PostgresAction.Delete -> a.decodeOldRecordOrNull<IdOnly>()?.let { old ->
                        _state.update { st ->
                            st.copy(
                                medications = st.medications.filterNot { it.id == old.id },
                                doses = st.doses.filterNot { it.medicationId == old.id },
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
        s.launch {
            log.collect { a ->
                markEvent(latency(a))
                if (a is PostgresAction.Insert) a.decodeRecordSafe<CareLogEntry>()?.let { applyLog(it) }
            }
        }
        s.launch {
            vitals.collect { a ->
                markEvent(latency(a))
                if (a is PostgresAction.Insert || a is PostgresAction.Update) {
                    a.decodeRecordSafe<VitalsLive>()?.let { v -> applyVitals(v, persisted = true) }
                }
            }
        }
        s.launch {
            caregivers.collect { a ->
                markEvent(latency(a))
                if (a is PostgresAction.Insert || a is PostgresAction.Update) {
                    a.decodeRecordSafe<Caregiver>()?.let { c ->
                        _state.update { st -> st.copy(caregivers = st.caregivers.filterNot { it.id == c.id } + c) }
                    }
                }
            }
        }
        s.launch {
            patient.collect { a ->
                markEvent(latency(a))
                if (a is PostgresAction.Update) a.decodeRecordSafe<Patient>()?.let { p -> _state.update { it.copy(patient = p) } }
            }
        }

        s.launch {
            ch.broadcastFlow<VitalsPulse>("vitals").collect { p ->
                markEvent(System.currentTimeMillis() - p.sentAt)
                applyPulse(p)
            }
        }
        s.launch {
            ch.broadcastFlow<CountdownSignal>("countdown").collect { c ->
                markEvent(System.currentTimeMillis() - c.sentAt)
                applyCountdown(c)
                _events.emit(CareEvent.Countdown(c))
            }
        }
        s.launch {
            ch.broadcastFlow<SimCommand>("sim").collect { c ->
                markEvent(System.currentTimeMillis() - c.sentAt)
                _events.emit(CareEvent.Sim(c))
            }
        }
        s.launch {
            ch.broadcastFlow<DevicePing>("ping").collect { p ->
                _state.update { st -> st.copy(devices = st.devices + (p.deviceId to OnlineDevice(p, System.currentTimeMillis()))) }
            }
        }
    }

    private inline fun <reified T : Any> PostgresAction.decodeRecordSafe(): T? = try {
        when (this) {
            is PostgresAction.Insert -> decodeRecord<T>()
            is PostgresAction.Update -> decodeRecord<T>()
            is PostgresAction.Select -> decodeRecord<T>()
            else -> null
        }
    } catch (e: Exception) {
        Log.w(tag, "No se pudo decodificar ${T::class.simpleName}: ${e.message}")
        null
    }

    suspend fun refreshAll() {
        runCatching { client.postgrest.rpc("ensure_doses", buildJsonObject { put("p_patient", patientId) }) }
        val patient = client.from("patients").select { filter { eq("id", patientId) } }.decodeSingleOrNull<Patient>()
        val caregivers = client.from("caregivers").select { filter { eq("patient_id", patientId) } }.decodeList<Caregiver>()
        val vitals = client.from("vitals_live").select { filter { eq("patient_id", patientId) } }.decodeSingleOrNull<VitalsLive>()
        val history = client.from("vitals_history").select {
            filter { eq("patient_id", patientId) }
            order("recorded_at", Order.DESCENDING)
            limit(60)
        }.decodeList<VitalsSample>()
        val meds = client.from("medications").select {
            filter { eq("patient_id", patientId) }
            order("created_at", Order.ASCENDING)
        }.decodeList<Medication>()
        val from = Instant.now().minus(20, ChronoUnit.HOURS).toString()
        val to = Instant.now().plus(28, ChronoUnit.HOURS).toString()
        val doses = client.from("medication_doses").select {
            filter {
                eq("patient_id", patientId)
                gte("scheduled_for", from)
                lte("scheduled_for", to)
            }
            order("scheduled_for", Order.ASCENDING)
        }.decodeList<MedicationDose>()
        val alerts = client.from("alerts").select {
            filter { eq("patient_id", patientId) }
            order("created_at", Order.DESCENDING)
            limit(40)
        }.decodeList<Alert>()
        val log = client.from("care_log").select {
            filter { eq("patient_id", patientId) }
            order("created_at", Order.DESCENDING)
            limit(80)
        }.decodeList<CareLogEntry>()

        _state.update { st ->
            val liveHistory = st.bpmHistory.filter { System.currentTimeMillis() - it.t < 120_000 }
            val historyPoints = history.mapNotNull { h -> h.bpm?.let { BpmPoint(Times.millis(h.recordedAt), it) } }.reversed()
            st.copy(
                patient = patient ?: st.patient,
                caregivers = caregivers,
                vitals = if ((st.vitalsReceivedAt > Times.millis(vitals?.updatedAt))) st.vitals else vitals ?: st.vitals,
                bpmHistory = if (liveHistory.size >= 20) st.bpmHistory else (historyPoints + liveHistory).takeLast(90),
                medications = meds,
                doses = mergePendingDoses(doses, st.doses),
                alerts = alerts,
                log = log,
                loadedFromCache = false,
            )
        }
        Log.i(tag, "Datos sincronizados: ${meds.size} medicinas, ${doses.size} tomas, ${alerts.size} alertas, ${log.size} bitácora")
    }

    /** Conserva cambios locales aún no confirmados por la nube (bandeja de salida). */
    private fun mergePendingDoses(remote: List<MedicationDose>, local: List<MedicationDose>): List<MedicationDose> {
        val pendingIds = store.loadOutbox().filter { it.table == "medication_doses" }.mapNotNull { it.rowId }.toSet()
        if (pendingIds.isEmpty()) return remote
        val localById = local.associateBy { it.id }
        return remote.map { r -> if (r.id in pendingIds) localById[r.id] ?: r else r }
    }

    // endregion

    // region Reductores de estado

    private suspend fun applyAlert(alert: Alert, fromRemote: Boolean) {
        var previous: Alert? = null
        _state.update { st ->
            previous = st.alerts.firstOrNull { it.id == alert.id }
            val list = (st.alerts.filterNot { it.id == alert.id } + alert).sortedByDescending { Times.millis(it.createdAt) }
            st.copy(alerts = list.take(60))
        }
        if (previous == null) _events.emit(CareEvent.AlertRaised(alert))
        else if (previous != alert) _events.emit(CareEvent.AlertChanged(alert, previous))
        if (fromRemote && alert.isOpen) Log.i(tag, "Alerta en vivo: ${alert.type} ${alert.status}")
    }

    private suspend fun applyDose(dose: MedicationDose) {
        var previous: MedicationDose? = null
        _state.update { st ->
            previous = st.doses.firstOrNull { it.id == dose.id }
            st.copy(doses = (st.doses.filterNot { it.id == dose.id } + dose).sortedBy { Times.millis(it.scheduledFor) })
        }
        if (previous != dose) _events.emit(CareEvent.DoseChanged(dose, previous))
    }

    private fun applyMedication(med: Medication) {
        _state.update { st -> st.copy(medications = st.medications.filterNot { it.id == med.id } + med) }
    }

    private suspend fun applyLog(entry: CareLogEntry) {
        var isNew = false
        _state.update { st ->
            isNew = st.log.none { it.id == entry.id && entry.id != null }
            val rest = st.log.filterNot {
                (it.id == entry.id && entry.id != null) ||
                    (it.id == null && it.author == entry.author && it.message == entry.message && it.kind == entry.kind)
            }
            st.copy(log = (listOf(entry) + rest).take(120))
        }
        if (isNew) _events.emit(CareEvent.NewLog(entry))
    }

    private fun applyVitals(v: VitalsLive, persisted: Boolean) {
        _state.update { st ->
            val receivedAt = if (persisted) maxOf(st.vitalsReceivedAt, Times.millis(v.updatedAt).coerceAtMost(System.currentTimeMillis())) else System.currentTimeMillis()
            val history = if (!persisted && v.bpm != null) (st.bpmHistory + BpmPoint(System.currentTimeMillis(), v.bpm)).takeLast(90) else st.bpmHistory
            // Un registro persistido más viejo que el último pulso en vivo no debe pisar la vista
            if (persisted && st.vitalsReceivedAt > 0 && System.currentTimeMillis() - st.vitalsReceivedAt < 3_000) st
            else st.copy(vitals = v, vitalsReceivedAt = receivedAt, bpmHistory = history)
        }
    }

    private fun applyPulse(p: VitalsPulse) {
        applyVitals(
            VitalsLive(
                patientId = patientId, bpm = p.bpm, steps = p.steps, battery = p.battery, lat = p.lat, lng = p.lng,
                distanceHomeM = p.distance, inSafeZone = p.inZone, status = p.status, activity = p.activity,
                source = "watch", sensorMode = p.sensorMode, updatedAt = Instant.ofEpochMilli(p.sentAt).toString(),
            ),
            persisted = false,
        )
    }

    private fun applyCountdown(c: CountdownSignal) {
        _state.update {
            it.copy(countdown = if (c.phase == CountdownSignal.CANCELLED || c.phase == CountdownSignal.SENT) null else c)
        }
        if (c.phase == CountdownSignal.CANCELLED || c.phase == CountdownSignal.SENT) return
        // Si se pierde el mensaje final, la cuenta regresiva no se queda colgada
        scope.launch {
            delay((c.secondsLeft + 4) * 1_000L)
            _state.update { st -> if (st.countdown?.sentAt == c.sentAt) st.copy(countdown = null) else st }
        }
    }

    // endregion

    // region Acciones (optimistas + bandeja de salida)

    /** Confirma una toma. Devuelve false si otro familiar ya la había confirmado. */
    suspend fun confirmDose(dose: MedicationDose): Boolean {
        val id = dose.id ?: return false
        val now = Times.nowIso()
        val updated = dose.copy(status = DoseStatus.TAKEN, confirmedBy = me.name, confirmedDevice = deviceKind, confirmedAt = now)
        applyDose(updated)
        val payload = buildJsonObject {
            put("status", DoseStatus.TAKEN)
            put("confirmed_by", me.name)
            put("confirmed_device", deviceKind)
            put("confirmed_at", now)
        }
        return guardedUpdate("medication_doses", id, payload, "status", listOf(DoseStatus.PENDING, DoseStatus.SNOOZED))
    }

    suspend fun snoozeDose(dose: MedicationDose, minutes: Long = 10): Boolean {
        val id = dose.id ?: return false
        val until = Times.isoIn(minutes)
        val updated = dose.copy(status = DoseStatus.SNOOZED, snoozedUntil = until, snoozeCount = dose.snoozeCount + 1, confirmedBy = me.name, confirmedDevice = deviceKind)
        applyDose(updated)
        val payload = buildJsonObject {
            put("status", DoseStatus.SNOOZED)
            put("snoozed_until", until)
            put("snooze_count", dose.snoozeCount + 1)
            put("confirmed_by", me.name)
            put("confirmed_device", deviceKind)
        }
        return guardedUpdate("medication_doses", id, payload, "status", listOf(DoseStatus.PENDING, DoseStatus.SNOOZED))
    }

    suspend fun skipDose(dose: MedicationDose): Boolean {
        val id = dose.id ?: return false
        applyDose(dose.copy(status = DoseStatus.SKIPPED, confirmedBy = me.name, confirmedDevice = deviceKind))
        val payload = buildJsonObject {
            put("status", DoseStatus.SKIPPED)
            put("confirmed_by", me.name)
            put("confirmed_device", deviceKind)
            put("confirmed_at", Times.nowIso())
        }
        return guardedUpdate("medication_doses", id, payload, "status", listOf(DoseStatus.PENDING, DoseStatus.SNOOZED))
    }

    /** Crea una toma para "ahora mismo" (botón del simulador o recordatorio extra). */
    suspend fun triggerDoseNow(medicationId: String) {
        val dose = MedicationDose(
            id = UUID.randomUUID().toString(),
            medicationId = medicationId,
            patientId = patientId,
            scheduledFor = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
        )
        applyDose(dose)
        insert("medication_doses", Cloud.json.encodeToJsonElement(MedicationDose.serializer(), dose).jsonObject, dose.id)
    }

    suspend fun addMedication(name: String, dosage: String, times: List<String>, color: String, instructions: String?) {
        val med = Medication(
            id = UUID.randomUUID().toString(), patientId = patientId, name = name, dosage = dosage,
            instructions = instructions, scheduleTimes = times.sorted(), color = color, createdBy = me.name,
        )
        applyMedication(med)
        insert("medications", Cloud.json.encodeToJsonElement(Medication.serializer(), med).jsonObject, med.id)
        addLog("note", "Agregó ${med.name} ${med.dosage} a las ${times.sorted().joinToString(", ")}")
    }

    suspend fun setMedicationActive(med: Medication, active: Boolean) {
        val id = med.id ?: return
        applyMedication(med.copy(active = active))
        update("medications", id, buildJsonObject { put("active", active) })
        addLog("note", "${if (active) "Reactivó" else "Pausó"} ${med.name} ${med.dosage}")
    }

    suspend fun raiseAlert(type: String, severity: String, title: String, message: String?, bpm: Int?, lat: Double?, lng: Double?, source: String = deviceKind): Alert {
        val alert = Alert(
            id = UUID.randomUUID().toString(), patientId = patientId, type = type, severity = severity, title = title,
            message = message, bpm = bpm, lat = lat, lng = lng, source = source, createdAt = Times.nowIso(),
        )
        applyAlert(alert, fromRemote = false)
        val payload = Cloud.json.encodeToJsonElement(Alert.serializer(), alert.copy(createdAt = null)).jsonObject
        insert("alerts", payload, alert.id)
        return alert
    }

    /** "Yo atiendo la emergencia". Devuelve false si otro familiar la tomó primero. */
    suspend fun acknowledgeAlert(alert: Alert, etaMinutes: Int?): Boolean {
        val id = alert.id ?: return false
        val now = Times.nowIso()
        applyAlert(alert.copy(status = AlertStatus.ACKNOWLEDGED, acknowledgedBy = me.name, acknowledgedAt = now, etaMinutes = etaMinutes), fromRemote = false)
        val payload = buildJsonObject {
            put("status", AlertStatus.ACKNOWLEDGED)
            put("acknowledged_by", me.name)
            put("acknowledged_at", now)
            etaMinutes?.let { put("eta_minutes", it) }
        }
        return guardedUpdate("alerts", id, payload, "status", listOf(AlertStatus.ACTIVE))
    }

    suspend fun resolveAlert(alert: Alert, cancelled: Boolean = false) {
        val id = alert.id ?: return
        val now = Times.nowIso()
        val status = if (cancelled) AlertStatus.CANCELLED else AlertStatus.RESOLVED
        applyAlert(alert.copy(status = status, resolvedBy = me.name, resolvedAt = now), fromRemote = false)
        val payload = buildJsonObject {
            put("status", status)
            put("resolved_by", me.name)
            put("resolved_at", now)
        }
        guardedUpdate("alerts", id, payload, "status", listOf(AlertStatus.ACTIVE, AlertStatus.ACKNOWLEDGED))
    }

    suspend fun addLog(kind: String, message: String) {
        val entry = CareLogEntry(patientId = patientId, author = me.name, device = deviceKind, kind = kind, message = message)
        val payload = Cloud.json.encodeToJsonElement(CareLogEntry.serializer(), entry).jsonObject
        _state.update { st -> st.copy(log = (listOf(entry.copy(createdAt = Times.nowIso())) + st.log).take(120)) }
        insert("care_log", payload, null)
    }

    /** Marca a este miembro como activo (las reglas de acceso sólo le dejan tocar su propia fila). */
    suspend fun touchMembership() {
        val uid = client.auth.currentUserOrNull()?.id ?: return
        runCatching {
            client.from("caregivers").update(buildJsonObject { put("last_seen_at", Times.nowIso()) }) {
                filter {
                    eq("patient_id", patientId)
                    eq("user_id", uid)
                }
            }
        }.onFailure { Log.w(tag, "No se pudo actualizar la membresía: ${it.message}") }
    }

    /** Detiene la sincronización de este paciente (cambio de círculo o cierre de sesión). */
    suspend fun stop(clearLocal: Boolean = false) {
        val ch = channel
        channel = null
        scope.cancel()
        runCatching { ch?.let { client.realtime.removeChannel(it) } }
        if (clearLocal) store.clear()
    }

    // endregion

    // region Signos vitales (los publica el reloj o el simulador del teléfono)

    suspend fun publishVitals(r: VitalsReading, source: String = "watch") {
        val now = System.currentTimeMillis()
        val pulse = VitalsPulse(
            bpm = r.bpm, steps = r.steps, battery = r.battery, lat = r.lat, lng = r.lng, distance = r.distanceM,
            inZone = r.inZone, status = r.status, activity = r.activity, sensorMode = r.sensorMode, sentAt = now,
        )
        applyPulse(pulse)
        channel?.let { ch -> runCatching { ch.broadcast("vitals", pulse) } }

        val statusChanged = r.status != lastPersistedStatus
        if (now - lastPersistVitals >= 3_000 || statusChanged) {
            lastPersistVitals = now
            lastPersistedStatus = r.status
            val live = VitalsLive(
                patientId = patientId, bpm = r.bpm, steps = r.steps, battery = r.battery, charging = r.charging,
                lat = r.lat, lng = r.lng, distanceHomeM = r.distanceM, inSafeZone = r.inZone, status = r.status,
                activity = r.activity, source = source, sensorMode = r.sensorMode, updatedAt = Times.nowIso(),
            )
            runCatching { client.from("vitals_live").upsert(live) { onConflict = "patient_id" } }
                .onFailure { Log.w(tag, "vitals_live sin red: ${it.message}") }
        }
        if (now - lastHistoryAt >= 10_000) {
            lastHistoryAt = now
            runCatching {
                client.from("vitals_history").insert(VitalsSample(patientId = patientId, bpm = r.bpm, steps = r.steps, battery = r.battery))
            }
        }
    }

    suspend fun broadcastCountdown(signal: CountdownSignal) {
        applyCountdown(signal)
        channel?.let { ch -> runCatching { ch.broadcast("countdown", signal) } }
    }

    /** Envía una orden del simulador. Devuelve true si salió por Realtime. */
    suspend fun sendSim(action: String, medicationId: String? = null): Boolean {
        val cmd = SimCommand(action = action, from = me.name, sentAt = System.currentTimeMillis(), medicationId = medicationId)
        val ch = channel ?: return false
        return runCatching { ch.broadcast("sim", cmd) }.isSuccess
    }

    private suspend fun sendPing() {
        val p = me
        val ping = DevicePing(p.deviceId, p.name, deviceKind, p.color, System.currentTimeMillis())
        _state.update { st -> st.copy(devices = st.devices + (p.deviceId to OnlineDevice(ping, System.currentTimeMillis()))) }
        channel?.let { ch -> runCatching { ch.broadcast("ping", ping) } }
    }

    private suspend fun presenceLoop() {
        while (scope.isActive) {
            delay(8_000)
            sendPing()
            val now = System.currentTimeMillis()
            _state.update { st -> st.copy(devices = st.devices.filterValues { now - it.lastSeen < 30_000 }) }
        }
    }

    // endregion

    // region Escrituras con bandeja de salida

    private suspend fun insert(table: String, payload: JsonObject, rowId: String?) {
        val op = PendingOp(kind = PendingOp.INSERT, table = table, payload = payload, rowId = rowId)
        if (!execute(op)) enqueue(op)
    }

    private suspend fun update(table: String, rowId: String, payload: JsonObject) {
        val op = PendingOp(kind = PendingOp.UPDATE, table = table, payload = payload, rowId = rowId)
        if (!execute(op)) enqueue(op)
    }

    /**
     * Actualización condicional: sólo gana el primero. Si la fila ya no cumple la condición,
     * se recarga desde la nube y se devuelve false.
     */
    private suspend fun guardedUpdate(table: String, rowId: String, payload: JsonObject, guardColumn: String, guardValues: List<String>): Boolean {
        val op = PendingOp(kind = PendingOp.UPDATE, table = table, payload = payload, rowId = rowId, guardColumn = guardColumn, guardValues = guardValues)
        return try {
            val rows = runUpdate(op)
            if (rows == 0) {
                Log.i(tag, "$table/$rowId ya había sido atendido por otro dispositivo")
                reloadRow(table, rowId)
                false
            } else true
        } catch (e: Exception) {
            Log.w(tag, "Sin red, se encola $table/$rowId: ${e.message}")
            enqueue(op)
            true
        }
    }

    private suspend fun reloadRow(table: String, rowId: String) {
        runCatching {
            when (table) {
                "medication_doses" -> client.from(table).select { filter { eq("id", rowId) } }.decodeSingleOrNull<MedicationDose>()?.let { applyDose(it) }
                "alerts" -> client.from(table).select { filter { eq("id", rowId) } }.decodeSingleOrNull<Alert>()?.let { applyAlert(it, fromRemote = true) }
            }
        }
    }

    private suspend fun runUpdate(op: PendingOp): Int {
        val result = client.from(op.table).update(op.payload) {
            select()
            filter {
                eq("id", op.rowId!!)
                if (op.guardColumn != null && op.guardValues.isNotEmpty()) isIn(op.guardColumn, op.guardValues)
            }
        }
        return Cloud.json.parseToJsonElement(result.data).let { (it as? kotlinx.serialization.json.JsonArray)?.size ?: 0 }
    }

    private suspend fun execute(op: PendingOp): Boolean = try {
        when (op.kind) {
            PendingOp.INSERT -> {
                if (op.rowId != null) {
                    client.from(op.table).upsert(op.payload) { onConflict = "id"; ignoreDuplicates = true }
                } else {
                    client.from(op.table).insert(op.payload)
                }
            }
            PendingOp.UPDATE -> runUpdate(op)
        }
        true
    } catch (e: Exception) {
        Log.w(tag, "Escritura ${op.kind} ${op.table} falló: ${e.message}")
        false
    }

    private suspend fun enqueue(op: PendingOp) {
        outboxMutex.withLock {
            val ops = store.loadOutbox() + op
            store.saveOutbox(ops)
            _state.update { it.copy(connection = it.connection.copy(pendingWrites = ops.size)) }
        }
    }

    suspend fun flushOutbox() {
        outboxMutex.withLock {
            val ops = store.loadOutbox()
            if (ops.isEmpty()) return
            val remaining = mutableListOf<PendingOp>()
            for (op in ops) {
                val ok = execute(op)
                if (!ok) remaining += op
            }
            store.saveOutbox(remaining)
            _state.update { it.copy(connection = it.connection.copy(pendingWrites = remaining.size)) }
            Log.i(tag, "Bandeja de salida: ${ops.size - remaining.size} enviadas, ${remaining.size} pendientes")
        }
    }

    private suspend fun outboxLoop() {
        while (scope.isActive) {
            delay(6_000)
            if (_state.value.connection.pendingWrites > 0) flushOutbox()
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun persistCacheLoop() {
        _state.debounce(1_500).collect { st ->
            store.saveSnapshot(
                CacheSnapshot(
                    patient = st.patient, caregivers = st.caregivers, vitals = st.vitals, bpmHistory = st.bpmHistory,
                    medications = st.medications, doses = st.doses, alerts = st.alerts.take(30), log = st.log.take(60),
                    savedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    // endregion

    fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)
}

@kotlinx.serialization.Serializable
private data class IdOnly(val id: String? = null)
