package com.vitalink.core.data

import android.util.Log
import com.vitalink.core.model.Membership
import com.vitalink.core.model.Patient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Error pensado para mostrarse tal cual en la interfaz. */
class AccountException(message: String) : Exception(message)

data class NewPatient(
    val fullName: String,
    val nickname: String,
    val age: Int?,
    val bloodType: String?,
    val conditions: String?,
    val emergencyPhone: String?,
    val homeLat: Double,
    val homeLng: Double,
    val homeLabel: String,
    val safeRadiusM: Int,
    val timezone: String,
    val sampleMeds: Boolean,
)

@Serializable
data class PairingTicket(
    @SerialName("pairing_id") val pairingId: String,
    val code: String,
    val secret: String,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
data class PairingStatus(
    val ok: Boolean,
    val status: String? = null,
    val error: String? = null,
    val email: String? = null,
    val password: String? = null,
    @SerialName("patient_id") val patientId: String? = null,
    val nickname: String? = null,
)

/** Cuenta de Supabase Auth, círculos de cuidado y vinculación de relojes. */
class Account {
    private val tag = "VitaLink-Auth"
    private val client get() = Cloud.client

    val status: StateFlow<SessionStatus> get() = client.auth.sessionStatus

    suspend fun awaitReady() = client.auth.awaitInitialization()

    /** Con sesión guardada (aunque no se haya podido renovar por falta de red). */
    fun hasSession(): Boolean = when (status.value) {
        is SessionStatus.Authenticated, is SessionStatus.RefreshFailure -> true
        else -> false
    }

    fun userId(): String? = client.auth.currentUserOrNull()?.id

    fun email(): String? = client.auth.currentUserOrNull()?.email

    fun displayName(): String {
        val user = client.auth.currentUserOrNull()
        return user?.userMetadata?.get("name")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: user?.email?.substringBefore('@')?.replaceFirstChar { it.uppercase() }
            ?: "Familiar"
    }

    suspend fun signUp(name: String, email: String, password: String) {
        val body = buildJsonObject {
            put("name", name.trim())
            put("email", email.trim())
            put("password", password)
        }
        val result = callFunction("sign-up", body)
        if (result["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            throw AccountException(result["error"]?.jsonPrimitive?.content ?: "No se pudo crear la cuenta")
        }
        signIn(email, password)
    }

    suspend fun signIn(email: String, password: String) {
        try {
            client.auth.signInWith(Email) {
                this.email = email.trim().lowercase()
                this.password = password
            }
        } catch (e: AuthRestException) {
            throw AccountException(
                when (e.error) {
                    "invalid_credentials" -> "Correo o contraseña incorrectos"
                    "email_not_confirmed" -> "Esta cuenta aún no está confirmada"
                    "over_request_rate_limit", "over_email_send_rate_limit" -> "Demasiados intentos, espera un minuto"
                    else -> e.errorDescription
                },
            )
        } catch (e: RestException) {
            throw AccountException(e.error)
        } catch (e: Exception) {
            throw AccountException("Sin conexión. Revisa tu internet e intenta de nuevo")
        }
    }

    suspend fun signOut() {
        runCatching { client.auth.signOut() }.onFailure {
            Log.w(tag, "Cierre de sesión sin red; se borra la sesión local: ${it.message}")
            client.auth.clearSession()
        }
    }

    suspend fun memberships(): List<Membership> {
        val uid = userId() ?: return emptyList()
        return guard {
            client.from("caregivers").select(Columns.raw("patient_id,name,relation,color,role,patients(*)")) {
                filter { eq("user_id", uid) }
            }.decodeList<Membership>()
        }
    }

    suspend fun createPatient(p: NewPatient, myName: String, myRelation: String, myColor: String): Patient = guard {
        client.postgrest.rpc(
            "create_patient",
            buildJsonObject {
                put("p_full_name", p.fullName)
                put("p_nickname", p.nickname)
                put("p_age", p.age)
                put("p_blood_type", p.bloodType)
                put("p_conditions", p.conditions)
                put("p_emergency_phone", p.emergencyPhone)
                put("p_home_lat", p.homeLat)
                put("p_home_lng", p.homeLng)
                put("p_home_label", p.homeLabel)
                put("p_safe_radius_m", p.safeRadiusM)
                put("p_timezone", p.timezone)
                put("p_my_name", myName)
                put("p_my_relation", myRelation)
                put("p_my_color", myColor)
                put("p_sample_meds", p.sampleMeds)
            },
        ).decodeAs<Patient>()
    }

    suspend fun joinCircle(code: String, myName: String, myRelation: String, myColor: String): Patient {
        val patientId = guard {
            client.postgrest.rpc(
                "join_circle",
                buildJsonObject {
                    put("p_code", code)
                    put("p_my_name", myName)
                    put("p_my_relation", myRelation)
                    put("p_my_color", myColor)
                },
            ).decodeAsOrNull<String>()
        } ?: throw AccountException("No encontramos ese código de invitación")
        return guard {
            client.from("patients").select { filter { eq("id", patientId) } }.decodeSingle<Patient>()
        }
    }

    /** Un familiar escribe el código que muestra el reloj para vincularlo a su paciente. */
    suspend fun claimDevice(code: String, patientId: String) {
        val claimed = guard {
            client.postgrest.rpc(
                "claim_device",
                buildJsonObject {
                    put("p_code", code)
                    put("p_patient", patientId)
                },
            ).decodeAs<Boolean>()
        }
        if (!claimed) throw AccountException("Código de reloj inválido o vencido")
    }

    // region Reloj

    suspend fun startPairing(): PairingTicket {
        val result = callFunction("device-pairing", buildJsonObject { put("action", "start") })
        if (result["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            throw AccountException(result["error"]?.jsonPrimitive?.content ?: "No se pudo generar el código")
        }
        return Cloud.json.decodeFromJsonElement(PairingTicket.serializer(), result)
    }

    suspend fun pairingStatus(ticket: PairingTicket): PairingStatus {
        val result = callFunction(
            "device-pairing",
            buildJsonObject {
                put("action", "status")
                put("pairing_id", ticket.pairingId)
                put("secret", ticket.secret)
            },
        )
        return Cloud.json.decodeFromJsonElement(PairingStatus.serializer(), result)
    }

    // endregion

    private suspend fun callFunction(name: String, body: JsonObject): JsonObject = try {
        val response = client.functions.invoke(name) {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        Cloud.json.parseToJsonElement(response.bodyAsText()).jsonObject
    } catch (e: RestException) {
        runCatching { Cloud.json.parseToJsonElement(e.response.bodyAsText()).jsonObject }
            .getOrElse { throw AccountException(e.error) }
    } catch (e: AccountException) {
        throw e
    } catch (e: Exception) {
        Log.w(tag, "Función $name falló: ${e.message}")
        throw AccountException("Sin conexión. Revisa tu internet e intenta de nuevo")
    }

    private suspend fun <T> guard(block: suspend () -> T): T = try {
        block()
    } catch (e: RestException) {
        throw AccountException(e.error)
    } catch (e: Exception) {
        Log.w(tag, "Operación falló: ${e.message}")
        throw AccountException("Sin conexión. Revisa tu internet e intenta de nuevo")
    }
}
