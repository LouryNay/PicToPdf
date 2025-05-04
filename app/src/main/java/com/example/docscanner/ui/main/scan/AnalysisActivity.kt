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
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import com.example.docscanner.R
import com.example.docscanner.ui.main.await
import com.example.docscanner.ui.main.edit.EditActivity
import com.example.docscanner.ui.theme.DocScannerTheme
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
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
                    val preprocessedBitmap = prepareForDocumentProcessing(denoisedBitmap)
                    val textBitmap = scaleBitmapIfNeeded(preprocessedBitmap)
                    val imageBitmap = scaleBitmapIfNeeded(enhancedBitmap)
                    val result = analyzeDocument(textBitmap, imageBitmap)

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

    private fun scaleBitmapIfNeeded(
        bitmap: Bitmap,
        maxDimension: Int = MAX_IMAGE_DIMENSION
    ): Bitmap {
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

        // Utiliser un seuil de saturation plutôt que de cibler une couleur spécifique
        val saturationMask = Mat()
        Core.extractChannel(hsv, saturationMask, 1)  // Canal de saturation

        // Créer un masque pour les zones à saturation élevée
        Imgproc.threshold(saturationMask, saturationMask, 80.0, 255.0, Imgproc.THRESH_BINARY)

        // Opérations morphologiques pour nettoyer le masque
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        Imgproc.morphologyEx(saturationMask, saturationMask, Imgproc.MORPH_CLOSE, kernel)

        // Trouver les contours
        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(
            saturationMask,
            contours,
            Mat(),
            Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_SIMPLE
        )

        val invertedTextRegions = mutableListOf<SimpleRect>()

        for (contour in contours) {
            val rect = Imgproc.boundingRect(contour)
            val area = rect.width * rect.height

            // Filtrer par taille
            if (area > mat.width() * mat.height() * 0.005 && area < mat.width() * mat.height() * 0.2) {
                // Vérifier la luminosité moyenne dans la zone (pour confirmer qu'il s'agit d'une zone colorée)
                val roi = Mat(mat, rect)
                val mean = Core.mean(roi)

                // Si la luminosité moyenne est assez élevée (fond coloré probable)
                if (mean.`val`[0] + mean.`val`[1] + mean.`val`[2] > 150 * 3) {
                    invertedTextRegions.add(SimpleRect.from(rect))
                }

                roi.release()
            }
        }

        hsv.release()
        saturationMask.release()

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

        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGB2GRAY)

        // Blurring + Canny
        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)

        val edges = Mat()
        Imgproc.Canny(blurred, edges, 75.0, 200.0)

        // Trouver les contours
        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(
            edges,
            contours,
            Mat(),
            Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_SIMPLE
        )

        // Filtrer les plus gros
        val screenContours = contours
            .filter { Imgproc.contourArea(it) > bitmap.width * bitmap.height * 0.1 }
            .sortedByDescending { Imgproc.contourArea(it) }

        if (screenContours.isEmpty()) return bitmap

        var bestQuad: MatOfPoint2f? = null
        for (contour in screenContours.take(3)) {
            val peri = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(MatOfPoint2f(*contour.toArray()), approx, 0.02 * peri, true)

            if (approx.total() == 4L && Imgproc.isContourConvex(MatOfPoint(*approx.toArray()))) {
                bestQuad = approx
                break
            }
        }

        if (bestQuad == null) return bitmap

        // Trier les points
        val orderedPoints = sortPoints(bestQuad.toList())
        val (tl, tr, br, bl) = orderedPoints

        val widthA = distance(br, bl)
        val widthB = distance(tr, tl)
        val maxWidth = maxOf(widthA, widthB).toInt()

        val heightA = distance(tr, br)
        val heightB = distance(tl, bl)
        val maxHeight = maxOf(heightA, heightB).toInt()

        val src = MatOfPoint2f(tl, tr, br, bl)
        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(maxWidth.toDouble(), 0.0),
            Point(maxWidth.toDouble(), maxHeight.toDouble()),
            Point(0.0, maxHeight.toDouble())
        )

        val transform = Imgproc.getPerspectiveTransform(src, dst)
        val warped = Mat()
        Imgproc.warpPerspective(
            mat,
            warped,
            transform,
            Size(maxWidth.toDouble(), maxHeight.toDouble())
        )

        val result = Bitmap.createBitmap(maxWidth, maxHeight, bitmap.config!!)
        Utils.matToBitmap(warped, result)

        // Libération
        listOf(mat, gray, blurred, edges, warped).forEach { it.release() }

        return result
    }

    private fun distance(p1: Point, p2: Point): Double {
        return kotlin.math.hypot(p2.x - p1.x, p2.y - p1.y)
    }


    private fun sortPoints(points: List<Point>): List<Point> {
        val sorted = points.sortedWith(compareBy({ it.y }, { it.x }))
        val (top1, top2) = sorted.take(2).sortedBy { it.x }
        val (bottom1, bottom2) = sorted.takeLast(2).sortedBy { it.x }

        return listOf(top1, top2, bottom2, bottom1) // topLeft, topRight, bottomRight, bottomLeft
    }


    private suspend fun analyzeDocument(textBitmap: Bitmap, imageBitmap: Bitmap): AnalyzedDocument {
        try {
            // Préparation pour OpenCV
            val matText = Mat()
            Utils.bitmapToMat(textBitmap, matText)
            val matImage = Mat()
            Utils.bitmapToMat(imageBitmap, matImage)

            // Prétraitement pour améliorer la détection
            val processedText = Mat()
            if (matText.channels() == 4) {
                Imgproc.cvtColor(matText, processedText, Imgproc.COLOR_RGBA2RGB)
            } else if (matText.channels() == 3) {
                matText.copyTo(processedText)
            } else {
                Imgproc.cvtColor(matText, processedText, Imgproc.COLOR_GRAY2RGB)
            }

            val processedImage = Mat()
            if (matImage.channels() == 4) {
                Imgproc.cvtColor(matImage, processedImage, Imgproc.COLOR_RGBA2RGB)
            } else if (matImage.channels() == 3) {
                matImage.copyTo(processedImage)
            } else {
                Imgproc.cvtColor(matImage, processedImage, Imgproc.COLOR_GRAY2RGB)
            }

            // 1. Détecter les zones de texte inversé (texte blanc sur fond coloré) sur l'image de texte
            val invertedTextRegions = detectInvertedTextRegions(processedText)
            Log.d(TAG, "Zones de texte inversé détectées: ${invertedTextRegions.size}")

            // Copie du bitmap texte pour les modifications
            val workingBitmap = textBitmap.copy(textBitmap.config!!, true)
            val canvas = android.graphics.Canvas(workingBitmap)
            val paint = android.graphics.Paint()

            // Pour chaque région inversée, inverser les couleurs pour faciliter l'OCR
            for (region in invertedTextRegions) {
                // Créer un sous-mat pour cette région
                val roi = Mat(
                    processedText,
                    org.opencv.core.Rect(region.x, region.y, region.width, region.height)
                )

                // Inverser les couleurs
                Core.bitwise_not(roi, roi)

                // Convertir cette région en bitmap
                val regionBitmap =
                    Bitmap.createBitmap(region.width, region.height, Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(roi, regionBitmap)

                // Dessiner sur le bitmap de travail
                canvas.drawBitmap(regionBitmap, region.x.toFloat(), region.y.toFloat(), paint)

                // Libération des ressources
                roi.release()
                regionBitmap.recycle()
            }

            // 2. Utiliser ML Kit pour la détection des blocs de texte sur l'image de texte
            val recognizer = TextRecognition.getClient(
                TextRecognizerOptions.Builder()
                    .setExecutor(Dispatchers.Default.asExecutor())
                    .build()
            )
            val image = InputImage.fromBitmap(workingBitmap, 0)
            val result = recognizer.process(image).await()

            val textZones = mutableListOf<SimpleRect>()
            val texts = mutableListOf<String>()

            // Extraire les blocs de texte détectés par ML Kit
            for (block in result.textBlocks) {
                val boundingBox = block.boundingBox
                if (boundingBox != null) {
                    textZones.add(
                        SimpleRect(
                            boundingBox.left,
                            boundingBox.top,
                            boundingBox.width(),
                            boundingBox.height()
                        )
                    )
                    texts.add(block.text)
                    Log.d(
                        TAG,
                        "Bloc de texte détecté: ${boundingBox.left},${boundingBox.top} - '${
                            block.text.take(20)
                        }...'"
                    )
                }
            }

            // Ajouter les régions inversées comme zones de texte si elles n'ont pas été détectées par ML Kit
            for (region in invertedTextRegions) {
                var isOverlapping = false

                // Vérifier si cette région est déjà couverte par une zone de texte
                for (textZone in textZones) {
                    if (getIntersectionArea(
                            region,
                            textZone
                        ) / (region.width * region.height).toDouble() > 0.7
                    ) {
                        isOverlapping = true
                        break
                    }
                }

                if (!isOverlapping) {
                    // Préparer une image rognée pour l'OCR spécifique de cette région
                    val croppedBitmap = Bitmap.createBitmap(
                        workingBitmap,
                        region.x,
                        region.y,
                        region.width,
                        region.height
                    )
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
                    val sortedIndices = group.sortedWith(compareBy(
                        // D'abord par ligne (regrouper les blocs qui sont approximativement sur la même ligne)
                        { (textZones[it].y / (textZones[it].height * 0.8)).toInt() },
                        // Ensuite par position horizontale dans chaque ligne
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
                    mergedTextZones.add(
                        SimpleRect(
                            minX,
                            minY,
                            maxX - minX,
                            maxY - minY
                        )
                    )
                    mergedTexts.add(mergedText.toString())
                }
            }

// Organisation des blocs par positions relatives
            val organizedZones = mutableListOf<SimpleRect>()
            val organizedTexts = mutableListOf<String>()

// Grouper les blocs par lignes (blocs avec des positions Y similaires)
            val lineGroups = mutableListOf<MutableList<Int>>()
            val lineThreshold = textBitmap.height * 0.02  // 2% de la hauteur de l'image

            for (i in mergedTextZones.indices) {  // Utiliser mergedTextZones au lieu de textZones
                var foundGroup = false

                // Trouver une ligne existante où ce bloc pourrait s'intégrer
                for (group in lineGroups) {
                    val firstBlockInGroup = mergedTextZones[group[0]]
                    // Si ce bloc est à peu près à la même hauteur que les blocs de ce groupe
                    if (Math.abs(mergedTextZones[i].y - firstBlockInGroup.y) < lineThreshold) {
                        group.add(i)
                        foundGroup = true
                        break
                    }
                }

                // Si aucun groupe existant ne convient, créer un nouveau groupe
                if (!foundGroup) {
                    lineGroups.add(mutableListOf(i))
                }
            }

// Trier les groupes par position Y (de haut en bas)
            lineGroups.sortBy { group -> mergedTextZones[group[0]].y }

// Pour chaque ligne, trier les blocs de gauche à droite
            for (group in lineGroups) {
                group.sortBy { mergedTextZones[it].x }

                // Ajouter les blocs triés à nos listes organisées
                for (idx in group) {
                    organizedZones.add(mergedTextZones[idx])
                    organizedTexts.add(mergedTexts[idx])
                }
            }

// Remplacer les listes originales par les listes organisées
            val (finalTextZones, finalTexts) = mergeTextBlocks(textZones, texts)

            Log.d(TAG, "Après organisation et fusion: ${finalTextZones.size} paragraphes")

            // 3. Utiliser OpenCV pour la détection d'images (sur imageBitmap, pas textBitmap)
            // Prétraitement pour la détection d'images
            val hsv = Mat()
            Imgproc.cvtColor(processedImage, hsv, Imgproc.COLOR_RGB2HSV)

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
            Imgproc.findContours(
                colorMask,
                contours,
                Mat(),
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE
            )

            val imageZones = mutableListOf<SimpleRect>()

            // Filtrer les contours pour ne conserver que les grandes zones
            for (contour in contours) {
                val rect = Imgproc.boundingRect(contour)
                val area = rect.width * rect.height

                // Ignorer les contours trop petits ou trop grands
                val minArea =
                    imageBitmap.width * imageBitmap.height * 0.02  // Au moins 2% de l'image
                val maxArea =
                    imageBitmap.width * imageBitmap.height * 0.7   // Pas plus de 70% de l'image

                if (area < minArea || area > maxArea) continue

                val margin = 2  // Marge réduite
                if ((rect.x <= margin && rect.width < imageBitmap.width * 0.3) ||
                    (rect.y <= margin && rect.height < imageBitmap.height * 0.3) ||
                    (rect.x + rect.width >= imageBitmap.width - margin && rect.width < imageBitmap.width * 0.3) ||
                    (rect.y + rect.height >= imageBitmap.height - margin && rect.height < imageBitmap.height * 0.3)
                ) {
                    // Ne pas filtrer si l'élément couvre une grande partie de la bordure
                    // car il pourrait s'agir d'un élément important
                    if (rect.width < imageBitmap.width * 0.15 || rect.height < imageBitmap.height * 0.15) {
                        continue
                    }
                }

                val imageRect = SimpleRect.from(rect)

                // Vérifier la variance des couleurs dans cette région
                val roi = Mat(
                    processedImage,
                    org.opencv.core.Rect(rect.x, rect.y, rect.width, rect.height)
                )
                val stdDev = MatOfDouble()
                val mean = MatOfDouble()
                Core.meanStdDev(roi, mean, stdDev)

                // Une image a généralement plus de variance de couleur qu'une zone unie
                val hasEnoughVariance = stdDev.get(0, 0)[0] > 20.0 ||
                        stdDev.get(1, 0)[0] > 20.0 ||
                        stdDev.get(2, 0)[0] > 20.0

                if (hasEnoughVariance && !isOverlappingWithTextZones(
                        imageRect,
                        finalTextZones,
                        0.5
                    )
                ) {
                    imageZones.add(imageRect)
                    Log.d(
                        TAG,
                        "Zone d'image détectée: ${rect.x},${rect.y},${rect.width},${rect.height}"
                    )
                }

                roi.release()
            }

            // 4. Si aucune zone d'image n'est détectée, essayer une approche alternative
            if (imageZones.isEmpty()) {
                Log.d(TAG, "Aucune zone d'image détectée, utilisation de la méthode alternative")

                // Convertir en niveaux de gris
                val gray = Mat()
                Imgproc.cvtColor(processedImage, gray, Imgproc.COLOR_RGB2GRAY)

                // Détection des bords (Canny)
                val edges = Mat()
                Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
                Imgproc.Canny(gray, edges, 50.0, 150.0)

                // Dilatation pour connecter les bords
                Imgproc.dilate(edges, edges, kernel)

                // Détection des contours des objets
                contours.clear()
                Imgproc.findContours(
                    edges,
                    contours,
                    Mat(),
                    Imgproc.RETR_EXTERNAL,
                    Imgproc.CHAIN_APPROX_SIMPLE
                )

                for (contour in contours) {
                    val rect = Imgproc.boundingRect(contour)
                    val area = rect.width * rect.height

                    // Filtrer par taille et vérifie que ce n'est pas déjà un bloc de texte
                    if (area > imageBitmap.width * imageBitmap.height * 0.05) { // Au moins 5% de l'image
                        val imageRect = SimpleRect.from(rect)

                        if (!isOverlappingWithTextZones(imageRect, finalTextZones, 0.7)) {
                            imageZones.add(imageRect)
                            Log.d(
                                TAG,
                                "Zone d'image alternative: ${rect.x},${rect.y},${rect.width},${rect.height}"
                            )
                        }
                    }
                }

                // Libérer les ressources
                gray.release()
                edges.release()
            }

            // Libérer les ressources
            matText.release()
            matImage.release()
            processedText.release()
            processedImage.release()
            hsv.release()
            for (ch in channels) ch.release()
            colorMask.release()
            workingBitmap.recycle()

            Log.d(
                TAG,
                "Analyse terminée: ${finalTextZones.size} zones de texte, ${imageZones.size} zones d'image"
            )

            val elements = mutableListOf<DocumentElement>()

            // Ajouter les zones de texte
            for (i in finalTextZones.indices) {
                elements.add(DocumentElement.TextElement(
                    rect = finalTextZones[i],
                    text = finalTexts.getOrElse(i) { "" },
                    isInverted = invertedTextRegions.any {
                        getIntersectionArea(
                            it,
                            finalTextZones[i]
                        ) > 0.7 * it.width * it.height
                    }
                ))
            }

            // Ajouter les zones d'image
            for (imageZone in imageZones) {
                // Créer un bitmap pour cette zone à partir de l'imageBitmap (image couleur)
                val extractedImageBitmap = Bitmap.createBitmap(
                    imageBitmap,
                    imageZone.x,
                    imageZone.y,
                    imageZone.width,
                    imageZone.height
                )

                elements.add(
                    DocumentElement.ImageElement(
                        rect = imageZone,
                        bitmap = extractedImageBitmap
                    )
                )
            }

            return AnalyzedDocument(elements, imageBitmap)  // Utiliser imageBitmap comme référence

        } catch (e: Exception) {
            Log.e(TAG, "Erreur pendant l'analyse du document", e)
            throw e
        }
    }


    // Amélioration de la fonction pour détecter si des blocs appartiennent au même paragraphe
    private fun areBlocksInSameParagraph(block1: SimpleRect, block2: SimpleRect): Boolean {
        // Paramètres ajustés pour une meilleure détection
        val verticalOverlapThreshold =
            0.3  // Réduction pour permettre plus de flexibilité pour les lignes
        val horizontalGapThreshold = 50     // Augmenté pour mieux gérer les espaces entre mots
        val heightRatioThreshold =
            1.5      // Plus flexible pour accommoder différentes tailles de police
        val lineHeightFactor = 1.2          // Facteur pour estimer la hauteur d'une ligne

        // Vérifier si les hauteurs sont similaires (nécessaire pour les vrais paragraphes)
        val heightRatio = maxOf(
            block1.height.toFloat() / block2.height,
            block2.height.toFloat() / block1.height
        )
        if (heightRatio > heightRatioThreshold) return false

        // Calculer le chevauchement vertical
        val verticalOverlap = maxOf(
            0,
            minOf(block1.y + block1.height, block2.y + block2.height) -
                    maxOf(block1.y, block2.y)
        )

        val minHeight = minOf(block1.height, block2.height)
        val overlapRatio = verticalOverlap.toFloat() / minHeight

        // Vérifier si les blocs sont potentiellement sur la même ligne
        val isOnSameLine = overlapRatio >= verticalOverlapThreshold ||
                Math.abs(block1.y - block2.y) <= minHeight * 0.3

        // Si les blocs sont sur la même ligne, vérifier l'espacement horizontal
        if (isOnSameLine) {
            val block1Right = block1.x + block1.width
            val block2Left = block2.x
            val block2Right = block2.x + block2.width
            val block1Left = block1.x

            // Si block1 est à gauche de block2 (ce qui est le cas le plus courant pour des mots consécutifs)
            if (block1Right < block2Left) {
                return block2Left - block1Right <= horizontalGapThreshold
            }
            // Si block2 est à gauche de block1
            else if (block2Right < block1Left) {
                return block1Left - block2Right <= horizontalGapThreshold
            }
            // Les blocs se chevauchent horizontalement (probablement une erreur de détection)
            else {
                return true
            }
        }

        // Vérifier si les blocs sont consécutifs verticalement (retour à la ligne)
        val isConsecutiveVertically =
            (block2.y >= block1.y + block1.height && block2.y <= block1.y + block1.height * lineHeightFactor) ||
                    (block1.y >= block2.y + block2.height && block1.y <= block2.y + block2.height * lineHeightFactor)

        // Si un bloc est directement en dessous de l'autre avec un alignement horizontal correct
        if (isConsecutiveVertically) {
            // Vérifier l'alignement horizontal (important pour les paragraphes)
            val horizontalOverlap = maxOf(
                0,
                minOf(block1.x + block1.width, block2.x + block2.width) -
                        maxOf(block1.x, block2.x)
            )

            val minWidth = minOf(block1.width, block2.width)
            val horizontalOverlapRatio = horizontalOverlap.toFloat() / minWidth

            // Si les blocs sont suffisamment alignés horizontalement
            return horizontalOverlapRatio >= 0.3
        }

        return false
    }

    // Amélioration de l'organisation des blocs de texte pour respecter l'ordre de lecture
    private fun organizeTextBlocks(
        textZones: List<SimpleRect>,
        texts: List<String>
    ): Pair<List<SimpleRect>, List<String>> {
        val organizedZones = mutableListOf<SimpleRect>()
        val organizedTexts = mutableListOf<String>()

        // Si aucun bloc, retourner des listes vides
        if (textZones.isEmpty()) return Pair(organizedZones, organizedTexts)

        // Identification des paragraphes
        val paragraphs = mutableListOf<MutableList<Int>>()
        val visited = BooleanArray(textZones.size) { false }

        // Première étape : identifier les paragraphes et les lignes
        for (i in textZones.indices) {
            if (visited[i]) continue

            val paragraph = mutableListOf<Int>()
            val blocksToProcess = mutableListOf(i)
            visited[i] = true

            // Parcourir tous les blocs connectés pour former un paragraphe complet
            while (blocksToProcess.isNotEmpty()) {
                val currentIndex = blocksToProcess.removeAt(0)
                paragraph.add(currentIndex)

                // Chercher les blocs connectés à celui-ci
                for (j in textZones.indices) {
                    if (!visited[j] && areBlocksInSameParagraph(
                            textZones[currentIndex],
                            textZones[j]
                        )
                    ) {
                        blocksToProcess.add(j)
                        visited[j] = true
                    }
                }
            }

            paragraphs.add(paragraph)
        }

        // Deuxième étape : organiser les paragraphes par position verticale
        paragraphs.sortBy { paragraph ->
            // Utiliser la position Y moyenne pour tenir compte des paragraphes inclinés
            paragraph.sumOf { textZones[it].y } / paragraph.size
        }

        // Troisième étape : pour chaque paragraphe, organiser les blocs selon l'ordre de lecture
        for (paragraph in paragraphs) {
            // Organiser les blocs par lignes
            val lines = mutableListOf<MutableList<Int>>()
            val lineThreshold = paragraph.map { textZones[it].height }.average() * 0.7

            for (blockIndex in paragraph) {
                var foundLine = false
                for (line in lines) {
                    val firstInLine = textZones[line[0]]
                    if (Math.abs(textZones[blockIndex].y - firstInLine.y) < lineThreshold) {
                        line.add(blockIndex)
                        foundLine = true
                        break
                    }
                }

                if (!foundLine) {
                    lines.add(mutableListOf(blockIndex))
                }
            }

            // Trier les lignes par position Y
            lines.sortBy { line -> textZones[line[0]].y }

            // Pour chaque ligne, trier les blocs de gauche à droite
            for (line in lines) {
                line.sortBy { textZones[it].x }

                // Ajouter les blocs triés à nos listes organisées
                for (idx in line) {
                    organizedZones.add(textZones[idx])
                    organizedTexts.add(texts[idx])
                }
            }
        }

        return Pair(organizedZones, organizedTexts)
    }

    // Fonction pour fusionner les blocs qui forment un même paragraphe
    private fun mergeTextBlocks(
        textZones: List<SimpleRect>,
        texts: List<String>
    ): Pair<List<SimpleRect>, List<String>> {
        // Organiser d'abord les blocs selon l'ordre de lecture
        val (organizedZones, organizedTexts) = organizeTextBlocks(textZones, texts)

        val mergedZones = mutableListOf<SimpleRect>()
        val mergedTexts = mutableListOf<String>()

        if (organizedZones.isEmpty()) return Pair(mergedZones, mergedTexts)

        var currentZone = organizedZones[0]
        var currentText = organizedTexts[0]

        for (i in 1 until organizedZones.size) {
            val nextZone = organizedZones[i]
            val nextText = organizedTexts[i]

            // Vérifier si le bloc suivant fait partie du même paragraphe
            if (areBlocksInSameParagraph(currentZone, nextZone)) {
                // Fusionner les zones
                val minX = minOf(currentZone.x, nextZone.x)
                val minY = minOf(currentZone.y, nextZone.y)
                val maxX = maxOf(currentZone.x + currentZone.width, nextZone.x + nextZone.width)
                val maxY = maxOf(currentZone.y + currentZone.height, nextZone.y + nextZone.height)

                currentZone = SimpleRect(minX, minY, maxX - minX, maxY - minY)

                // Ajouter un espace ou un saut de ligne selon le cas
                val isLineBreak = nextZone.y > currentZone.y + currentZone.height * 0.5
                currentText += if (isLineBreak) "\n" + nextText else " " + nextText
            } else {
                // Ajouter le paragraphe courant et passer au suivant
                mergedZones.add(currentZone)
                mergedTexts.add(currentText)
                currentZone = nextZone
                currentText = nextText
            }
        }

        // Ajouter le dernier paragraphe
        mergedZones.add(currentZone)
        mergedTexts.add(currentText)

        return Pair(mergedZones, mergedTexts)
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


    private fun prepareForDocumentProcessing(bitmap: Bitmap): Bitmap {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)

        // Convertir en niveaux de gris
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGB2GRAY)

        // Égalisation globale de l’histogramme (pas CLAHE)
        val equalized = Mat()
        Imgproc.equalizeHist(gray, equalized)

        // Lissage léger pour éviter le bruit
        val blurred = Mat()
        Imgproc.GaussianBlur(equalized, blurred, Size(3.0, 3.0), 0.0)

        // Reconvertir en bitmap
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Imgproc.cvtColor(blurred, blurred, Imgproc.COLOR_GRAY2RGBA) // conversion pour compatibilité
        Utils.matToBitmap(blurred, result)

        // Libération mémoire
        mat.release()
        gray.release()
        equalized.release()
        blurred.release()

        return result
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
        Log.d(TAG, "goToEditActivity: $newElements")

        val bitmapPath = data.originalBitmap?.let {
            saveBitmapToCache(this, it, "original_bitmap")
        }

        Log.d(TAG, "goToEditActivity: $bitmapPath")

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
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.res.colorResource(id = R.color.light_grey)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Animation vectorielle
            val context = LocalContext.current
            val animatedVectorDrawable = remember {
                AnimatedVectorDrawableCompat.create(
                    context,
                    R.drawable.loading_logo
                )
            }

            // État pour contrôler l'animation
            var isPlaying by remember { mutableStateOf(false) }

            // Composant image avec l'animation
            AndroidView(
                factory = { ctx ->
                    ImageView(ctx).apply {
                        setImageDrawable(animatedVectorDrawable)
                    }
                },
                modifier = Modifier.size(100.dp),
                update = { imageView ->
                    if (isPlaying) {
                        animatedVectorDrawable?.start()
                    } else {
                        animatedVectorDrawable?.stop()
                    }
                }
            )

            // Déclencher l'animation en boucle
            LaunchedEffect(Unit) {
                while (true) {
                    isPlaying = true
                    delay(3000)
                    isPlaying = false
                    delay(100)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("Analyse en cours...")
        }
    }
}