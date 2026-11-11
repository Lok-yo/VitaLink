package com.vitalink.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vitalink.core.data.AccountException
import com.vitalink.mobile.VitaLinkApp
import com.vitalink.mobile.ui.theme.Vita
import kotlinx.coroutines.launch

@Composable
fun Splash() {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Vita.Teal, Vita.TealDark))), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BeatingHeart(bpm = 68, color = Color.White, size = 72.dp)
            Spacer(Modifier.height(12.dp))
            Text("VitaLink", style = MaterialTheme.typography.headlineMedium, color = Color.White)
        }
    }
}

@Composable
fun AuthScreen(app: VitaLinkApp) {
    var register by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        error = when {
            register && name.trim().length < 2 -> "Escribe tu nombre"
            !email.contains('@') -> "Escribe un correo válido"
            password.length < 8 -> "La contraseña debe tener al menos 8 caracteres"
            else -> null
        }
        if (error != null) return
        busy = true
        scope.launch {
            try {
                if (register) app.account.signUp(name, email, password) else app.account.signIn(email, password)
            } catch (e: AccountException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Vita.Teal, Vita.TealDark)))) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(20.dp))
            Box(Modifier.size(80.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                BeatingHeart(bpm = 68, color = Color.White, size = 60.dp)
            }
            Text("VitaLink", style = MaterialTheme.typography.displayMedium, color = Color.White)
            Text(
                "Cuida en equipo a quien más quieres: su reloj y los teléfonos de toda la familia, conectados en tiempo real.",
                color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyLarge,
            )
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = !register, onClick = { register = false; error = null },
                            shape = SegmentedButtonDefaults.itemShape(0, 2), modifier = Modifier.testTag("auth_tab_login"),
                        ) { Text("Iniciar sesión") }
                        SegmentedButton(
                            selected = register, onClick = { register = true; error = null },
                            shape = SegmentedButtonDefaults.itemShape(1, 2), modifier = Modifier.testTag("auth_tab_register"),
                        ) { Text("Crear cuenta") }
                    }
                    AnimatedVisibility(register) {
                        OutlinedTextField(
                            value = name, onValueChange = { name = it }, label = { Text("Tu nombre") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            modifier = Modifier.fillMaxWidth().testTag("auth_name"),
                        )
                    }
                    OutlinedTextField(
                        value = email, onValueChange = { email = it.trim() }, label = { Text("Correo") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth().testTag("auth_email"),
                    )
                    OutlinedTextField(
                        value = password, onValueChange = { password = it }, label = { Text("Contraseña") }, singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        trailingIcon = { TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Ocultar" else "Ver") } },
                        supportingText = { if (register) Text("Mínimo 8 caracteres") },
                        modifier = Modifier.fillMaxWidth().testTag("auth_password"),
                    )
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("auth_error"))
                    }
                    Button(
                        onClick = ::submit,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(56.dp).testTag("auth_submit"),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Vita.Teal),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(if (register) "Crear mi cuenta" else "Entrar", fontWeight = FontWeight.Bold)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Text(
                    "Tus datos de salud sólo los ve tu círculo de cuidado.",
                    color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                )
            }
        }
    }
}
