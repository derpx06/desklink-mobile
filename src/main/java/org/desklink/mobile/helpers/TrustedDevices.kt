package org.desklink.mobile.helpers

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import org.desklink.mobile.helpers.security.SslHelper.parseCertificate
import java.io.File
import java.security.cert.Certificate

object TrustedDevices {

    @JvmStatic
    fun isTrustedDevice(context: Context, deviceId: String): Boolean {
        val preferences: SharedPreferences = context.getSharedPreferences("trusted_devices", MODE_PRIVATE)
        return preferences.getBoolean(deviceId, false)
    }

    fun addTrustedDevice(context: Context, deviceId: String) {
        val preferences = context.getSharedPreferences("trusted_devices", MODE_PRIVATE)
        preferences.edit(commit = true) { putBoolean(deviceId, true) }
    }

    fun removeTrustedDevice(context: Context, deviceId: String) {
        val preferences = context.getSharedPreferences("trusted_devices", MODE_PRIVATE)
        preferences.edit { remove(deviceId) }
        val deviceSettings = context.getSharedPreferences(deviceId, Context.MODE_PRIVATE)
        deviceSettings.edit { clear() }
        clearDeviceSpecificPluginSettings(context, deviceId)
    }

    /**
     * Clears every `<deviceId>_<plugin>_preferences` file for this device.
     *
     * Unpairing cleared the trust flag and the device's own record but left
     * these behind, so a device that later re-paired silently inherited the
     * per-plugin state it had before it was removed — including which features
     * had been enabled or denied. "Forget this device" has to mean it.
     *
     * Enumerated from disk rather than from a list of plugins, because a plugin
     * that is no longer loaded still has a file, and that file would otherwise
     * be the one thing nothing ever cleans up.
     */
    private fun clearDeviceSpecificPluginSettings(context: Context, deviceId: String) {
        // Best effort, and deliberately so. Dropping the trust flag and the
        // certificate is the security-critical half of an unpair and has
        // already happened by the time this runs; a failure to tidy up
        // secondary per-plugin state must never propagate and make "forget this
        // device" look like it failed.
        runCatching {
            val prefix = "${deviceId}_"
            // `filesDir.parentFile` rather than `applicationInfo.dataDir`:
            // identical on device — both are /data/data/<pkg> — but `filesDir`
            // is a method a test can stub, where `dataDir` is a public field on
            // a framework class that cannot even be constructed off-device.
            val sharedPrefsDir = File(context.filesDir.parentFile, "shared_prefs")
            val stale = sharedPrefsDir.listFiles { _, name ->
                name.startsWith(prefix) && name.endsWith("_preferences.xml")
            } ?: return@runCatching
            for (file in stale) {
                val name = file.name.removeSuffix(".xml")
                Log.d("DeskLink", "Clearing per-device plugin settings: $name")
                context.getSharedPreferences(name, MODE_PRIVATE).edit { clear() }
            }
        }.onFailure { error ->
            Log.w("DeskLink", "Could not clear per-device plugin settings for $deviceId", error)
        }
    }

    fun getAllTrustedDevices(context: Context): List<String> {
        val preferences = context.getSharedPreferences("trusted_devices", MODE_PRIVATE)
        return preferences.all.keys.filter { preferences.getBoolean(it, false) }
    }

    /**
     * Forgets every trusted device, certificates and per-device settings
     * included.
     *
     * This used to remove only the booleans from `trusted_devices`, leaving each
     * device's stored certificate on disk. `SslHelper` calls this precisely when
     * the local identity has changed and every existing trust record is
     * therefore meaningless — so the one caller that most needs a clean slate
     * was the one getting a half-cleared one.
     */
    fun removeAllTrustedDevices(context: Context) {
        val preferences = context.getSharedPreferences("trusted_devices", MODE_PRIVATE)
        // Snapshotted, because `removeTrustedDevice` writes to the map being
        // iterated.
        for (deviceId in preferences.all.keys.toList()) {
            Log.d("DeskLink", "Removing device: $deviceId")
            removeTrustedDevice(context, deviceId)
        }
    }

    /**
     * Returns the stored certificate for a trusted device
     *
     * @throws java.security.cert.CertificateException if there is no certificate stored (ie: the device isn't trusted)
     */
    fun getDeviceCertificate(context: Context, deviceId: String): Certificate {
        val devicePreferences = context.getSharedPreferences(deviceId, MODE_PRIVATE)
        val certificateBytes = Base64.decode(devicePreferences.getString("certificate", ""), 0)
        return parseCertificate(certificateBytes)
    }

    @JvmStatic
    fun isCertificateStored(context: Context, deviceId: String): Boolean {
        val devicePreferences = context.getSharedPreferences(deviceId, MODE_PRIVATE)
        val cert: String = devicePreferences.getString("certificate", "")!!
        return cert.isNotEmpty()
    }

    fun getDeviceSettings(context: Context, deviceId: String): SharedPreferences {
        return context.getSharedPreferences(deviceId, MODE_PRIVATE)
    }

}
