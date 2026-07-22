/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.transport

/** Callback boundary intentionally contains no coroutines or Kotlin Flow. */
interface TransportCallback {
    fun onSuccess()

    fun onFailure(error: TransportError)

    fun onProgress(percent: Int) {}
}
