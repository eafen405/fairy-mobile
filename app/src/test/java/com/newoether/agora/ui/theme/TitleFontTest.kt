package com.newoether.agora.ui.theme

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The title font subset is produced offline by `scripts/subset_title_font.py`,
 * which itself asserts that every string-resource character the source font can
 * encode is covered (exiting non-zero otherwise). This test pins the shipped
 * artifact: it exists and stays within the 1.5 MB budget.
 */
class TitleFontTest {

    @Test
    fun `title font subset exists within the size budget`() {
        val font = locate("app/src/main/res/font/title_black.ttf")
        assertTrue("title_black.ttf missing", font.isFile)
        assertTrue(
            "title_black.ttf exceeds 1.5 MB: ${font.length()}",
            font.length() <= 1_500_000L,
        )
        val header = font.inputStream().use { it.readNBytes(4) }
        assertTrue(
            "title_black.ttf is not a TTF",
            header.contentEquals(byteArrayOf(0, 1, 0, 0)) ||
                header.contentEquals(byteArrayOf('O'.code.toByte(), 'T'.code.toByte(), 'T'.code.toByte(), 'O'.code.toByte())),
        )
    }

    @Test
    fun `anton label font is present`() {
        assertTrue(locate("app/src/main/res/font/anton_regular.ttf").isFile)
    }

    private fun locate(relativePath: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            File(directory, relativePath).takeIf(File::isFile)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        return File(directory, relativePath)
    }
}
