package com.vitalink.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vitalink.core.data.AccountException
import com.vitalink.core.data.NewPatient
import com.vitalink.core.model.MemberRole
import com.vitalink.core.model.Membership
import com.vitalink.mobile.VitaLinkApp
import com.vitalink.mobile.ui.theme.VIcons
import com.vitalink.mobile.ui.theme.Vita
import kotlinx.coroutines.launch
import java.time.ZoneId

val relations = listOf("Hija", "Hijo", "Nieta", "Nieto", "Pareja", "Enfermera/o", "Médico", "Vecina/o")
val memberColors = listOf("#4F6BFF", "#E8618C", "#0E9F8E", "#F59E0B", "#7C5CFF", "#18A0FB")

private enum class CircleMode { List, Create, Join }

/** Después de iniciar sesión: elegir un círculo, crear uno nuevo o unirse con un código. */
@Composable
fun CirclePicker(app: VitaLinkApp) {
    var mode by rememberSaveable { mutableStateOf(CircleMode.List) }
    var memberships by remember { mutableStateOf<List<Membership>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reload) {
        error = null
        try {
            memberships = app.account.memberships().filter { it.role != MemberRole.DEVICE }
        } catch (e: AccountException) {
            error = e.message
            memberships = emptyList()
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Hola, ${app.account.displayName()}", style = MaterialTheme.typography.headlineSmall)
                Text(app.account.email().orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { app.signOut() }, modifier = Modifier.testTag("sign_out")) { Text("Cerrar sesión") }
        }
        when (mode) {
            CircleMode.List -> {
                Text("Tus círculos de cuidado", style = MaterialTheme.typography.titleMedium)
                val list = memberships
                when {
                    list == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    list.isEmpty() -> Text(
                        "Aún no perteneces a ningún círculo. Registra a tu familiar o únete con el código que te compartieron.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> list.forEach { m ->
                        Surface(
                            onClick = { app.enterCircle(m) },
                            shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp,
                            modifier = Modifier.fillMaxWidth().testTag("circle_${m.patients?.nickname}"),
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Avatar(m.patients?.nickname ?: "?", Vita.Teal, 48.dp)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(m.patients?.nickname ?: "Paciente", fontWeight = FontWeight.Bold)
                                    Text(
                                        "Eres ${m.relation.lowercase()}" + if (m.role == MemberRole.OWNER) " · administras el círculo" else "",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Icon(VIcons.Play, null, tint = Vita.Teal)
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = { mode = CircleMode.Create },
                    modifier = Modifier.fillMaxWidth().height(56.dp).testTag("circle_create"),
                    shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = Vita.Teal),
                ) {
                    Icon(VIcons.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Registrar a mi familiar", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = { mode = CircleMode.Join },
                    modifier = Modifier.fillMaxWidth().height(56.dp).testTag("circle_join"),
                    shape = RoundedCornerShape(18.dp),
                ) { Text("Tengo un código de invitación", fontWeight = FontWeight.SemiBold) }
                if (error != null) TextButton(onClick = { reload++ }) { Text("Reintentar") }
            }
            CircleMode.Create -> CreatePatientForm(
                app,
                onCancel = { mode = CircleMode.List },
                onCreated = { membership -> app.enterCircle(membership) },
            )
            CircleMode.Join -> JoinCircleForm(
                app,
                onCancel = { mode = CircleMode.List },
                onJoined = { membership -> app.enterCircle(membership) },
            )
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MemberIdentityFields(relation: String, onRelation: (String) -> Unit, color: String, onColor: (String) -> Unit) {
    Text("¿Qué eres de tu familiar?", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        relations.forEach { r -> FilterChip(selected = relation == r, onClick = { onRelation(r) }, label = { Text(r) }) }
    }
    Text("Tu color en la familia", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        memberColors.forEach { c ->
            val selected = c == color
            Box(
                Modifier.size(if (selected) 40.dp else 34.dp).clip(CircleShape).background(Vita.parse(c)).clickable { onColor(c) },
                contentAlignment = Alignment.Center,
            ) { if (selected) Icon(VIcons.Check, null, tint = Color.White) }
        }
    }
}

@Composable
private fun CreatePatientForm(app: VitaLinkApp, onCancel: () -> Unit, onCreated: (Membership) -> Unit) {
    var nickname by rememberSaveable { mutableStateOf("") }
    var fullName by rememberSaveable { mutableStateOf("") }
    var age by rememberSaveable { mutableStateOf("") }
    var conditions by rememberSaveable { mutableStateOf("") }
    var bloodType by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var homeLabel by rememberSaveable { mutableStateOf("Casa") }
    var lat by rememberSaveable { mutableStateOf("29.0892") }
    var lng by rememberSaveable { mutableStateOf("-110.9613") }
    var radius by rememberSaveable { mutableIntStateOf(250) }
    var sampleMeds by rememberSaveable { mutableStateOf(true) }
    var relation by rememberSaveable { mutableStateOf("Nieto") }
    var color by rememberSaveable { mutableStateOf(memberColors.first()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    VitaCard {
        Text("Registrar a mi familiar", style = MaterialTheme.typography.titleLarge)
        Text("Tú quedarás como administrador del círculo y podrás invitar al resto de la familia.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(nickname, { nickname = it }, label = { Text("¿Cómo le dicen? (ej. Abuela Rosa)") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("patient_nickname"))
        OutlinedTextField(fullName, { fullName = it }, label = { Text("Nombre completo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(age, { age = it.filter(Char::isDigit).take(3) }, label = { Text("Edad") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            OutlinedTextField(bloodType, { bloodType = it.take(4) }, label = { Text("Sangre") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(conditions, { conditions = it }, label = { Text("Padecimientos (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(phone, { phone = it }, label = { Text("Teléfono de emergencia") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
        Text("Zona segura", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(homeLabel, { homeLabel = it }, label = { Text("Nombre del lugar") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(lat, { lat = it }, label = { Text("Latitud") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(lng, { lng = it }, label = { Text("Longitud") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(150, 250, 500).forEach { r -> FilterChip(selected = radius == r, onClick = { radius = r }, label = { Text("$r m") }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Cargar tratamiento de ejemplo", fontWeight = FontWeight.SemiBold)
                Text("4 medicinas para probar la demo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = sampleMeds, onCheckedChange = { sampleMeds = it })
        }
        MemberIdentityFields(relation, { relation = it }, color, { color = it })
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onCancel) { Text("Cancelar") }
            Spacer(Modifier.weight(1f))
            Button(
                enabled = !busy && nickname.isNotBlank(),
                onClick = {
                    val latitude = lat.replace(',', '.').toDoubleOrNull()
                    val longitude = lng.replace(',', '.').toDoubleOrNull()
                    if (latitude == null || longitude == null) {
                        error = "Revisa la latitud y longitud"
                        return@Button
                    }
                    busy = true
                    scope.launch {
                        try {
                            val me = app.account.displayName()
                            val patient = app.account.createPatient(
                                NewPatient(
                                    fullName = fullName, nickname = nickname, age = age.toIntOrNull(), bloodType = bloodType,
                                    conditions = conditions, emergencyPhone = phone, homeLat = latitude, homeLng = longitude,
                                    homeLabel = homeLabel, safeRadiusM = radius, timezone = ZoneId.systemDefault().id, sampleMeds = sampleMeds,
                                ),
                                myName = me, myRelation = relation, myColor = color,
                            )
                            onCreated(Membership(patient.id, me, relation, color, MemberRole.OWNER, patient))
                        } catch (e: AccountException) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Vita.Teal),
                modifier = Modifier.testTag("patient_save"),
            ) { Text("Crear círculo") }
        }
    }
}

@Composable
private fun JoinCircleForm(app: VitaLinkApp, onCancel: () -> Unit, onJoined: (Membership) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    var relation by rememberSaveable { mutableStateOf("Hija") }
    var color by rememberSaveable { mutableStateOf(memberColors[1]) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    VitaCard {
        Text("Unirme a un círculo", style = MaterialTheme.typography.titleLarge)
        Text("Pide el código de 6 dígitos a quien administra el círculo (lo ve en su panel, en el menú del círculo).", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("Código de invitación") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().testTag("join_code"),
        )
        MemberIdentityFields(relation, { relation = it }, color, { color = it })
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) }
        Row {
            TextButton(onClick = onCancel) { Text("Cancelar") }
            Spacer(Modifier.weight(1f))
            Button(
                enabled = !busy && code.length == 6,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val me = app.account.displayName()
                            val patient = app.account.joinCircle(code, me, relation, color)
                            onJoined(Membership(patient.id, me, relation, color, MemberRole.CAREGIVER, patient))
                        } catch (e: AccountException) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Vita.Teal),
                modifier = Modifier.testTag("join_submit"),
            ) { Text("Unirme") }
        }
    }
}
