package com.vitalink.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.vitalink.mobile.ui.AppRoot
import com.vitalink.mobile.ui.theme.VitaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
        GuardianService.start(this)
        val app = application as VitaLinkApp
        setContent {
            VitaTheme { AppRoot(app) }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as VitaLinkApp).inForeground = true
    }

    override fun onStop() {
        (application as VitaLinkApp).inForeground = false
        super.onStop()
    }
}
