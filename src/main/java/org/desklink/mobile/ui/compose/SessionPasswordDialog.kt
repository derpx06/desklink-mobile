/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.ui.compose

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.desklink.mobile.R
import org.desklink.mobile.webrtc.SessionPassword

/**
 * Asks for the computer's session password. [wrong] says the last one was refused. The text is
 * kept only in this dialog and in memory once submitted, never saved.
 */
@Composable
fun SessionPasswordDialog(wrong: Boolean, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val valid = text.isNotEmpty() && SessionPassword.fits(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.session_password_title)) },
        text = {
            Column {
                Text(stringResource(R.string.session_password_message))
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.session_password_label)) },
                    isError = wrong,
                    supportingText = if (wrong) {
                        { Text(stringResource(R.string.session_password_wrong), color = MaterialTheme.colorScheme.error) }
                    } else {
                        null
                    },
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (valid) onSubmit(text) }),
                    trailingIcon = {
                        TextButton(onClick = { visible = !visible }) {
                            Text(stringResource(if (visible) R.string.session_password_hide else R.string.session_password_show))
                        }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(text) }, enabled = valid) {
                Text(stringResource(R.string.session_password_unlock))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** "Too many attempts. Try again in …", for a lockout of [seconds]. */
fun sessionPasswordLockedMessage(context: Context, seconds: Long): String =
    if (seconds < 60) {
        context.getString(R.string.session_password_locked_seconds, seconds.coerceAtLeast(1))
    } else {
        context.getString(R.string.session_password_locked_minutes, (seconds + 59) / 60)
    }
