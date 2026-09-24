package com.newoether.agora.ui.remote

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.R
import com.newoether.agora.model.RemoteFile
import com.newoether.agora.remote.RemoteViewModel
import com.newoether.agora.ui.chat.message.LocalRemoteFileAction
import com.newoether.agora.ui.chat.message.LocalRemoteFileSaving
import com.newoether.agora.ui.chat.message.RemoteFileCardList
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RemoteFileCardTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private fun file(id: String = "file-1", name: String = "report.pdf", bytes: Long = 2048,
        source: String? = "Alice") = RemoteFile(fileId = id, deliveryId = "delivery-1",
        name = name, bytes = bytes, mime = "application/pdf", source = source)

    @Test fun cardShowsSafeMetadataAndInvokesTheSaveAction() {
        var saved: RemoteFile? = null
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalRemoteFileAction provides { f: RemoteFile -> saved = f }) {
                    RemoteFileCardList(listOf(file()))
                }
            }
        }
        compose.onNodeWithText("report.pdf").assertIsDisplayed()
        compose.onNodeWithText("2.0 KB · " + context.getString(R.string.remote_file_from, "Alice"))
            .assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.remote_file_save))
            .assertIsDisplayed().performClick()
        assertEquals("file-1", saved?.fileId)
    }

    @Test fun internalIdentifiersNeverReachTheSurface() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalRemoteFileAction provides { _: RemoteFile -> }) {
                    RemoteFileCardList(listOf(file()))
                }
            }
        }
        compose.onNodeWithText("file-1").assertDoesNotExist()
        compose.onNodeWithText("delivery-1").assertDoesNotExist()
        compose.onNodeWithText("report.pdf").assertIsDisplayed()
    }

    @Test fun inFlightSaveShowsProgressInsteadOfAnotherSave() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalRemoteFileAction provides { _: RemoteFile -> },
                    LocalRemoteFileSaving provides setOf("file-1"),
                ) {
                    RemoteFileCardList(listOf(file()))
                }
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.remote_file_save))
            .assertDoesNotExist()
    }

    @Test fun aMissingHandlerAndBlankIdRenderNoSaveControl() {
        compose.setContent {
            MaterialTheme { RemoteFileCardList(listOf(file(id = "  "))) }
        }
        compose.onNodeWithText("report.pdf").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.remote_file_save))
            .assertDoesNotExist()
    }

    @Test fun multipleCardsRenderOneRowPerFile() {
        compose.setContent {
            MaterialTheme {
                RemoteFileCardList(listOf(file(id = "a", name = "first.pdf"),
                    file(id = "b", name = "second.bin", source = null)))
            }
        }
        compose.onNodeWithText("first.pdf").assertIsDisplayed()
        compose.onNodeWithText("second.bin").assertIsDisplayed()
    }

    @Test fun pickerMountPointOffersPhotosAndFilesOnly() {
        val vm = mockk<RemoteViewModel>(relaxed = true)
        compose.setContent {
            MaterialTheme { RemoteAttachmentPicker("owner", enabled = true, vm = vm) }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.add_attachment))
            .assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.photos)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.files)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.camera)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.videos)).assertDoesNotExist()
        // The Files entry is wired to a document launcher; the click must not crash.
        compose.onNodeWithText(context.getString(R.string.files)).performClick()
    }
}
