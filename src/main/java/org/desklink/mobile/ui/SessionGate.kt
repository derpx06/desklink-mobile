/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui

import android.content.Context
import org.desklink.mobile.helpers.DeskNames
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceManager
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The phone-side gate before Terminals and the desk screen, once per WebRTC connection.
 *
 * - The desk's session password can be remembered behind this phone's fingerprint: a Keystore
 *   AES-GCM key that needs strong biometrics for every use and dies when a new fingerprint is
 *   enrolled (the password is then asked again, in the screens' own dialog).
 * - With no remembered password the person may still ask for a fingerprint or screen-lock check
 *   before those screens (Settings switch), phone-only.
 * - With no biometrics the screens just ask for the password when the desk wants it.
 */
object SessionGate {
    const val KEY_PHONE_GATE = "session_gate_phone_only"
    private const val PREFS = "session_gate"
    private const val KEY_ALIAS = "desklink_session_password"
    private const val TAG = "DeskLink/SessionGate"

    enum class Step {
        /** Nothing to check (or already checked on this connection). */
        OPEN,

        /** Decrypt the remembered password with a fingerprint; it then rides on the first request. */
        UNLOCK_STORED,

        /** Phone-only check, nothing is sent. */
        PHONE_CHECK,
    }

    /** What to do before a gated screen opens. Pure, for tests. */
    fun step(alreadyPassed: Boolean, passwordKnown: Boolean, stored: Boolean, strongEnrolled: Boolean, phoneGate: Boolean, anyAuth: Boolean): Step = when {
        alreadyPassed -> Step.OPEN
        stored && strongEnrolled && !passwordKnown -> Step.UNLOCK_STORED
        phoneGate && anyAuth -> Step.PHONE_CHECK
        else -> Step.OPEN
    }

    /** After the remembered password could not be used: the phone-only check when the person turned it on, else the screen. */
    fun afterStoredFails(phoneGate: Boolean, anyAuth: Boolean): Step = if (phoneGate && anyAuth) Step.PHONE_CHECK else Step.OPEN

    /** `iv:ciphertext`, both Base64 (no wrap); null when malformed. Pure, for tests. */
    fun encodeBlob(iv: ByteArray, ciphertext: ByteArray): String =
        Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP)

    fun decodeBlob(blob: String?): Pair<ByteArray, ByteArray>? {
        val parts = blob?.split(':')?.takeIf { it.size == 2 && it.all(String::isNotEmpty) } ?: return null
        return runCatching { Base64.decode(parts[0], Base64.NO_WRAP) to Base64.decode(parts[1], Base64.NO_WRAP) }
            .getOrNull()?.takeIf { (iv, ct) -> iv.size == 12 && ct.size > 16 }
    }

    /** deviceId → the connection on which the gate was passed. */
    private val passed = HashMap<String, Long>()

    /** The gate was passed on [connection] (a session id): only that connection, never the next. */
    internal fun markPassed(deviceId: String, connection: Long) = synchronized(passed) { passed[deviceId] = connection }

    internal fun isPassed(deviceId: String, connection: Long?) = connection != null && synchronized(passed) { passed[deviceId] == connection }

    internal fun clearPassed() = synchronized(passed) { passed.clear() }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasStored(context: Context, deviceId: String) = decodeBlob(prefs(context).getString(deviceId, null)) != null

    fun phoneGateOn(context: Context) = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_PHONE_GATE, false)

    /** The password was refused, or the device was unpaired: never offer it again. */
    fun forget(context: Context, deviceId: String) = prefs(context).edit { remove(deviceId) }

    /**
     * Runs the gate, then [onOpen] (always on the main thread). [onDenied] when the phone-only
     * check was cancelled: the screen does not open.
     */
    fun check(activity: FragmentActivity, deviceId: String, onOpen: () -> Unit, onDenied: () -> Unit) {
        val app = DeskLinkApplication.getInstance()
        val connection = app.connectionKey(deviceId)
        val stored = hasStored(activity, deviceId)
        val step = step(
            alreadyPassed = isPassed(deviceId, connection),
            passwordKnown = app.sessionPassword(deviceId) != null,
            stored = stored,
            strongEnrolled = Biometrics.strongEnrolled(activity),
            phoneGate = phoneGateOn(activity),
            anyAuth = Biometrics.available(activity, Biometrics.STRONG_OR_CREDENTIAL),
        )
        fun pass() {
            connection?.let { markPassed(deviceId, it) }
            onOpen()
        }
        val desk = DeskNames.of(deviceId)
        fun phoneCheck() = Biometrics.prompt(activity, activity.getString(R.string.gate_title, desk), null, Biometrics.STRONG_OR_CREDENTIAL, null) { result ->
            if (result is BiometricResult.Succeeded) pass() else onDenied()
        }
        // The remembered password could not be used (cancelled, failed, key gone): the phone-only check the person asked
        // for still stands, so it is asked instead of opening, never skipped.
        fun withoutStored() = when (afterStoredFails(phoneGate = phoneGateOn(activity), anyAuth = Biometrics.available(activity, Biometrics.STRONG_OR_CREDENTIAL))) {
            Step.PHONE_CHECK -> phoneCheck()
            else -> onOpen()
        }
        when (step) {
            Step.OPEN -> onOpen()
            Step.PHONE_CHECK -> phoneCheck()
            Step.UNLOCK_STORED -> {
                val (iv, ciphertext) = decodeBlob(prefs(activity).getString(deviceId, null))!!
                val cipher = try {
                    Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
                } catch (invalidated: KeyPermanentlyInvalidatedException) {
                    // A fingerprint was added or removed: the password is asked again in the screen.
                    Log.i(TAG, "Session password key invalidated", invalidated)
                    dropKey()
                    prefs(activity).edit { clear() }
                    withoutStored()
                    return
                } catch (error: Exception) {
                    Log.w(TAG, "Could not open the remembered session password", error)
                    withoutStored()
                    return
                }
                Biometrics.prompt(
                    activity,
                    activity.getString(R.string.gate_title, desk),
                    activity.getString(R.string.gate_stored_subtitle),
                    BIOMETRIC_STRONG,
                    activity.getString(R.string.gate_type_password),
                    BiometricPrompt.CryptoObject(cipher),
                ) { result ->
                    val password = (result as? BiometricResult.Succeeded)?.crypto?.cipher?.let { c ->
                        runCatching { String(c.doFinal(ciphertext), Charsets.UTF_8) }.getOrNull()
                    }
                    if (password != null) {
                        app.setSessionPassword(deviceId, password)
                        pass()
                    } else {
                        // Cancelled: the screen asks for the password if the desk wants it, but a phone-only check that is
                        // switched on is not skipped by cancelling the fingerprint.
                        withoutStored()
                    }
                }
            }
        }
    }

    /**
     * After the person typed the desk's password: if strong biometrics are enrolled and nothing is
     * remembered yet, offer to keep it behind the fingerprint (the prompt is the question).
     */
    fun offerToRemember(activity: FragmentActivity, deviceId: String, password: String) {
        if (hasStored(activity, deviceId) || !Biometrics.strongEnrolled(activity)) return
        val cipher = runCatching { Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) } }
            .recoverCatching { error ->
                if (error !is KeyPermanentlyInvalidatedException) throw error
                dropKey()
                Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
            }
            .onFailure { Log.w(TAG, "Could not prepare the session password key", it) }
            .getOrNull() ?: return
        val desk = DeskNames.of(deviceId)
        Biometrics.prompt(
            activity,
            activity.getString(R.string.gate_remember_title, desk),
            activity.getString(R.string.gate_remember_subtitle),
            BIOMETRIC_STRONG,
            activity.getString(R.string.gate_not_now),
            BiometricPrompt.CryptoObject(cipher),
        ) { result ->
            val c = (result as? BiometricResult.Succeeded)?.crypto?.cipher ?: return@prompt
            runCatching { encodeBlob(c.iv, c.doFinal(password.toByteArray(Charsets.UTF_8))) }
                .onSuccess { blob -> prefs(activity).edit { putString(deviceId, blob) } }
                .onFailure { Log.w(TAG, "Could not remember the session password", it) }
        }
    }

    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun dropKey() = runCatching { keyStore().deleteEntry(KEY_ALIAS) }

    private fun key(): SecretKey {
        (keyStore().getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) setInvalidatedByBiometricEnrollment(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }
}
