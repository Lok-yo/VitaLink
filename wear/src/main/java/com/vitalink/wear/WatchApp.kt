package com.vitalink.wear

import android.app.Application
import com.vitalink.core.data.CareRepository
import com.vitalink.core.data.DeviceIdentity

class WatchApp : Application() {
    lateinit var repository: CareRepository
        private set
    lateinit var brain: WatchBrain
        private set

    override fun onCreate() {
        super.onCreate()
        val identity = DeviceIdentity(this, defaultName = "Abuela Rosa", defaultRelation = "Paciente")
        repository = CareRepository(this, identity, deviceKind = "watch")
        brain = WatchBrain(this, repository)
        repository.start()
        brain.start()
    }
}
