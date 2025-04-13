package com.example.docscanner.ui.main.scan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.docscanner.ui.theme.DocScannerTheme

class EditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val document = intent.getParcelableExtra<AnalyzedDocument>("documentData")
        setContent {
            DocScannerTheme {
                document?.let {
                    EditScreen(it)
                } ?: Text("Erreur : données manquantes")
            }
        }
    }
}

@Composable
fun EditScreen(document: AnalyzedDocument) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text("Zones de texte détectées : ${document.textZones.size}")
        document.texts.forEachIndexed { i, text ->
            Text("Texte ${i + 1} : $text")
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Zones d’image détectées : ${document.imageZones.size}")
    }
}
