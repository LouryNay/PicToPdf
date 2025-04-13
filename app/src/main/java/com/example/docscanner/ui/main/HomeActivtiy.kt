package com.example.docscanner.ui.main


import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.example.docscanner.ui.main.scan.AnalysisActivity
import com.example.docscanner.ui.theme.DocScannerTheme

class HomeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            DocScannerTheme {
                HomeScreen(
                    onOpenPreferences = { openPreferences() },
                    onImageSelected = { uri ->
                        goToLoadingActivity(uri)
                    }
                )
            }
        }
    }

    private fun goToLoadingActivity(imageUri: Uri) {
        val intent = Intent(this, AnalysisActivity::class.java).apply {
            putExtra("imageUri", imageUri)
        }
        startActivity(intent)
    }

    private fun openPreferences() {
        Toast.makeText(this, "Page de préférences à venir", Toast.LENGTH_SHORT).show()
    }
}
@Composable
fun HomeScreen(
    onOpenPreferences: () -> Unit,
    onImageSelected: (Uri) -> Unit
) {
    val context = LocalContext.current
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && imageUri != null) {
            onImageSelected(imageUri!!)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            onImageSelected(it)
        }
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Button(onClick = { showDialog = true }) {
            Text("Nouveau scan")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = onOpenPreferences) {
            Text("Préférences")
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Choisir une source") },
            text = { Text("Voulez-vous prendre une photo ou importer une image ?") },
            confirmButton = {
                Column {
                    TextButton(onClick = {
                        val file = createImageFile(context)
                        file?.let {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                it
                            )
                            imageUri = uri
                            cameraLauncher.launch(uri)
                        }
                        showDialog = false
                    }) {
                        Text("Prendre une photo")
                    }

                    TextButton(onClick = {
                        galleryLauncher.launch("image/*")
                        showDialog = false
                    }) {
                        Text("Importer depuis la galerie")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Annuler")
                }
            }
        )
    }
}
