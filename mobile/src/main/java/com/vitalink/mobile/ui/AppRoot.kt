package com.vitalink.mobile.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vitalink.core.data.CareState
import com.vitalink.core.model.AlertType
import com.vitalink.core.model.CountdownSignal
import com.vitalink.mobile.VitaLinkApp
import com.vitalink.mobile.ui.theme.VIcons
import com.vitalink.mobile.ui.theme.Vita
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Tab(val label: String, val icon: ImageVector) {
    Home("Inicio", VIcons.Home),
    Meds("Medicinas", VIcons.Pill),
    Alerts("Alertas", VIcons.Bell),
    Log("Bitácora", VIcons.Notes),
    Demo("Demo", VIcons.Bolt),
}

@Composable
fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
fun AppRoot(app: VitaLinkApp) {
    val profile by app.identity.profile.collectAsState()
    Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        if (!profile.onboarded) {
            Onboarding(app)
        } else {
            MainShell(app)
        }
    }
}

@Composable
private fun MainShell(app: VitaLinkApp) {
    val state by app.repository.state.collectAsState()
    val profile by app.identity.profile.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var dismissed by rememberSaveable { mutableStateOf(setOf<String>()) }
    val now = rememberNow()

    val overlayAlert = state.openAlerts
        .filter { isCriticalType(it.type) && it.id !in dismissed }
        .maxByOrNull { com.vitalink.core.util.Times.millis(it.createdAt) }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            modifier = Modifier.testTag("tab_${t.name}"),
                            icon = {
                                val badge = when (t) {
                                    Tab.Alerts -> state.openAlerts.size
                                    Tab.Meds -> state.doses.count { it.isOpen && com.vitalink.core.util.Times.millis(it.scheduledFor) <= now }
                                    else -> 0
                                }
                                BadgedBox(badge = { if (badge > 0) Badge { Text("$badge") } }) { Icon(t.icon, t.label) }
                            },
                            label = { Text(t.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Spacer(Modifier.statusBarsPadding())
                CountdownBanner(state)
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "tabs",
                    modifier = Modifier.weight(1f),
                ) { current ->
                    when (current) {
                        Tab.Home -> DashboardScreen(app, state, profile, now, openTab = { tab = it }, openAlert = { id -> dismissed = dismissed - id })
                        Tab.Meds -> MedsScreen(app, state, now)
                        Tab.Alerts -> AlertsScreen(app, state, profile, now, open = { id -> dismissed = dismissed - id })
                        Tab.Log -> LogScreen(app, state, profile, now)
                        Tab.Demo -> DemoScreen(app, state, now)
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = overlayAlert != null,
            enter = fadeIn() + slideInVertically { it / 6 },
            exit = fadeOut() + slideOutVertically { it / 6 },
        ) {
            val alert = overlayAlert ?: state.alerts.firstOrNull() ?: return@AnimatedVisibility
            EmergencyOverlay(
                app = app,
                alert = alert,
                state = state,
                me = profile.name,
                now = now,
                onMinimize = { alert.id?.let { dismissed = dismissed + it } },
            )
        }
    }
}

@Composable
private fun CountdownBanner(state: CareState) {
    val c = state.countdown
    AnimatedVisibility(visible = c != null, enter = slideInVertically() + fadeIn(), exit = slideOutVertically() + fadeOut()) {
        val signal = c ?: return@AnimatedVisibility
        val nickname = state.patient?.nickname ?: "El paciente"
        Surface(color = Vita.Coral, modifier = Modifier.fillMaxWidth().testTag("countdown_banner")) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (signal.type == AlertType.FALL) VIcons.Fall else VIcons.Sos, null, tint = Color.White)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (signal.type == AlertType.FALL) "Posible caída de $nickname" else "$nickname está pidiendo ayuda",
                        color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                    )
                    Text("${signal.secondsLeft}s", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                }
                Text(
                    "El reloj está en cuenta regresiva. Si no la cancela, se enviará la alerta a toda la familia.",
                    color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { signal.secondsLeft / signal.total.toFloat().coerceAtLeast(1f) },
                    color = Color.White, trackColor = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                )
            }
        }
    }
}

private val relations = listOf("Hija", "Hijo", "Nieta", "Nieto", "Pareja", "Enfermera/o", "Médico", "Vecina/o")
private val colors = listOf("#4F6BFF", "#E8618C", "#0E9F8E", "#F59E0B", "#7C5CFF", "#18A0FB")

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Onboarding(app: VitaLinkApp) {
    val state by app.repository.state.collectAsState()
    var name by rememberSaveable { mutableStateOf("Alexis") }
    var relation by rememberSaveable { mutableStateOf("Nieto") }
    var color by rememberSaveable { mutableStateOf(colors.first()) }
    var phone by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val nickname = state.patient?.nickname ?: "Abuela Rosa"

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Vita.Teal, Vita.TealDark)),
        ),
    ) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Box(Modifier.size(84.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                BeatingHeart(bpm = 68, color = Color.White, size = 64.dp)
            }
            Text("VitaLink", style = MaterialTheme.typography.displayMedium, color = Color.White)
            Text(
                "Cuidemos juntos a $nickname. Todo lo que pase en su reloj lo verás aquí al instante, y lo que hagas aquí lo verá toda la familia.",
                color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyLarge,
            )
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("¿Quién eres en el círculo de cuidado?", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = name, onValueChange = { name = it }, label = { Text("Tu nombre") },
                        singleLine = true, modifier = Modifier.fillMaxWidth().testTag("onboarding_name"),
                    )
                    Text("Parentesco", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        relations.forEach { r -> FilterChip(selected = relation == r, onClick = { relation = r }, label = { Text(r) }) }
                    }
                    Text("Tu color", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        colors.forEach { c ->
                            val selected = c == color
                            Box(
                                Modifier.size(if (selected) 40.dp else 34.dp).clip(CircleShape).background(Vita.parse(c))
                                    .clickable { color = c },
                                contentAlignment = Alignment.Center,
                            ) { if (selected) Icon(VIcons.Check, null, tint = Color.White) }
                        }
                    }
                    OutlinedTextField(
                        value = phone, onValueChange = { phone = it }, label = { Text("Teléfono (opcional)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            app.identity.save(name.trim().ifBlank { "Familiar" }, relation, color, phone.trim())
                            scope.launch { app.repository.registerCaregiver() }
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp).testTag("onboarding_enter"),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Vita.Teal),
                    ) { Text("Entrar al círculo de cuidado", fontWeight = FontWeight.Bold) }
                }
            }
            Text(
                "Sin cuentas ni contraseñas para la demo: todos los dispositivos se conectan directo a Supabase.",
                color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}