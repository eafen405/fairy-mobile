package com.newoether.agora.speech

import android.app.Application
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.provider.Settings
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SpeechRecognitionEngineTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun clearDefault() {
        Settings.Secure.putString(context.contentResolver, "voice_recognition_service", null)
    }

    private fun provider(
        name: String,
        enabled: Boolean = true,
        exported: Boolean = true,
        permission: String? = "android.permission.BIND_RECOGNITION_SERVICE",
    ) = ResolveInfo().apply {
        serviceInfo = ServiceInfo().apply {
            packageName = "test.provider"
            this.name = name
            this.enabled = enabled
            this.exported = exported
            this.permission = permission
            applicationInfo = ApplicationInfo().apply { this.enabled = true }
        }
    }

    @Test fun validSystemDefaultWinsOverFirstProvider() {
        val preferred = ComponentName("test.provider", "PreferredService")
        Settings.Secure.putString(
            context.contentResolver, "voice_recognition_service", preferred.flattenToString(),
        )
        assertEquals(
            preferred,
            selectRecognitionService(context, listOf(provider("OtherService"), provider("PreferredService"))),
        )
    }

    @Test fun invalidDefaultFallsBackToEnabledExportedProvider() {
        Settings.Secure.putString(
            context.contentResolver, "voice_recognition_service", "test.provider/DisabledService",
        )
        assertEquals(
            ComponentName("test.provider", "WorkingService"),
            selectRecognitionService(
                context,
                listOf(provider("DisabledService", enabled = false), provider("WorkingService")),
            ),
        )
    }

    @Test fun missingDefaultFallsBackButNoValidProviderReturnsUnavailable() {
        assertEquals(
            ComponentName("test.provider", "WorkingService"),
            selectRecognitionService(context, listOf(provider("WorkingService"))),
        )
        assertNull(selectRecognitionService(context, emptyList()))
        assertNull(selectRecognitionService(context, listOf(
            provider("PrivateService", exported = false),
            provider("UnprotectedService", permission = null),
        )))
    }

    @Test fun platformErrorsKeepAudioClientAndNetworkDistinct() {
        assertEquals(SpeechInputFailure.AUDIO, SpeechRecognizer.ERROR_AUDIO.toSpeechError())
        assertEquals(SpeechInputFailure.CLIENT, SpeechRecognizer.ERROR_CLIENT.toSpeechError())
        assertEquals(SpeechInputFailure.NETWORK, SpeechRecognizer.ERROR_NETWORK.toSpeechError())
        assertEquals(SpeechInputFailure.PERMISSION,
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS.toSpeechError())
    }
}
