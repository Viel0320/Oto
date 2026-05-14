package com.viel.aplayer.ui.utils

import android.graphics.BitmapFactory
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

const val DEFAULT_COVER_BACKGROUND_ARGB: Int = 0xFF1C1B1F.toInt()

suspend fun extractCoverDominantColorArgb(
    coverPath: String?,
    fallbackColor: Int = DEFAULT_COVER_BACKGROUND_ARGB
): Int = withContext(Dispatchers.IO) {
    val path = coverPath ?: return@withContext fallbackColor
    val file = File(path)
    if (!file.exists()) return@withContext fallbackColor

    val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@withContext fallbackColor
    Palette.from(bitmap).generate().getDominantColor(fallbackColor)
}
