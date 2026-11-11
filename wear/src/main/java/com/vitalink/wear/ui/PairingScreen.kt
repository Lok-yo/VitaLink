package com.vitalink.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import com.vitalink.core.data.AccountException
import com.vitalink.core.data.PairingTicket
import com.vitalink.wear.WatchApp
import kotlinx.coroutines.delay

@Composable
fun BeatingSplash() {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        BeatingHeart(68, W.Green, 48.dp)
    }
}

/** El reloj muestra un código; un familiar lo escribe en su teléfono y el reloj queda vinculado. */
@Composable
fun PairingScreen(app: WatchApp) {
    var ticket by remember { mutableStateOf<PairingTicket?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var linking by remember { mutableStateOf(false) }

    LaunchedEffect(attempt) {
        ticket = null
        message = null
        val t = try {
            app.account.startPairing()
        } catch (e: AccountException) {
            message = e.message
            return@LaunchedEffect
        }
        ticket = t
        while (true) {
            delay(2_500)
            val status = try {
                app.account.pairingStatus(t)
            } catch (e: AccountException) {
                continue
            }
            when {
                !status.ok -> {
                    message = status.error ?: "No se pudo vincular"
                    return@LaunchedEffect
                }
                status.status == "expired" -> {
                    ticket = null
                    message = "El código venció"
                    return@LaunchedEffect
                }
                status.status == "paired" -> {
                    linking = true
                    try {
                        app.completePairing(status)
                    } catch (e: AccountException) {
                        linking = false
                        message = e.message
                    }
                    return@LaunchedEffect
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).testTag("watch_pairing"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 26.dp)) {
            BeatingHeart(68, W.Green, 26.dp)
            Text(if (linking) "¡Vinculado!" else "Vincula tu reloj", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(4.dp))
            val t = ticket
            when {
                linking -> Text("Entrando…", fontSize = 13.sp, color = W.Green)
                t != null -> {
                    Text(
                        t.code.chunked(3).joinToString(" "), fontSize = 36.sp, fontWeight = FontWeight.Black, color = W.Green,
                        modifier = Modifier.testTag("watch_pair_code"),
                    )
                    Text(
                        "En el teléfono: VitaLink › tu foto › Vincular reloj",
                        fontSize = 11.sp, color = W.Muted, textAlign = TextAlign.Center,
                    )
                }
                message == null -> Text("Pidiendo código…", fontSize = 13.sp, color = W.Muted)
            }
            message?.let { msg ->
                Text(msg, fontSize = 12.sp, color = W.Amber, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(20.dp)).background(W.Green).clickable { attempt++ }
                        .padding(horizontal = 16.dp, vertical = 8.dp).testTag("watch_pair_retry"),
                ) { Text("Nuevo código", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
            }
        }
    }
}
