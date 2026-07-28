package app.refract.keyboard

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import app.refract.keyboard.protocol.PairingEstablishedSession
import app.refract.keyboard.protocol.PairingInitiatorSession
import app.refract.keyboard.protocol.PairingInvite
import app.refract.keyboard.protocol.PairingResponderSession
import com.android.inputmethod.latin.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File

internal const val ACTION_START_PAIRING = "app.refract.keyboard.action.START_PAIRING"

/**
 * Focused Refract configuration, independent of the LatinIME settings UI.
 *
 * Uses Material 3 components and activity result contracts.
 */
class RefractSettingsActivity : AppCompatActivity() {
    private lateinit var repository: ConversationRepository
    private lateinit var preferences: KeyboardPreferences
    private lateinit var scanner: GmsBarcodeScanner

    private lateinit var conversationsView: LinearLayout
    private lateinit var keyboardStatus: MaterialTextView
    private lateinit var modelStatus: MaterialTextView
    private lateinit var operationStatus: MaterialTextView
    private lateinit var preloadModel: MaterialCheckBox
    private lateinit var backend: RadioGroup

    private var pendingInitiator: PairingInitiatorSession? = null
    private var pendingResponder: PairingResponderSession? = null
    private var finishAfterPairing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        title = getString(R.string.refract_app_name)
        setContentView(R.layout.refract_settings_activity)

        repository = ConversationRepository(this)
        preferences = KeyboardPreferences(this)
        scanner =
            GmsBarcodeScanning.getClient(
                this,
                GmsBarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .enableAutoZoom()
                    .build(),
            )

        conversationsView = findViewById(R.id.refract_conversations)
        keyboardStatus = findViewById(R.id.refract_keyboard_status)
        modelStatus = findViewById(R.id.refract_model_status)
        operationStatus = findViewById(R.id.refract_operation_status)
        preloadModel = findViewById(R.id.refract_preload_model)
        backend = findViewById(R.id.refract_backend)

        findViewById<MaterialButton>(R.id.refract_enable_keyboard).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<MaterialButton>(R.id.refract_choose_keyboard).setOnClickListener {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }
        findViewById<MaterialButton>(R.id.refract_start_pairing).setOnClickListener {
            runCatching { startPairing(pairingName()) }
                .onFailure(::showError)
        }
        findViewById<MaterialButton>(R.id.refract_join_pairing).setOnClickListener {
            scanCode { encoded ->
                val invitation = PairingInvite.decode(encoded)
                respondToInvitation(invitation, pairingName())
            }
        }

        preloadModel.isChecked = preferences.preloadModel
        preloadModel.setOnCheckedChangeListener { _, checked ->
            preferences.preloadModel = checked
        }
        when (preferences.runtimeBackend) {
            KeyboardPreferences.RuntimeBackend.GPU ->
                backend.check(R.id.refract_backend_gpu)
            KeyboardPreferences.RuntimeBackend.CPU ->
                backend.check(R.id.refract_backend_cpu)
        }
        backend.setOnCheckedChangeListener { _, checkedId ->
            preferences.runtimeBackend =
                if (checkedId == R.id.refract_backend_cpu) {
                    KeyboardPreferences.RuntimeBackend.CPU
                } else {
                    KeyboardPreferences.RuntimeBackend.GPU
                }
        }

