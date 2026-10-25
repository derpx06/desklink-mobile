/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.folders

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.format.DateUtils
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.horizontalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R
import org.desklink.mobile.plugins.share.TransferRecord
import org.desklink.mobile.plugins.share.previewKind
import org.desklink.mobile.ui.compose.DeskLinkTheme
import org.desklink.mobile.ui.compose.MonoFamily
import org.desklink.mobile.ui.compose.components.IconCircle
import org.desklink.mobile.ui.compose.components.LargeTitle
import org.desklink.mobile.ui.compose.components.PillButton
import org.desklink.mobile.ui.compose.components.PillStyle
import org.desklink.mobile.ui.compose.components.ScreenHeader
import org.desklink.mobile.ui.compose.components.shimmer
import org.desklink.mobile.ui.compose.screen.transfers.iconFor
import org.desklink.mobile.ui.compose.screen.transfers.rememberFilePreview
import org.desklink.mobile.ui.compose.tokens
import java.io.File

/**
 * F8b, the Bucket: everything put in it shows up on the desk too. A thumbnail grid, newest first,
 * each item with where it came from and its state; `+` adds files, a photo or the clipboard; the
 * share sheet's "DeskLink Bucket" adds anything from any app.
 */
class BucketActivity : AppCompatActivity() {
    /** Shared in from another app, waiting for the Bucket (or added at once). */
    private val pendingUris = mutableStateOf<List<Uri>>(emptyList())
    private val pendingText = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        BucketEngine.start(this)
        val deviceId = deskFor(intent.getStringExtra(EXTRA_DEVICE)) ?: run {
            Toast.makeText(this, R.string.bucket_no_desk, Toast.LENGTH_LONG).show()
            return finish()
        }
        if (savedInstanceState == null) takeShare(intent)
        setContent { DeskLinkTheme(this) { org.desklink.mobile.ui.compose.SecureWindow(); Screen(deviceId) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeShare(intent)
    }

    /** The share sheet (one or several files, or text and links). */
    private fun takeShare(intent: Intent) {
        val proposal = org.desklink.mobile.plugins.share.ShareIntake.forBucket(this, intent)
        if (proposal.uris.isNotEmpty()) pendingUris.value = proposal.uris
        proposal.text?.let { pendingText.value = it }
    }

    /** The desk to use: the one asked for, else the one with a Bucket, else the first paired. */
    private fun deskFor(wanted: String?): String? {
        val app = DeskLinkApplication.getInstance()
        val paired = app.devices.values.filter { it.isPaired }
        wanted?.takeIf { id -> paired.any { it.deviceId == id } }?.let { return it }
        val withBucket = BucketEngine.views.value.values.firstOrNull { it.treeUri != null && paired.any { d -> d.deviceId == it.deviceId } }
        return withBucket?.deviceId ?: paired.firstOrNull { it.hasActiveWebRtcFeatureTransport }?.deviceId ?: paired.firstOrNull()?.deviceId
    }

    @Composable
    private fun Screen(deviceId: String) = ui.Screen(deviceId, onBack = ::finish)

    private val ui by lazy { BucketUi(this, pendingUris, pendingText) }

    companion object {
        const val EXTRA_DEVICE = "deviceId"
        /** Files taken from one share. */
        const val MAX_SHARED = 500

        fun intent(context: Context, deviceId: String?): Intent = Intent(context, BucketActivity::class.java).apply { deviceId?.let { putExtra(EXTRA_DEVICE, it) } }

        /** The reason a thing is not synced, as a plural-free format with the count first. */
        @androidx.annotation.StringRes
        fun skipText(why: Skip): Int = when (why) {
            Skip.TOO_DEEP -> R.string.bucket_skip_too_deep
            Skip.NAME_TOO_LONG -> R.string.bucket_skip_name_long
            Skip.INVALID_NAME -> R.string.bucket_skip_invalid
            Skip.CASE_CLASH -> R.string.bucket_skip_case
            Skip.TOO_LARGE -> R.string.bucket_skip_large
            Skip.COULDNT_FETCH -> R.string.bucket_skip_fetch
        }

        /** "Syncing 3 files · 1.1 GB of 2.4 GB · about 4 min left" (Paused while the link or the person pauses it). */
        fun summaryText(context: Context, summary: ProgressSummary, userPaused: Boolean, desk: String): String {
            val w = BucketSyncNotification.words(context)
            val n = BucketSyncNotification.noteWords(context)
            val head = if (summary.files == 1) context.getString(R.string.bucket_header_syncing_one) else context.getString(R.string.bucket_header_syncing_many).format(summary.files)
            val amount = w.ofTotal.format(org.desklink.mobile.helpers.Sizes.iec(summary.done), org.desklink.mobile.helpers.Sizes.iec(summary.total))
            val tail = when {
                userPaused -> context.getString(R.string.bucket_paused_by_you)
                summary.paused -> n.paused.format(desk)
                else -> summary.etaSeconds?.let { n.about.format(BucketProgress.eta(it, w)) }
            }
            return listOfNotNull(head, amount, tail).joinToString(" · ")
        }

        /** Documents on the phone's storage: where the folder picker starts ("Choose or create DeskLink Bucket"). */
        fun documentsUri(): Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents")
    }
}
