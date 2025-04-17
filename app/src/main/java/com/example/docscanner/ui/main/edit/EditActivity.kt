package com.example.docscanner.ui.main.edit

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.docscanner.ui.main.scan.AnalyzedDocument
import com.example.docscanner.ui.main.scan.DocumentElement
import com.example.docscanner.ui.main.scan.SimpleRect
import com.example.docscanner.ui.theme.DocScannerTheme
import java.io.File
import kotlin.math.roundToInt

class EditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Récupérer le document analysé depuis l'intent
        val analyzedDocument = intent.getParcelableExtra<AnalyzedDocument>("analyzed_document")
            ?: AnalyzedDocument(emptyList()) // Document vide par défaut

        setContent {
            DocScannerTheme {
                DocumentEditorScreen(
                    analyzedDocument = analyzedDocument,
                    onSavePdf = { updatedDocument ->
                        // Sauvegarder le PDF et terminer l'activité
                        saveDocumentAsPdf(this, updatedDocument)
                        finish()
                    }
                )
            }
        }
    }

    private fun saveDocumentAsPdf(context: Context, document: AnalyzedDocument) {
        // Créer le fichier de sortie
        val outputFile = File(context.filesDir, "document_${System.currentTimeMillis()}.pdf")

        // Analyser la mise en page
        val layoutAnalyzer = DocumentLayoutAnalyzer()
        val documentGrid = layoutAnalyzer.analyzeLayout(document)
        val optimizedGrid = layoutAnalyzer.optimizeGrid(documentGrid)

        // Générer le PDF
        layoutAnalyzer.generatePdfFromGrid(optimizedGrid, outputFile)

        // Enregistrer le chemin du fichier pour utilisation ultérieure
        val sharedPreferences = getSharedPreferences("pdf_prefs", Context.MODE_PRIVATE)
        sharedPreferences.edit().putString("last_pdf_path", outputFile.absolutePath).apply()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentEditorScreen(
    analyzedDocument: AnalyzedDocument,
    onSavePdf: (AnalyzedDocument) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Éditeur de document") },
                actions = {
                    Button(
                        onClick = { onSavePdf(analyzedDocument) },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("Enregistrer PDF")
                    }
                }
            )
        }
    ) { paddingValues ->
        ResponsiveGridDocumentEditor(
            analyzedDocument = analyzedDocument,
            onSavePdf = onSavePdf,
            modifier = Modifier.padding(paddingValues)
        )
    }
}

@Composable
fun ResponsiveGridDocumentEditor(
    analyzedDocument: AnalyzedDocument,
    onSavePdf: (AnalyzedDocument) -> Unit,
    modifier: Modifier = Modifier
) {
    var documentState by remember { mutableStateOf(analyzedDocument) }
    var gridState by remember { mutableStateOf(GridState()) }

    // Initialiser ou mettre à jour l'état de la grille
    LaunchedEffect(documentState) {
        gridState = analyzeDocumentToGrid(documentState)
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Barre d'outils
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(onClick = { /* Annuler */ }) {
                Text("Annuler")
            }
            Button(onClick = { onSavePdf(documentState) }) {
                Text("Enregistrer PDF")
            }
        }

        // Grille d'édition
        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .background(Color.White)
                .padding(16.dp)
        ) {
            // Affichage de la grille avec séparateurs
            EditableGrid(
                gridState = gridState,
                onGridChange = { newGridState ->
                    gridState = newGridState
                    // Mettre à jour le document basé sur la nouvelle grille
                    documentState = updateDocumentFromGrid(documentState, newGridState)
                }
            )
        }
    }
}

enum class Orientation {
    Vertical, Horizontal
}

data class GridState(
    val columns: List<Float> = listOf(0f, 1f), // Tailles relatives des colonnes (0-1)
    val rows: List<Float> = listOf(0f, 1f),    // Tailles relatives des rangées (0-1)
    val cellContent: Map<Pair<Int, Int>, DocElement> = mapOf() // Contenu des cellules
)

// Adapter le DocElement pour utiliser les nouvelles classes
sealed class DocElement {
    abstract val rect: SimpleRect

    data class TextElement(
        override val rect: SimpleRect,
        val content: String,
        val isInverted: Boolean = false
    ) : DocElement()

    data class ImageElement(
        override val rect: SimpleRect,
        val bitmap: androidx.compose.ui.graphics.ImageBitmap? = null,
        val imageUri: String? = null
    ) : DocElement()
}

