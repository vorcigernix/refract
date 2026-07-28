package app.refract.keyboard.protocol

import java.text.Normalizer

data class TransportTokenMasks(
    val safeTokens: ByteArray,
    val postFrameTokens: ByteArray,
    val safeTokenIds: IntArray,
)

internal fun buildTransportMasks(
    vocabSize: Int,
    initiallyAllowedTokenIds: IntRange,
    alwaysExcludedTokenIds: IntArray,
    canonicalExclusions: List<String>,
    tokenize: (String) -> IntArray,
    detokenize: (IntArray) -> String,
    requireCanonicalIndividualTokens: Boolean = false,
): TransportTokenMasks {
    val safeTokens = ByteArray(vocabSize)
    initiallyAllowedTokenIds.forEach { tokenId ->
        if (tokenId in safeTokens.indices) safeTokens[tokenId] = 1
    }
    alwaysExcludedTokenIds.forEach { tokenId ->
        if (tokenId in safeTokens.indices) safeTokens[tokenId] = 0
    }
    if (requireCanonicalIndividualTokens) {
        safeTokens.forEachIndexed { tokenId, isSafe ->
            if (isSafe.toInt() == 0) return@forEachIndexed
            val tokenIds = intArrayOf(tokenId)
            val decoded = runCatching { detokenize(tokenIds) }.getOrNull()
            val isCanonical =
                decoded != null &&
                    decoded.isNotEmpty() &&
                    isTransportSafeText(decoded) &&
                    runCatching { tokenize(decoded).contentEquals(tokenIds) }.getOrDefault(false)
            if (!isCanonical) safeTokens[tokenId] = 0
        }
    }
    canonicalExclusions.forEach { excludedText ->
        val tokenIds = tokenize(excludedText)
        if (
            tokenIds.size == 1 &&
                tokenIds.single() in safeTokens.indices &&
                detokenize(tokenIds) == excludedText
        ) {
            safeTokens[tokenIds.single()] = 0
        }
    }

    val safeTokenIds =
        IntArray(safeTokens.count { it.toInt() != 0 }).also { result ->
            var resultIndex = 0
            safeTokens.forEachIndexed { tokenId, isSafe ->
                if (isSafe.toInt() != 0) {
                    result[resultIndex++] = tokenId
                }
            }
        }
    return TransportTokenMasks(
        safeTokens = safeTokens,
        // Once the authenticated frame is complete, let the model finish naturally, including EOS.
        postFrameTokens = ByteArray(vocabSize) { 1 },
        safeTokenIds = safeTokenIds,
    )
}

private fun isTransportSafeText(text: String): Boolean {
    if (!Normalizer.isNormalized(text, Normalizer.Form.NFC)) return false

    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        if (Character.isWhitespace(codePoint) && codePoint != ASCII_SPACE) return false
        if (Character.isISOControl(codePoint)) return false
        when (Character.getType(codePoint)) {
            Character.FORMAT.toInt(),
            Character.PRIVATE_USE.toInt(),
            Character.SURROGATE.toInt(),
            Character.UNASSIGNED.toInt(),
            Character.LINE_SEPARATOR.toInt(),
            Character.PARAGRAPH_SEPARATOR.toInt(),
            -> return false
        }
        index += Character.charCount(codePoint)
    }
    return true
}

private const val ASCII_SPACE = 0x20

internal val STAGE_DIRECTION_DELIMITERS =
    listOf("(", " (", ")", ") ", "*", " *", "[", " [", "]", "] ")
