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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.docscanner.R
import com.example.docscanner.ui.main.scan.AnalyzedDocument
import com.example.docscanner.ui.main.scan.DocumentElement
import com.example.docscanner.ui.theme.DocScannerTheme
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Image as PdfImage
import com.itextpdf.layout.element.Paragraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class EditActivity : ComponentActivity() {

    companion object {
        private const val TAG = "EditActivity"
        // A4 dimensions in points (72 points per inch)
        private const val A4_WIDTH_POINTS = 595f
        private const val A4_HEIGHT_POINTS = 842f
    }

    private lateinit var analyzedDocument: AnalyzedDocument
    private var originalBitmap: Bitmap? = null
    private var documentName: String = "Document"
    private var originalWidth: Int = 0
    private var originalHeight: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Récupération des données transmises
        intent.getParcelableExtra<AnalyzedDocument>("analyzedDocument")?.let {
            analyzedDocument = it
            originalBitmap = analyzedDocument.originalBitmap
            if (originalBitmap != null) {
                originalWidth = originalBitmap!!.width
                originalHeight = originalBitmap!!.height
            }
        } ?: run {
            Toast.makeText(this, "Erreur: document non trouvé", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Récupérer l'image originale si disponible
        if (originalBitmap == null) {
            intent.getStringExtra("bitmapPath")?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    originalBitmap = BitmapFactory.decodeFile(path)
                    originalWidth = originalBitmap!!.width
                    originalHeight = originalBitmap!!.height
                }
            }
        }

        // Récupérer le nom du document si disponible
        intent.getStringExtra("documentName")?.let {
            documentName = it
        }

        setContent {
            DocScannerTheme {
                DocumentPreviewScreen(
                    analyzedDocument = analyzedDocument,
                    documentName = documentName,
                    originalWidth = originalWidth,
                    originalHeight = originalHeight,
                    onDocumentNameChange = { documentName = it },
                    onBackPressed = { finish() },
                    onSaveDocument = { exportToPdf() }
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
                    val pdfFileName = "${documentName.replace(" ", "_")}_$timeStamp.pdf"

                    // Créer le fichier dans le dossier de cache de l'application
                    val pdfFile = File(filesDir, pdfFileName)

                    // Créer le PDF avec format A4
                    val pdfWriter = PdfWriter(FileOutputStream(pdfFile))
                    val pdf = PdfDocument(pdfWriter)
                    val document = Document(pdf, PageSize.A4)

                    // Facteur d'échelle pour convertir les coordonnées de l'image originale en coordonnées A4
                    val scaleX = A4_WIDTH_POINTS / originalWidth
                    val scaleY = A4_HEIGHT_POINTS / originalHeight

                    // Ajouter les éléments au document PDF avec leur position correcte
                    analyzedDocument.elements.forEach { element ->
                        when (element) {
                            is DocumentElement.TextElement -> {
                                val paragraph = Paragraph(element.text)
                                // Positionner le texte selon les coordonnées d'origine
                                paragraph.setFixedPosition(
                                    element.rect.x * scaleX,
                                    PageSize.A4.height - (element.rect.y * scaleY) - (element.rect.height * scaleY),
                                    element.rect.width * scaleX
                                )
                                document.add(paragraph)
                            }
                            is DocumentElement.ImageElement -> {
                                val imageBitmap = element.bitmap ?: element.imageUri?.let { uri ->
                                    val imageFile = File(uri)
                                    if (imageFile.exists()) {
                                        BitmapFactory.decodeFile(uri)
                                    } else null
                                }

                                imageBitmap?.let { bitmap ->
                                    // Sauvegarder temporairement le bitmap
                                    val tempFile = File(cacheDir, "temp_img_${System.currentTimeMillis()}.jpg")
                                    FileOutputStream(tempFile).use { out ->
                                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                                    }

                                    // Ajouter l'image au PDF avec le positionnement correct
                                    val imageData = ImageDataFactory.create(tempFile.absolutePath)
                                    val image = PdfImage(imageData)
                                    image.setFixedPosition(
                                        element.rect.x * scaleX,
                                        PageSize.A4.height - (element.rect.y * scaleY) - (element.rect.height * scaleY),
                                    )
                                    image.scaleToFit(element.rect.width * scaleX, element.rect.height * scaleY)
                                    document.add(image)

                                    // Nettoyer le fichier temporaire
                                    tempFile.delete()
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
fun DocumentPreviewScreen(
    analyzedDocument: AnalyzedDocument,
    documentName: String,
    originalWidth: Int,
    originalHeight: Int,
    onDocumentNameChange: (String) -> Unit,
    onBackPressed: () -> Unit,
    onSaveDocument: () -> Unit
) {
    var showTextEditDialog by remember { mutableStateOf(false) }
    var selectedTextElement by remember { mutableStateOf<DocumentElement.TextElement?>(null) }
    var currentDocumentName by remember { mutableStateOf(documentName) }
    var showFontSettingsDialog by remember { mutableStateOf(false) }

    // États pour les préférences de texte
    var fontFamily by remember { mutableStateOf("Arial") }
    var fontSize by remember { mutableStateOf("12") }
    var lineSpacing by remember { mutableStateOf("1.5") }

    // Dimensions pour l'aperçu au format A4
    val a4AspectRatio = 210f / 297f // Largeur / Hauteur en mm
    val density = LocalDensity.current

    Scaffold(
        topBar = {
            Surface(
                color = Color(0xFF1C2135),
                contentColor = Color.White,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBackPressed) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Retour")
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Text(
                            text = "Édition du document",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFFE5E9EC))
                .padding(16.dp)
        ) {
            // Nom du document
            OutlinedTextField(
                value = currentDocumentName,
                onValueChange = {
                    currentDocumentName = it
                    onDocumentNameChange(it)
                },
                label = { Text("Nom du document") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                singleLine = true
            )

            // Contrôles d'édition
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Bouton d'édition de texte
                Button(
                    onClick = {
                        // Sélectionner le premier élément de texte pour l'édition
                        analyzedDocument.elements.filterIsInstance<DocumentElement.TextElement>().firstOrNull()?.let {
                            selectedTextElement = it
                            showTextEditDialog = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF26304C)
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Éditer le texte")
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Bouton préférences de texte
                Button(
                    onClick = { showFontSettingsDialog = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF26304C)
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Préférences")
                }
            }

            // Aperçu du document au format A4
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .aspectRatio(a4AspectRatio)
                    .background(Color.White)
                    .border(1.dp, Color.Gray)
                    .padding(8.dp)
            ) {
                // Calculer les facteurs d'échelle pour le rendu
                val containerWidth = with(density) {
                    // Estimer la largeur disponible en pixels (approximation)
                    LocalContext.current.resources.displayMetrics.widthPixels / density.density - 48
                }
                val scaleX = containerWidth / originalWidth
                val scaleY = containerWidth / a4AspectRatio / originalHeight

                // Afficher chaque élément du document à sa position correcte
                analyzedDocument.elements.forEach { element ->
                    val x = element.rect.x * scaleX
                    val y = element.rect.y * scaleY
                    val width = element.rect.width * scaleX
                    val height = element.rect.height * scaleY

                    Box(
                        modifier = Modifier
                            .absoluteOffset(x = x.dp, y = y.dp)
                            .width(width.dp)
                            .height(height.dp)
                    ) {
                        when (element) {
                            is DocumentElement.TextElement -> {
                                Text(
                                    text = element.text,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            is DocumentElement.ImageElement -> {
                                element.bitmap?.let { bitmap ->
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "Image élément",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } ?: element.imageUri?.let { uri ->
                                    val file = File(uri)
                                    if (file.exists()) {
                                        val bitmap = BitmapFactory.decodeFile(uri)
                                        bitmap?.let {
                                            Image(
                                                bitmap = it.asImageBitmap(),
                                                contentDescription = "Image élément",
                                                contentScale = ContentScale.Fit,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }
                                } ?: run {
                                    // Placeholder pour les images manquantes
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.LightGray),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.camera),
                                            contentDescription = "Image placeholder",
                                            modifier = Modifier.size(48.dp),
                                            tint = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Bouton Enregistrer le document
            Button(
                onClick = onSaveDocument,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFF9D142)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .height(56.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Done,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Enregistrer en PDF",
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }
        }

        // Dialogue d'édition de texte
        if (showTextEditDialog && selectedTextElement != null) {
            EditTextDialog(
                textElement = selectedTextElement!!,
                onDismiss = { showTextEditDialog = false },
                onSave = { updatedElement ->
                    // Mettre à jour l'élément dans la liste
                    // Dans une implémentation réelle, mise à jour de analyzedDocument
                    selectedTextElement = updatedElement
                    showTextEditDialog = false
                }
            )
        }

        // Dialogue des préférences de texte
        if (showFontSettingsDialog) {
            FontSettingsDialog(
                fontFamily = fontFamily,
                fontSize = fontSize,
                lineSpacing = lineSpacing,
                onFontFamilyChange = { fontFamily = it },
                onFontSizeChange = { fontSize = it },
                onLineSpacingChange = { lineSpacing = it },
                onDismiss = { showFontSettingsDialog = false },
                onApply = {
                    // Appliquer les préférences à tous les éléments de texte
                    showFontSettingsDialog = false
                }
            )
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
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    "Éditer le texte",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(24.dp))

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White
                    ),
                    label = { Text("Contenu du texte") }
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Texte inversé",
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = isInverted,
                        onCheckedChange = { isInverted = it }
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Annuler")
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Button(
                        onClick = {
                            val updatedElement = textElement.copy(
                                text = text,
                                isInverted = isInverted
                            )
                            onSave(updatedElement)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF26304C)
                        )
                    ) {
                        Text("Enregistrer")
                    }
                }
            }
        }
    }
}

@Composable
fun FontSettingsDialog(
    fontFamily: String,
    fontSize: String,
    lineSpacing: String,
    onFontFamilyChange: (String) -> Unit,
    onFontSizeChange: (String) -> Unit,
    onLineSpacingChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onApply: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    "Préférences de texte",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Police
                OutlinedTextField(
                    value = fontFamily,
                    onValueChange = onFontFamilyChange,
                    label = { Text("Police") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Taille
                OutlinedTextField(
                    value = fontSize,
                    onValueChange = onFontSizeChange,
                    label = { Text("Taille (pt)") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Interligne
                OutlinedTextField(
                    value = lineSpacing,
                    onValueChange = onLineSpacingChange,
                    label = { Text("Interligne") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White
                    )
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Annuler")
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Button(
                        onClick = onApply,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF26304C)
                        )
                    ) {
                        Text("Appliquer")
                    }
                }
            }
        }
    }
}