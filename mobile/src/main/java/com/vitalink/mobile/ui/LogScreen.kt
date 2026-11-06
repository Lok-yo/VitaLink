package com.vitalink.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vitalink.core.data.CareState
import com.vitalink.core.data.Profile
import com.vitalink.core.util.Times
import com.vitalink.mobile.VitaLinkApp
import com.vitalink.mobile.ui.theme.VIcons
import com.vitalink.mobile.ui.theme.Vita
import kotlinx.coroutines.launch

@Composable
fun LogScreen(app: VitaLinkApp, state: CareState, profile: Profile, now: Long) {
    var text by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val tz = state.patient?.timezone
    val colorByName = state.caregivers.associate { it.name to Vita.parse(it.color) }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f).testTag("log_screen"),
        ) {
            item {
                Text("Bitácora familiar", style = MaterialTheme.typography.headlineSmall)
                Text("Todo lo que pasa, en orden y para todos", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.log, key = { it.id ?: it.hashCode().toLong() }) { e ->
                val (icon, color) = when (e.kind) {
                    "medication" -> VIcons.Pill to Vita.Violet
                    "alert" -> VIcons.Warning to Vita.Coral
                    "vitals" -> VIcons.Heart to Vita.Coral
                    "note" -> VIcons.Notes to Vita.Indigo
                    else -> VIcons.Cloud to Vita.Teal
                }
                Row(verticalAlignment = Alignment.Top) {
                    IconBadge(icon, color, 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(e.author, fontWeight = FontWeight.Bold, color = colorByName[e.author] ?: MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                when (e.device) { "watch" -> VIcons.Watch; "cloud" -> VIcons.Cloud; else -> VIcons.Phone },
                                null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 1.dp).width(14.dp),
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (Times.isToday(e.createdAt, tz)) Times.hour(e.createdAt, tz) else Times.relative(e.createdAt, now),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(e.message, style = MaterialTheme.typography.bodyMedium)
                        if (e.id == null) {
                            Text("Pendiente de envío · se sincroniza al volver la conexión", style = MaterialTheme.typography.labelSmall, color = Vita.Amber)
                        }
                    }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    placeholder = { Text("Escribe una nota para la familia…") },
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.weight(1f).testTag("note_input"),
                    maxLines = 3,
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = {
                        val msg = text.trim()
                        if (msg.isNotEmpty()) {
                            text = ""
                            scope.launch { app.repository.addLog("note", msg) }
                        }
                    },
                    modifier = Modifier.testTag("note_send"),
                ) { Icon(VIcons.Send, "Enviar") }
            }
        }
    }
}
