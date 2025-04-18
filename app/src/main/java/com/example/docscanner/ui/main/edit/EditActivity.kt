package com.example.docscanner.ui.main.edit

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.docscanner.ui.main.scan.AnalyzedDocument
import com.example.docscanner.ui.main.scan.DocumentElement
import com.example.docscanner.ui.theme.DocScannerTheme
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Image as PdfImage
import com.itextpdf.layout.element.Paragraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.coroutineContext

class EditActivity : ComponentActivity() {

    companion object {
        private const val TAG = "EditActivity"
    }

    private lateinit var analyzedDocument: AnalyzedDocument
    private var originalBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Récupération des données transmises
        intent.getParcelableExtra<AnalyzedDocument>("analyzedDocument")?.let {
            analyzedDocument = it
        } ?: run {
            Toast.makeText(this, "Erreur: document non trouvé", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Récupérer l'image originale si disponible
        intent.getStringExtra("bitmapPath")?.let { path ->
            val file = File(path)
            if (file.exists()) {
                originalBitmap = BitmapFactory.decodeFile(path)
            }
        }

        setContent {
            DocScannerTheme {
                EditScreen(
                    analyzedDocument = analyzedDocument,
                    originalBitmap = originalBitmap,
                    onBackPressed = { finish() },
                    onExportPdf = { exportToPdf() }
                )
            }
        }
    }

    private fun exportToPdf() {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Créer un nom de fichier unique
                    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    val pdfFileName = "DOC_$timeStamp.pdf"

                    // Créer le fichier dans le dossier de cache de l'application
                    val pdfFile = File(filesDir, pdfFileName)

                    // Créer le PDF
                    val pdfWriter = PdfWriter(FileOutputStream(pdfFile))
                    val pdf = PdfDocument(pdfWriter)
                    val document = Document(pdf)

                    // Ajouter les éléments au document PDF
                    analyzedDocument.elements.forEach { element ->
                        when (element) {
                            is DocumentElement.TextElement -> {
                                val paragraph = Paragraph(element.text)
                                document.add(paragraph)
                            }
                            is DocumentElement.ImageElement -> {
                                element.imageUri?.let { uri ->
                                    val imageFile = File(uri)
                                    if (imageFile.exists()) {
                                        val bitmap = BitmapFactory.decodeFile(uri)
                                        if (bitmap != null) {
                                            // Sauvegarder temporairement le bitmap
                                            val tempFile = File(cacheDir, "temp_img_${System.currentTimeMillis()}.jpg")
                                            FileOutputStream(tempFile).use { out ->
                                                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                                            }

                                            // Ajouter l'image au PDF
                                            val imageData = ImageDataFactory.create(tempFile.absolutePath)
                                            val image = PdfImage(imageData)
                                            document.add(image)

                                            // Nettoyer le fichier temporaire
                                            tempFile.delete()
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Fermer le document PDF
                    document.close()

                    // Partager le PDF
                    withContext(Dispatchers.Main) {
                        sharePdf(pdfFile)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating PDF: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "Erreur lors de la création du PDF: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun sharePdf(pdfFile: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", pdfFile)
        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_STREAM, uri)
            type = "application/pdf"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Partager le PDF via"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(
    analyzedDocument: AnalyzedDocument,
    originalBitmap: Bitmap?,
    onBackPressed: () -> Unit,
    onExportPdf: () -> Unit
) {
    var documentElements by remember { mutableStateOf(analyzedDocument.elements) }
    var showDialog by remember { mutableStateOf(false) }
    var selectedElementIndex by remember { mutableStateOf(-1) }
    var isLoading by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Éditer le document") },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = onExportPdf) {
                        Icon(Icons.Default.Share, contentDescription = "Exporter PDF")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Afficher l'image originale si disponible
                originalBitmap?.let { bitmap ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(bottom = 16.dp)
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Document original",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // Afficher le nombre d'éléments
                Text(
                    text = "Éléments: ${documentElements.size}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Liste des éléments du document
                LazyColumn(
                    modifier = Modifier.weight(1f)
                ) {
                    itemsIndexed(documentElements) { index, element ->
                        DocumentElementItem(
                            element = element,
                            onEditClick = {
                                selectedElementIndex = index
                                showDialog = true
                            },
                            onDeleteClick = {
                                val newList = documentElements.toMutableList()
                                newList.removeAt(index)
                                documentElements = newList
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        )
                    }
                }

                // Bouton pour exporter le PDF
                Button(
                    onClick = {
                        isLoading = true
                        onExportPdf()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    enabled = !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text("Exporter en PDF")
                    }
                }
            }

            // Dialogue d'édition
            if (showDialog && selectedElementIndex >= 0) {
                val element = documentElements[selectedElementIndex]
                when (element) {
                    is DocumentElement.TextElement -> {
                        EditTextDialog(
                            textElement = element,
                            onDismiss = { showDialog = false },
                            onSave = { updatedElement ->
                                val newList = documentElements.toMutableList()
                                newList[selectedElementIndex] = updatedElement
                                documentElements = newList
                                showDialog = false
                            }
                        )
                    }
                    is DocumentElement.ImageElement -> {
                        // Dialogue d'édition pour les images si nécessaire
                        Toast.makeText(LocalContext.current, "Édition d'image pas encore implémentée", Toast.LENGTH_SHORT).show()
                        showDialog = false
                    }
                }
            }
        }
    }
}

@Composable
fun DocumentElementItem(
    element: DocumentElement,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icône basée sur le type d'élément
            Icon(
                imageVector = when (element) {
                    is DocumentElement.TextElement -> Icons.Default.Menu
                    is DocumentElement.ImageElement -> Icons.Default.AccountBox
                },
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .padding(end = 8.dp)
            )

            // Contenu de l'élément
            when (element) {
                is DocumentElement.TextElement -> {
                    Text(
                        text = element.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                is DocumentElement.ImageElement -> {
                    element.imageUri?.let { uri ->
                        val file = File(uri)
                        if (file.exists()) {
                            val bitmap = remember {
                                BitmapFactory.decodeFile(uri)
                            }
                            bitmap?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = "Image élément",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))
                }
            }

            // Boutons d'action
            IconButton(onClick = onEditClick) {
                Icon(Icons.Default.Edit, contentDescription = "Éditer")
            }

            IconButton(onClick = onDeleteClick) {
                Icon(Icons.Default.Delete, contentDescription = "Supprimer")
            }
        }
    }
}

@Composable
fun EditTextDialog(
    textElement: DocumentElement.TextElement,
    onDismiss: () -> Unit,
    onSave: (DocumentElement.TextElement) -> Unit
) {
    var text by remember { mutableStateOf(textElement.text) }
    var isInverted by remember { mutableStateOf(textElement.isInverted) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    "Éditer le texte",
                    style = MaterialTheme.typography.titleLarge
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Texte") }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Texte inversé")
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = isInverted,
                        onCheckedChange = { isInverted = it }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Annuler")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            val updatedElement = textElement.copy(
                                text = text,
                                isInverted = isInverted
                            )
                            onSave(updatedElement)
                        }
                    ) {
                        Text("Enregistrer")
                    }
                }
            }
        }
    }
}