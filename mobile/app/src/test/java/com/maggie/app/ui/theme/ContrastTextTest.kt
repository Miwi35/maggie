package com.maggie.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

// MAG-252: the text of an event bar follows the agenda's colour. Same threshold as the
// admin (MUI's getContrastText, contrast of 3 against white) so both screens agree.
class ContrastTextTest {

    @Test
    fun `light agenda colours get dark text`() {
        listOf(0xFFFDD663, 0xFFF8BBD0, 0xFFA8DAB5, 0xFFFFFFFF).forEach { argb ->
            assertEquals("#%08X".format(argb), Color.Black, readableTextOn(Color(argb)))
        }
    }

    @Test
    fun `dark agenda colours get white text`() {
        listOf(0xFF1A73E8, 0xFF7B1FA2, 0xFFC62828, 0xFF000000).forEach { argb ->
            assertEquals("#%08X".format(argb), Color.White, readableTextOn(Color(argb)))
        }
    }
}
