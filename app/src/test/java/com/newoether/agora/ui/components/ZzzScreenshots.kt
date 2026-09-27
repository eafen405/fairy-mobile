package com.newoether.agora.ui.components

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File

/**
 * Screenshot harness: writes captured Compose frames as PNGs under
 * `app/build/outputs/zzz-screenshots/` for visual acceptance across slices.
 */
object ZzzScreenshots {

    fun outputDir(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(directory, "app/build/outputs/zzz-screenshots")
            if (File(directory, "app/src/main").isDirectory) {
                candidate.mkdirs()
                return candidate
            }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate app module")
    }

    fun savePng(name: String, image: ImageBitmap): File =
        savePng(name, image.asAndroidBitmap())

    fun savePng(name: String, bitmap: Bitmap): File {
        val file = File(outputDir(), "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }
}
