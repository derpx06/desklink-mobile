/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.folders

import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.desklink.mobile.R
import org.desklink.mobile.plugins.share.TransferRecord
import org.desklink.mobile.plugins.share.previewKind
import org.desklink.mobile.ui.compose.components.IconCircle
import org.desklink.mobile.ui.compose.screen.transfers.iconFor
import org.desklink.mobile.ui.compose.screen.transfers.rememberFilePreview
import org.desklink.mobile.ui.compose.tokens

/** Home's Bucket card: the last 3 items, or how to start; one tap opens the Bucket. Local-first: always shown. */
@Composable
fun BucketHomeCard(deviceId: String, desk: String, onOpen: () -> Unit) {
    val t = tokens
    val context = LocalContext.current
    val views by BucketEngine.views.collectAsState()
    val view = views[deviceId]
    val on = view?.treeUri != null && view.phase != Phase.ASKING
    val items = view?.items.orEmpty().take(3)
    val shape = RoundedCornerShape(24.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(t.surface).border(1.dp, t.hairline, shape).clickable(role = Role.Button, onClick = onOpen)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconCircle(R.drawable.sym_inventory, 36.dp, 20.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.bucket_title), fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.semantics { heading() })
                val summary = view?.summary
                Text(
                    when {
                        summary != null -> BucketActivity.summaryText(context, summary, view.userPaused, desk)
                        !on -> stringResource(R.string.bucket_home_off, desk)
                        items.isEmpty() -> stringResource(R.string.bucket_home_empty, desk)
                        else -> Formatter.formatShortFileSize(context, view!!.bytes)
                    },
                    fontSize = 12.sp, color = t.textDim, maxLines = 2,
                )
            }
            Icon(painterResource(R.drawable.sym_chevron_right), contentDescription = null, tint = t.textDim, modifier = Modifier.size(20.dp))
        }
        view?.summary?.let { summary ->
            androidx.compose.material3.LinearProgressIndicator(
                { summary.fraction },
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp).height(3.dp),
                color = t.accent, trackColor = t.fill, drawStopIndicator = {}, gapSize = 0.dp,
            )
        }
        if (items.isNotEmpty()) {
            val sizePx = with(LocalDensity.current) { 96.dp.roundToPx() }
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { item ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(14.dp)).background(t.accentContainer), contentAlignment = Alignment.Center) {
                            val uri = item.uri
                            val bitmap = uri?.let {
                                val preview by rememberFilePreview(TransferRecord(it.toString() + item.mtime, deviceId, desk, item.name, item.size, false, item.mtime, it.toString(), null), sizePx)
                                preview.bitmap
                            }
                            if (bitmap != null) {
                                Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            } else {
                                Icon(painterResource(iconFor(previewKind(null, item.name))), contentDescription = null, tint = t.onAccentContainer, modifier = Modifier.size(26.dp))
                            }
                        }
                        Text(item.name, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, color = t.textDim)
                    }
                }
                repeat(3 - items.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}
