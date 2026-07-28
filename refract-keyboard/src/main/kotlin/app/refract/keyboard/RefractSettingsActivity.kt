package app.refract.keyboard

import android.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import app.refract.keyboard.protocol.PairingEstablishedSession
import app.refract.keyboard.protocol.PairingInitiatorSession
import app.refract.keyboard.protocol.PairingInvite
import app.refract.keyboard.protocol.PairingResponderSession
import com.android.inputmethod.latin.R
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors

/**
 * Focused Refract configuration, independent of the LatinIME settings UI.
 *
 * Extends [AppCompatActivity] because the Material 3 Expressive theme
 * (`Theme.Refract`) inherits from an AppCompat theme, which a plain
 * `android.app.Activity` cannot host. The view tree here is still hand-built from
 * platform widgets; converting it to Material components is a later phase.
 */
class RefractSettingsActivity : AppCompatActivity() {
    private lateinit var repository: ConversationRepository
    private lateinit var preferences: KeyboardPreferences
    private lateinit var scanner: GmsBarcodeScanner

    private lateinit var conversationsView: LinearLayout
    private lateinit var keyboardStatus: TextView
    private lateinit var modelStatus: TextView
    private lateinit var operationStatus: TextView
    private lateinit var preloadModel: CheckBox
    private lateinit var backend: RadioGroup

    private val worker = Executors.newSingleThreadExecutor()
    private var pendingInitiator: PairingInitiatorSession? = null
    private var pendingResponder: PairingResponderSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Overlays the wallpaper-derived palette onto Theme.Refract's M3 colour
        // roles. Must run before setContentView so inflation sees the final theme.
        // No-op below API 31 or where no dynamic palette exists, leaving the static
        // Theme.Material3Expressive.DayNight colours in place.
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
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

