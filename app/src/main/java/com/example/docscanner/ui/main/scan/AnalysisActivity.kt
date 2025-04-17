package com.example.docscanner.ui.main.scan

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import com.example.docscanner.ui.main.edit.EditActivity
import com.example.docscanner.ui.theme.DocScannerTheme
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import java.io.File
import java.io.FileOutputStream


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
                    val correctedBitmap = correctPerspective(bitmap)
                    val denoisedBitmap = denoiseBitmap(correctedBitmap)
                    val enhancedBitmap = enhanceContrast(denoisedBitmap)
                    val scaledBitmap = scaleBitmapIfNeeded(enhancedBitmap)
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

    private fun detectInvertedTextRegions(mat: Mat): List<SimpleRect> {
        val hsv = Mat()
        Imgproc.cvtColor(mat, hsv, Imgproc.COLOR_RGB2HSV)

        // Définir une plage pour la couleur bleue
        val lowerBlue = Scalar(100.0, 100.0, 100.0)  // Ajustez selon vos besoins
        val upperBlue = Scalar(140.0, 255.0, 255.0)  // Ajustez selon vos besoins

        val blueMask = Mat()
        Core.inRange(hsv, lowerBlue, upperBlue, blueMask)

        // Trouver les contours des zones bleues
        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(blueMask, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        val invertedTextRegions = mutableListOf<SimpleRect>()

        for (contour in contours) {
            val rect = Imgproc.boundingRect(contour)
            val area = rect.width * rect.height

            // Filtrer par taille (ajustez selon vos besoins)
            if (area > mat.width() * mat.height() * 0.01) {
                invertedTextRegions.add(SimpleRect.from(rect))
            }
        }

        hsv.release()
        blueMask.release()

        return invertedTextRegions
    }


    private fun denoiseBitmap(bitmap: Bitmap): Bitmap {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)

        // Conversion en RGB si nécessaire
        val rgbMat = Mat()
        if (mat.channels() == 4) {
            Imgproc.cvtColor(mat, rgbMat, Imgproc.COLOR_RGBA2RGB)
        } else {
            mat.copyTo(rgbMat)
        }

        // Appliquer un débruitage non-local means
        val denoised = Mat()
        Photo.fastNlMeansDenoisingColored(rgbMat, denoised, 10f, 10f, 7, 21)

        // Conversion en bitmap
        val resultBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config!!)
        Utils.matToBitmap(denoised, resultBitmap)

        // Libération des ressources
        mat.release()
        rgbMat.release()
        denoised.release()

        return resultBitmap
    }

    private fun correctPerspective(bitmap: Bitmap): Bitmap {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)

        // Conversion en niveaux de gris
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGB2GRAY)

        // Détection des bords
        val edges = Mat()
        Imgproc.Canny(gray, edges, 75.0, 200.0)

        // Dilatation pour renforcer les bords
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(edges, edges, kernel)

        // Trouver les contours
        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(edges, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        // Trier les contours par aire (du plus grand au plus petit)
        contours.sortByDescending { Imgproc.contourArea(it) }

        // Aucun contour trouvé
        if (contours.isEmpty()) {
            gray.release()
            edges.release()
            return bitmap
        }

        // Approximer le contour pour trouver le rectangle du document
        val largest = contours[0]
        val peri = Imgproc.arcLength(MatOfPoint2f(*largest.toArray()), true)
        val approx = MatOfPoint2f()
        Imgproc.approxPolyDP(MatOfPoint2f(*largest.toArray()), approx, 0.02 * peri, true)

        // Si nous n'avons pas un quadrilatère, retourner l'image originale
        if (approx.rows() != 4) {
            gray.release()
            edges.release()
            return bitmap
        }

        // Trouver les coordonnées des coins
        val points = approx.toList()

        // Tri des points pour obtenir [haut-gauche, haut-droite, bas-droite, bas-gauche]
        val sortedPoints = points.sortedWith(compareBy { it.y })
        val topPoints = sortedPoints.take(2).sortedBy { it.x }
        val bottomPoints = sortedPoints.takeLast(2).sortedByDescending { it.x }

        val topLeft = topPoints[0]
        val topRight = topPoints[1]
        val bottomRight = bottomPoints[0]
        val bottomLeft = bottomPoints[1]

        // Calculer la largeur et la hauteur maximales
        val width = maxOf(
            Math.abs(bottomRight.x - bottomLeft.x),
            Math.abs(topRight.x - topLeft.x)
        ).toInt()

        val height = maxOf(
            Math.abs(topRight.y - bottomRight.y),
            Math.abs(topLeft.y - bottomLeft.y)
        ).toInt()

        // Définir les points source et destination
        val src = MatOfPoint2f(
            topLeft,
            topRight,
            bottomRight,
            bottomLeft
        )

        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(width.toDouble(), 0.0),
            Point(width.toDouble(), height.toDouble()),
            Point(0.0, height.toDouble())
        )

        // Calculer et appliquer la transformation de perspective
        val matrix = Imgproc.getPerspectiveTransform(src, dst)
        val warped = Mat()
        Imgproc.warpPerspective(mat, warped, matrix, Size(width.toDouble(), height.toDouble()))

        // Convertir en bitmap
        val resultBitmap = Bitmap.createBitmap(width, height, bitmap.config!!)
        Utils.matToBitmap(warped, resultBitmap)

        // Libération des ressources
        gray.release()
        edges.release()
        warped.release()
        matrix.release()

        return resultBitmap
    }


    private suspend fun analyzeDocument(bitmap: Bitmap): AnalyzedDocument {
        try {
            Log.d(TAG, "Début de l'analyse du document: ${bitmap.width} x ${bitmap.height}")

            // Préparation pour OpenCV
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)

            // Prétraitement pour améliorer la détection
            val processed = Mat()
            if (mat.channels() == 4) {
                Imgproc.cvtColor(mat, processed, Imgproc.COLOR_RGBA2RGB)
            } else if (mat.channels() == 3) {
                mat.copyTo(processed)
            } else {
                Imgproc.cvtColor(mat, processed, Imgproc.COLOR_GRAY2RGB)
            }

            // 1. Détecter les zones de texte inversé (texte blanc sur fond coloré)
            val invertedTextRegions = detectInvertedTextRegions(processed)
            Log.d(TAG, "Zones de texte inversé détectées: ${invertedTextRegions.size}")

            // Copie du bitmap pour les modifications
            val workingBitmap = bitmap.copy(bitmap.config!!, true)
            val canvas = android.graphics.Canvas(workingBitmap)
            val paint = android.graphics.Paint()

            // Pour chaque région inversée, inverser les couleurs pour faciliter l'OCR
            for (region in invertedTextRegions) {
                // Créer un sous-mat pour cette région
                val roi = Mat(processed, org.opencv.core.Rect(region.x, region.y, region.width, region.height))

                // Inverser les couleurs
                Core.bitwise_not(roi, roi)

                // Convertir cette région en bitmap
                val regionBitmap = Bitmap.createBitmap(region.width, region.height, Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(roi, regionBitmap)

                // Dessiner sur le bitmap de travail
                canvas.drawBitmap(regionBitmap, region.x.toFloat(), region.y.toFloat(), paint)

                // Libération des ressources
                roi.release()
                regionBitmap.recycle()
            }

            // 2. Utiliser ML Kit pour la détection des blocs de texte sur l'image entière
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val image = InputImage.fromBitmap(workingBitmap, 0)
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

            // Ajouter les régions inversées comme zones de texte si elles n'ont pas été détectées par ML Kit
            for (region in invertedTextRegions) {
                var isOverlapping = false

                // Vérifier si cette région est déjà couverte par une zone de texte
                for (textZone in textZones) {
                    if (getIntersectionArea(region, textZone) / (region.width * region.height).toDouble() > 0.7) {
                        isOverlapping = true
                        break
                    }
                }

                if (!isOverlapping) {
                    // Préparer une image rognée pour l'OCR spécifique de cette région
                    val croppedBitmap = Bitmap.createBitmap(workingBitmap, region.x, region.y, region.width, region.height)
                    val regionImage = InputImage.fromBitmap(croppedBitmap, 0)

                    // OCR de la région
                    val regionText = recognizer.process(regionImage).await().text

                    if (regionText.isNotEmpty()) {
                        textZones.add(region)
                        texts.add(regionText)
                        Log.d(TAG, "Texte dans région inversée: '${regionText.take(20)}...'")
                    }

                    croppedBitmap.recycle()
                }
            }

            // Regrouper les blocs de texte qui forment probablement le même paragraphe
            val mergedTextZones = mutableListOf<SimpleRect>()
            val mergedTexts = mutableListOf<String>()

            // Préparer les groupes de blocs de texte
            val visited = BooleanArray(textZones.size) { false }
            val paragraphGroups = mutableListOf<MutableList<Int>>()

            // Identifier les paragraphes potentiels
            for (i in textZones.indices) {
                if (visited[i]) continue

                val currentGroup = mutableListOf<Int>()
                currentGroup.add(i)
                visited[i] = true

                // Chercher des blocs qui peuvent appartenir au même paragraphe
                for (j in textZones.indices) {
                    if (visited[j] || i == j) continue

                    // Si les blocs sont proches horizontalement et ont un alignement vertical similaire
                    if (areBlocksInSameParagraph(textZones[i], textZones[j])) {
                        currentGroup.add(j)
                        visited[j] = true
                    }
                }

                paragraphGroups.add(currentGroup)
            }

            // Fusionner les blocs de chaque groupe
            for (group in paragraphGroups) {
                if (group.size == 1) {
                    // Bloc unique, pas de fusion nécessaire
                    mergedTextZones.add(textZones[group[0]])
                    mergedTexts.add(texts[group[0]])
                } else {
                    // Trier les blocs de gauche à droite puis de haut en bas
                    val sortedIndices = group.sortedWith(compareBy(
                        { textZones[it].y },
                        { textZones[it].x }
                    ))

                    // Fusionner les zones
                    var minX = Int.MAX_VALUE
                    var minY = Int.MAX_VALUE
                    var maxX = 0
                    var maxY = 0
                    val mergedText = StringBuilder()

                    for (idx in sortedIndices) {
                        val zone = textZones[idx]
                        minX = minOf(minX, zone.x)
                        minY = minOf(minY, zone.y)
                        maxX = maxOf(maxX, zone.x + zone.width)
                        maxY = maxOf(maxY, zone.y + zone.height)

                        if (mergedText.isNotEmpty()) {
                            mergedText.append(" ")
                        }
                        mergedText.append(texts[idx])
                    }

                    // Créer une zone fusionnée
                    mergedTextZones.add(SimpleRect(
                        minX,
                        minY,
                        maxX - minX,
                        maxY - minY
                    ))
                    mergedTexts.add(mergedText.toString())
                }
            }

            // Remplacer les listes originales par les listes fusionnées
            textZones.clear()
            textZones.addAll(mergedTextZones)
            texts.clear()
            texts.addAll(mergedTexts)

            Log.d(TAG, "Après fusion: ${textZones.size} paragraphes")

            // 3. Utiliser OpenCV pour la détection d'images
            // Prétraitement pour la détection d'images
            val hsv = Mat()
            Imgproc.cvtColor(processed, hsv, Imgproc.COLOR_RGB2HSV)

            // Extraction du canal de saturation (permet de distinguer les zones colorées)
            val channels = ArrayList<Mat>()
            Core.split(hsv, channels)
            val saturation = channels[1]

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

            // 4. Si aucune zone d'image n'est détectée, essayer une approche alternative
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
            colorMask.release()
            workingBitmap.recycle()

            Log.d(TAG, "Analyse terminée: ${textZones.size} zones de texte, ${imageZones.size} zones d'image")

            val elements = mutableListOf<DocumentElement>()

            // Ajouter les zones de texte
            for (i in textZones.indices) {
                elements.add(DocumentElement.TextElement(
                    rect = textZones[i],
                    text = texts.getOrElse(i) { "" },
                    isInverted = invertedTextRegions.any { getIntersectionArea(it, textZones[i]) > 0.7 * it.width * it.height }
                ))
            }

            // Ajouter les zones d'image
            for (imageZone in imageZones) {
                // Créer un bitmap pour cette zone
                val imageBitmap = Bitmap.createBitmap(bitmap, imageZone.x, imageZone.y, imageZone.width, imageZone.height)

                elements.add(DocumentElement.ImageElement(
                    rect = imageZone,
                    bitmap = imageBitmap
                ))
            }

            return AnalyzedDocument(elements, bitmap)

        } catch (e: Exception) {
            Log.e(TAG, "Erreur pendant l'analyse du document", e)
            throw e
        }
    }

    private fun areBlocksInSameParagraph(block1: SimpleRect, block2: SimpleRect): Boolean {
        // Facteurs de seuil à ajuster selon vos besoins
        val verticalOverlapThreshold = 0.3 // Chevauchement vertical minimum
        val horizontalGapThreshold = 50    // Espacement horizontal maximum
        val heightRatioThreshold = 1.5     // Ratio maximum de hauteur acceptable

        // Vérifier si les hauteurs sont similaires
        val heightRatio = maxOf(block1.height.toFloat() / block2.height,
            block2.height.toFloat() / block1.height)
        if (heightRatio > heightRatioThreshold) return false

        // Calculer le chevauchement vertical
        val verticalOverlap = maxOf(0,
            minOf(block1.y + block1.height, block2.y + block2.height) -
                    maxOf(block1.y, block2.y)
        )

        val minHeight = minOf(block1.height, block2.height)
        val overlapRatio = verticalOverlap.toFloat() / minHeight

        // Si le chevauchement vertical est suffisant
        if (overlapRatio >= verticalOverlapThreshold) {
            // Vérifier l'espacement horizontal
            val block1Right = block1.x + block1.width
            val block2Left = block2.x
            val block2Right = block2.x + block2.width
            val block1Left = block1.x

            // Si block1 est à gauche de block2
            if (block1Right < block2Left) {
                return block2Left - block1Right <= horizontalGapThreshold
            }
            // Si block2 est à gauche de block1
            else if (block2Right < block1Left) {
                return block1Left - block2Right <= horizontalGapThreshold
            }
            // Les blocs se chevauchent horizontalement
            else {
                return true
            }
        }

        return false
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

    private fun enhanceContrast(bitmap: Bitmap): Bitmap {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)

        // Convertir en Lab pour meilleur contraste
        val labMat = Mat()
        Imgproc.cvtColor(mat, labMat, Imgproc.COLOR_BGR2Lab)

        // Séparation des canaux
        val channels = ArrayList<Mat>()
        Core.split(labMat, channels)

        // CLAHE (Contrast Limited Adaptive Histogram Equalization) sur le canal L
        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        clahe.apply(channels[0], channels[0])

        // Fusion des canaux
        Core.merge(channels, labMat)
        Imgproc.cvtColor(labMat, mat, Imgproc.COLOR_Lab2BGR)

        // Conversion en bitmap
        val resultBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config!!)
        Utils.matToBitmap(mat, resultBitmap)

        // Libération des ressources
        mat.release()
        labMat.release()
        for (channel in channels) channel.release()

        return resultBitmap
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

    fun saveBitmapToCache(context: Context, bitmap: Bitmap, fileName: String): String {
        val file = File(context.cacheDir, "$fileName.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        return file.absolutePath
    }


    private fun goToEditActivity(data: AnalyzedDocument) {
        val newElements = data.elements.mapIndexed { index, element ->
            when (element) {
                is DocumentElement.ImageElement -> {
                    val path = element.bitmap?.let {
                        saveBitmapToCache(this, it, "element_$index")
                    }
                    element.copy(bitmap = null, imageUri = path)
                }
                else -> element
            }
        }
        Log.d(TAG, "goToEditActivity: $newElements", )

        val bitmapPath = data.originalBitmap?.let {
            saveBitmapToCache(this, it, "original_bitmap")
        }

        Log.d(TAG, "goToEditActivity: $bitmapPath", )

        val reducedAnalyzedDocument = data.copy(
            elements = newElements,
            originalBitmap = null
        )

        val intent = Intent(this, EditActivity::class.java)
        intent.putExtra("analyzedDocument", reducedAnalyzedDocument)
        intent.putExtra("bitmapPath", bitmapPath)
        startActivity(intent)
        finish()
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