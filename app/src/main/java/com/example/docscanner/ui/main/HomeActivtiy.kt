package com.example.docscanner.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.docscanner.R
import com.example.docscanner.ui.main.scan.AnalysisActivity
import com.example.docscanner.ui.theme.DocScannerTheme

class HomeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            DocScannerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.ui.res.colorResource(id = R.color.light_grey)
                ) {
                    HomeScreen(
                        onOpenLayoutPreferences = { openLayoutPreferences() },
                        onOpenOtherPreferences = { openOtherPreferences() },
                        onImageSelected = { uri -> goToAnalysisActivity(uri) }
                    )
                }
            }
        }
    }

    private fun goToAnalysisActivity(imageUri: Uri) {
        val intent = Intent(this, AnalysisActivity::class.java).apply {
                putExtra("imageUri", imageUri)
            }
        startActivity(intent)
    }

    private fun openLayoutPreferences() {
        Toast.makeText(this, "Page de mise en page à venir", Toast.LENGTH_SHORT).show()
        // Implementation for opening layout preferences
    }

    private fun openOtherPreferences() {
        Toast.makeText(this, "Autres préférences à venir", Toast.LENGTH_SHORT).show()
        // Implementation for opening other preferences
    }
}

@Composable
fun HomeScreen(
    onOpenLayoutPreferences: () -> Unit,
    onOpenOtherPreferences: () -> Unit,
    onImageSelected: (Uri) -> Unit
) {
    val context = LocalContext.current
    var imageUri by remember { mutableStateOf<Uri?>(null) }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Logo
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp),
            contentAlignment = Alignment.Center
        ) {
            // Replace with your actual logo resource
            Image(
                painter = painterResource(id = R.drawable.pictopdf),
                contentDescription = "App Logo",
                modifier = Modifier.size(190.dp)
            )
        }

        // Nouveau Scan Title
        Text(
            text = "Nouveau Scan",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 26.dp, bottom = 24.dp)
        )

        // Scan Options Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Camera Option
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
            ) {
                Button(
                    onClick = {
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
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = androidx.compose.ui.res.colorResource(id = R.color.yellow)
                    ),
                    modifier = Modifier.size(100.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.camera),
                        contentDescription = "Camera",
                        tint = androidx.compose.ui.res.colorResource(id = R.color.dark),
                        modifier = Modifier.size(48.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Prendre une photo",
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )
            }

            // Gallery Option
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
            ) {
                Button(
                    onClick = {
                        galleryLauncher.launch("image/*")
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = androidx.compose.ui.res.colorResource(id = R.color.light_blue)
                    ),
                    modifier = Modifier.size(100.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.gallery),
                        contentDescription = "Gallery",
                        tint = androidx.compose.ui.res.colorResource(id = R.color.dark),
                        modifier = Modifier.size(48.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Importer une Image",
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        // Préférences Section
        Text(
            text = "Préférences",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 14.dp)
        )

        // Mise en page Button
        Button(
            onClick = onOpenLayoutPreferences,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = androidx.compose.ui.res.colorResource(id = R.color.medium_dark)
            )
        ) {
            Text(
                text = "Mise en page",
                fontSize = 16.sp,
                color = androidx.compose.ui.res.colorResource(id = R.color.light_grey)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Autres Button
        Button(
            onClick = onOpenOtherPreferences,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = androidx.compose.ui.res.colorResource(id = R.color.medium_dark)
            )
        ) {
            Text(
                text = "Autres",
                fontSize = 16.sp,
                color = androidx.compose.ui.res.colorResource(id = R.color.light_grey)
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Recent Scans Section
        Text(
            text = "Scans récents",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "Pas de scans récents",
            fontSize = 16.sp,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 16.dp)
        )
    }
}
