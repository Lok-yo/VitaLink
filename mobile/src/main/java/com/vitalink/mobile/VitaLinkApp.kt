package com.vitalink.mobile

import android.app.Application
import com.vitalink.core.data.CareRepository
import com.vitalink.core.data.DeviceIdentity
import com.vitalink.mobile.sim.PhoneSimulator

class VitaLinkApp : Application() {
    lateinit var identity: DeviceIdentity
        private set
    lateinit var repository: CareRepository
        private set
    lateinit var simulator: PhoneSimulator
        private set
    lateinit var notifier: Notifier
        private set

    @Volatile var inForeground: Boolean = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        identity = DeviceIdentity(this, defaultName = "Familiar", defaultRelation = "Familia")
        repository = CareRepository(this, identity, deviceKind = "phone")
        simulator = PhoneSimulator(repository)
        notifier = Notifier(this, repository)
        notifier.createChannels()
        repository.start()
        notifier.start()
        simulator.start()
        if (identity.profile.value.onboarded) repository.launch { repository.registerCaregiver() }
    }

    companion object {
        lateinit var instance: VitaLinkApp
            private set
    }
}
