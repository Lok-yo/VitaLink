package com.vitalink.mobile

import android.app.Application
import com.vitalink.core.data.Account
import com.vitalink.core.data.CareRepository
import com.vitalink.core.data.DeviceIdentity
import com.vitalink.core.model.Membership
import com.vitalink.mobile.sim.PhoneSimulator
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Un círculo de cuidado abierto: sincronización en vivo + simulador de ese paciente. */
class CareSession(val repository: CareRepository, val simulator: PhoneSimulator)

class VitaLinkApp : Application() {
    lateinit var identity: DeviceIdentity
        private set
    lateinit var notifier: Notifier
        private set
    val account = Account()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _session = MutableStateFlow<CareSession?>(null)
    val session: StateFlow<CareSession?> = _session.asStateFlow()

    /** Sólo se usa dentro del panel, donde siempre hay un círculo abierto. */
    val repository: CareRepository get() = checkNotNull(_session.value).repository
    val simulator: PhoneSimulator get() = checkNotNull(_session.value).simulator

    @Volatile var inForeground: Boolean = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        identity = DeviceIdentity(this, defaultName = "Familiar", defaultRelation = "Familia")
        notifier = Notifier(this)
        notifier.createChannels()
        scope.launch {
            account.awaitReady()
            val patientId = identity.selectedPatientId
            if (account.hasSession() && patientId != null) open(patientId)
            // Si la sesión se cierra o se revoca en otro lado, se cierra el círculo
            account.status.collect { status ->
                if (status is SessionStatus.NotAuthenticated && _session.value != null) closeSession(clearLocal = true)
            }
        }
    }

    fun enterCircle(membership: Membership) {
        identity.save(membership.name, membership.relation, membership.color, phone = "")
        open(membership.patientId)
    }

    private fun open(patientId: String) {
        _session.value?.repository?.let { old -> scope.launch { old.stop() } }
        identity.selectedPatientId = patientId
        val repo = CareRepository(this, identity, deviceKind = "phone", patientId = patientId)
        val simulator = PhoneSimulator(repo)
        repo.start()
        simulator.start()
        notifier.attach(repo)
        repo.launch { repo.touchMembership() }
        _session.value = CareSession(repo, simulator)
        GuardianService.start(this)
    }

    /** Vuelve al selector de círculos sin cerrar sesión. */
    fun switchCircle() {
        scope.launch { closeSession(clearLocal = false) }
        identity.selectedPatientId = null
    }

    fun signOut() {
        scope.launch {
            closeSession(clearLocal = true)
            identity.clearCircle()
            account.signOut()
        }
    }

    private suspend fun closeSession(clearLocal: Boolean) {
        val current = _session.value ?: return
        _session.value = null
        current.repository.stop(clearLocal)
    }

    companion object {
        lateinit var instance: VitaLinkApp
            private set
    }
}
