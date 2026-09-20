package com.newoether.agora.data

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeBackupV5BaselineTest {
    @Test
    fun unchangedConversationAndMediaAreRawCopiedWhileChangesAndDeletesAreRebuilt() {
        val directory = Files.createTempDirectory("agora-v5-baseline").toFile()
        val baselineFile = File(directory, "baseline.agora")
        val resultFile = File(directory, "result.agora")
        try {
            writeBaseline(baselineFile)
            NativeBackupV5Baseline.openOrNull(baselineFile).use { baseline ->
                requireNotNull(baseline)
                assertEquals(setOf("same"), baseline.unchangedConversationIds(mapOf("same" to 1L, "changed" to 3L)))
                ZipArchiveOutputStream(resultFile).use { output ->
                    NativeBackupV5Writer.write(
                        zip = output,
                        conversations = listOf(
                            json("id" to "same", "dataChangedAt" to 1L),
                            json("id" to "changed", "dataChangedAt" to 3L),
                            json("id" to "new", "dataChangedAt" to 4L),
                        ),
                        runs = emptyList(),
                        messages = listOf(
                            json("id" to "changed-message", "conversationId" to "changed"),
                            json("id" to "new-message", "conversationId" to "new"),
                        ),
                        tasks = emptyList(),
                        loops = emptyList(),
                        baseline = baseline,
                        unchangedConversationIds = setOf("same"),
                    )
                }
            }

            ZipFile.builder().setFile(baselineFile).get().use { oldZip ->
                ZipFile.builder().setFile(resultFile).get().use { newZip ->
                    val sameEntry = NativeBackupFormat.conversationEntry("same")
                    assertArrayEquals(raw(oldZip, sameEntry), raw(newZip, sameEntry))
                    assertArrayEquals(raw(oldZip, MEDIA_ENTRY), raw(newZip, MEDIA_ENTRY))
                    assertFalse(newZip.entries.asSequence().any { it.name == NativeBackupFormat.conversationEntry("deleted") })
                    val changed = newZip.getInputStream(newZip.getEntry(NativeBackupFormat.conversationEntry("changed")))
                        .bufferedReader().use { it.readText() }
                    assertTrue(changed.contains("changed-message"))
                    assertTrue(newZip.getEntry(NativeBackupFormat.conversationEntry("new")) != null)
                    val index = newZip.getInputStream(newZip.getEntry(NativeBackupFormat.CONVERSATION_INDEX_ENTRY))
                        .bufferedReader().use { Json.decodeFromString<NativeConversationIndex>(it.readText()) }
                    assertEquals(listOf("same", "changed", "new"), index.conversations.map { it.id })
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun invalidBaselineFallsBackToFullExport() {
        val file = Files.createTempFile("agora-invalid-baseline", ".agora").toFile()
        try {
            file.writeText("not a zip")
            assertNull(NativeBackupV5Baseline.openOrNull(file))
        } finally {
            file.delete()
        }
    }

    private fun writeBaseline(file: File) {
        ZipArchiveOutputStream(file).use { output ->
            writeEntry(
                output,
                NativeBackupFormat.MANIFEST_ENTRY,
                """{"agora_export_version":5,"categories":["conversations"]}""",
            )
            writeEntry(output, MEDIA_ENTRY, "media-bytes")
            val index = NativeConversationIndex(
                listOf(
                    NativeConversationIndexEntry("same", 1L, NativeBackupFormat.conversationEntry("same"), listOf(MEDIA_ENTRY)),
                    NativeConversationIndexEntry("changed", 2L, NativeBackupFormat.conversationEntry("changed")),
                    NativeConversationIndexEntry("deleted", 2L, NativeBackupFormat.conversationEntry("deleted")),
                ),
            )
            index.conversations.forEach { item -> writeEntry(output, item.entry, "payload-${item.id}") }
            writeEntry(output, NativeBackupFormat.CONVERSATION_INDEX_ENTRY, Json.encodeToString(index))
        }
    }

    private fun writeEntry(output: ZipArchiveOutputStream, name: String, value: String) {
        output.putArchiveEntry(ZipArchiveEntry(name))
        output.write(value.encodeToByteArray())
        output.closeArchiveEntry()
    }

    private fun raw(zip: ZipFile, name: String): ByteArray =
        zip.getRawInputStream(zip.getEntry(name)).use { it.readBytes() }

    private fun json(vararg values: Pair<String, Any>) = buildJsonObject {
        values.forEach { (key, value) ->
            when (value) {
                is String -> put(key, value)
                is Long -> put(key, value)
            }
        }
    }

    private companion object {
        const val MEDIA_ENTRY = "media/images/same.png"
    }
}
