/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.folders

import android.content.Context
import org.desklink.mobile.R

/**
 * The Bucket's words for progress. Its notification is the one every transfer shares (see TransferNotification): this keeps the
 * strings the Bucket screens and that notification use.
 */
object BucketSyncNotification {
    fun words(context: Context) = BucketProgress.Words(
        ofTotal = context.getString(R.string.bucket_of_total),
        joined = context.getString(R.string.bucket_joined),
        seconds = context.getString(R.string.bucket_eta_seconds),
        minutes = context.getString(R.string.bucket_eta_minutes),
        hours = context.getString(R.string.bucket_eta_hours),
        waiting = context.getString(R.string.bucket_waiting),
        paused = context.getString(R.string.bucket_paused),
        perSecond = context.getString(R.string.bucket_per_second),
    )

    fun noteWords(context: Context) = BucketProgress.NoteWords(
        titleOne = context.getString(R.string.bucket_note_title_one),
        titleMany = context.getString(R.string.bucket_note_title_many),
        about = context.getString(R.string.bucket_about),
        line = context.getString(R.string.bucket_note_line),
        percent = context.getString(R.string.bucket_note_percent),
        more = context.getString(R.string.bucket_note_more),
        paused = context.getString(R.string.bucket_note_paused),
        sending = context.getString(R.string.bucket_note_sending),
        receiving = context.getString(R.string.bucket_note_receiving),
        both = context.getString(R.string.bucket_note_both),
        doneOne = context.getString(R.string.bucket_done_one),
        doneMany = context.getString(R.string.bucket_done_many),
    )
}
