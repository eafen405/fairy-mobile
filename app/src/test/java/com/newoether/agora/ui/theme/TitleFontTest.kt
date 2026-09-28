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
    fun `title font covers every Han, CJK punctuation and ASCII char in string resources`() {
        val cmap = readCmap(locate("app/src/main/res/font/title_black.ttf").readBytes())
        val res = locate("app/src/main/res/values/strings.xml").parentFile!!.parentFile!!
        val wanted = res.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values") }
            .flatMap { dir -> dir.listFiles().orEmpty().filter { it.name.endsWith(".xml") } }
            .flatMap { file ->
                Regex(">([^<]*)<").findAll(file.readText()).flatMap { it.groupValues[1].codePoints().toArray().asList() }
            }
            .filter { cp ->
                cp in 0x20..0x7E || cp in 0x3000..0x303F || cp in 0xFF00..0xFFEF ||
                    cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF
            }
            .toSortedSet()
        val missing = wanted.filterNot { it in cmap }
        assertTrue(
            "title_black.ttf lacks: " + missing.joinToString("") { String(Character.toChars(it)) },
            missing.isEmpty(),
        )
    }

    /** Minimal `cmap` reader: unions every format 4 and format 12 subtable. */
    private fun readCmap(font: ByteArray): Set<Int> {
        fun u16(o: Int) = ((font[o].toInt() and 0xFF) shl 8) or (font[o + 1].toInt() and 0xFF)
        fun u32(o: Int) = (u16(o).toLong() shl 16 or u16(o + 2).toLong()).toInt()
        val tables = u16(4)
        val cmapOffset = (0 until tables).map { 12 + it * 16 }
            .first { String(font, it, 4, Charsets.US_ASCII) == "cmap" }
            .let { u32(it + 8) }
        val codepoints = HashSet<Int>()
        repeat(u16(cmapOffset + 2)) { index ->
            val sub = cmapOffset + u32(cmapOffset + 4 + index * 8 + 4)
            when (u16(sub)) {
                4 -> {
                    val segX2 = u16(sub + 6)
                    val ends = sub + 14
                    val starts = ends + segX2 + 2
                    val deltas = starts + segX2
                    val ranges = deltas + segX2
                    for (seg in 0 until segX2 / 2) {
                        val end = u16(ends + seg * 2)
                        val start = u16(starts + seg * 2)
                        val delta = u16(deltas + seg * 2)
                        val rangeOffset = u16(ranges + seg * 2)
                        for (cp in start..end) {
                            if (cp == 0xFFFF) continue
                            val glyph = if (rangeOffset == 0) {
                                (cp + delta) and 0xFFFF
                            } else {
                                val at = ranges + seg * 2 + rangeOffset + (cp - start) * 2
                                u16(at).let { if (it == 0) 0 else (it + delta) and 0xFFFF }
                            }
                            if (glyph != 0) codepoints += cp
                        }
                    }
                }
                12 -> {
                    val groups = u32(sub + 12)
                    for (g in 0 until groups) {
                        val base = sub + 16 + g * 12
                        for (cp in u32(base)..u32(base + 4)) codepoints += cp
                    }
                }
            }
        }
        return codepoints
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
