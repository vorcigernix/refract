package app.refract.keyboard.protocol

/**
 * Broad Phase 0 mask for Gemma 4 E2B.
 *
 * The four base control IDs and canonical chat/tool/media control tokens are withheld while the
 * frame is embedded. This is not yet a messaging-app transport policy; callers must re-tokenize
 * and authenticate the completed carrier before accepting it.
 */
object GemmaTransportSafeTokens {
    const val VOCAB_SIZE = 262_144

    fun build(
        tokenize: (String) -> IntArray,
        detokenize: (IntArray) -> String,
    ): TransportTokenMasks =
        buildTransportMasks(
            vocabSize = VOCAB_SIZE,
            initiallyAllowedTokenIds = 0 until VOCAB_SIZE,
            // <pad>, <eos>, <bos>, and <unk>.
            alwaysExcludedTokenIds = intArrayOf(0, 1, 2, 3),
            canonicalExclusions = GEMMA_CONTROL_TOKENS + STAGE_DIRECTION_DELIMITERS,
            tokenize = tokenize,
            detokenize = detokenize,
            requireCanonicalIndividualTokens = true,
        )

    private val GEMMA_CONTROL_TOKENS =
        listOf(
            "<turn|>",
            "<|turn>",
            "<channel|>",
            "<|channel>",
            "<tool_call|>",
            "<|tool_call>",
            "<tool|>",
            "<|tool>",
            "<tool_response|>",
            "<|tool_response>",
            "<image|>",
            "<|image>",
            "<audio|>",
            "<|audio>",
            "<|video|>",
            "<mask>",
            "<|think|>",
        )
}
