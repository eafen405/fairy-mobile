package com.newoether.agora.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.ui.components.FairyWindowState
import com.newoether.agora.ui.components.FairyWindowTopBar

/**
 * The remote conversation's top bar. Remote always draws Fairy's window bar;
 * all behavior is routed through callbacks.
 */
@Composable
internal fun ChatTopBar(
    subtitle: String? = null,
    subtitleLeading: (@Composable () -> Unit)? = null,
    searchActive: Boolean = false,
    searchQuery: String = "",
    searchMatchIndex: Int = -1,
    searchMatchCount: Int = 0,
    onNavigateBack: () -> Unit,
    onSearchQueryChange: (String) -> Unit = {},
    onSearchPrevious: () -> Unit = {},
    onSearchNext: () -> Unit = {},
    onSearchDismiss: () -> Unit = {},
    moreMenuContent: (@Composable ColumnScope.(dismiss: () -> Unit) -> Unit)? = null,
    fairyWindow: FairyWindowState,
) {
    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(searchActive) {
        if (searchActive) {
            // Let the window bar commit the search field before asking the IME for focus.
            // Requesting focus on the state-change frame makes the keyboard and enter
            // transition compete for the first layout and produces a visible flash.
            withFrameNanos { }
            searchFocusRequester.requestFocus()
        }
    }
    FairyWindowTopBar(
        window = fairyWindow,
        subtitle = subtitle,
        subtitleLeading = subtitleLeading,
        searchActive = searchActive,
        onNavigateBack = onNavigateBack,
        moreMenuContent = moreMenuContent,
        searchContent = {
            ChatTopBarSearchRow(
                searchQuery = searchQuery,
                searchMatchIndex = searchMatchIndex,
                searchMatchCount = searchMatchCount,
                searchFocusRequester = searchFocusRequester,
                onSearchQueryChange = onSearchQueryChange,
                onSearchPrevious = onSearchPrevious,
                onSearchNext = onSearchNext,
                onSearchDismiss = onSearchDismiss,
            )
        },
    )
}

@Composable
private fun ChatTopBarSearchRow(
    searchQuery: String,
    searchMatchIndex: Int,
    searchMatchCount: Int,
    searchFocusRequester: FocusRequester,
    onSearchQueryChange: (String) -> Unit,
    onSearchPrevious: () -> Unit,
    onSearchNext: () -> Unit,
    onSearchDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(5.dp))
        IconButton(
            onClick = onSearchDismiss,
            modifier = Modifier.size(44.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back),
                modifier = Modifier.size(24.dp),
            )
        }
        BasicTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Search,
            ),
            modifier = Modifier
                .weight(1f)
                .focusRequester(searchFocusRequester),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            stringResource(R.string.conversation_search_hint),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                .copy(alpha = 0.6f),
                            maxLines = 1,
                        )
                    }
                    inner()
                }
            },
        )
        Text(
            text = if (searchMatchCount == 0) {
                "0/0"
            } else {
                "${searchMatchIndex + 1}/$searchMatchCount"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        IconButton(
            enabled = searchMatchIndex > 0,
            onClick = onSearchPrevious,
            modifier = Modifier.size(38.dp),
        ) {
            Icon(
                Icons.Default.KeyboardArrowUp,
                contentDescription = null,
            )
        }
        IconButton(
            enabled = searchMatchIndex >= 0 &&
                searchMatchIndex < searchMatchCount - 1,
            onClick = onSearchNext,
            modifier = Modifier.size(38.dp),
        ) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
            )
        }
        Spacer(Modifier.width(5.dp))
    }
}
