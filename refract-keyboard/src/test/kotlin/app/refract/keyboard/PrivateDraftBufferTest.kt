package app.refract.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivateDraftBufferTest {
    @Test
    fun insertsAtCursorInsteadOfAppending() {
        val draft = PrivateDraftBuffer("helo")

        draft.setSelection(3)
        draft.replaceSelection("l")

        assertEquals("hello", draft.text)
        assertEquals(4, draft.selectionStart)
    }

    @Test
    fun replacesSelectedText() {
        val draft = PrivateDraftBuffer("hello there")

        draft.setSelection(6, 11)
        draft.replaceSelection("friend")

        assertEquals("hello friend", draft.text)
        assertEquals(12, draft.selectionStart)
    }

    @Test
    fun backspaceRemovesOneUnicodeCodePoint() {
        val draft = PrivateDraftBuffer("Hi 😊")

        draft.backspace()

        assertEquals("Hi ", draft.text)
        assertEquals(3, draft.selectionStart)
    }
}
