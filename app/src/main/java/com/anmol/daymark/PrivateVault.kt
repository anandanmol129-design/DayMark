package com.anmol.daymark

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The lock on the Private section of Notes and Saved. Unlocking goes through the system prompt
 * (fingerprint, face, or the phone's PIN/pattern/password); it locks again when the app leaves
 * the screen or you leave the Notes tab.
 */
object PrivateVault {
    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    private val open = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = open

    /** True while the system prompt (or the PIN screen it opens) is up, so that doesn't count as leaving the app. */
    @Volatile private var authenticating = false

    /** False when the phone has no screen lock, so there is nothing to unlock with. */
    fun available(context: Context): Boolean =
        context.getSystemService(BiometricManager::class.java)?.canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    fun lock() { open.value = false }

    /** Called when the activity stops: going home or to another app locks Private. */
    fun onAppHidden() { if (!authenticating) lock() }

    fun unlock(context: Context, onResult: (Boolean, String?) -> Unit) {
        if (open.value) { onResult(true, null); return }
        if (!available(context)) { onResult(false, "Set a screen lock on your phone to use Private"); return }
        val prompt = BiometricPrompt.Builder(context)
            .setTitle("Unlock Private")
            .setSubtitle("Your private notes and saves")
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        authenticating = true
        prompt.authenticate(CancellationSignal(), context.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                authenticating = false
                open.value = true
                onResult(true, null)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                authenticating = false
                val cancelled = errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED || errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                onResult(false, if (cancelled) null else errString.toString())
            }
            // A single failed fingerprint just lets the prompt try again; nothing to do here.
        })
    }
}
