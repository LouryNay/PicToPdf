package com.example.docscanner.ui.main.scan

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.docscanner.ui.main.await
import com.example.docscanner.ui.theme.DocScannerTheme
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.core.MatOfPoint
import org.opencv.android.Utils
import org.opencv.imgproc.Imgproc
import org.opencv.core.Core
import java.util.ArrayList


class AnalysisActivity : ComponentActivity() {

    companion object {
        private const val TAG = "AnalysisActivity"
        private const val MAX_IMAGE_DIMENSION = 1080

        init {
            if (!OpenCVLoader.initDebug()) {
                Log.e("OpenCV", "Erreur de chargement OpenCV !")
            } else {
                Log.d("OpenCV", "OpenCV chargé avec succès.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            DocScannerTheme {
                AnalysisScreen()
            }
        }

        val imageUri = intent.getParcelableExtra<Uri>("imageUri")

        if (imageUri != null) {
            // Lancer l'analyse dans un thread séparé
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val bitmap = uriToBitmap(imageUri)
                    val scaledBitmap = scaleBitmapIfNeeded(bitmap)
                    val result = analyzeDocument(scaledBitmap)

                    // Revenir sur le thread principal pour passer à l'activité suivante
                    withContext(Dispatchers.Main) {
                        goToEditActivity(result)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erreur lors de l'analyse de l'image", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@AnalysisActivity,
                            "Erreur lors de l'analyse: ${e.localizedMessage}",
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    }
                }
            }
        } else {
            Toast.makeText(this, "Erreur : image non trouvée", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun scaleBitmapIfNeeded(bitmap: Bitmap, maxDimension: Int = MAX_IMAGE_DIMENSION): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxDimension && height <= maxDimension) {
            return bitmap
        }

        val scale = maxDimension.toFloat() / Math.max(width, height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        Log.d(TAG, "Redimensionnement du bitmap: $width x $height -> $newWidth x $newHeight")
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private suspend fun analyzeDocument(bitmap: Bitmap): AnalyzedDocument {
        try {
            Log.d(TAG, "Début de l'analyse du document: ${bitmap.width} x ${bitmap.height}")

            // 1. Utiliser directement ML Kit pour la détection des blocs de texte
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = recognizer.process(image).await()

            val textZones = mutableListOf<SimpleRect>()
            val texts = mutableListOf<String>()

            // Extraire les blocs de texte détectés par ML Kit
            for (block in result.textBlocks) {
                val boundingBox = block.boundingBox
                if (boundingBox != null) {
                    textZones.add(SimpleRect(
                        boundingBox.left,
                        boundingBox.top,
                        boundingBox.width(),
                        boundingBox.height()
                    ))
                    texts.add(block.text)
                    Log.d(TAG, "Bloc de texte détecté: ${boundingBox.left},${boundingBox.top} - '${block.text.take(20)}...'")
                }
            }

            // 2. Utiliser OpenCV pour la détection d'images
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)

            // Prétraitement pour la détection d'images
            val processed = Mat()
            if (mat.channels() == 4) {
                Imgproc.cvtColor(mat, processed, Imgproc.COLOR_RGBA2RGB)
            } else if (mat.channels() == 3) {
                mat.copyTo(processed)
            } else {
                Imgproc.cvtColor(mat, processed, Imgproc.COLOR_GRAY2RGB)
            }

            // Conversion en HSV pour mieux détecter les zones colorées (potentiellement des images)
            val hsv = Mat()
            Imgproc.cvtColor(processed, hsv, Imgproc.COLOR_RGB2HSV)

            // Extraction du canal de saturation (permet de distinguer les zones colorées)
            val channels =  ArrayList<Mat>()
            Core.split(hsv, channels)
            val saturation = channels.get(1)

            // Binarisation pour isoler les zones colorées
            val colorMask = Mat()
            Imgproc.threshold(saturation, colorMask, 40.0, 255.0, Imgproc.THRESH_BINARY)

            // Opérations morphologiques pour regrouper les pixels
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(15.0, 15.0))
            Imgproc.morphologyEx(colorMask, colorMask, Imgproc.MORPH_CLOSE, kernel)

            // Trouver les contours des zones colorées
            val contours = mutableListOf<MatOfPoint>()
            Imgproc.findContours(colorMask, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            val imageZones = mutableListOf<SimpleRect>()

            // Filtrer les contours pour ne conserver que les grandes zones
            for (contour in contours) {
                val rect = Imgproc.boundingRect(contour)
                val area = rect.width * rect.height

                // Ignorer les contours trop petits
                if (area < bitmap.width * bitmap.height * 0.01) continue // Au moins 1% de l'image

                val imageRect = SimpleRect.from(rect)

                // Vérifier que cette zone n'est pas déjà couverte par une zone de texte
                if (!isOverlappingWithTextZones(imageRect, textZones, 0.7)) {
                    imageZones.add(imageRect)
                    Log.d(TAG, "Zone d'image détectée: ${rect.x},${rect.y},${rect.width},${rect.height}")
                }
            }

            // 3. Si aucune zone d'image n'est détectée, essayer une approche alternative
            if (imageZones.isEmpty()) {
                Log.d(TAG, "Aucune zone d'image détectée, utilisation de la méthode alternative")

                // Convertir en niveaux de gris
                val gray = Mat()
                Imgproc.cvtColor(processed, gray, Imgproc.COLOR_RGB2GRAY)

                // Détection des bords (Canny)
                val edges = Mat()
                Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
                Imgproc.Canny(gray, edges, 50.0, 150.0)

                // Dilatation pour connecter les bords
                Imgproc.dilate(edges, edges, kernel)

                // Détection des contours des objets
                contours.clear()
                Imgproc.findContours(edges, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

                for (contour in contours) {
                    val rect = Imgproc.boundingRect(contour)
                    val area = rect.width * rect.height

                    // Filtrer par taille et vérifie que ce n'est pas déjà un bloc de texte
                    if (area > bitmap.width * bitmap.height * 0.05) { // Au moins 5% de l'image
                        val imageRect = SimpleRect.from(rect)

                        if (!isOverlappingWithTextZones(imageRect, textZones, 0.7)) {
                            imageZones.add(imageRect)
                            Log.d(TAG, "Zone d'image alternative: ${rect.x},${rect.y},${rect.width},${rect.height}")
                        }
                    }
                }
            }

            // Libérer les ressources
            mat.release()
            processed.release()
            hsv.release()
            for (ch in channels) ch.release()
            saturation.release()
            colorMask.release()

            Log.d(TAG, "Analyse terminée: ${textZones.size} zones de texte, ${imageZones.size} zones d'image")

            return AnalyzedDocument(textZones, imageZones, texts)

        } catch (e: Exception) {
            Log.e(TAG, "Erreur pendant l'analyse du document", e)
            throw e
        }
    }

    // Vérifie si un rectangle d'image chevauche significativement les zones de texte
    private fun isOverlappingWithTextZones(
        imageRect: SimpleRect,
        textZones: List<SimpleRect>,
        threshold: Double
    ): Boolean {
        for (textZone in textZones) {
            val intersection = getIntersectionArea(imageRect, textZone)
            val textArea = textZone.width * textZone.height

            // Si l'intersection couvre une grande partie de la zone de texte
            if (intersection > 0 && intersection / textArea > threshold) {
                return true
            }
        }
        return false
    }

    private fun getIntersectionArea(r1: SimpleRect, r2: SimpleRect): Double {
        val left = Math.max(r1.x, r2.x)
        val top = Math.max(r1.y, r2.y)
        val right = Math.min(r1.x + r1.width, r2.x + r2.width)
        val bottom = Math.min(r1.y + r1.height, r2.y + r2.height)

        if (left < right && top < bottom) {
            return (right - left) * (bottom - top).toDouble()
        }
        return 0.0
    }

    private fun preprocessImage(mat: Mat): Mat {
        try {
            val gray = Mat()

            // Traitement adapté selon le nombre de canaux
            when (mat.channels()) {
                4 -> {
                    // RGBA -> RGB -> GRIS
                    Log.d(TAG, "Conversion RGBA -> Gris")
                    val rgb = Mat()
                    Imgproc.cvtColor(mat, rgb, Imgproc.COLOR_RGBA2RGB)
                    Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
                    rgb.release() // Libérer la mémoire
                }
                3 -> {
                    // RGB -> GRIS
                    Log.d(TAG, "Conversion RGB -> Gris")
                    Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGB2GRAY)
                }
                1 -> {
                    // Déjà en niveaux de gris
                    Log.d(TAG, "Image déjà en niveaux de gris")
                    mat.copyTo(gray)
                }
                else -> {
                    Log.e(TAG, "Nombre de canaux inattendu: ${mat.channels()}")
                    mat.copyTo(gray)
                }
            }

            // Vérifier que gray n'est pas vide
            if (gray.empty()) {
                Log.e(TAG, "Échec de la conversion en niveaux de gris")
                return mat.clone()
            }

            Log.d(TAG, "Application du flou gaussien")
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

            Log.d(TAG, "Application du seuillage adaptatif")
            Imgproc.adaptiveThreshold(
                gray,
                gray,
                255.0,
                Imgproc.ADAPTIVE_THRESH_MEAN_C,
                Imgproc.THRESH_BINARY_INV,
                15,
                10.0
            )

            return gray
        } catch (e: Exception) {
            Log.e(TAG, "Erreur dans le prétraitement de l'image", e)
            throw e
        }
    }

    private suspend fun runOcrOnZones(bitmap: Bitmap, textZones: MutableList<SimpleRect>): List<String> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val results = mutableListOf<String>()

        Log.d(TAG, "Début de l'OCR sur ${textZones.size} zones")

        for ((index, zone) in textZones.withIndex()) {
            try {
                // Vérifier que les dimensions sont valides
                if (zone.x < 0 || zone.y < 0 || zone.width <= 0 || zone.height <= 0 ||
                    zone.x + zone.width > bitmap.width || zone.y + zone.height > bitmap.height) {
                    Log.e(TAG, "Zone de texte invalide: ($zone.x, $zone.y, $zone.width, $zone.height)")
                    results.add("")
                    continue
                }

                Log.d(TAG, "OCR sur zone $index: (${zone.x}, ${zone.y}, ${zone.width}, ${zone.height})")

                val cropped = Bitmap.createBitmap(bitmap, zone.x, zone.y, zone.width, zone.height)
                val image = InputImage.fromBitmap(cropped, 0)

                val result = recognizer.process(image).await()
                results.add(result.text)

                Log.d(TAG, "OCR zone $index terminé: ${result.text.length} caractères")

                // Libérer la mémoire
                if (!cropped.isRecycled) {
                    cropped.recycle()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erreur OCR sur zone $index", e)
                results.add("")
            }
        }

        return results
    }

    private fun uriToBitmap(uri: Uri): Bitmap {
        return try {
            Log.d(TAG, "Conversion de l'Uri en Bitmap: $uri")

            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.setMutableRequired(true)  // Assurer que le bitmap est mutable
                }
            } else {
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }

            Log.d(TAG, "Bitmap créé avec succès: ${bitmap.width} x ${bitmap.height}")
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Erreur de conversion de l'Uri en Bitmap", e)
            throw IllegalArgumentException("Erreur de conversion de l'Uri en Bitmap: ${e.localizedMessage}")
        }
    }

    private fun goToEditActivity(data: AnalyzedDocument) {
        Log.d(TAG, "Navigation vers EditActivity avec ${data.textZones.size} zones de texte " +
                "et ${data.imageZones.size} zones d'image")

        val intent = Intent(this, EditActivity::class.java)
        intent.putExtra("documentData", data)
        startActivity(intent)
        finish()
    }

    fun uriToMat(context: Context, imageUri: Uri): Mat? {
        return try {
            val inputStream = context.contentResolver.openInputStream(imageUri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)
            inputStream?.close()
            mat
        } catch (e: Exception) {
            Log.e(TAG, "Erreur de conversion Uri -> Mat", e)
            null
        }
    }
}

@Composable
fun AnalysisScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("Analyse en cours...")
        }
    }
}