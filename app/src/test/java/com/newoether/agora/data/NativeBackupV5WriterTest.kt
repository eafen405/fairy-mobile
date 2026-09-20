package com.newoether.agora.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream

class NativeBackupV5WriterTest {
    @Test
    fun writesConversationEntriesIndexTasksAndMediaReferences() {
        val bytes = ByteArrayOutputStream().also { sink ->
            ZipArchiveOutputStream(sink).use { zip ->
                NativeBackupV5Writer.write(
                    zip = zip,
                    conversations = listOf(
                        json("id" to "one", "dataChangedAt" to 11L),
                        json("id" to "two", "dataChangedAt" to 22L),
                    ),
                    runs = listOf(json("id" to "run-one", "conversationId" to "one")),
                    messages = listOf(
                        json(
                            "id" to "message-one",
                            "conversationId" to "one",
                            "image" to "media/images/image-one.png",
                        ),
                    ),
                    tasks = listOf(json("id" to "task-one")),
                    loops = listOf(json("conversationId" to "two")),
                )
            }
        }.toByteArray()

        val entries = readEntries(bytes)
        val oneEntry = NativeBackupFormat.conversationEntry("one")
        val twoEntry = NativeBackupFormat.conversationEntry("two")
        assertEquals(
            setOf(NativeBackupFormat.CONVERSATION_INDEX_ENTRY, NativeBackupFormat.TASKS_ENTRY, oneEntry, twoEntry),
            entries.keys,
        )
        assertFalse(entries.containsKey(NativeBackupFormat.CONVERSATIONS_ENTRY))
        assertTrue(entries.getValue(oneEntry).contains("run-one"))
        assertTrue(entries.getValue(oneEntry).contains("message-one"))
        assertTrue(entries.getValue(oneEntry).contains("media/images/image-one.png"))
        assertFalse(entries.getValue(oneEntry).contains("\"two\""))
        assertTrue(entries.getValue(twoEntry).contains("\"loops\""))
        assertTrue(entries.getValue(NativeBackupFormat.TASKS_ENTRY).contains("task-one"))

        val index = Json.decodeFromString<NativeConversationIndex>(
            entries.getValue(NativeBackupFormat.CONVERSATION_INDEX_ENTRY),
        )
        assertEquals(listOf("one", "two"), index.conversations.map { it.id })
        assertEquals(listOf(11L, 22L), index.conversations.map { it.dataChangedAt })
        assertEquals(listOf("media/images/image-one.png"), index.conversations.first().mediaEntries)
        assertTrue(index.conversations.last().mediaEntries.isEmpty())
    }

    @Test
    fun emptySnapshotWritesOnlyEmptyIndexAndTasks() {
        val bytes = ByteArrayOutputStream().also { sink ->
            ZipArchiveOutputStream(sink).use { zip ->
                NativeBackupV5Writer.write(zip, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
            }
        }.toByteArray()
        val entries = readEntries(bytes)
        assertEquals(
            setOf(NativeBackupFormat.CONVERSATION_INDEX_ENTRY, NativeBackupFormat.TASKS_ENTRY),
            entries.keys,
        )
        assertEquals(
            emptyList<NativeConversationIndexEntry>(),
            Json.decodeFromString<NativeConversationIndex>(
                entries.getValue(NativeBackupFormat.CONVERSATION_INDEX_ENTRY),
            ).conversations,
        )
    }

    private fun json(vararg values: Pair<String, Any>) = buildJsonObject {
        values.forEach { (key, value) ->
            when (value) {
                is String -> put(key, value)
                is Long -> put(key, value)
                else -> error("Unsupported test value: $value")
            }
        }
    }

    private fun readEntries(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                put(entry.name, zip.readBytes().decodeToString())
                entry = zip.nextEntry
            }
        }
    }
}
