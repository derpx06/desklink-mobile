/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui

/**
 * The rule: wherever a feature is blocked, the screen shows ONE action that does it (a button here, Ask on computer, Android's
 * permission dialog or settings page, Copy command), never only instructions. BlockedActionsTest fails for any English string that
 * reads like a manual step ("turn on", "Settings,", "Open DeskLink", "in DeskLink on"...) and is not listed here with its action.
 */
object BlockedActions {
    /** String resource -> the action shown with it. */
    val STRINGS: Map<String, String> = mapOf(
        "qr_not_a_pairing_code" to "the scanner it is shown over: scan again",
        "welcome_qr_1" to "the scanner on the same screen",
        "guide_pair_how" to "the card's Pair button",
        "mouse_receiver_service_description" to "none needed: Android's own description of the service, not a blocked state",
        "home_clipboard_on_q" to "the Send clipboard button and tile",
        "settings_clipboard_auto_grant_oem" to "the row copies the adb command (Copy command)",
        "accessibility_setup_title" to "the dialog's button opens Accessibility settings",
        "accessibility_setup_step" to "the dialog's button opens Accessibility settings",
        "accessibility_setup_ask_text" to "the notification opens the accessibility setup",
        "settings_glide_accessibility" to "the Allow row under it opens the accessibility setup",
        "widget_cd_open_app" to "is the action: opens DeskLink",
        "bucket_turn_on" to "is the action",
        "everything_step_turn_on" to "the Everything row's Turn on button (turns the switch on, then asks for its permission)",
        "everything_glide_off" to "the Everything Glide row's Turn on button",
        "bucket_home_off" to "the card opens the Bucket, whose Turn on button turns it on",
        "perm_title" to "each row's Allow button",
        "setup_step_notification_access" to "the step's button opens Android's notification access page",
        "home_turn_on" to "is the action",
        "home_notifications_turn_on" to "is the action",
        "ask_blocked" to "Ask again (and the desk's window shows the request)",
    )

    /** Every place a computer-side switch blocks a feature on the phone -> the asks.json toDesk key its Ask on computer sends. */
    val ASK_ON_COMPUTER: Map<String, String> = mapOf(
        "Terminals tab and terminal screen: terminal access off" to "terminalAccess",
        "Home Unlock action and Settings, Privacy: unlock with this phone off" to "unlockNearby",
        "Settings, Connection: keep the computer awake not allowed" to "keepAwakeAllowed",
        "Settings, Notifications: share Do Not Disturb off at the computer" to "dndSync",
        "Settings, Files: photos and screenshots not accepted" to "galleryAccepted",
        "Settings, Control: Glide off at the computer" to "glide",
        "Settings, Control: control of the computer's screen not approved" to "control",
    )
}
