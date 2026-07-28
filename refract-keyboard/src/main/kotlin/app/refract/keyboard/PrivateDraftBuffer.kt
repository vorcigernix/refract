package app.refract.keyboard

/**
 * Editable text that never touches the host application's InputConnection.
 *
 * Selection indexes are UTF-16 offsets, matching Android's EditText API.
 */
class PrivateDraftBuffer(initialText: String = "") {
    private val content = StringBuilder(initialText)

    var selectionStart: Int = content.length
        private set
    var selectionEnd: Int = content.length
        private set

    val text: String
        get() = content.toString()

    fun setSelection(
        start: Int,
        end: Int = start,
    ) {
        selectionStart = start.coerceIn(0, content.length)
        selectionEnd = end.coerceIn(0, content.length)
    }

    fun replaceSelection(replacement: String) {
        val start = minOf(selectionStart, selectionEnd)
        val end = maxOf(selectionStart, selectionEnd)
        content.replace(start, end, replacement)
        setSelection(start + replacement.length)
    }

    fun backspace() {
        val start = minOf(selectionStart, selectionEnd)
        val end = maxOf(selectionStart, selectionEnd)
        if (start != end) {
            content.delete(start, end)
            setSelection(start)
            return
        }
        if (start == 0) return

        val previousCodePoint = content.offsetByCodePoints(start, -1)
        content.delete(previousCodePoint, start)
        setSelection(previousCodePoint)
    }

    fun clear() {
        content.clear()
        setSelection(0)
    }
}
