/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** How a prompt ended. [Failed] carries the platform's own message (locked out, no hardware…). */
sealed interface BiometricResult {
    data class Succeeded(val crypto: BiometricPrompt.CryptoObject?) : BiometricResult
    data object Cancelled : BiometricResult
    data class Failed(val message: String) : BiometricResult
}

/** androidx.biometric, with the combinations each Android version accepts. */
object Biometrics {
    /**
     * Biometrics or the screen lock. Android 9–10 refuse STRONG together with the device credential,
     * so they get WEAK (the credential is just as strong a check).
     */
    val STRONG_OR_CREDENTIAL: Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    fun available(context: Context, authenticators: Int): Boolean =
        BiometricManager.from(context).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS

    /** Strong biometrics are enrolled: what a Keystore key bound to biometrics needs. */
    fun strongEnrolled(context: Context): Boolean = available(context, BIOMETRIC_STRONG)

    fun prompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String?,
        authenticators: Int,
        negative: String?,
        crypto: BiometricPrompt.CryptoObject? = null,
        onResult: (BiometricResult) -> Unit,
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                    onResult(BiometricResult.Succeeded(result.cryptoObject))

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(
                    when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_CANCELED,
                        -> BiometricResult.Cancelled
                        else -> BiometricResult.Failed(errString.toString())
                    },
                )
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { subtitle?.let(::setSubtitle) }
            .setAllowedAuthenticators(authenticators)
            .apply { if (authenticators and DEVICE_CREDENTIAL == 0) setNegativeButtonText(negative ?: activity.getString(android.R.string.cancel)) }
            .build()
        if (crypto != null) prompt.authenticate(info, crypto) else prompt.authenticate(info)
    }
}
