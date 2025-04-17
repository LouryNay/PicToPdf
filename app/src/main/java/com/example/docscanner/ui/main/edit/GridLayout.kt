package com.example.docscanner.ui.main.edit

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Parcelable
import com.example.docscanner.ui.main.scan.AnalyzedDocument
import com.example.docscanner.ui.main.scan.DocumentElement
import com.example.docscanner.ui.main.scan.ElementType
import com.example.docscanner.ui.main.scan.SimpleRect
import kotlinx.parcelize.Parcelize
import java.io.File
import java.io.FileOutputStream

@Parcelize
data class GridCell(
    val rowStart: Int,
    val rowEnd: Int,
    val colStart: Int,
    val colEnd: Int,
    val element: DocumentElement
) : Parcelable

@Parcelize
data class DocumentGrid(
    val rows: List<Float>, // Coordonnées Y des lignes de la grille
    val columns: List<Float>, // Coordonnées X des colonnes de la grille
    val cells: List<GridCell> // Éléments placés dans la grille
) : Parcelable

class DocumentLayoutAnalyzer {

    fun analyzeLayout(document: AnalyzedDocument): DocumentGrid {
        // Étape 1: Utiliser directement les éléments de la nouvelle structure
        val elements = document.elements

        // Étape 2: Extraire toutes les coordonnées pour définir les lignes et colonnes
        val horizontalLines = mutableSetOf<Float>()
        val verticalLines = mutableSetOf<Float>()

        elements.forEach { element ->
            val rect = element.rect

            // Ajouter les bordures de chaque élément
            horizontalLines.add(rect.y.toFloat())
            horizontalLines.add((rect.y + rect.height).toFloat())
            verticalLines.add(rect.x.toFloat())
            verticalLines.add((rect.x + rect.width).toFloat())
        }

        // Étape 3: Trier les coordonnées pour obtenir les lignes et colonnes ordonnées
        val sortedRows = horizontalLines.toList().sorted()
        val sortedCols = verticalLines.toList().sorted()

        // Étape 4: Associer chaque élément à sa position dans la grille
        val gridCells = mutableListOf<GridCell>()

        elements.forEach { element ->
            val rect = element.rect

            // Trouver les indices des lignes et colonnes correspondant aux bordures de l'élément
            val rowStart = sortedRows.indexOfFirst { it >= rect.y - TOLERANCE }
            val rowEnd = sortedRows.indexOfFirst { it >= rect.y + rect.height - TOLERANCE } + 1
            val colStart = sortedCols.indexOfFirst { it >= rect.x - TOLERANCE }
            val colEnd = sortedCols.indexOfFirst { it >= rect.x + rect.width - TOLERANCE } + 1

            // Vérifier que les indices sont valides
            if (rowStart != -1 && rowEnd != 0 && colStart != -1 && colEnd != 0) {
                gridCells.add(
                    GridCell(
                        rowStart = rowStart,
                        rowEnd = rowEnd,
                        colStart = colStart,
                        colEnd = colEnd,
                        element = element
                    )
                )
            }
        }

        return DocumentGrid(
            rows = sortedRows,
            columns = sortedCols,
            cells = gridCells
        )
    }

    // Fonction pour fusionner des lignes ou colonnes trop proches
    private fun mergeCloseLines(lines: List<Float>, threshold: Float): List<Float> {
        if (lines.isEmpty()) return emptyList()

        val result = mutableListOf<Float>()
        var currentLine = lines.first()

        for (i in 1 until lines.size) {
            if (lines[i] - currentLine <= threshold) {
                // Fusionner en prenant la moyenne
                currentLine = (currentLine + lines[i]) / 2
            } else {
                result.add(currentLine)
                currentLine = lines[i]
            }
        }

        result.add(currentLine)
        return result
    }

