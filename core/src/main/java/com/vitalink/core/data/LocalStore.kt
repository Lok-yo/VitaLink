package com.vitalink.core.data

import android.content.Context
import com.vitalink.core.model.Alert
import com.vitalink.core.model.CareLogEntry
import com.vitalink.core.model.Caregiver
import com.vitalink.core.model.Medication
import com.vitalink.core.model.MedicationDose
import com.vitalink.core.model.Patient
import com.vitalink.core.model.VitalsLive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.UUID

/** Última foto conocida del círculo de cuidado: permite abrir la app sin red. */
@Serializable
data class CacheSnapshot(
    val patient: Patient? = null,
    val caregivers: List<Caregiver> = emptyList(),
    val vitals: VitalsLive? = null,
    val bpmHistory: List<BpmPoint> = emptyList(),
    val medications: List<Medication> = emptyList(),
    val doses: List<MedicationDose> = emptyList(),
    val alerts: List<Alert> = emptyList(),
    val log: List<CareLogEntry> = emptyList(),
    val savedAt: Long = 0,
)

@Serializable
data class BpmPoint(val t: Long, val bpm: Int)

/** Escritura pendiente que se reintenta cuando vuelve la conexión. */
@Serializable
data class PendingOp(
    val id: String = UUID.randomUUID().toString(),
    val kind: String,
    val table: String,
    val payload: JsonObject,
    val rowId: String? = null,
    val guardColumn: String? = null,
    val guardValues: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val INSERT = "insert"
        const val UPDATE = "update"
    }
}

class LocalStore(context: Context, patientId: String) {
    private val dir = context.filesDir
    private val cacheFile = File(dir, "vitalink_cache_$patientId.json")
    private val outboxFile = File(dir, "vitalink_outbox_$patientId.json")

    fun loadSnapshot(): CacheSnapshot? = runCatching {
        if (!cacheFile.exists()) null else Cloud.json.decodeFromString(CacheSnapshot.serializer(), cacheFile.readText())
    }.getOrNull()

    fun saveSnapshot(snapshot: CacheSnapshot) {
        runCatching {
            val tmp = File(dir, "vitalink_cache.tmp")
            tmp.writeText(Cloud.json.encodeToString(CacheSnapshot.serializer(), snapshot))
            tmp.renameTo(cacheFile)
        }
    }

    fun clear() {
        runCatching {
            cacheFile.delete()
            outboxFile.delete()
        }
    }

    @Synchronized
    fun loadOutbox(): List<PendingOp> = runCatching {
        if (!outboxFile.exists()) emptyList()
        else Cloud.json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(PendingOp.serializer()), outboxFile.readText())
    }.getOrDefault(emptyList())

    @Synchronized
    fun saveOutbox(ops: List<PendingOp>) {
        runCatching {
            outboxFile.writeText(
                Cloud.json.encodeToString(kotlinx.serialization.builtins.ListSerializer(PendingOp.serializer()), ops),
            )
        }
    }
}

data class Profile(
    val deviceId: String,
    val name: String,
    val relation: String,
    val color: String,
    val phone: String,
    val onboarded: Boolean,
)

/** Identidad de este dispositivo dentro del círculo de cuidado. */
class DeviceIdentity(context: Context, defaultName: String, defaultRelation: String) {
    private val prefs = context.getSharedPreferences("vitalink_identity", Context.MODE_PRIVATE)
    private val _profile = MutableStateFlow(read(defaultName, defaultRelation))
    val profile: StateFlow<Profile> = _profile.asStateFlow()

    private fun read(defaultName: String, defaultRelation: String): Profile {
        val id = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
        return Profile(
            deviceId = id,
            name = prefs.getString("name", defaultName) ?: defaultName,
            relation = prefs.getString("relation", defaultRelation) ?: defaultRelation,
            color = prefs.getString("color", "#4F7CFF") ?: "#4F7CFF",
            phone = prefs.getString("phone", "") ?: "",
            onboarded = prefs.getBoolean("onboarded", false),
        )
    }

    /** Círculo abierto la última vez: permite reabrirlo al instante y sin red. */
    var selectedPatientId: String?
        get() = prefs.getString("patient_id", null)
        set(value) = prefs.edit().putString("patient_id", value).apply()

    fun clearCircle() {
        prefs.edit().remove("patient_id").putBoolean("onboarded", false).apply()
        _profile.value = _profile.value.copy(onboarded = false)
    }

    fun save(name: String, relation: String, color: String, phone: String) {
        prefs.edit()
            .putString("name", name)
            .putString("relation", relation)
            .putString("color", color)
            .putString("phone", phone)
            .putBoolean("onboarded", true)
            .apply()
        _profile.value = _profile.value.copy(name = name, relation = relation, color = color, phone = phone, onboarded = true)
    }
}
