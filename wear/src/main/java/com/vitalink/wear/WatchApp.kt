package com.vitalink.wear

import android.app.Application
import com.vitalink.core.data.Account
import com.vitalink.core.data.AccountException
import com.vitalink.core.data.CareRepository
import com.vitalink.core.data.DeviceIdentity
import com.vitalink.core.data.PairingStatus
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WatchSession(val repository: CareRepository, val brain: WatchBrain)

class WatchApp : Application() {
    lateinit var identity: DeviceIdentity
        private set
    val account = Account()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _session = MutableStateFlow<WatchSession?>(null)
    val session: StateFlow<WatchSession?> = _session.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        identity = DeviceIdentity(this, defaultName = "Paciente", defaultRelation = "Paciente")
        scope.launch {
            account.awaitReady()
            val patientId = identity.selectedPatientId
            if (account.hasSession() && patientId != null) open(patientId)
            account.status.collect { status ->
                if (status is SessionStatus.NotAuthenticated && _session.value != null) close(clearLocal = true)
            }
        }
    }

    /** El familiar ya escribió el código: el reloj entra con su cuenta propia. */
    suspend fun completePairing(result: PairingStatus) {
        val email = result.email ?: throw AccountException("Respuesta de vinculación incompleta")
        val password = result.password ?: throw AccountException("Respuesta de vinculación incompleta")
        val patientId = result.patientId ?: throw AccountException("Respuesta de vinculación incompleta")
        account.signIn(email, password)
        identity.save(result.nickname ?: "Paciente", "Paciente", "#0E9F8E", phone = "")
        open(patientId)
    }

    private fun open(patientId: String) {
        identity.selectedPatientId = patientId
        val repo = CareRepository(this, identity, deviceKind = "watch", patientId = patientId)
        val brain = WatchBrain(this, repo)
        repo.start()
        brain.start()
        repo.launch { repo.touchMembership() }
        _session.value = WatchSession(repo, brain)
    }

    fun unpair() {
        scope.launch {
            close(clearLocal = true)
            identity.clearCircle()
            account.signOut()
        }
    }

    private suspend fun close(clearLocal: Boolean) {
        val current = _session.value ?: return
        _session.value = null
        current.brain.stop()
        current.repository.stop(clearLocal)
    }
}
