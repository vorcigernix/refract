package app.refract.keyboard

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import app.refract.keyboard.protocol.GemmaTransportSafeTokens
import app.refract.keyboard.protocol.SecureBucketCarrierCodec
import app.refract.keyboard.protocol.TransportTokenMasks
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.StegoBucketConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Owns the long-lived Gemma engine used by the keyboard process. */
class CarrierModel(context: Context) {
    private val appContext = context.applicationContext

    enum class ModelStatus {
        IDLE,
        NOT_PROVISIONED,
        LOADING,
        READY,
        ERROR
    }

    data class State(
        val status: ModelStatus = ModelStatus.IDLE,
        val message: String? = null,
    )

    interface ModelStatusListener {
        fun onStatusChanged(status: ModelStatus, message: String? = null)
    }

    interface GenerationListener {
        fun onProgress(message: String)

        fun onCarrierUpdate(carrier: String)

        fun onSuccess(result: GenerationResult)

        fun onError(error: Throwable)
    }

    class GenerationResult internal constructor(
        val carrier: String,
        val embeddedTokens: Int,
        val carrierTokens: Int,
        val elapsedSeconds: Double,
        internal val owner: CarrierModel,
        internal val session: ConversationRepository.Session,
    )

    private val worker = Executors.newSingleThreadExecutor()
    private val generating = AtomicBoolean(false)
    private val preloadQueued = AtomicBoolean(false)
    private val reloadAfterGeneration = AtomicBoolean(false)
    private val pendingGeneration = AtomicReference<GenerationResult?>(null)

    private val _modelState = MutableStateFlow(State())
    val modelState: StateFlow<State> = _modelState.asStateFlow()

    val modelStatus: ModelStatus
        get() = _modelState.value.status

    val lastStatusMessage: String?
        get() = _modelState.value.message

    @Volatile
    private var statusListener: ModelStatusListener? = null

