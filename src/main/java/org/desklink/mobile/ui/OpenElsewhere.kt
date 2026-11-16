/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent

/** Handing a received file to another app: the ways it can fail are said, never a crash on the main thread. */
object OpenElsewhere {
    enum class Outcome { STARTED, NO_APP, NOT_ALLOWED }

    /**
     * [intent] is built here, inside the guard: `FileProvider.getUriForFile` throws for a path outside its roots, and
     * starting with a read grant on a URI DeskLink no longer holds (a revoked or expired grant) throws SecurityException.
     */
    fun start(context: Context, intent: () -> Intent): Outcome = try {
        context.startActivity(intent())
        Outcome.STARTED
    } catch (_: ActivityNotFoundException) {
        Outcome.NO_APP
    } catch (_: SecurityException) {
        Outcome.NOT_ALLOWED
    } catch (_: IllegalArgumentException) {
        Outcome.NOT_ALLOWED
    }
}
