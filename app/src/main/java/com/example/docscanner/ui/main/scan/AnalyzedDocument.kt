package com.example.docscanner.ui.main.scan

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class AnalyzedDocument(
    val textZones: List<SimpleRect>,
    val imageZones: List<SimpleRect>,
    val texts: List<String>
) : Parcelable


