package com.example.docscanner.ui.main

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun createImageFile(context: Context): File? {
    val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    val storageDir: File? = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
    return try {
        File.createTempFile("JPEG_${timeStamp}_", ".jpg", storageDir)
    } catch (e: IOException) {
        e.printStackTrace()
        null
    }
}
