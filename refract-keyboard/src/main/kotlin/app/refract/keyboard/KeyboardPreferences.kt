package app.refract.keyboard

import android.content.Context
import android.content.SharedPreferences

class KeyboardPreferences(context: Context) {
    enum class RuntimeBackend(val storageValue: String) {
        GPU("gpu"),
        CPU("cpu");

        companion object {
            fun fromStorage(value: String?): RuntimeBackend =
                entries.firstOrNull { it.storageValue == value } ?: GPU
        }
    }

    private val preferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var runtimeBackend: RuntimeBackend
        get() =
            RuntimeBackend.fromStorage(
                preferences.getString(KEY_RUNTIME_BACKEND, RuntimeBackend.GPU.storageValue)
            )
        set(value) {
            preferences.edit().putString(KEY_RUNTIME_BACKEND, value.storageValue).apply()
        }

    var preloadModel: Boolean
        get() = preferences.getBoolean(KEY_PRELOAD_MODEL, true)
        set(value) {
            preferences.edit().putBoolean(KEY_PRELOAD_MODEL, value).apply()
        }

    var hapticFeedback: Boolean
        get() = preferences.getBoolean(KEY_HAPTIC_FEEDBACK, true)
        set(value) {
            preferences.edit().putBoolean(KEY_HAPTIC_FEEDBACK, value).apply()
        }

    var pairingDisplayName: String
        get() = preferences.getString(KEY_PAIRING_DISPLAY_NAME, "").orEmpty()
        set(value) {
            preferences.edit().putString(KEY_PAIRING_DISPLAY_NAME, value.trim()).apply()
        }

    fun notifyModelChanged() {
        val nextRevision = preferences.getInt(KEY_MODEL_REVISION, 0) + 1
        preferences.edit().putInt(KEY_MODEL_REVISION, nextRevision).apply()
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        const val KEY_RUNTIME_BACKEND = "runtime_backend"
        const val KEY_PRELOAD_MODEL = "preload_model"
        const val KEY_HAPTIC_FEEDBACK = "haptic_feedback"
        const val KEY_MODEL_REVISION = "model_revision"
        const val KEY_PAIRING_DISPLAY_NAME = "pairing_display_name"

        private const val FILE_NAME = "keyboard_preferences"
    }
}
