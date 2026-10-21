/*
 * SPDX-FileCopyrightText: 2015 David Edmundson <kde@davidedmundson.co.uk>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/
package org.desklink.mobile.plugins.mousepad

import android.view.inputmethod.BaseInputConnection

/**
 * Text from the phone keyboard, forwarded to the remote desktop.
 *
 * Keyboards *compose* before they commit: voice typing streams a phrase as
 * composing text, and a typing keyboard may compose the current word. Only
 * committed text used to be sent, so anything that ended with
 * `finishComposingText` (voice input, switching fields) was silently lost.
 * Composing text is held here and sent once it is final.
 *
 * Nothing is kept as local text: the field always looks empty to the
 * keyboard, so Backspace arrives as a key event and reaches the desktop.
 */
internal class KeyInputConnection(private val view: KeyListenerView, fullEditor: Boolean) :
    BaseInputConnection(view, fullEditor) {

    private var composing: CharSequence = ""

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        // A commit replaces whatever was composing.
        composing = ""
        if (text.isNotEmpty()) view.sendChars(text)
        return true
    }

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        composing = text
        return true
    }

    override fun finishComposingText(): Boolean {
        val finished = composing
        composing = ""
        if (finished.isNotEmpty()) view.sendChars(finished)
        return true
    }

    override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence = ""

    override fun getTextAfterCursor(length: Int, flags: Int): CharSequence = ""
}