        findViewById<Button>(R.id.refract_enable_keyboard).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.refract_choose_keyboard).setOnClickListener {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }
        findViewById<Button>(R.id.refract_start_pairing).setOnClickListener {
            promptIdentity(
                title = "Start a private conversation",
                aliasDefault = "",
            ) { alias, displayName ->
                startPairing(alias, displayName)
            }
        }
        findViewById<Button>(R.id.refract_join_pairing).setOnClickListener {
            scanCode { encoded ->
                val invitation = PairingInvite.decode(encoded)
                promptIdentity(
                    title = "Join ${invitation.inviterName}",
                    aliasDefault = invitation.inviterName,
                ) { alias, displayName ->
                    respondToInvitation(invitation, alias, displayName)
                }
            }
        }
        findViewById<Button>(R.id.refract_import_model).setOnClickListener {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*"),
                REQUEST_MODEL,
            )
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
                !model.isFile -> "Missing — import $MODEL_FILE_NAME to generate carriers."
                model.length() != MODEL_FILE_SIZE ->
                    "Invalid model file (${model.length()} of $MODEL_FILE_SIZE bytes)."
                else -> "Installed locally (${model.length()} bytes)."
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
                TextView(this).apply {
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
                Button(this).apply {
                    text =
                        if (conversation.profileId == activeId) {
                            "${conversation.alias} · active"
                        } else {
                            conversation.alias
                        }
                    isAllCaps = false
                    setOnClickListener {
                        repository.select(conversation.profileId)
                        refreshConversations()
                    }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            row.addView(
                Button(this).apply {
                    text = "Forget"
                    isAllCaps = false
                    setOnClickListener {
                        AlertDialog.Builder(this@RefractSettingsActivity)
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

    private fun promptIdentity(
        title: String,
        aliasDefault: String,
        onConfirm: (alias: String, displayName: String) -> Unit,
    ) {
        val alias =
            EditText(this).apply {
                hint = "Name on this phone"
                setText(aliasDefault)
                isSingleLine = true
            }
        val displayName =
            EditText(this).apply {
                hint = "Your name shown to them"
                setText(preferences.pairingDisplayName)
                isSingleLine = true
            }
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), 0, dp(24), 0)
                addView(alias)
                addView(displayName)
            }
        val dialog =
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(
                    "Refract creates temporary X25519 keys. No secret phrase or conversation key " +
                        "is shown in either QR."
                )
                .setView(content)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue", null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val cleanAlias = alias.text.toString().trim()
                val cleanName = displayName.text.toString().trim()
                when {
                    cleanAlias.isEmpty() -> alias.error = "Name this conversation."
                    cleanName.isEmpty() -> displayName.error = "Enter your display name."
                    else -> {
                        preferences.pairingDisplayName = cleanName
                        dialog.dismiss()
                        onConfirm(cleanAlias, cleanName)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun startPairing(
        alias: String,
        displayName: String,
    ) {
        pendingInitiator?.close()
        val session = PairingInitiatorSession.start(displayName)
        pendingInitiator = session
        val qr = qrImage(session.invitation.encode())
        val dialog = AlertDialog.Builder(this)
            .setTitle("1 of 2 · Let them scan this invitation")
            .setMessage(
                "This QR contains a temporary public key and expires in 15 minutes. " +
                    "After they scan it, scan the response shown on their phone."
            )
            .setView(qr)
            .setNegativeButton("Cancel") { _, _ ->
                closeInitiator(session)
            }
            .setPositiveButton("Scan response") { _, _ ->
                scanCode(
                    onFailure = { closeInitiator(session) },
                ) { response ->
                    runCatching { session.complete(response) }
                        .onSuccess { pairing ->
                            closeInitiator(session)
                            confirmSafetyWords(alias, pairing)
                        }
                        .onFailure { error ->
                            closeInitiator(session)
                            showError(error)
                        }
                }
            }
            .create()
        dialog.setOnCancelListener { closeInitiator(session) }
        dialog.show()
    }

    private fun respondToInvitation(
        invitation: PairingInvite,
        alias: String,
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
                    LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(16), 0, dp(16), 0)
                        addView(qrImage(session.responseCode))
                        addView(
                            TextView(this@RefractSettingsActivity).apply {
                                text = session.pairing.safetyPhrase
                                textSize = 20f
                                textAlignment = TextView.TEXT_ALIGNMENT_CENTER
                                setPadding(0, dp(12), 0, dp(8))
                            }
                        )
                    }
                val dialog = AlertDialog.Builder(this)
                    .setTitle("2 of 2 · Let ${invitation.inviterName} scan this response")
                    .setMessage(
                        "After they scan it, compare all five safety words aloud. " +
                            "A mismatch means the exchange was replaced."
                    )
                    .setView(content)
                    .setNegativeButton("Cancel") { _, _ ->
                        closeResponder(session)
                    }
                    .setPositiveButton("Words match · finish") { _, _ ->
                        runCatching { storePairing(alias, session.pairing) }
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

    private fun confirmSafetyWords(
        alias: String,
        pairing: PairingEstablishedSession,
    ) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Verify ${pairing.peerName}")
            .setMessage(
                "Compare all five words with their phone:\n\n${pairing.safetyPhrase}\n\n" +
                    "If any word differs, cancel and start again."
            )
            .setNegativeButton("Cancel") { _, _ -> pairing.close() }
            .setPositiveButton("Words match · finish") { _, _ ->
                runCatching { storePairing(alias, pairing) }
                    .onSuccess {
                        setOperation("Paired with ${pairing.peerName}.")
                        pairing.close()
                        refresh()
                    }
                    .onFailure { error ->
                        pairing.close()
                        showError(error)
                    }
            }
            .create()
        dialog.setOnCancelListener { pairing.close() }
        dialog.show()
    }

    private fun storePairing(
        alias: String,
        pairing: PairingEstablishedSession,
    ) {
        val key = pairing.copyConversationKey()
        try {
            repository.createFromPairing(
                alias = alias,
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
    }

    private fun qrImage(value: String): ImageView {
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
        val bitmap =
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, size, 0, 0, size, size)
            }
        return ImageView(this).apply {
            setImageBitmap(bitmap)
            contentDescription = "Refract public-key pairing QR code"
            adjustViewBounds = true
        }
    }

    @Deprecated("The settings activity uses the platform document picker contract.")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MODEL || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        setOperation("Importing Gemma model… keep this screen open.")
        worker.execute {
            runCatching {
                    val metadata =
                        contentResolver.query(
                            uri,
                            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                            null,
                            null,
                            null,
                        )?.use { cursor ->
                            if (!cursor.moveToFirst()) {
                                null
                            } else {
                                Pair(
                                    cursor.getString(
                                        cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                                    ),
                                    cursor
                                        .getColumnIndexOrThrow(OpenableColumns.SIZE)
                                        .let { sizeColumn ->
                                            if (cursor.isNull(sizeColumn)) null
                                            else cursor.getLong(sizeColumn)
                                        },
                                )
                            }
                        }
                    require(metadata?.first == MODEL_FILE_NAME) {
                        "Expected $MODEL_FILE_NAME."
                    }
                    require(metadata.second == null || metadata.second == MODEL_FILE_SIZE) {
                        "The selected model has the wrong size."
                    }
                    require(filesDir.usableSpace > MODEL_FILE_SIZE + IMPORT_HEADROOM_BYTES) {
                        "There is not enough free storage to import this model safely."
                    }
                    val destination = File(filesDir, "models/$MODEL_FILE_NAME")
                    destination.parentFile?.mkdirs()
                    val temporary = File(destination.parentFile, "$MODEL_FILE_NAME.importing")
                    temporary.delete()
                    try {
                        contentResolver.openInputStream(uri).use { input ->
                            requireNotNull(input) { "The selected model could not be opened." }
                            temporary.outputStream().buffered().use(input::copyTo)
                        }
                        require(temporary.length() == MODEL_FILE_SIZE) {
                            "The imported model is incomplete."
                        }
                        try {
                            Files.move(
                                temporary.toPath(),
                                destination.toPath(),
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING,
                            )
                        } catch (_: AtomicMoveNotSupportedException) {
                            Files.move(
                                temporary.toPath(),
                                destination.toPath(),
                                StandardCopyOption.REPLACE_EXISTING,
                            )
                        }
                        preferences.notifyModelChanged()
                    } catch (error: Throwable) {
                        temporary.delete()
                        throw error
                    }
                }
                .onSuccess {
                    runOnUiThread {
                        setOperation("Gemma model imported.")
                        refresh()
                    }
                }
                .onFailure { error ->
                    runOnUiThread { showError(error) }
                }
        }
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        pendingInitiator?.close()
        pendingResponder?.close()
        worker.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_MODEL = 2201
        const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
        const val MODEL_FILE_SIZE = 2_588_147_712L
        const val IMPORT_HEADROOM_BYTES = 256L * 1024 * 1024
    }
}
