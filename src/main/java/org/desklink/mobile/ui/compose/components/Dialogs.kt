/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.desklink.mobile.ui.compose.DeskButtonStyle
import org.desklink.mobile.ui.compose.DeskLinkShape
import org.desklink.mobile.ui.compose.StadiumButton
import org.desklink.mobile.ui.compose.tokens

/** One button of a [DeskDialog]: [destructive] makes a confirm the coral one (remove, unpair, close). */
class DialogAction(val label: String, val enabled: Boolean = true, val destructive: Boolean = false, val onClick: () -> Unit)

/**
 * The one dialog: a 24 dp card on the raised surface, a 20 sp title, an optional body ([text], scrolls when a big font or
 * a landscape phone leaves no room), then stadium buttons at the end: [dismiss] outlined, [confirm] filled (coral when destructive).
 * Both wrap onto a second line at a large font instead of being cut. A dialog that only informs passes [confirm] alone; one
 * that waits for something passes neither.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeskDialog(
    onDismissRequest: () -> Unit,
    title: String? = null,
    confirm: DialogAction? = null,
    dismiss: DialogAction? = null,
    properties: DialogProperties = DialogProperties(),
    text: (@Composable () -> Unit)? = null,
) {
    val t = tokens
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        Surface(shape = DeskLinkShape.group, color = t.surface2, contentColor = t.text, tonalElevation = 0.dp, shadowElevation = 8.dp, modifier = Modifier.widthIn(max = 560.dp)) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                title?.let { Text(it, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) }
                text?.let {
                    // Scrolls here, so the buttons below always stay on screen.
                    Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        androidx.compose.material3.ProvideTextStyle(TextStyle(fontSize = 15.sp, lineHeight = 21.sp)) { it() }
                    }
                }
                if (confirm != null || dismiss != null) {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        dismiss?.let { StadiumButton(it.label, it.onClick, style = DeskButtonStyle.Outlined, height = 48.dp, enabled = it.enabled) }
                        confirm?.let {
                            StadiumButton(it.label, it.onClick, style = if (it.destructive) DeskButtonStyle.Danger else DeskButtonStyle.Primary, height = 48.dp, enabled = it.enabled)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The text field of the app: an outlined field with 16 dp corners in the tokens (accent when focused, coral on [isError]).
 * A thin wrapper so a dialog, an editor and a sheet look the same; every parameter is the stock field's.
 */
@Composable
fun DeskTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    isError: Boolean = false,
    supportingText: String? = null,
    textStyle: TextStyle = TextStyle(fontSize = 16.sp),
    placeholderStyle: TextStyle = textStyle,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val t = tokens
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.heightIn(min = 56.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        isError = isError,
        textStyle = textStyle.copy(color = t.text),
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it, style = placeholderStyle) } },
        supportingText = supportingText?.let { { Text(it, color = if (isError) t.coralText else t.textDim) } },
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = t.text, unfocusedTextColor = t.text,
            focusedBorderColor = t.accent, unfocusedBorderColor = t.hairlineStrong, errorBorderColor = t.coral,
            focusedLabelColor = t.accentText, unfocusedLabelColor = t.textDim, errorLabelColor = t.coralText,
            focusedPlaceholderColor = t.textDim, unfocusedPlaceholderColor = t.textDim,
            cursorColor = t.accent, errorCursorColor = t.coral,
            focusedContainerColor = t.surface, unfocusedContainerColor = t.surface, errorContainerColor = t.surface,
        ),
    )
}
