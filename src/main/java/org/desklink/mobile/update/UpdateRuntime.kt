/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.update

import android.content.Context
import android.net.ConnectivityManager
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.desklink.mobile.BuildConfig

/**
 * The one process-wide instance, built lazily from the app context, and the UI-free background refresh (docs/UPDATES.md 4.1):
 * a coroutine in this object's own scope, started and stopped by BackgroundService. No alarm, no wake lock, no WorkManager.
 * Every call is wrapped so a failure here can never reach the service. With an empty build URL nothing runs at all.
 */
internal object UpdateRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var instance: Holder? = null

    internal class Holder(val engine: UpdateEngine, val store: UpdateStore)

    fun get(context: Context): Holder = instance ?: synchronized(this) {
        instance ?: run {
            val app = context.applicationContext
            val store = UpdateStore(app)
            val name = BuildConfig.VERSION_NAME
            val build = UpdateBuild(Version.parseLenient(name), BuildConfig.VERSION_CODE, name, BuildConfig.DEFAULT_UPDATE_URL, BuildConfig.DEBUG)
            val cm = app.getSystemService<ConnectivityManager>()
            val engine = UpdateEngine(build, store, UpdateChecker(name, BuildConfig.DEBUG), UpdateKeys::all, System::currentTimeMillis) {
                cm?.isActiveNetworkMetered == false
            }
            Holder(engine, store).also { instance = it }
        }
    }

    fun startBackground(context: Context) {
        runCatching {
            // An empty build URL (and no debug override to look at) is strictly inert: nothing is built, nothing runs.
            if (BuildConfig.DEFAULT_UPDATE_URL.isBlank() && !BuildConfig.DEBUG) return
            val h = get(context)
            if (!h.engine.configured() || job?.isActive == true) return
            job = scope.launch {
                delay(h.engine.initialDelayMs())
                while (isActive) {
                    runCatching { h.engine.refresh() }
                    delay(h.engine.nextDelayMs())
                }
            }
        }
    }

    fun stopBackground() {
        job?.cancel()
        job = null
    }

    internal fun scopeForOpen(): CoroutineScope = scope

    @Suppress("unused") // for tests that reset between runs
    internal fun reset() {
        stopBackground()
        instance = null
    }
}
