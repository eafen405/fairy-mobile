package com.newoether.agora.data

import android.content.Context
import android.os.Process
import androidx.room.Transactor
import androidx.room.useReaderConnection
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.LoopEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.data.local.TaskEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Streams one point-in-time conversation graph from an independent Room connection pool. */
internal class ConversationExportSnapshotReader(
    private val context: Context,
) {
    companion object {
        private const val MESSAGE_PAGE_SIZE = 64
        private const val SNAPSHOT_THREAD_COUNT = 2
        private val snapshotThreadSequence = AtomicInteger()
    }

    data class ConversationSnapshot(
        val conversation: ChatEntity,
        val runs: List<RunEntity>,
        val messages: List<MessageEntity>,
        val loops: List<LoopEntity>,
    )

    suspend fun readSnapshot(
        onConversation: suspend (ConversationSnapshot) -> Unit,
        onTask: suspend (TaskEntity) -> Unit,
    ) {
        val snapshotExecutor = Executors.newFixedThreadPool(SNAPSHOT_THREAD_COUNT) { runnable ->
            Thread(
                {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    runnable.run()
                },
                "agora-export-db-${snapshotThreadSequence.incrementAndGet()}",
            )
        }
        val snapshotDatabase = ChatDatabase.build(
            context,
            queryExecutor = snapshotExecutor,
            transactionExecutor = snapshotExecutor,
        )
        try {
            val snapshotDao = snapshotDatabase.chatDao()
            snapshotDatabase.useReaderConnection { connection ->
                connection.withTransaction(Transactor.SQLiteTransactionType.DEFERRED) {
                    for (conversation in snapshotDao.getAllConversationsList()) {
                        currentCoroutineContext().ensureActive()
                        val messages = mutableListOf<MessageEntity>()
                        var afterMessageId: String? = null
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val page = snapshotDao.getConversationMessagesPage(
                                conversation.id,
                                afterMessageId,
                                MESSAGE_PAGE_SIZE,
                            )
                            if (page.isEmpty()) break
                            messages += page
                            afterMessageId = page.last().id
                            if (page.size < MESSAGE_PAGE_SIZE) break
                        }
                        onConversation(
                            ConversationSnapshot(
                                conversation = conversation,
                                runs = snapshotDao.getRunsForConversationSnapshot(conversation.id),
                                messages = messages,
                                loops = snapshotDao.getLoopsForConversationSnapshot(conversation.id),
                            )
                        )
                    }
                    for (task in snapshotDao.getAllTasksList()) {
                        currentCoroutineContext().ensureActive()
                        onTask(task)
                    }
                }
            }
        } finally {
            snapshotDatabase.close()
            snapshotExecutor.shutdown()
        }
    }
}
