package com.newoether.agora.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.theme.LocalFairyTokens

/**
 * Fairy panel surface: [color] (default panel) inside [shape] with a 1 dp
 * hairline outline. Children are not clipped by this modifier.
 */
@Composable
fun Modifier.fairyPanel(
    shape: Shape,
    color: Color? = null,
): Modifier {
    val tokens = LocalFairyTokens.current
    return this
        .background(color ?: tokens.panel, shape)
        .border(1.dp, tokens.hairline, shape)
}