@Composable
fun DropTargetArea(
    modifier: Modifier = Modifier,
    onDrop: (Float, Float) -> Unit
) {
    var currentPosition by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        currentPosition = Offset.Zero
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        currentPosition += Offset(dragAmount.x, dragAmount.y)
                    },
                    onDragEnd = {
                        // Appeler onDrop avec la position actuelle
                        onDrop(currentPosition.x, currentPosition.y)
                    }
                )
            }
    )
}

@Composable
fun EditableGrid(
    gridState: GridState,
    onGridChange: (GridState) -> Unit
) {
    val gridModifier = Modifier.fillMaxSize()

    BoxWithConstraints(modifier = gridModifier) {
        val totalWidth = constraints.maxWidth.toFloat()
        val totalHeight = constraints.maxHeight.toFloat()

        // Calculer les positions absolues des colonnes et rangées
        val columnPositions = gridState.columns.map { it * totalWidth }
        val rowPositions = gridState.rows.map { it * totalHeight }

        // Afficher les séparateurs verticaux (colonnes)
        for (i in 1 until columnPositions.size - 1) {
            val xPos = columnPositions[i]

            // Séparateur de colonne glissable
            DraggableDivider(
                modifier = Modifier
                    .offset { IntOffset(xPos.roundToInt(), 0) }
                    .width(8.dp)
                    .fillMaxHeight(),
                orientation = Orientation.Vertical,
                onPositionChange = { deltaX ->
                    // Calculer la nouvelle position relative
                    val newRelativePos = (xPos + deltaX) / totalWidth

                    // Mettre à jour les positions des colonnes
                    val newColumns = gridState.columns.toMutableList()
                    newColumns[i] = newRelativePos.coerceIn(
                        newColumns[i-1] + 0.1f, // Min 10% de largeur
                        newColumns[i+1] - 0.1f  // Max proche de la colonne suivante
                    )

                    onGridChange(gridState.copy(columns = newColumns))
                }
            )
        }

        // Afficher les séparateurs horizontaux (rangées)
        for (i in 1 until rowPositions.size - 1) {
            val yPos = rowPositions[i]

            // Séparateur de rangée glissable
            DraggableDivider(
                modifier = Modifier
                    .offset { IntOffset(0, yPos.roundToInt()) }
                    .height(8.dp)
                    .fillMaxWidth(),
                orientation = Orientation.Horizontal,
                onPositionChange = { deltaY ->
                    // Calculer la nouvelle position relative
                    val newRelativePos = (yPos + deltaY) / totalHeight

                    // Mettre à jour les positions des rangées
                    val newRows = gridState.rows.toMutableList()
                    newRows[i] = newRelativePos.coerceIn(
                        newRows[i-1] + 0.1f, // Min 10% de hauteur
                        newRows[i+1] - 0.1f  // Max proche de la rangée suivante
                    )

                    onGridChange(gridState.copy(rows = newRows))
                }
            )
        }

        // Afficher le contenu des cellules
        gridState.cellContent.forEach { (cellPos, element) ->
            val (colIndex, rowIndex) = cellPos

            // Vérifier que les indices sont dans les limites
            if (colIndex < columnPositions.size - 1 && rowIndex < rowPositions.size - 1) {
                val xStart = columnPositions[colIndex]
                val xEnd = columnPositions[colIndex + 1]
                val yStart = rowPositions[rowIndex]
                val yEnd = rowPositions[rowIndex + 1]

                // Calculer largeur et hauteur de la cellule
                val width = xEnd - xStart
                val height = yEnd - yStart

                // Afficher l'élément dans la cellule
                DocumentElementInCell(
                    element = element,
                    xPosition = xStart,
                    yPosition = yStart,
                    width = width,
                    height = height,
                    onDragStart = { /* Logique au début du drag */ },
                    onDragEnd = { newX, newY ->
                        // Trouver la nouvelle cellule basée sur la position de fin
                        val newColIndex = columnPositions.indexOfLast { pos -> pos <= newX }
                        val newRowIndex = rowPositions.indexOfLast { pos -> pos <= newY }

                        if (newColIndex >= 0 && newRowIndex >= 0) {
                            // Créer une copie du map de contenu des cellules
                            val newCellContent = gridState.cellContent.toMutableMap()

                            // Supprimer l'élément de l'ancienne position
                            newCellContent.remove(cellPos)

                            // Ajouter l'élément à la nouvelle position
                            newCellContent[Pair(newColIndex, newRowIndex)] = element

                            // Mettre à jour l'état de la grille
                            onGridChange(gridState.copy(cellContent = newCellContent))
                        }
                    }
                )
            }
        }

        // Ajouter une zone pour déposer un nouvel élément (créer une nouvelle colonne/rangée)
        DropTargetArea(
            modifier = Modifier.fillMaxSize(),
            onDrop = { x, y ->
                // Logique pour ajouter une nouvelle colonne ou rangée
                val relativeX = x / totalWidth
                val relativeY = y / totalHeight

                // Trouver où insérer la nouvelle colonne
                val colIndex = gridState.columns.indexOfLast { it < relativeX }

                // Trouver où insérer la nouvelle rangée
                val rowIndex = gridState.rows.indexOfLast { it < relativeY }

                // Créer la nouvelle structure de grille
                val newGridState = createNewGridStructure(
                    gridState,
                    colIndex,
                    rowIndex,
                    relativeX,
                    relativeY
                )

                onGridChange(newGridState)
            }
        )
    }
}

