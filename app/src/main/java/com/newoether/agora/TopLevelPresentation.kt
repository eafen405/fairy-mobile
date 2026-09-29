package com.newoether.agora

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class TopLevelPresentation {
    REMOTE,
    MEDIA_PREVIEW,
}

/** Single main-thread owner for the top-level surface currently covering the remote shell. */
@Stable
internal class TopLevelPresentationState(
    private val baseOwner: TopLevelPresentation = TopLevelPresentation.REMOTE,
    private val onOwnerChanged: (TopLevelPresentation) -> Unit = {},
) {
    private val presentations = mutableListOf(baseOwner)
    var owner by mutableStateOf(baseOwner)
        private set

    init {
        onOwnerChanged(owner)
    }

    fun present(presentation: TopLevelPresentation) {
        require(presentation != baseOwner)
        presentations.remove(presentation)
        presentations.add(presentation)
        owner = presentation
        onOwnerChanged(owner)
    }

    /** A stale exiting surface cannot return ownership after a newer surface was presented. */
    fun release(presentation: TopLevelPresentation): Boolean {
        // An underlying surface may finish exiting while a preview still covers it.
        presentations.remove(presentation)
        if (owner != presentation) return false
        owner = presentations.lastOrNull() ?: baseOwner
        onOwnerChanged(owner)
        return true
    }
}
