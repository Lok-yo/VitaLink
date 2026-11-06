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

class LocalStore(context: Context) {
    private val dir = context.filesDir
    private val cacheFile = File(dir, "vitalink_cache.json")
    private val outboxFile = File(dir, "vitalink_outbox.json")

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