@Composable
fun DraggableDivider(
    modifier: Modifier,
    orientation: Orientation,
    onPositionChange: (Float) -> Unit
) {
    Box(
        modifier = modifier
            .background(Color.LightGray.copy(alpha = 0.6f))
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val delta = if (orientation == Orientation.Vertical) {
                        dragAmount.x
                    } else {
                        dragAmount.y
                    }
                    onPositionChange(delta)
                }
            }
            .hoverable(remember { MutableInteractionSource() })
            .cursorForHorizontalResize(orientation == Orientation.Vertical)
    )
}

fun Modifier.cursorForHorizontalResize(isHorizontalResize: Boolean): Modifier {
    return composed {
        val context = LocalContext.current
        val cursorType = if (isHorizontalResize) {
            // Utilise les constantes de curseur Android
            android.view.PointerIcon.getSystemIcon(context, android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW)
        } else {
            android.view.PointerIcon.getSystemIcon(context, android.view.PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW)
        }

        // Convertir le curseur Android en PointerIcon Compose
        val pointerIcon = remember(cursorType) { PointerIcon(cursorType) }
        this.pointerHoverIcon(pointerIcon)
    }
}

@Composable
fun DocumentElementInCell(
    element: DocElement,
    xPosition: Float,
    yPosition: Float,
    width: Float,
    height: Float,
    onDragStart: () -> Unit,
    onDragEnd: (Float, Float) -> Unit
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .offset { IntOffset(xPosition.roundToInt(), yPosition.roundToInt()) }
            .size(width = width.toInt().dp, height = height.toInt().dp)
            .border(1.dp, Color.Gray)
            .background(Color.White)
            .padding(4.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        isDragging = true
                        onDragStart()
                    },
                    onDragEnd = {
                        isDragging = false
                        // Calculer la position finale
                        val finalX = xPosition + dragOffset.x
                        val finalY = yPosition + dragOffset.y
                        onDragEnd(finalX, finalY)
                        // Réinitialiser l'offset
                        dragOffset = Offset.Zero
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragOffset = Offset(
                            dragOffset.x + dragAmount.x,
                            dragOffset.y + dragAmount.y
                        )
                    }
                )
            }
    ) {
        when (element) {
            is DocElement.TextElement -> {
                val textColor = if (element.isInverted) Color.White else Color.Black
                val backgroundColor = if (element.isInverted) Color.Black else Color.White

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = element.content,
                        color = textColor,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            is DocElement.ImageElement -> {
                element.bitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Document image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } ?: Box(
                    modifier = Modifier.fillMaxSize().background(Color.LightGray),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Image" + (element.imageUri?.let { " (URI: $it)" } ?: ""))
                }
            }
        }
    }
}