        if (savedInstanceState == null && intent.action == ACTION_START_PAIRING) {
            finishAfterPairing = true
            intent.action = null
            window.decorView.post {
                runCatching { startPairing(pairingName()) }
                    .onFailure(::showError)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        refreshKeyboardStatus()
        refreshConversations()
        val model = File(filesDir, "models/$MODEL_FILE_NAME")
        modelStatus.text =
            when {
                model.isFile && model.length() == MODEL_FILE_SIZE ->
                    "Model installed (${model.length()} bytes). Runtime loads with the keyboard."
                model.isFile ->
                    "Model detected (${model.length()} bytes)."
                else ->
                    "Bundled model will be extracted automatically on initial generation."
            }
    }

    private fun refreshKeyboardStatus() {
        val inputMethodManager = getSystemService(InputMethodManager::class.java)
        val enabled =
            inputMethodManager.enabledInputMethodList.any {
                it.packageName == packageName
            }
        val selected =
            Settings.Secure.getString(
                contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD,
            )?.startsWith("$packageName/") == true
        keyboardStatus.text =
            when {
                selected -> "Keyboard selected and ready."
                enabled -> "Keyboard enabled — choose it as the active keyboard."
                else -> "Keyboard not enabled in Android settings."
            }
    }

    private fun refreshConversations() {
        conversationsView.removeAllViews()
        val activeId = repository.activeSummary()?.profileId
        val conversations = repository.list()
        if (conversations.isEmpty()) {
            conversationsView.addView(
                MaterialTextView(this).apply {
                    text = "No paired conversations. Carrier generation is disabled."
                    setPadding(0, dp(8), 0, dp(8))
                }
            )
            return
        }
        conversations.forEach { conversation ->
            val row =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(4), 0, dp(4))
                }
            row.addView(
                MaterialButton(
                    this,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle,
                ).apply {
                    text =
                        if (conversation.profileId == activeId) {
                            "${conversation.alias} · active"
                        } else {
                            conversation.alias
                        }
                    isAllCaps = false
                    minHeight = dp(48)
                    setOnClickListener {
                        repository.select(conversation.profileId)
                        refreshConversations()
                    }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(8)
                },
            )
            row.addView(
                MaterialButton(
                    this,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle,
                ).apply {
                    text = "Forget"
                    isAllCaps = false
                    minHeight = dp(48)
                    setOnClickListener {
                        MaterialAlertDialogBuilder(this@RefractSettingsActivity)
                            .setTitle("Forget ${conversation.alias}?")
                            .setMessage("This removes its wrapped key and sender-chain state.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Forget") { _, _ ->
                                repository.remove(conversation.profileId)
                                refreshConversations()
                            }
                            .show()
                    }
                }
            )
            conversationsView.addView(row)
        }
    }

    /**
     * The name the peer will see: whatever the user typed, else a device-derived
     * default.
     *
     * Re-validated in BYTES here rather than trusting the field's character limit,
     * because MAX_PAIRING_NAME_BYTES is a protocol constraint and a 24-character name
     * in a multi-byte script can still exceed 48 bytes. Over-long or control-character
     * input falls back to the device name rather than failing the pairing.
     */
    private fun pairingName(): String {
        val chosen = preferences.pairingDisplayName.trim()
        return if (chosen.isNotEmpty() && isValidPairingName(chosen)) {
            chosen
        } else {
            systemPairingName()
        }
    }

    private fun isValidPairingName(candidate: String): Boolean =
        candidate.toByteArray(Charsets.UTF_8).size <= MAX_PAIRING_NAME_BYTES &&
            candidate.none { it == '\u0000' || it == '\r' || it == '\n' }

    private fun systemPairingName(): String {
        val deviceName =
            runCatching {
                Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
            }
                .getOrNull()
                .orEmpty()
                .trim()
                .takeIf(String::isNotEmpty)

        return listOfNotNull(
                deviceName,
                Build.MODEL.trim().takeIf(String::isNotEmpty),
                "Android device",
            )
            .first { candidate ->
                candidate.toByteArray(Charsets.UTF_8).size <= MAX_PAIRING_NAME_BYTES &&
                    candidate.none { it == '\u0000' || it == '\r' || it == '\n' }
            }
    }

    private fun startPairing(displayName: String) {
        pendingInitiator?.close()
        var session = PairingInitiatorSession.start(displayName)
        pendingInitiator = session
        var qrBitmap = qrBitmap(session.invitation.encode())
        val content =
            pairingDialogContent(
                qr = qrBitmap,
                body = getString(R.string.refract_pair_step1_body, displayName),
            )
        val nameLayout =
            content.findViewById<TextInputLayout>(R.id.refract_dialog_name_layout).apply {
                visibility = View.VISIBLE
            }
        val nameInput =
            content.findViewById<TextInputEditText>(R.id.refract_dialog_name).apply {
                setText(displayName)
            }
        val qrView = content.findViewById<ImageView>(R.id.refract_dialog_qr)
        val bodyView = content.findViewById<MaterialTextView>(R.id.refract_dialog_body)
        content.findViewById<MaterialButton>(R.id.refract_dialog_share).apply {
            visibility = View.VISIBLE
            setOnClickListener {
                runCatching {
                        sharePairingCode(
                            bitmap = qrBitmap,
                            kind = "invitation",
                        )
                    }
                    .onFailure(::showError)
            }
        }
        nameInput.doAfterTextChanged { editable ->
            val chosen = editable?.toString().orEmpty().trim()
            val updatedName =
                when {
                    chosen.isEmpty() -> systemPairingName()
                    isValidPairingName(chosen) -> chosen
                    else -> {
                        nameLayout.error = getString(R.string.refract_display_name_error)
                        return@doAfterTextChanged
                    }
                }
            nameLayout.error = null
            preferences.pairingDisplayName = chosen
            if (updatedName == session.invitation.inviterName) return@doAfterTextChanged

            runCatching {
                    val replacement = PairingInitiatorSession.start(updatedName)
                    try {
                        val replacementQr = qrBitmap(replacement.invitation.encode())
                        val previous = session
                        session = replacement
                        qrBitmap = replacementQr
                        pendingInitiator = replacement
                        qrView.setImageBitmap(replacementQr)
                        bodyView.text =
                            getString(R.string.refract_pair_step1_body, updatedName)
                        previous.close()
                    } catch (error: Throwable) {
                        replacement.close()
                        throw error
                    }
                }
                .onFailure(::showError)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.refract_pair_step1_title)
            .setView(content)
            .setNegativeButton(R.string.refract_cancel) { _, _ ->
                closeInitiator(session)
                finishPairingLaunch()
            }
            .setPositiveButton(R.string.refract_scan_response) { _, _ ->
                scanCode(
                    onFailure = {
                        closeInitiator(session)
                        finishPairingLaunch()
                    },
                ) { response ->
                    runCatching { session.complete(response) }
                        .onSuccess { pairing ->
                            closeInitiator(session)
                            confirmSafetyWords(pairing)
                        }
                        .onFailure { error ->
                            closeInitiator(session)
                            showError(error)
                        }
                }
            }
            .create()
        dialog.setOnCancelListener {
            closeInitiator(session)
            finishPairingLaunch()
        }
        dialog.show()
    }

    private fun respondToInvitation(
        invitation: PairingInvite,
        displayName: String,
    ) {
        pendingResponder?.close()
        runCatching {
                PairingResponderSession.respond(
                    encodedInvitation = invitation.encode(),
                    responderName = displayName,
                )
            }
            .onSuccess { session ->
                pendingResponder = session
                val content =
                    pairingDialogContent(
                        qr = qrBitmap(session.responseCode),
                        body = getString(R.string.refract_pair_step2_body, displayName),
                        shareKind = "response",
                        safetyPhrase = session.pairing.safetyPhrase,
                    )
                val dialog = MaterialAlertDialogBuilder(this)
                    .setTitle(
                        getString(R.string.refract_pair_step2_title, invitation.inviterName)
                    )
                    .setView(content)
                    .setNegativeButton(R.string.refract_cancel) { _, _ ->
                        closeResponder(session)
                    }
                    .setPositiveButton(R.string.refract_words_match) { _, _ ->
                        runCatching { storePairing(session.pairing) }
                            .onSuccess {
                                val peerName = session.pairing.peerName
                                closeResponder(session)
                                setOperation("Paired with $peerName.")
                                refresh()
                            }
                            .onFailure { error ->
                                closeResponder(session)
                                showError(error)
                            }
                    }
                    .create()
                dialog.setOnCancelListener { closeResponder(session) }
                dialog.show()
            }
            .onFailure(::showError)
    }

    private fun confirmSafetyWords(pairing: PairingEstablishedSession) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.refract_pair_verify_title, pairing.peerName))
            // Safety words rendered as headline type in the shared layout rather than
            // buried mid-paragraph in setMessage, since reading them aloud IS the task.
            .setMessage(R.string.refract_pair_verify_body)
            .setView(
                pairingDialogVerifyContent(safetyPhrase = pairing.safetyPhrase)
            )
            .setNegativeButton(R.string.refract_cancel) { _, _ ->
                pairing.close()
                finishPairingLaunch()
            }
            .setPositiveButton(R.string.refract_words_match) { _, _ ->
                runCatching { storePairing(pairing) }
                    .onSuccess {
                        setOperation("Paired with ${pairing.peerName}.")
                        pairing.close()
                        refresh()
                        finishPairingLaunch()
                    }
                    .onFailure { error ->
                        pairing.close()
                        showError(error)
                    }
            }
            .create()
        dialog.setOnCancelListener {
            pairing.close()
            finishPairingLaunch()
        }
        dialog.show()
    }

    private fun storePairing(pairing: PairingEstablishedSession) {
        val key = pairing.copyConversationKey()
        try {
            repository.createFromPairing(
                alias = pairing.peerName,
                conversationId = pairing.conversationId,
                localSender = pairing.localSender,
                peerSender = pairing.peerSender,
                conversationKey = key,
            )
        } finally {
            key.fill(0)
        }
    }

    private fun scanCode(
        onFailure: () -> Unit = {},
        onValue: (String) -> Unit,
    ) {
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val value = barcode.rawValue
                if (value == null) {
                    onFailure()
                    setOperation("The QR code did not contain pairing data.")
                } else {
                    runCatching { onValue(value) }
                        .onFailure { error ->
                            onFailure()
                            showError(error)
                        }
                }
            }
            .addOnFailureListener { error ->
                onFailure()
                showError(error)
            }
            .addOnCanceledListener { onFailure() }
    }

    private fun qrBitmap(value: String): Bitmap {
        val size = resources.displayMetrics.widthPixels.coerceAtMost(dp(360))
        val matrix =
            QRCodeWriter().encode(
                value,
                BarcodeFormat.QR_CODE,
                size,
                size,
                mapOf(
                    EncodeHintType.CHARACTER_SET to "UTF-8",
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN to 2,
                ),
            )
        val pixels =
            IntArray(size * size) { index ->
                if (matrix[index % size, index / size]) Color.BLACK else Color.WHITE
            }
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
    }

    /**
     * Builds the shared content view for the pairing dialogs.
     *
     * Share is a button inside this view rather than the dialog's neutral button:
     * three buttons plus labels of this length overflow the M3 dialog button bar,
     * which is a single non-wrapping row, and the neutral one was being clipped away
     * entirely. It also reads better next to the code it acts on.
     *
     * @param safetyPhrase shown only when the step has one to compare (step 2 and
     *   the verify dialog); step 1 has not derived it yet.
     */
    /**
     * Verify dialog: safety words only. Reuses the shared layout so the phrase gets
     * identical treatment to step 2, hiding the QR card and the share button since
     * there is nothing left to scan or send at this point.
     */
    private fun pairingDialogVerifyContent(safetyPhrase: String): View {
        val content = layoutInflater.inflate(R.layout.refract_pairing_dialog, null, false)
        // Hide the card, not just the ImageView: the card carries the white QR field
        // and would otherwise remain as an empty white block.
        content.findViewById<View>(R.id.refract_dialog_qr_card).visibility = View.GONE
        content.findViewById<View>(R.id.refract_dialog_share).visibility = View.GONE
        content.findViewById<MaterialTextView>(R.id.refract_dialog_body).visibility = View.GONE
        content.findViewById<MaterialTextView>(R.id.refract_dialog_safety).apply {
            text = safetyPhrase
            visibility = View.VISIBLE
        }
        return content
    }

    private fun pairingDialogContent(
        qr: Bitmap,
        body: String,
        shareKind: String? = null,
        safetyPhrase: String? = null,
    ): View {
        val content = layoutInflater.inflate(R.layout.refract_pairing_dialog, null, false)
        content.findViewById<ImageView>(R.id.refract_dialog_qr).setImageBitmap(qr)
        content.findViewById<MaterialTextView>(R.id.refract_dialog_body).text = body
        content.findViewById<MaterialTextView>(R.id.refract_dialog_safety).apply {
            text = safetyPhrase
            visibility = if (safetyPhrase == null) View.GONE else View.VISIBLE
        }
        content.findViewById<MaterialButton>(R.id.refract_dialog_share).apply {
            if (shareKind == null) {
                visibility = View.GONE
            } else {
                setOnClickListener {
                    runCatching { sharePairingCode(bitmap = qr, kind = shareKind) }
                        .onFailure(::showError)
                }
            }
        }
        return content
    }

    private fun sharePairingCode(
        bitmap: Bitmap,
        kind: String,
    ) {
        val directory = File(cacheDir, "pairing").apply { mkdirs() }
        val image = File(directory, "refract-$kind-${System.currentTimeMillis()}.png")
        image.outputStream().buffered().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "The pairing code could not be prepared for sharing."
            }
        }
        window.decorView.postDelayed(
            { image.delete() },
            PAIRING_SHARE_LIFETIME_MILLIS,
        )

        val uri =
            FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                image,
            )
        val share =
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                clipData = ClipData.newRawUri("Refract pairing code", uri)
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Refract Keyboard pairing $kind. This public-key code expires in 15 minutes.",
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        startActivity(Intent.createChooser(share, "Share pairing code"))
    }

    private fun closeInitiator(session: PairingInitiatorSession) {
        if (pendingInitiator === session) pendingInitiator = null
        session.close()
    }

    private fun closeResponder(session: PairingResponderSession) {
        if (pendingResponder === session) pendingResponder = null
        session.close()
    }

    private fun showError(error: Throwable) {
        setOperation(error.message ?: error.javaClass.simpleName)
    }

    private fun setOperation(message: String) {
        operationStatus.text = message
    }

    private fun finishPairingLaunch() {
        if (finishAfterPairing) finish()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        pendingInitiator?.close()
        pendingResponder?.close()
        super.onDestroy()
    }

    private companion object {
        const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
        const val MODEL_FILE_SIZE = 2_588_147_712L
        const val MAX_PAIRING_NAME_BYTES = 48
        const val PAIRING_SHARE_LIFETIME_MILLIS = 16 * 60 * 1000L
    }
}