    // Fonction pour optimiser la grille (éliminer les cellules vides, etc.)
    fun optimizeGrid(grid: DocumentGrid): DocumentGrid {
        // Identifier les lignes et colonnes qui ne contiennent aucun élément
        val usedRows = mutableSetOf<Int>()
        val usedCols = mutableSetOf<Int>()

        grid.cells.forEach { cell ->
            for (row in cell.rowStart until cell.rowEnd) usedRows.add(row)
            for (col in cell.colStart until cell.colEnd) usedCols.add(col)
        }

        // Créer un mapping pour les nouveaux indices après suppression des lignes/colonnes vides
        val rowMapping = grid.rows.indices.filter { it in usedRows }.withIndex().associate { (newIndex, oldIndex) ->
            oldIndex to newIndex
        }

        val colMapping = grid.columns.indices.filter { it in usedCols }.withIndex().associate { (newIndex, oldIndex) ->
            oldIndex to newIndex
        }

        // Créer une nouvelle grille optimisée
        val optimizedCells = grid.cells.map { cell ->
            GridCell(
                rowStart = rowMapping[cell.rowStart] ?: 0,
                rowEnd = rowMapping[cell.rowEnd - 1]?.plus(1) ?: 1, // -1, +1 pour gérer les indices
                colStart = colMapping[cell.colStart] ?: 0,
                colEnd = colMapping[cell.colEnd - 1]?.plus(1) ?: 1, // -1, +1 pour gérer les indices
                element = cell.element
            )
        }

        return DocumentGrid(
            rows = grid.rows.filterIndexed { index, _ -> index in usedRows },
            columns = grid.columns.filterIndexed { index, _ -> index in usedCols },
            cells = optimizedCells
        )
    }

    // Fonction pour générer un PDF en utilisant la structure de grille
    fun generatePdfFromGrid(grid: DocumentGrid, outputFile: File) {
        val document = PdfDocument()
        try {
            // Vérifier que la grille n'est pas vide
            if (grid.rows.isEmpty() || grid.columns.isEmpty()) {
                throw IllegalArgumentException("La grille ne peut pas être vide")
            }

            // Créer une page avec une taille adaptée au contenu
            val pageInfo = PdfDocument.PageInfo.Builder(
                grid.columns.last().toInt() + MARGIN * 2,
                grid.rows.last().toInt() + MARGIN * 2,
                1
            ).create()

            val page = document.startPage(pageInfo)
            val canvas = page.canvas

            grid.cells.forEach { cell ->
                // Vérifier que les indices sont valides
                if (cell.colStart < grid.columns.size && cell.rowStart < grid.rows.size) {
                    when (val element = cell.element) {
                        is DocumentElement.TextElement -> {
                            val paint = Paint().apply {
                                color = if (element.isInverted) Color.WHITE else Color.BLACK
                                textSize = 12f
                            }

                            // Positionner le texte selon sa cellule dans la grille
                            val x = grid.columns[cell.colStart]
                            val y = grid.rows[cell.rowStart] + paint.textSize

                            canvas.drawText(element.text, x, y, paint)
                        }
                        is DocumentElement.ImageElement -> {
                            element.bitmap?.let { bitmap ->
                                // Positionner l'image selon sa cellule dans la grille
                                val x = grid.columns[cell.colStart]
                                val y = grid.rows[cell.rowStart]

                                canvas.drawBitmap(bitmap, x, y, null)
                            }
                        }
                    }
                }
            }

            document.finishPage(page)
            document.writeTo(FileOutputStream(outputFile))
        } finally {
            document.close()
        }
    }

    // Convertir la grille en document analysé (pour les modifications et retours)
    fun gridToAnalyzedDocument(grid: DocumentGrid): AnalyzedDocument {
        val elements = mutableListOf<DocumentElement>()

        // Extraire les informations des cellules
        grid.cells.forEach { cell ->
            if (cell.colStart < grid.columns.size &&
                cell.colEnd <= grid.columns.size &&
                cell.rowStart < grid.rows.size &&
                cell.rowEnd <= grid.rows.size) {

                // Calculer les coordonnées exactes de la cellule
                val x = grid.columns[cell.colStart].toInt()
                val y = grid.rows[cell.rowStart].toInt()
                val width = (grid.columns[cell.colEnd - 1] - grid.columns[cell.colStart]).toInt()
                val height = (grid.rows[cell.rowEnd - 1] - grid.rows[cell.rowStart]).toInt()

                val rect = SimpleRect(x, y, width, height)

                // Conserver l'élément original mais mettre à jour son rectangle
                when (val element = cell.element) {
                    is DocumentElement.TextElement -> {
                        elements.add(
                            DocumentElement.TextElement(
                                rect = rect,
                                text = element.text,
                                isInverted = element.isInverted
                            )
                        )
                    }
                    is DocumentElement.ImageElement -> {
                        elements.add(
                            DocumentElement.ImageElement(
                                rect = rect,
                                bitmap = element.bitmap,
                                imageUri = element.imageUri
                            )
                        )
                    }
                }
            }
        }

        return AnalyzedDocument(elements)
    }

    companion object {
        private const val TOLERANCE = 5f // Tolérance pour gérer les imprécisions de détection
        private const val MARGIN = 50 // Marge autour du document en pixels
    }
}