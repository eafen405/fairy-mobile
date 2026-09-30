package com.newoether.agora.ui.remote

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.remote.StagedRemoteFile
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RemoteFileViewingTest {
    @Test fun textFileOpensWithItsMimeAndOnlyReadPermission() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = File(context.cacheDir, "remote-files/test-view").apply { parentFile!!.mkdirs(); writeText("hello") }
        val staged = StagedRemoteFile("token", file, "notes.txt", "application/octet-stream", 5)
        val intent = remoteFileViewIntent(context, staged)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals("content", intent.data!!.scheme)
        assertEquals("${context.packageName}.fileprovider", intent.data!!.authority)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        assertEquals(intent.data, intent.clipData!!.getItemAt(0).uri)
        assertEquals("hello", context.contentResolver.openInputStream(intent.data!!)!!.bufferedReader().use { it.readText() })
    }
}
