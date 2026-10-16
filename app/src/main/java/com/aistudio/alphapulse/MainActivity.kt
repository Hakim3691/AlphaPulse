package com.aistudio.alphapulse

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.aistudio.alphapulse.service.AlphaPulseService
import com.aistudio.alphapulse.ui.AlphaPulseScreen
import com.aistudio.alphapulse.ui.theme.MyApplicationTheme
import com.aistudio.alphapulse.ui.viewmodel.AlphaPulseViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: AlphaPulseViewModel by viewModels()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        startAlphaPulseService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startAlphaPulseService()
            }
        } else {
            startAlphaPulseService()
        }

        setContent {
            MyApplicationTheme {
                AlphaPulseScreen(viewModel = viewModel)
            }
        }
    }

    private fun startAlphaPulseService() {
        try {
            val serviceIntent = Intent(this, AlphaPulseService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

