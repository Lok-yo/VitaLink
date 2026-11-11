package com.vitalink.mobile

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.app.Notification
import com.vitalink.core.data.CareEvent
import com.vitalink.core.data.CareRepository
import com.vitalink.core.model.AlertStatus
import com.vitalink.core.model.AlertType
import com.vitalink.core.model.CountdownSignal
import com.vitalink.core.model.DoseStatus

/** Notificaciones del sistema para cuando la app no está en pantalla, más vibración en vivo. */
class Notifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val vibrator = context.getSystemService(Vibrator::class.java)

    fun createChannels() {
        val critical = NotificationChannel(CRITICAL, "Emergencias", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "SOS, caídas, pulso anormal y salida de zona segura"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 200, 600, 200, 900)
            enableLights(true)
            lightColor = Color.RED
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
        }
        val care = NotificationChannel(CARE, "Cuidado diario", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Medicinas confirmadas, notas de la familia y avisos"
        }
        val live = NotificationChannel(LIVE, "Monitoreo en vivo", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Mantiene la conexión en tiempo real"
            setShowBadge(false)
        }
        manager.createNotificationChannels(listOf(critical, care, live))
    }

    /** Escucha los eventos del círculo abierto; se detiene solo cuando ese repositorio se cierra. */
    fun attach(repository: CareRepository) {
        repository.launch {
            repository.events.collect { event -> handle(repository, event) }
        }
    }

    private fun handle(repository: CareRepository, event: CareEvent) {
        val nickname = repository.state.value.patient?.nickname ?: "Tu familiar"
        val me = VitaLinkApp.instance.identity.profile.value.name
        when (event) {
            is CareEvent.AlertRaised -> {
                val a = event.alert
                if (!a.isOpen) return
                if (a.type != AlertType.BATTERY) vibrateEmergency()
                if (!VitaLinkApp.instance.inForeground) notify(
                    id = a.id.hashCode(),
                    channel = if (a.type == AlertType.BATTERY) CARE else CRITICAL,
                    title = "${titleFor(a.type)} · $nickname",
                    text = a.message ?: a.title,
                    critical = a.type != AlertType.BATTERY,
                )
            }
            is CareEvent.AlertChanged -> {
                val a = event.alert
                if (a.status == AlertStatus.ACKNOWLEDGED && a.acknowledgedBy != me && event.previous?.status == AlertStatus.ACTIVE) {
                    notify(a.id.hashCode(), CARE, "${a.acknowledgedBy} atiende la emergencia", a.title + (a.etaMinutes?.let { " · llega en ~$it min" } ?: ""), false)
                } else if (!a.isOpen) {
                    manager.cancel(a.id.hashCode())
                }
            }
            is CareEvent.Countdown -> {
                val c = event.signal
                when (c.phase) {
                    CountdownSignal.STARTED -> {
                        vibrateShort()
                        if (!VitaLinkApp.instance.inForeground) notify(COUNTDOWN_ID, CRITICAL, "Posible ${if (c.type == AlertType.FALL) "caída" else "SOS"} de $nickname",
                            "El reloj enviará la alerta en ${c.secondsLeft} s si no se cancela", true)
                    }
                    CountdownSignal.CANCELLED -> if (!VitaLinkApp.instance.inForeground) notify(COUNTDOWN_ID, CARE, "$nickname canceló la alerta", "Fue una falsa alarma: está bien", false)
                    CountdownSignal.SENT -> manager.cancel(COUNTDOWN_ID)
                }
            }
            is CareEvent.DoseChanged -> {
                val d = event.dose
                val prev = event.previous ?: return
                if (prev.status == d.status || d.confirmedBy == me) return
                val med = repository.state.value.medication(d.medicationId) ?: return
                val text = when (d.status) {
                    DoseStatus.TAKEN -> "${d.confirmedBy} confirmó ${med.name} ${med.dosage}" + if (d.confirmedDevice == "watch") " desde el reloj" else ""
                    DoseStatus.SNOOZED -> "${d.confirmedBy} pospuso ${med.name} 10 minutos"
                    DoseStatus.MISSED -> "Nadie confirmó ${med.name} ${med.dosage}"
                    else -> return
                }
                if (!VitaLinkApp.instance.inForeground) notify(d.id.hashCode(), CARE, "Medicinas de $nickname", text, false)
            }
            else -> Unit
        }
    }

    private fun titleFor(type: String) = when (type) {
        AlertType.SOS -> "SOS activado"
        AlertType.FALL -> "Caída detectada"
        AlertType.TACHYCARDIA -> "Pulso muy alto"
        AlertType.BRADYCARDIA -> "Pulso muy bajo"
        AlertType.GEOFENCE -> "Salió de la zona segura"
        AlertType.BATTERY -> "Batería baja del reloj"
        else -> "Alerta"
    }

    private fun notify(id: Int, channel: String, title: String, text: String, critical: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val intent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setColor(if (critical) 0xFFE5484D.toInt() else 0xFF0E9F8E.toInt())
            .setCategory(if (critical) Notification.CATEGORY_ALARM else Notification.CATEGORY_STATUS)
            .build()
        manager.notify(id, n)
    }

    fun vibrateEmergency() {
        if (vibrator?.hasVibrator() == true) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 150, 500, 150, 800), -1))
        }
    }

    private fun vibrateShort() {
        if (vibrator?.hasVibrator() == true) vibrator.vibrate(VibrationEffect.createOneShot(220, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    fun ongoing(text: String): Notification {
        val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(context, LIVE)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle("VitaLink en vivo")
            .setContentText(text)
            .setContentIntent(intent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CRITICAL = "critical"
        const val CARE = "care"
        const val LIVE = "live"
        const val COUNTDOWN_ID = 4242
        const val ONGOING_ID = 1
    }
}
