/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/** The few moments the app answers with a touch of vibration. */
enum class Haptic { TICK, TOGGLE_ON, TOGGLE_OFF, LONG_PRESS, SUCCESS, FAILURE }

/**
 * The one place the app vibrates for the interface. It goes through [View.performHapticFeedback] without the "ignore
 * global setting" flag, so Android's own "Touch feedback" / "Vibration" setting turns it off for everyone: no extra
 * preference, no vibrator permission, no custom waveform (battery). Older versions fall back to the nearest constant.
 */
@Stable
class DeskHaptics(private val view: View?) {
    fun perform(haptic: Haptic) {
        view?.performHapticFeedback(constantFor(haptic, Build.VERSION.SDK_INT))
    }

    fun toggle(on: Boolean) = perform(if (on) Haptic.TOGGLE_ON else Haptic.TOGGLE_OFF)

    companion object {
        /** Pure so a test can pin it: the constant used for [haptic] on [sdk]. */
        fun constantFor(haptic: Haptic, sdk: Int): Int = when (haptic) {
            Haptic.TICK -> HapticFeedbackConstants.CLOCK_TICK
            Haptic.LONG_PRESS -> HapticFeedbackConstants.LONG_PRESS
            Haptic.TOGGLE_ON -> if (sdk >= 34) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.CONTEXT_CLICK
            Haptic.TOGGLE_OFF -> if (sdk >= 34) HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.CLOCK_TICK
            Haptic.SUCCESS -> if (sdk >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK
            Haptic.FAILURE -> if (sdk >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        }
    }
}

@Composable
fun rememberDeskHaptics(): DeskHaptics {
    val view = LocalView.current
    return remember(view) { DeskHaptics(view) }
}
