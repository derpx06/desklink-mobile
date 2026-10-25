/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.share

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * What an intent from ANOTHER app may ask the Bucket and Notes to take. Only a proposal: both screens ask the person before they
 * add or save anything (any app can send these intents, and what is added reaches the computer).
 */
object ShareIntake {
    const val MAX_ITEMS = 500
    const val MAX_TEXT_CHARS = 1024 * 1024

    data class Proposal(val uris: List<Uri> = emptyList(), val text: String? = null) {
        val isEmpty get() = uris.isEmpty() && text == null
    }

    @Suppress("DEPRECATION")
    fun forBucket(context: Context, intent: Intent): Proposal = when (intent.action) {
        Intent.ACTION_SEND -> {
            val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.takeIf { ShareUris.isAllowedFromOutside(context, it) }
            if (stream != null) Proposal(uris = listOf(stream))
            else Proposal(text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.take(MAX_TEXT_CHARS))
        }
        Intent.ACTION_SEND_MULTIPLE ->
            Proposal(uris = ShareUris.fromOutside(context, intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()).take(MAX_ITEMS))
        else -> Proposal()
    }

    /** (subject, text) of a text share for Notes, or null when there is nothing in it. */
    fun forNotes(intent: Intent): Pair<String, String>? {
        if (intent.action != Intent.ACTION_SEND) return null
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty().take(MAX_TEXT_CHARS)
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty().take(200)
        return if (text.isBlank() && subject.isBlank()) null else subject to text
    }
}
