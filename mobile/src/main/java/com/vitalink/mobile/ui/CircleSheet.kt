package com.vitalink.mobile.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitalink.core.data.AccountException
import com.vitalink.core.data.CareState
import com.vitalink.core.model.MemberRole
import com.vitalink.core.util.Times
import com.vitalink.mobile.VitaLinkApp
import com.vitalink.mobile.ui.theme.NumberStyle
import com.vitalink.mobile.ui.theme.VIcons
import com.vitalink.mobile.ui.theme.Vita
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CircleSheet(app: VitaLinkApp, state: CareState, now: Long, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val patient = state.patient
    var watchCode by remember { mutableStateOf("") }
    var linking by remember { mutableStateOf(false) }
    var linkMessage by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val devices = state.caregivers.filter { it.role == MemberRole.DEVICE }
    val family = state.caregivers.filter { it.role != MemberRole.DEVICE }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            // La hoja vive en su propia ventana: no hereda testTagsAsResourceId de la raíz
            Modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }
                .verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Círculo de ${patient?.nickname ?: "cuidado"}", style = MaterialTheme.typography.headlineSmall)
            Text(app.account.email().orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)

            Surface(shape = RoundedCornerShape(20.dp), color = Vita.Teal.copy(alpha = 0.1f), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Código para invitar a la familia", fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            patient?.inviteCode?.chunked(3)?.joinToString(" ") ?: "------",
                            style = NumberStyle.copy(fontSize = 34.sp, letterSpacing = 2.sp), color = Vita.TealDark,
                            modifier = Modifier.weight(1f).testTag("invite_code"),
                        )
                        Button(
                            onClick = {
                                val text = "Únete al círculo de cuidado de ${patient?.nickname} en VitaLink con el código ${patient?.inviteCode}"
                                context.startActivity(
                                    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Compartir código")
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            },
                            shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Vita.Teal),
                        ) { Text("Compartir") }
                    }
                    Text("Quien lo escriba al crear su cuenta verá todo lo de ${patient?.nickname ?: "tu familiar"}.", style = MaterialTheme.typography.bodySmall)
                }
            }

            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(VIcons.Watch, if (devices.isNotEmpty()) Vita.Ok else Vita.Muted, 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Reloj del paciente", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (devices.isEmpty()) "Sin vincular" else "${devices.size} reloj(es) vinculado(s)" +
                                    if (state.realWatchOnline(now)) " · en línea" else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text("Abre VitaLink en el reloj: mostrará un código de 6 dígitos. Escríbelo aquí.", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            watchCode, { watchCode = it.filter(Char::isDigit).take(6) }, label = { Text("Código del reloj") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f).testTag("watch_code_input"),
                        )
                        Button(
                            enabled = watchCode.length == 6 && !linking && patient != null,
                            onClick = {
                                linking = true
                                scope.launch {
                                    linkMessage = try {
                                        app.account.claimDevice(watchCode, app.repository.patientId)
                                        watchCode = ""
                                        "Listo: el reloj se conecta en unos segundos" to true
                                    } catch (e: AccountException) {
                                        (e.message ?: "No se pudo vincular") to false
                                    } finally {
                                        linking = false
                                    }
                                }
                            },
                            shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Vita.Indigo),
                            modifier = Modifier.testTag("watch_code_submit"),
                        ) { Text("Vincular") }
                    }
                    linkMessage?.let { (msg, ok) ->
                        Text(msg, color = if (ok) Vita.Ok else MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("watch_link_result"))
                    }
                }
            }

            Text("Familia (${family.size})", style = MaterialTheme.typography.titleMedium)
            val online = state.devices.values.filter { now - it.lastSeen < 30_000 }.map { it.ping.name }.toSet()
            family.sortedBy { it.name }.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(c.name, Vita.parse(c.color), 40.dp, online = c.name in online)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name + if (c.userId == app.account.userId()) " (tú)" else "", fontWeight = FontWeight.SemiBold)
                        Text(
                            c.relation + (if (c.role == MemberRole.OWNER) " · administra" else "") + " · activo ${Times.relative(c.lastSeenAt, now)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider()
            OutlinedButton(onClick = { onDismiss(); app.switchCircle() }, modifier = Modifier.fillMaxWidth().testTag("switch_circle")) {
                Text("Cambiar de círculo")
            }
            TextButton(onClick = { onDismiss(); app.signOut() }, modifier = Modifier.fillMaxWidth().testTag("sheet_sign_out")) {
                Text("Cerrar sesión", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