    private val preferences = KeyboardPreferences(appContext)
    private val conversationRepository = ConversationRepository(appContext)
    private var engine: Engine? = null
    private var transportMasks: TransportTokenMasks? = null
    private var activeBackend: KeyboardPreferences.RuntimeBackend? = null
    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                KeyboardPreferences.KEY_RUNTIME_BACKEND,
                KeyboardPreferences.KEY_MODEL_REVISION,
                -> requestRuntimeReload()
                KeyboardPreferences.KEY_PRELOAD_MODEL -> {
                    if (preferences.preloadModel) {
                        preloadModelAsync()
                    } else {
                        requestRuntimeReload()
                    }
                }
            }
        }

    init {
        preferences.registerListener(preferenceListener)
        if (preferences.preloadModel) preloadModelAsync()
    }

    fun setStatusListener(listener: ModelStatusListener?) {
        this.statusListener = listener
        listener?.onStatusChanged(modelStatus, lastStatusMessage)
    }

    fun preloadModelAsync() {
        val modelFile = ensureModelFile()
        if (modelFile == null) {
            updateStatus(
                ModelStatus.NOT_PROVISIONED,
                "Gemma model is not found in internal storage or assets.",
            )
            return
        }
        if (generating.get()) {
            reloadAfterGeneration.set(true)
            return
        }

        val requestedBackend = preferences.runtimeBackend
        if (
            engine != null &&
                activeBackend == requestedBackend &&
                transportMasks != null
        ) {
            updateStatus(
                ModelStatus.READY,
                "Gemma ready on ${requestedBackend.displayName}",
            )
            return
        }
        if (!preloadQueued.compareAndSet(false, true)) return

        updateStatus(
            ModelStatus.LOADING,
            "Pre-loading Gemma on ${requestedBackend.displayName}…",
        )
        worker.execute {
            runCatching {
                val nextEngine = ensureEngine(requestedBackend)

                if (transportMasks == null) {
                    transportMasks =
                        GemmaTransportSafeTokens.build(
                            tokenize = nextEngine::tokenize,
                            detokenize = nextEngine::detokenize,
                        )
                }
                updateStatus(
                    ModelStatus.READY,
                    "Gemma ready on ${requestedBackend.displayName}",
                )
            }
                .onFailure { error ->
                    Log.e(TAG, "Failed to preload Gemma model", error)
                    updateStatus(
                        ModelStatus.ERROR,
                        "Failed to load model: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
            preloadQueued.set(false)
            if (
                preferences.preloadModel &&
                    preferences.runtimeBackend != requestedBackend
            ) {
                preloadModelAsync()
            }
        }
    }

    private fun updateStatus(status: ModelStatus, message: String? = null) {
        _modelState.value = State(status, message)
        Log.i(TAG, "model_status=$status message=$message")
        statusListener?.onStatusChanged(status, message)
    }

    fun generateSecureCarrier(
        privateMessage: String,
        listener: GenerationListener,
    ) {
        if (!generating.compareAndSet(false, true)) {
            listener.onError(IllegalStateException("Another carrier is already being generated."))
            return
        }

        val stage = AtomicReference("request_validation")
        worker.execute {
            runCatching {
                    val plaintext = privateMessage.toByteArray(Charsets.UTF_8)
                    require(plaintext.isNotEmpty()) { "Type a private message first." }
                    require(plaintext.size <= SecureBucketCarrierCodec.MAX_PLAINTEXT_BYTES) {
                        "This secure-frame prototype accepts at most " +
                            "${SecureBucketCarrierCodec.MAX_PLAINTEXT_BYTES} UTF-8 bytes."
                    }
                    val session =
                        conversationRepository.openActiveSession()
                            ?: throw IllegalStateException(
                                "Pair and select a conversation in settings first."
                            )
                    var codec: SecureBucketCarrierCodec? = null
                    try {
                        val activeCodec =
                            SecureBucketCarrierCodec(
                                key = session.key,
                                bitsPerToken = BITS_PER_TOKEN,
                                conversation = session.conversationId,
                                direction = session.localSender,
                                sequence = session.sendSequence,
                                previousHash = session.sendPreviousHash,
                            )
                        codec = activeCodec

                        val activeEngine = ensureRuntime(listener, stage)
                        reportProgress(
                            listener,
                            stage,
                            "vocabulary_preparation",
                            "Preparing Gemma’s transport-safe vocabulary…",
                        )
                        val masks =
                            transportMasks
                                ?: GemmaTransportSafeTokens.build(
                                        tokenize = activeEngine::tokenize,
                                        detokenize = activeEngine::detokenize,
                                    )
                                    .also { transportMasks = it }
                        updateStatus(
                            ModelStatus.READY,
                            "Gemma ready on ${preferences.runtimeBackend.displayName}",
                        )
                        val requiredBuckets = activeCodec.requiredBuckets(plaintext)
                        requiredBuckets.forEachIndexed { position, desiredBucket ->
                            require(
                                masks.safeTokenIds.any { tokenId ->
                                    activeCodec.buckets.bucket(position.toLong(), tokenId) ==
                                        desiredBucket
                                }
                            ) {
                                "No safe Gemma token covers bucket $desiredBucket at position $position."
                            }
                        }

                        listener.onProgress(
                            "Generating a ${requiredBuckets.size}-token encrypted carrier…"
                        )
                        stage.set("generation")
                        Log.i(
                            TAG,
                            "generation_stage=generation buckets=${requiredBuckets.size}",
                        )
                        generate(
                            activeEngine = activeEngine,
                            masks = masks,
                            codec = activeCodec,
                            session = session,
                            plaintext = plaintext,
                            requiredBuckets = requiredBuckets,
                            listener = listener,
                            stage = stage,
                        )
                    } catch (error: Throwable) {
                        codec?.close()
                        session.close()
                        throw error
                    }
                }
                .onFailure {
                    generating.set(false)
                    reportFailure(listener, stage.get(), it)
                    runPendingReload()
                }
        }
    }

    private fun ensureModelFile(): File? {
        val destination = File(appContext.filesDir, "models/$GEMMA_MODEL_FILE_NAME")
        if (destination.isFile && destination.length() > 0) {
            return destination
        }

        val assetPaths = listOf("models/$GEMMA_MODEL_FILE_NAME", GEMMA_MODEL_FILE_NAME)
        val assetManager = appContext.assets
        val foundAsset = assetPaths.firstOrNull { assetPath ->
            runCatching {
                assetManager.open(assetPath).use { }
                true
            }.getOrDefault(false)
        }

        if (foundAsset != null) {
            Log.i(TAG, "Extracting bundled Gemma model from assets: $foundAsset")
            destination.parentFile?.mkdirs()
            val tempFile = File(destination.parentFile, "$GEMMA_MODEL_FILE_NAME.extracting")
            tempFile.delete()
            try {
                assetManager.open(foundAsset).use { input ->
                    tempFile.outputStream().buffered().use { output ->
                        input.copyTo(output)
                    }
                }
                if (tempFile.renameTo(destination)) {
                    Log.i(TAG, "Successfully extracted bundled model to ${destination.absolutePath}")
                    return destination
                }
            } catch (error: Throwable) {
                tempFile.delete()
                Log.e(TAG, "Failed to extract bundled Gemma model", error)
            }
        }
        return if (destination.isFile) destination else null
    }

    private fun ensureRuntime(
        listener: GenerationListener,
        stage: AtomicReference<String>,
    ): Engine {
        val requestedBackend = preferences.runtimeBackend
        engine?.takeIf { activeBackend == requestedBackend }?.let {
            Log.i(TAG, "generation_stage=runtime_ready cached=true")
            return it
        }
        val modelFile = ensureModelFile()
        require(modelFile != null && modelFile.isFile) {
            "Gemma model is not provisioned or extracted."
        }
        reportProgress(
            listener,
            stage,
            "model_loading",
            "Loading Gemma on ${requestedBackend.displayName} for the first message…",
        )
        val nextEngine = ensureEngine(requestedBackend)
        Log.i(TAG, "generation_stage=runtime_ready cached=false")
        return nextEngine
    }

    private fun ensureEngine(requestedBackend: KeyboardPreferences.RuntimeBackend): Engine {
        engine?.takeIf { activeBackend == requestedBackend }?.let { return it }
        closeRuntime()
        val modelFile = ensureModelFile()
        require(modelFile != null && modelFile.isFile) {
            "Gemma model is not provisioned or extracted."
        }
        val nextEngine =
            Engine(
                EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend =
                        when (requestedBackend) {
                            KeyboardPreferences.RuntimeBackend.GPU -> Backend.GPU()
                            KeyboardPreferences.RuntimeBackend.CPU -> Backend.CPU()
                        },
                    maxNumTokens = MAX_NUM_TOKENS,
                    cacheDir =
                        if (requestedBackend == KeyboardPreferences.RuntimeBackend.CPU) {
                            File(appContext.cacheDir, "litertlm-cpu").absolutePath
                        } else {
                            null
                        },
                )
            )
        nextEngine.initialize()
        engine = nextEngine
        activeBackend = requestedBackend
        return nextEngine
    }

    private fun requestRuntimeReload() {
        if (generating.get()) {
            reloadAfterGeneration.set(true)
            return
        }
        worker.execute {
            closeRuntime()
            updateStatus(ModelStatus.IDLE, "Runtime setting changed")
            if (preferences.preloadModel) preloadModelAsync()
        }
    }

    private fun runPendingReload() {
        if (reloadAfterGeneration.compareAndSet(true, false)) {
            requestRuntimeReload()
        }
    }

    /**
     * Advances the sender chain only after the IME has staged the validated
     * carrier in the host editor.
     */
    fun commitGeneratedCarrier(result: GenerationResult): Boolean {
        val committed =
            finalizeGeneratedCarrier(result) {
                conversationRepository.commitSent(result.session, result.carrier)
            } ?: return false
        if (committed) {
            Log.i(
                TAG,
                "generation_stage=complete embedded_tokens=${result.embeddedTokens} " +
                    "carrier_tokens=${result.carrierTokens}",
            )
        } else {
            Log.e(TAG, "generation_stage=insertion_commit conversation_state_changed=true")
        }
        return committed
    }

    /** Releases a validated carrier which could not be inserted by the IME. */
    fun discardGeneratedCarrier(result: GenerationResult) {
        if (finalizeGeneratedCarrier(result) { true } != null) {
            Log.i(TAG, "generation_stage=discarded_before_insertion")
        }
    }

    private fun finalizeGeneratedCarrier(
        result: GenerationResult,
        finalize: () -> Boolean,
    ): Boolean? {
        if (result.owner !== this || !pendingGeneration.compareAndSet(result, null)) {
            return null
        }
        return try {
            finalize()
        } catch (error: Throwable) {
            Log.e(TAG, "Could not finalize the generated carrier", error)
            false
        } finally {
            result.session.close()
            generating.set(false)
            if (!worker.isShutdown) {
                worker.execute(::runPendingReload)
            }
        }
    }

    private fun closeRuntime() {
        engine?.let { activeEngine ->
            if (activeEngine.isInitialized()) activeEngine.close()
        }
        engine = null
        transportMasks = null
        activeBackend = null
    }

    private fun generate(
        activeEngine: Engine,
        masks: TransportTokenMasks,
        codec: SecureBucketCarrierCodec,
        session: ConversationRepository.Session,
        plaintext: ByteArray,
        requiredBuckets: IntArray,
        listener: GenerationListener,
        stage: AtomicReference<String>,
    ) {
        val conversation = createConversation(activeEngine)
        val response = StringBuilder()
        val finished = AtomicBoolean(false)
        val startedAt = System.nanoTime()
        val refractConfig =
            StegoBucketConfig(
                bucketKey = codec.bucketKey.copyOf(),
                bitsPerToken = BITS_PER_TOKEN,
                requiredBuckets =
                    ByteArray(requiredBuckets.size) { requiredBuckets[it].toByte() },
                safeTokens = masks.safeTokens,
                finishTokens = masks.postFrameTokens,
            )

        fun finish(error: Throwable? = null) {
            if (!finished.compareAndSet(false, true)) return
            runCatching(conversation::close)
            if (error != null) {
                try {
                    generating.set(false)
                    reportFailure(listener, stage.get(), error)
                    runPendingReload()
                } finally {
                    codec.close()
                    session.close()
                }
                return
            }
            stage.set("carrier_validation")
            Log.i(TAG, "generation_stage=carrier_validation")
            var handedOffForInsertion = false
            var generatedResult: GenerationResult? = null
            runCatching {
                    val carrier = response.toString()
                    require(carrier.isNotBlank()) { "Gemma returned an empty carrier." }
                    require(!STAGE_DIRECTION.containsMatchIn(carrier)) {
                        "Carrier contains a role-play stage direction."
                    }
                    val tokenIds = activeEngine.tokenize(carrier)
                    require(activeEngine.detokenize(tokenIds) == carrier) {
                        "The completed carrier changed during tokenizer round-trip."
                    }
                    val recovered = codec.decodeTokenIds(tokenIds)
                    if (!recovered.contentEquals(plaintext)) {
                        recovered.fill(0)
                        throw SecurityException("Recovered private message does not match.")
                    }
                    recovered.fill(0)
                    val result =
                        GenerationResult(
                            carrier = carrier,
                            embeddedTokens = requiredBuckets.size,
                            carrierTokens = tokenIds.size,
                            elapsedSeconds =
                                (System.nanoTime() - startedAt) / 1_000_000_000.0,
                            owner = this,
                            session = session,
                        )
                    generatedResult = result
                    check(pendingGeneration.compareAndSet(null, result)) {
                        "Another validated carrier is still awaiting insertion."
                    }
                    listener.onSuccess(result)
                    handedOffForInsertion = true
                    Log.i(
                        TAG,
                        "generation_stage=awaiting_insertion embedded_tokens=${requiredBuckets.size} " +
                            "carrier_tokens=${tokenIds.size}",
                    )
                }
                .onFailure { error ->
                    generatedResult?.let { pendingGeneration.compareAndSet(it, null) }
                    generating.set(false)
                    reportFailure(listener, stage.get(), error)
                    runPendingReload()
                }
            try {
                codec.close()
            } finally {
                if (!handedOffForInsertion) {
                    session.close()
                }
            }
        }

        runCatching {
                conversation.sendStegoMessageAsync(
                    Contents.of(carrierPrompt(requiredBuckets.size)),
                    refractConfig,
                    object : MessageCallback {
                        override fun onMessage(message: Message) {
                            response.append(message.toString())
                            listener.onCarrierUpdate(response.toString())
                        }

                        override fun onDone() {
                            worker.execute { finish() }
                        }

                        override fun onError(throwable: Throwable) {
                            worker.execute { finish(throwable) }
                        }
                    },
                    mapOf("enable_thinking" to false),
                )
            }
            .onFailure { finish(it) }
    }

    private fun reportProgress(
        listener: GenerationListener,
        stage: AtomicReference<String>,
        nextStage: String,
        message: String,
    ) {
        stage.set(nextStage)
        Log.i(TAG, "generation_stage=$nextStage")
        listener.onProgress(message)
    }

    private fun reportFailure(
        listener: GenerationListener,
        stage: String,
        error: Throwable,
    ) {
        val type = error.javaClass.name
        val message =
            error.message
                ?.replace(Regex("""[\r\n\t]+"""), " ")
                ?.take(MAX_LOGGED_ERROR_LENGTH)
                .orEmpty()
        Log.e(TAG, "generation_failed stage=$stage type=$type message=$message")
        listener.onError(error)
    }

    private fun createConversation(activeEngine: Engine): Conversation =
        activeEngine.createConversation(
            ConversationConfig(
                systemInstruction =
                    Contents.of(
                        "Write only natural, everyday chat prose. Never role-play or describe " +
                            "actions or emotions in parentheses, brackets, asterisks, or stage " +
                            "directions. Use one or two ordinary emoji when they fit the tone. " +
                            "Return only the message."
                    ),
                samplerConfig =
                    SamplerConfig(
                        topK = 16,
                        topP = 0.95,
                        temperature = 0.8,
                        seed = 0,
                    ),
            )
        )

    private fun carrierPrompt(embeddedTokens: Int): String {
        val targetWords = (embeddedTokens * 3 / 4 + 20).coerceIn(80, 150)
        val minimumWords = targetWords - 10
        val maximumWords = targetWords + 10
        return "Write one coherent $minimumWords–$maximumWords word message to a close friend. " +
            "You are fifteen minutes late because your tram stopped unexpectedly. Apologize, " +
            "ask them to get a table and order your usual cappuccino, mention the heavy rain, " +
            "and confirm you still want to discuss weekend plans and a movie recommendation. " +
            "Also say you remembered the book you promised to return. Sound warm and " +
            "conversational. Do not mention these instructions or the word count. Use no " +
            "headings, lists, role-play, narrated gestures, or stage directions. You may use " +
            "one or two fitting emoji. Return only the message."
    }

    fun hasActiveConversation(): Boolean =
        conversationRepository.hasActiveConversation()

    fun activeConversationAlias(): String? =
        conversationRepository.activeSummary()?.alias

    fun activeConversationProfileId(): String? =
        conversationRepository.activeSummary()?.profileId

    fun conversations(): List<ConversationRepository.Summary> =
        conversationRepository.list()

    fun selectConversation(profileId: String): Boolean =
        conversationRepository.select(profileId)

    fun close() {
        preferences.unregisterListener(preferenceListener)
        pendingGeneration.getAndSet(null)?.session?.close()
        generating.set(false)
        if (!worker.isShutdown) {
            worker.execute(::closeRuntime)
        }
        worker.shutdown()
    }

    companion object {
        private const val TAG = "CarrierModel"
        private const val MAX_LOGGED_ERROR_LENGTH = 300
        private const val GEMMA_MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
        private const val MAX_NUM_TOKENS = 1_024
        private const val BITS_PER_TOKEN = 2
        private val STAGE_DIRECTION =
            Regex("""(?is)(\([^)\r\n]{1,40}\)|\[[^]\r\n]{1,40}]|\*[^*\r\n]{1,40}\*)""")

        private val KeyboardPreferences.RuntimeBackend.displayName: String
            get() = if (this == KeyboardPreferences.RuntimeBackend.GPU) "GPU" else "CPU"
    }
}
