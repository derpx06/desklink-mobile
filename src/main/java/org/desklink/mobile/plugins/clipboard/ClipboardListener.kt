/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 * SPDX-FileCopyrightText: 2021 Ilmaz Gumerov <ilmaz1309@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/
package org.desklink.mobile.plugins.clipboard

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import org.desklink.mobile.helpers.ThreadHelper.execute
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The phone's clipboard, as far as Android lets an app see it.
 *
 * Android 10+ hands the clipboard only to the app whose window has focus. So the phone's copies go
 * out (a) at once while DeskLink is in front, (b) whenever a DeskLink window gains focus, (c) from
 * the explicit paths (Home, the tile, the notification action, the widget), and (d) with
 * "Automatic sync (advanced)": READ_LOGS granted over adb lets us see the system refuse our
 * listener, and a 1-px focusable overlay then reads the new copy.
 */
class ClipboardListener private constructor(ctx: Context) {
    interface ClipboardObserver {
        /** [copiedAt]: when the person copied it (Unix ms, this phone's clock), not when it is sent; 0 when unknown. */
        fun clipboardChanged(content: String, copiedAt: Long = 0L)

        /** An image copy, already a PNG (contract C3). Called off the main thread. */
        fun clipboardImage(png: ByteArray, copiedAt: Long = 0L) {}
    }

    // Concurrent: registerObserver runs on the packet-dispatch thread while
    // the listener iterates on the main thread.
    private val observers: MutableSet<ClipboardObserver> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    private val context: Context = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val cm: ClipboardManager = ContextCompat.getSystemService(context, ClipboardManager::class.java)!!

    @Volatile var currentContent: String? = null
        private set
    @Volatile var updateTimestamp: Long = 0
        private set

    /** When something was last copied on this phone, read or not (a background copy cannot be read, but it is newer than the desk's). */
    @Volatile var copiedAt: Long = 0
        private set

    /** The newest time this phone holds a copy of its own. */
    fun newestLocalCopy(): Long = maxOf(updateTimestamp, copiedAt)

    /** The system's stamp of the last clip looked at, so a refocus reads (and toasts) only new copies. */
    private var lastClipStamp = 0L
    /** When the desk's copy last landed here: the system's denial of our own write is not a phone copy. */
    @Volatile private var remoteSetAt = 0L
    private var lastAutoRead = 0L
    private var lastSyncAt = 0L
    private var pacedSync = false
    private var logcat: Process? = null

    init {
        main.post { cm.addPrimaryClipChangedListener { onPrimaryClipChanged() } }
        // Deliberately no automatic-sync start here: running logcat is what makes Android 13+ ask "Allow
        // DeskLink to access all device logs?", and that question belongs to the person turning the
        // setting on, never to a cold start.
    }

