package com.newoether.agora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopLevelPresentationStateTest {
    @Test
    fun releaseOfOverlayReturnsToTheBaseOwner() {
        val owners = mutableListOf<TopLevelPresentation>()
        val state = TopLevelPresentationState(onOwnerChanged = owners::add)

        state.present(TopLevelPresentation.MEDIA_PREVIEW)
        assertEquals(TopLevelPresentation.MEDIA_PREVIEW, state.owner)

        assertTrue(state.release(TopLevelPresentation.MEDIA_PREVIEW))
        assertEquals(TopLevelPresentation.REMOTE, state.owner)
        assertEquals(
            listOf(
                TopLevelPresentation.REMOTE,
                TopLevelPresentation.MEDIA_PREVIEW,
                TopLevelPresentation.REMOTE,
            ),
            owners,
        )
    }

    @Test
    fun exitedBaseSurfaceDoesNotReappearAfterPreview() {
        val state = TopLevelPresentationState(TopLevelPresentation.REMOTE)
        state.present(TopLevelPresentation.MEDIA_PREVIEW)
        assertFalse(state.release(TopLevelPresentation.REMOTE))
        assertEquals(TopLevelPresentation.MEDIA_PREVIEW, state.owner)
        assertTrue(state.release(TopLevelPresentation.MEDIA_PREVIEW))
        assertEquals(TopLevelPresentation.REMOTE, state.owner)
    }

    @Test
    fun repeatedPresentationDoesNotLeaveAnInvisibleBlocker() {
        val state = TopLevelPresentationState()
        repeat(3) { state.present(TopLevelPresentation.MEDIA_PREVIEW) }
        assertTrue(state.release(TopLevelPresentation.MEDIA_PREVIEW))
        assertEquals(TopLevelPresentation.REMOTE, state.owner)
        assertFalse(state.release(TopLevelPresentation.MEDIA_PREVIEW))
    }

    @Test
    fun restoredOverlayOwnerReportsImmediately() {
        val owners = mutableListOf<TopLevelPresentation>()
        val state = TopLevelPresentationState(TopLevelPresentation.MEDIA_PREVIEW, owners::add)

        assertEquals(TopLevelPresentation.MEDIA_PREVIEW, state.owner)
        assertEquals(listOf(TopLevelPresentation.MEDIA_PREVIEW), owners)
    }

    @Test
    fun baseOwnerCannotBePresentedAsAnOverlay() {
        val state = TopLevelPresentationState()

        var thrown = false
        try {
            state.present(TopLevelPresentation.REMOTE)
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
        assertEquals(TopLevelPresentation.REMOTE, state.owner)
    }

    @Test
    fun staleExitCannotReleaseAnAlreadyReleasedPresentation() {
        val owners = mutableListOf<TopLevelPresentation>()
        val state = TopLevelPresentationState(onOwnerChanged = owners::add)
        state.present(TopLevelPresentation.MEDIA_PREVIEW)

        assertTrue(state.release(TopLevelPresentation.MEDIA_PREVIEW))
        assertFalse(state.release(TopLevelPresentation.MEDIA_PREVIEW))
        assertEquals(TopLevelPresentation.REMOTE, state.owner)
        assertEquals(
            listOf(
                TopLevelPresentation.REMOTE,
                TopLevelPresentation.MEDIA_PREVIEW,
                TopLevelPresentation.REMOTE,
            ),
            owners,
        )
    }
}
