package com.example.docscanner.ui.main.scan

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.opencv.core.Rect

@Parcelize
data class SimpleRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
) : Parcelable {
    fun toOpenCVRect(): Rect = Rect(x, y, width, height)

    companion object {
        fun from(rect: Rect): SimpleRect = SimpleRect(rect.x, rect.y, rect.width, rect.height)
    }
}

