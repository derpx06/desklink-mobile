/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.ui.compose.update

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.desklink.mobile.ui.compose.tokens

/**
 * The update sheet, hosted once at the root of the main screen over whatever opened first (docs/UPDATES.md 6.3). No tab, screen
 * or list contains update UI. Swipe down, back and a tap outside all call [UpdateSheetActions.onNotNow]; a required update is
 * only dismissed for this open. The system's animator scale applies to the slide (0 = it simply appears).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UpdatePromptHost(model: UpdateSheetModel?, actions: UpdateSheetActions) {
    if (model == null) return
    val t = tokens
    ModalBottomSheet(
        onDismissRequest = actions.onNotNow,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // A tablet: centred, no wider than 560 dp.
        sheetMaxWidth = 560.dp,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = t.surface2,
        dragHandle = { BottomSheetDefaults.DragHandle(width = 38.dp, height = 4.dp, color = t.hairlineStrong) },
    ) {
        UpdateSheetContent(model, actions, Modifier.fillMaxWidth().verticalScroll(rememberScrollState()))
    }
}
