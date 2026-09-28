package com.newoether.agora.ui.theme

import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

class UserMessageTypographyTest {
    @Test
    fun `user message body matches the Fairy body at 16sp with a 26sp line`() {
        assertEquals(16.sp, ChatType.userBody.fontSize)
        assertEquals(26.sp, ChatType.userBody.lineHeight)
        assertEquals(androidx.compose.ui.text.font.FontWeight.Normal, ChatType.userBody.fontWeight)
    }
}
