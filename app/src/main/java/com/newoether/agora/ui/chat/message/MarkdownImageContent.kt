package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.elements.MarkdownImage
import com.mikepenz.markdown.compose.elements.MarkdownInlineImage

/**
 * Display-math images widened beyond the text column scroll horizontally instead
 * of squeezing. Remote answers never carry decodable image attachments, so the
 * default Markdown image element is the only renderer needed here.
 */
@Composable
internal fun ScrollableDisplayLatexImage(model: MarkdownComponentModel) {
    if (!isScrollableDisplayLatexImage(model.content, model.node)) {
        MarkdownImage(model.content, model.node)
        return
    }

    val horizontalScrollState = rememberScrollState()
    TrackStreamingHorizontalScroll(horizontalScrollState)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(horizontalScrollState),
    ) {
        MarkdownImage(model.content, model.node)
    }
}

@Composable
internal fun ChatMarkdownInlineImage(model: MarkdownComponentModel) {
    MarkdownInlineImage(model.content, model.node)
}
