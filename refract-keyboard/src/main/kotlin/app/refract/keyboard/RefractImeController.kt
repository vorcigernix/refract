package app.refract.keyboard

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.TextView
import com.android.inputmethod.event.Event
import com.android.inputmethod.latin.LatinIME
import com.android.inputmethod.latin.R
import com.android.inputmethod.latin.common.Constants

/**
 * Refract's small integration boundary with LatinIME.
 *
 * LatinIME continues to own and render the keyboard. This controller only diverts text-producing
 * events into the private draft while private mode is active, then inserts a validated carrier.
 */
class RefractImeController(
    private val ime: LatinIME,
) {
    private val draft = PrivateDraftBuffer()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val carrierModel = CarrierModel(ime)

    private var privateMode = false
    private var generationInProgress = false

    private var suggestionStrip: View? = null
    private var privatePanel: View? = null
    private var toggleButton: Button? = null
    private var draftView: TextView? = null
    private var statusView: TextView? = null
    private var settingsButton: Button? = null
    private var generateButton: Button? = null

    fun attach(inputView: View) {
        suggestionStrip = inputView.findViewById(R.id.suggestion_strip_view)
        privatePanel = inputView.findViewById(R.id.refract_private_panel)
        toggleButton = inputView.findViewById<Button>(R.id.refract_toggle).also { button ->
            button.setOnClickListener { setPrivateMode(!privateMode) }
        }
        draftView = inputView.findViewById(R.id.refract_private_draft)
        statusView = inputView.findViewById(R.id.refract_private_status)
        generateButton = inputView.findViewById<Button>(R.id.refract_generate).also { button ->
            button.setOnClickListener { generateCarrier() }
        }
        settingsButton =
            inputView.findViewById<Button>(R.id.refract_settings).also { button ->
                button.setOnClickListener {
                    ime.startActivity(
                        Intent(ime, RefractSettingsActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        render()
    }

    fun isPrivateMode(): Boolean = privateMode

    /**
     * Returns true when the event was consumed by the private composer.
     * Keyboard-state-only keys are left to LatinIME so shift, symbols, language, and emoji work.
     */
    fun handleEvent(event: Event): Boolean {
        if (!privateMode || generationInProgress) return privateMode
        event.mText?.let {
            draft.replaceSelection(it.toString())
            render()
            return true
        }
        when {
            event.mKeyCode == Constants.CODE_DELETE -> {
                draft.backspace()
                render()
                return true
            }
            event.mKeyCode == Constants.CODE_SHIFT_ENTER -> {
                draft.replaceSelection("\n")
                render()
                return true
            }
            event.mCodePoint != Event.NOT_A_CODE_POINT -> {
                draft.replaceSelection(String(Character.toChars(event.mCodePoint)))
                render()
                return true
            }
            event.mKeyCode in KEYBOARD_STATE_KEYS -> return false
            else -> return true
        }
    }

    fun handleText(text: String): Boolean {
        if (!privateMode) return false
        if (!generationInProgress) {
            draft.replaceSelection(text)
            render()
        }
        return true
    }

    private fun setPrivateMode(enabled: Boolean) {
        if (generationInProgress && !enabled) return
        privateMode = enabled
        val window = ime.window?.window
        if (enabled) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        render()
    }

    private fun generateCarrier() {
        if (draft.text.isBlank()) {
            setStatus("Type a private message first.")
            return
        }
        generationInProgress = true
        render()
        carrierModel.generateSecureCarrier(
            draft.text,
            object : CarrierModel.GenerationListener {
                override fun onProgress(message: String) {
                    postStatus(message)
                }

                override fun onCarrierUpdate(carrier: String) {
                    postStatus("Generating authenticated carrier… ${carrier.length} characters")
                }

                override fun onSuccess(result: CarrierModel.GenerationResult) {
                    mainHandler.post {
                        finishGeneration(result)
                    }
                }

                override fun onError(error: Throwable) {
                    mainHandler.post {
                        generationInProgress = false
                        setStatus(error.message ?: error.javaClass.simpleName)
                        render()
                    }
                }
            },
        )
    }

    private fun finishGeneration(
        result: CarrierModel.GenerationResult,
    ) {
        val connection: InputConnection? = ime.currentInputConnection
        val inserted = connection?.commitText(result.carrier, 1) == true
        val committed = inserted && carrierModel.commitGeneratedCarrier(result)
        if (!inserted) {
            carrierModel.discardGeneratedCarrier(result)
        }
        generationInProgress = false
        if (committed) {
            draft.clear()
            setStatus("Carrier inserted. Review it, then tap Send.")
            setPrivateMode(false)
        } else {
            setStatus(
                if (inserted) {
                    "Carrier inserted, but conversation state changed. Pair again before continuing."
                } else {
                    "The target message field is no longer available. Your draft was preserved."
                }
            )
        }
        render()
    }

    private fun render() {
        suggestionStrip?.visibility = if (privateMode) View.GONE else View.VISIBLE
        privatePanel?.visibility = if (privateMode) View.VISIBLE else View.GONE
        toggleButton?.apply {
            contentDescription =
                ime.getString(
                    if (privateMode) {
                        R.string.refract_private_exit
                    } else {
                        R.string.refract_private_mode
                    }
                )
            isActivated = privateMode
            isEnabled = !generationInProgress
        }
        draftView?.text =
            when {
                draft.text.isNotEmpty() -> draft.text
                else -> ime.getString(R.string.refract_private_draft_hint)
            }
        val hasActiveConversation = carrierModel.hasActiveConversation()
        settingsButton?.text =
            ime.getString(
                if (privateMode && !hasActiveConversation) {
                    R.string.refract_pair
                } else {
                    R.string.refract_settings
                }
            )
        generateButton?.isEnabled =
            privateMode &&
                !generationInProgress &&
                draft.text.isNotBlank() &&
                hasActiveConversation
        if (privateMode && !hasActiveConversation && !generationInProgress) {
            setStatus("No private conversation is paired yet.")
        }
    }

    private fun postStatus(message: String) {
        mainHandler.post { setStatus(message) }
    }

    private fun setStatus(message: String) {
        statusView?.text = message
    }

    fun close() {
        ime.window?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        carrierModel.close()
    }

    private companion object {
        val KEYBOARD_STATE_KEYS =
            setOf(
                Constants.CODE_SHIFT,
                Constants.CODE_CAPSLOCK,
                Constants.CODE_SWITCH_ALPHA_SYMBOL,
                Constants.CODE_SYMBOL_SHIFT,
                Constants.CODE_LANGUAGE_SWITCH,
                Constants.CODE_EMOJI,
                Constants.CODE_ALPHA_FROM_EMOJI,
            )
    }
}
