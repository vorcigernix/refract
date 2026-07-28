package app.refract.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardPreferencesTest {
    @Test
    fun gpuIsTheSafeDefaultForMissingOrUnknownValues() {
        assertEquals(
            KeyboardPreferences.RuntimeBackend.GPU,
            KeyboardPreferences.RuntimeBackend.fromStorage(null),
        )
        assertEquals(
            KeyboardPreferences.RuntimeBackend.GPU,
            KeyboardPreferences.RuntimeBackend.fromStorage("future-backend"),
        )
    }

    @Test
    fun storedCpuValueRoundTrips() {
        assertEquals(
            KeyboardPreferences.RuntimeBackend.CPU,
            KeyboardPreferences.RuntimeBackend.fromStorage(
                KeyboardPreferences.RuntimeBackend.CPU.storageValue
            ),
        )
    }
}
