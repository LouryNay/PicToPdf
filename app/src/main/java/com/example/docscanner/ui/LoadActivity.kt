package com.example.docscanner.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.docscanner.ui.theme.DocScannerTheme
import com.example.docscanner.ui.main.HomeActivity

class LoadActivity : ComponentActivity() {

    private val REQUIRED_PERMISSIONS = mutableListOf(
        android.Manifest.permission.CAMERA
    ).apply {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            add(android.Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    private val PERMISSION_REQUEST_CODE = 100

    private fun checkPermissions() {
        val allGranted = REQUIRED_PERMISSIONS.all {
            checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            goToHome()
        } else {
            requestPermissions(REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE)
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DocScannerTheme {
                LoadingScreen()
            }
        }
        window.decorView.postDelayed({
            checkPermissions()
        }, 800)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
        deviceId: Int
    ) {
        super.onRequestPermissionsResult(requestCode, permissions as Array<String>, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                goToHome()
            } else {
                // Tu peux afficher un message ici si besoin
                finish() // ou rester sur place si tu veux redemander plus tard
            }
        }
    }

    private fun goToHome() {
        val intent = android.content.Intent(this, HomeActivity::class.java)
        startActivity(intent)
        finish()
    }

}

@Composable
fun LoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("Chargement...", fontSize = 20.sp)
        }
    }
}