// Fonction modifiée pour analyser un document et créer un état de grille
// en fonction de la nouvelle structure AnalyzedDocument
fun analyzeDocumentToGrid(document: AnalyzedDocument): GridState {
    val elements = mutableListOf<DocElement>()

    // Convertir les éléments du nouveau format vers DocElement
    document.elements.forEach { element ->
        when (element) {
            is DocumentElement.TextElement -> {
                elements.add(
                    DocElement.TextElement(
                        rect = element.rect,
                        content = element.text,
                        isInverted = element.isInverted
                    )
                )
            }
            is DocumentElement.ImageElement -> {
                elements.add(
                    DocElement.ImageElement(
                        rect = element.rect,
                        bitmap = element.bitmap?.asImageBitmap(),
                        imageUri = element.imageUri
                    )
                )
            }
        }
    }

    // Analyser les positions pour créer des colonnes et rangées
    val xPositions = mutableSetOf<Int>()
    val yPositions = mutableSetOf<Int>()

    // Ajouter toutes les positions de début et fin
    elements.forEach { element ->
        val rect = element.rect

        xPositions.add(rect.x)
        xPositions.add(rect.x + rect.width)
        yPositions.add(rect.y)
        yPositions.add(rect.y + rect.height)
    }

    // Trier les positions
    val sortedXPositions = xPositions.toList().sorted()
    val sortedYPositions = yPositions.toList().sorted()

    // Normaliser les positions (0-1)
    val maxX = sortedXPositions.lastOrNull() ?: 0
    val maxY = sortedYPositions.lastOrNull() ?: 0

    val columns = if (maxX > 0) sortedXPositions.map { it.toFloat() / maxX } else listOf(0f, 1f)
    val rows = if (maxY > 0) sortedYPositions.map { it.toFloat() / maxY } else listOf(0f, 1f)

    // Assigner les éléments aux cellules
    val cellContent = mutableMapOf<Pair<Int, Int>, DocElement>()

    elements.forEach { element ->
        val rect = element.rect

        // Trouver l'indice de colonne et rangée
        val colIndex = sortedXPositions.indexOfFirst { it == rect.x }
        val rowIndex = sortedYPositions.indexOfFirst { it == rect.y }

        if (colIndex >= 0 && rowIndex >= 0) {
            cellContent[Pair(colIndex, rowIndex)] = element
        }
    }

    return GridState(columns, rows, cellContent)
}

// Fonction modifiée pour mettre à jour le document à partir de l'état de la grille
// en fonction de la nouvelle structure AnalyzedDocument
fun updateDocumentFromGrid(document: AnalyzedDocument, gridState: GridState): AnalyzedDocument {
    val elements = mutableListOf<DocumentElement>()

    // Calculer les dimensions absolues
    val maxDimension = 1000 // Valeur arbitraire pour la taille max du document

    // Trier les cellules pour maintenir l'ordre des textes
    val sortedCells = gridState.cellContent.entries.sortedBy { (pos, _) ->
        pos.first * 1000 + pos.second // Trier par colonne puis par rangée
    }

    sortedCells.forEach { (cellPos, element) ->
        val (colIndex, rowIndex) = cellPos

        // Vérifier que les indices sont dans les limites
        if (colIndex < gridState.columns.size - 1 && rowIndex < gridState.rows.size - 1) {
            // Calculer la position et dimension absolues
            val xStart = (gridState.columns[colIndex] * maxDimension).toInt()
            val xEnd = (gridState.columns[colIndex + 1] * maxDimension).toInt()
            val yStart = (gridState.rows[rowIndex] * maxDimension).toInt()
            val yEnd = (gridState.rows[rowIndex + 1] * maxDimension).toInt()

            val width = xEnd - xStart
            val height = yEnd - yStart

            // Créer un nouveau rectangle
            val newRect = SimpleRect(xStart, yStart, width, height)

            // Traiter selon le type d'élément
            when (element) {
                is DocElement.TextElement -> {
                    elements.add(
                        DocumentElement.TextElement(
                            rect = newRect,
                            text = element.content,
                            isInverted = element.isInverted
                        )
                    )
                }
                is DocElement.ImageElement -> {
                    // Note: la conversion de ImageBitmap à Bitmap Android doit être gérée si nécessaire
                    elements.add(
                        DocumentElement.ImageElement(
                            rect = newRect,
                            bitmap = null, // Conversion ImageBitmap -> Bitmap nécessaire ici si besoin
                            imageUri = element.imageUri
                        )
                    )
                }
            }
        }
    }

    // Conserver l'image d'origine du document
    return AnalyzedDocument(elements, document.originalBitmap)
}

// Fonction pour créer une nouvelle structure de grille avec une nouvelle colonne ou rangée
fun createNewGridStructure(
    currentGrid: GridState,
    colIndex: Int,
    rowIndex: Int,
    relativeX: Float,
    relativeY: Float
): GridState {
    // Déterminer si nous ajoutons une colonne ou une rangée
    val isAddingColumn = colIndex >= 0 && colIndex < currentGrid.columns.size - 1
    val isAddingRow = rowIndex >= 0 && rowIndex < currentGrid.rows.size - 1

    var newColumns = currentGrid.columns
    var newRows = currentGrid.rows

    if (isAddingColumn) {
        // Insérer une nouvelle colonne
        newColumns = newColumns.toMutableList().apply {
            add(colIndex + 1, relativeX)
        }
    }

    if (isAddingRow) {
        // Insérer une nouvelle rangée
        newRows = newRows.toMutableList().apply {
            add(rowIndex + 1, relativeY)
        }
    }

    return currentGrid.copy(columns = newColumns, rows = newRows)
}