    /**
     * When this clip was copied: the system's stamp on it (API 26+), else the time the change callback saw it, else now; never in
     * the future. The computer orders copies by this, so a copy that waited to be sent cannot replace a newer one made there.
     */
    private fun copyTime(description: ClipDescription): Long {
        val now = System.currentTimeMillis()
        val stamp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) description.timestamp else 0L
        return CopyTime.of(stamp, copiedAt, now, lastCopiedAtSent).also { lastCopiedAtSent = it }
    }

    /** The newest copiedAt this process has sent: copy times never go backwards, even if the system clock is set back. */
    @Volatile
    var lastCopiedAtSent: Long = 0L
        private set

    /** Something was copied (the system says THAT, not what). In front: read and sync now. Behind: only an offer, never a read. */
    @MainThread
    private fun onPrimaryClipChanged() {
        // Something was copied here (we may not be allowed to read it): the computer's older copy must not overwrite it on reconnect.
        if (remoteSetAt == 0L || SystemClock.elapsedRealtime() - remoteSetAt >= 2_000) copiedAt = System.currentTimeMillis()
        val inFront = runCatching {
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        }.getOrDefault(false)
        if (inFront) {
            ClipboardOffer.cancel(context)
            sync(force = false)
            return
        }
        val description = try { cm.primaryClipDescription } catch (_: Exception) { null }
        val own = remoteSetAt != 0L && SystemClock.elapsedRealtime() - remoteSetAt < 2_000
        val names = offerTargets()
        if (ClipboardOffer.shouldOffer(false, own, description != null && isSensitive(description), names.size, watching)) {
            ClipboardOffer.show(context, ClipboardOffer.target(names, context.getString(org.desklink.mobile.R.string.clipboard_many_computers)))
        }
    }

    /** Names of the computers that have clipboard sharing on and want the offer. */
    private fun offerTargets(): List<String> = runCatching {
        val key = org.desklink.mobile.plugins.Plugin.getPluginKey(ClipboardPlugin::class.java)
        org.desklink.mobile.DeskLinkApplication.getInstance().devices.values
            .filter { ClipboardOffer.eligible(it.isPaired, it.isPluginEnabled(key), it.hasActiveWebRtcFeatureTransport, ClipboardOffer.enabled(context, it.deviceId)) }
            .map { it.name }
    }.getOrDefault(emptyList())

    fun registerObserver(observer: ClipboardObserver) {
        observers.add(observer)
    }

    fun removeObserver(observer: ClipboardObserver) {
        observers.remove(observer)
    }

    /** Kept for callers of the old name: a changed clip goes out, an unchanged one does not. */
    fun onClipboardChanged() {
        sync(force = false)
    }

    /**
     * Reads the clipboard (a DeskLink window must have focus on Android 10+) and returns its text,
     * or null when it holds none or Android refused. Remembers it as the phone's current copy.
     */
    @MainThread
    fun readNow(): String? {
        val content = try {
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        } catch (e: Exception) {
            Log.w(TAG, "clipboard: unreadable (${e.javaClass.simpleName})")
            null
        }
        if (content.isNullOrEmpty()) return null
        currentContent = content
        updateTimestamp = System.currentTimeMillis()
        return content
    }

    /**
     * Hands the clipboard to every desk with clipboard sync: only a new copy, or any text when
     * [force]d (an explicit "Send clipboard"). Returns whether text went to at least one desk.
     */
    @MainThread
    fun sync(force: Boolean): Boolean {
        if (observers.isEmpty()) {
            if (force) Log.i(TAG, "clipboard: no desk has clipboard sync on")
            return false
        }
        // At most one send in 500 ms: a burst of copies (a selection being adjusted) goes out once, the last, a moment later.
        if (!force) {
            val wait = SendPacing.waitMs(lastSyncAt, SystemClock.elapsedRealtime())
            if (wait > 0) {
                if (!pacedSync) { pacedSync = true; main.postDelayed({ pacedSync = false; sync(force = false) }, wait) }
                return false
            }
        }
        lastSyncAt = SystemClock.elapsedRealtime()
        val description = try { cm.primaryClipDescription } catch (_: Exception) { null } ?: return false
        val stamp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) description.timestamp else 0L
        if (!force && stamp != 0L && stamp == lastClipStamp) return false
        lastClipStamp = stamp
        // Password managers mark their copies sensitive: never sent unless asked for.
        if (!force && isSensitive(description)) return false
        if (description.hasMimeType("image/*")) return syncImage(force)
        val previous = currentContent
        val content = readNow() ?: return false
        if (!force && content == previous) return false
        Log.i(TAG, "clipboard: ${content.length} chars to ${observers.size} desk(s) (${if (force) "asked" else "new copy"})")
        ClipboardOffer.cancel(context)
        val targets = observers.toList()
        // Off the main thread: sizing and queueing a megabyte of text is not the UI's work.
        val at = copyTime(description)
        execute { targets.forEach { it.clipboardChanged(content, at) } }
        return true
    }

    /** An image copy goes as a PNG file transfer; decoding happens off the main thread. */
    @MainThread
    private fun syncImage(force: Boolean): Boolean {
        val uri = try { cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri } catch (_: Exception) { null } ?: return false
        val at = cm.primaryClipDescription?.let { copyTime(it) } ?: System.currentTimeMillis()
        val key = "image:$uri"
        if (!force && key == currentContent) return false
        currentContent = key
        updateTimestamp = System.currentTimeMillis()
        val targets = observers.toList()
        ClipboardOffer.cancel(context)
        Log.i(TAG, "clipboard: image to ${targets.size} desk(s) (${if (force) "asked" else "new copy"})")
        execute {
            val png = ClipboardImages.encodePng(context, uri) ?: return@execute
            targets.forEach { it.clipboardImage(png, at) }
        }
        return true
    }

    /** The desk's image copy (a content URI of ours): on this phone's clipboard, never sent back. */
    fun setImage(uri: android.net.Uri) {
        remoteSetAt = SystemClock.elapsedRealtime()
        updateTimestamp = System.currentTimeMillis()
        currentContent = "image:$uri"
        cm.setPrimaryClip(ClipData.newUri(context.contentResolver, "DeskLink", uri))
    }

    /** The desk's copy: put on this phone's clipboard, and never sent back. */
    fun setText(text: String?) {
        if (text == null) return
        remoteSetAt = SystemClock.elapsedRealtime()
        updateTimestamp = System.currentTimeMillis()
        currentContent = text
        cm.setPrimaryClip(ClipData.newPlainText("DeskLink", text))
    }

    /** Whether the logcat watch is running now (and so whether the person's phone copies sync by themselves). */
    val watching: Boolean get() = logcat != null

    /**
     * "Automatic sync (advanced)", the one place that may start the logcat watch: the person turned it on
     * (or off) in Settings. Needs the READ_LOGS grant; without it nothing runs and nothing is asked.
     */
    @Synchronized
    fun setAutoSync(on: Boolean) {
        val want = shouldWatch(Build.VERSION.SDK_INT, readLogsGranted(context), requestedByPerson = on)
        if (want == (logcat != null)) return
        if (!want) {
            logcat?.destroy()
            logcat = null
            Log.i(TAG, "clipboard: automatic sync off")
            return
        }
        val since = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val filter = if (Build.VERSION.SDK_INT > Build.VERSION_CODES.VANILLA_ICE_CREAM) "E ClipboardService" else "ClipboardService:E"
        val process = try {
            Runtime.getRuntime().exec(arrayOf("logcat", "-T", since, filter, "*:S"))
        } catch (e: Exception) {
            Log.w(TAG, "clipboard: logcat unavailable", e)
            return
        }
        logcat = process
        Log.i(TAG, "clipboard: automatic sync on")
        execute {
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    if (deniesPackage(line, context.packageName)) main.post(::readInBackground)
                }
            } catch (_: Exception) {
            }
            synchronized(this) { if (logcat === process) logcat = null }
        }
    }

    /** Someone copied while DeskLink was behind: take focus for a moment and read it. */
    @MainThread
    private fun readInBackground() {
        val now = SystemClock.elapsedRealtime()
        // The desk's own copy is refused to our listener too, and one copy logs several lines.
        if (now - remoteSetAt < 2_000 || now - lastAutoRead < 1_000 || observers.isEmpty()) return
        lastAutoRead = now
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!Settings.canDrawOverlays(context)) {
            // Without "Display over other apps" only the activity can try (Android may block it).
            runCatching { context.startActivity(ClipboardFloatingActivity.getIntent(context, false)) }
            return
        }
        // A window context: WindowManager from the application context is the wrong visual context.
        val window = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val display = ContextCompat.getSystemService(context, DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return
            context.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else {
            context
        }
        val wm = ContextCompat.getSystemService(window, WindowManager::class.java) ?: return
        val view = object : View(window) {
            var done = false
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (hasWindowFocus && !done) {
                    done = true
                    sync(force = false)
                    runCatching { wm.removeView(this) }
                }
            }
        }
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSPARENT,
        )
        runCatching { wm.addView(view, params) }.onFailure { return }
        // Focus never came: do not leave a window behind.
        main.postDelayed({ if (!view.done) { view.done = true; runCatching { wm.removeView(view) } } }, 1_500)
    }

    @VisibleForTesting
    fun clipChangedForTest() = onPrimaryClipChanged()

    @VisibleForTesting
    fun resetPacing() { lastSyncAt = 0L; pacedSync = false }

    object CopyTime {
        /** The best known copy time: the clip's own stamp, else when the change was seen; clamped to [now]; [now] when neither is known. */
        fun of(clipStamp: Long, seenAt: Long, now: Long, lastSent: Long = 0L): Long =
            (clipStamp.takeIf { it > 0 } ?: seenAt.takeIf { it > 0 } ?: now).coerceAtMost(now).coerceAtLeast(lastSent)
    }

    /** The 500 ms pacing, pure for the test. */
    object SendPacing {
        const val MIN_GAP_MS = 500L
        fun waitMs(lastAt: Long, now: Long): Long = if (lastAt == 0L) 0L else (MIN_GAP_MS - (now - lastAt)).coerceIn(0L, MIN_GAP_MS)
    }

    companion object {
        private const val TAG = "DeskLink/Clipboard"
        /** The old stored switch: it made a grant alone start the watch at launch. Removed quietly (see RemovedFeatures). */
        const val KEY_AUTO_SYNC = "clipboard_auto_sync"

        private var _instance: ClipboardListener? = null

        @JvmStatic
        @Synchronized
        fun instance(context: Context): ClipboardListener =
            // Synchronized: two listeners meant two logcat readers and every copy sent twice.
            _instance ?: ClipboardListener(context).also { _instance = it }

        /** Whether this build declares the permission the workaround needs (only hand-installed debug builds do). */
        fun workaroundDeclared(context: Context): Boolean = runCatching {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions
                .orEmpty().contains(Manifest.permission.READ_LOGS)
        }.getOrDefault(false)

        fun readLogsGranted(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED

        /** "Automatic sync (advanced)" is on when the watch is actually running: asked for by the person, this run. */
        fun autoSyncActive(context: Context): Boolean = _instance?.watching == true

        /**
         * The logcat watch runs only for Android 10+, with the READ_LOGS grant, when the person asked for it.
         * Never because the grant exists (a cold start must not trigger the system's log-access question).
         */
        @VisibleForTesting
        fun shouldWatch(sdk: Int, readLogsGranted: Boolean, requestedByPerson: Boolean): Boolean =
            sdk >= Build.VERSION_CODES.Q && readLogsGranted && requestedByPerson

        /** The one-line grant, for the package actually installed (debug builds end in .debug). */
        fun grantCommand(context: Context) = "adb shell pm grant ${context.packageName} android.permission.READ_LOGS"

        /** ClipboardService refusing [pkg] itself, not a package whose name merely starts the same. */
        @VisibleForTesting
        fun deniesPackage(line: String, pkg: String): Boolean =
            Regex("(^|[^\\w.])" + Regex.escape(pkg) + "($|[^\\w.])").containsMatchIn(line)

        private fun isSensitive(description: ClipDescription): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true

        /** Every DeskLink window gaining focus reads the clipboard: the phone's copies go out on open. */
        fun followForeground(app: Application) {
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    if (activity is ClipboardFloatingActivity) return
                    activity.window.decorView.viewTreeObserver.addOnWindowFocusChangeListener { focused ->
                        if (focused) instance(activity).sync(force = false)
                    }
                }
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
        }
    }
}
