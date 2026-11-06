package com.vitalink.mobile

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.vitalink.core.data.LinkStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Mantiene vivo el canal de Realtime para recibir emergencias aunque la app esté en segundo plano. */
class GuardianService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as VitaLinkApp
        val notification = app.notifier.ongoing("Conectando con el reloj…")
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(Notifier.ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(Notifier.ONGOING_ID, notification)
            }
        } catch (e: Exception) {
            Log.w("VitaLink", "No se pudo iniciar el servicio en primer plano: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        scope.launch {
            app.repository.state
                .map { st ->
                    val name = st.patient?.nickname ?: "tu familiar"
                    when (st.connection.status) {
                        LinkStatus.LIVE -> "Cuidando a $name · en vivo"
                        LinkStatus.CONNECTING -> "Reconectando…"
                        LinkStatus.OFFLINE -> "Sin conexión · reintentando"
                    }
                }
                .distinctUntilChanged()
                .collect { text ->
                    getSystemService(android.app.NotificationManager::class.java)
                        .notify(Notifier.ONGOING_ID, app.notifier.ongoing(text))
                }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, GuardianService::class.java)) }
        }
    }
}
