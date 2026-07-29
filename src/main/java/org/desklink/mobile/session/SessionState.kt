/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.session

/** Lifecycle of the logical session owned by [DeviceManager]. */
enum class SessionState {
    ACTIVE,
    DISCONNECTED,
    TERMINATED,
}
