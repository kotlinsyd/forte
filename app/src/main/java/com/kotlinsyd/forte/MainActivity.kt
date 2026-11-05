package com.kotlinsyd.forte

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import com.kotlinsyd.forte.ui.ForteRoot
import com.kotlinsyd.forte.ui.ForteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge everywhere: content draws behind the system bars
        // and every screen applies the real status/navigation insets
        // itself (see ForteRoot), so no control ever sits under the
        // battery/signal tray or the gesture bar.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark("#0A0A0E".toColorInt()),
            // Transparent navigation area (v1.0.6): page content
            // flows behind the gesture bar to the physical bottom
            // edge — the floating capsule must not sit on a solid
            // band (owner's rule).
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        // API 29+ only: this property does not exist before
        // Android 10, and touching it on Android 8/9 crashed the
        // app at launch (FORTE-4).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001
            )
        }
        setContent {
            ForteTheme {
                ForteRoot()
            }
        }
    }
}
