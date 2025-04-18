package com.example.docscanner.ui.main.scan

import android.graphics.Bitmap
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue



@Parcelize
data class AnalyzedDocument(
    val elements: List<DocumentElement>,
    val originalBitmap: @RawValue Bitmap? = null
) : Parcelable

@Parcelize
sealed class DocumentElement : Parcelable {
    abstract val rect: SimpleRect
    abstract val type: ElementType

    @Parcelize
    data class TextElement(
        override val rect: SimpleRect,
        val text: String,
        val isInverted: Boolean = false
    ) : DocumentElement(), Parcelable {
        override val type = ElementType.TEXT
    }

    @Parcelize
    data class ImageElement(
        override val rect: SimpleRect,
        val bitmap: @RawValue Bitmap? = null,
        val imageUri: String? = null
    ) : DocumentElement(), Parcelable {
        override val type = ElementType.IMAGE
    }
}

enum class ElementType {
    TEXT,
    IMAGE
}