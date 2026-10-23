/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.plugins.deskunlock

import android.content.Context
import org.desklink.mobile.helpers.DeskNames
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R
import org.desklink.mobile.ui.BiometricResult
import org.desklink.mobile.ui.Biometrics
import org.desklink.mobile.ui.compose.DeskLinkTheme
import org.desklink.mobile.ui.compose.tokens

/**
 * "Unlock arch-desk?": a sheet over whatever is on screen, then the system's biometric or
 * screen-lock prompt. Only its success approves the desk's nonce; anything else leaves the desk
 * locked (the nonce expires there).
 */
class UnlockActivity : AppCompatActivity() {
    private val deviceId by lazy { intent.getStringExtra(EXTRA_DEVICE_ID).orEmpty() }
    private val plugin get() = DeskLinkApplication.getInstance().getDevicePlugin(deviceId, DeskUnlockPlugin::class.java)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Over the lock screen too: the desk's button is pressed with this phone in a pocket.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        val offer = plugin?.offer
        if (offer == null) {
            finish()
            return
        }
        val desk = DeskNames.of(deviceId)
        // Back is "Not me": the desk stays locked.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = decline()
        })
        setContent {
            DeskLinkTheme(this) {
                UnlockSheet(desk, offer.reason, onUnlock = ::authenticate, onDecline = ::decline)
            }
        }
        if (savedInstanceState == null) authenticate()
    }

    private fun authenticate() {
        val desk = DeskNames.of(deviceId)
        if (!Biometrics.available(this, Biometrics.STRONG_OR_CREDENTIAL)) {
            // No screen lock at all: nothing proves it is the owner holding the phone.
            Toast.makeText(this, R.string.unlock_no_screen_lock, Toast.LENGTH_LONG).show()
            return
        }
        Biometrics.prompt(this, getString(R.string.unlock_title, desk), getString(R.string.unlock_prompt_subtitle), Biometrics.STRONG_OR_CREDENTIAL, negative = null) { result ->
            when (result) {
                is BiometricResult.Succeeded -> {
                    val sent = plugin?.approve() == true
                    if (!sent) Toast.makeText(this, R.string.unlock_expired, Toast.LENGTH_LONG).show()
                    finish()
                }
                BiometricResult.Cancelled -> Unit // the sheet stays: Touch to unlock, or Not me
                is BiometricResult.Failed -> Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun decline() {
        plugin?.decline()
        finish()
    }


    companion object {
        private const val EXTRA_DEVICE_ID = "deviceId"

        fun intent(context: Context, deviceId: String): Intent =
            Intent(context, UnlockActivity::class.java).putExtra(EXTRA_DEVICE_ID, deviceId)
    }
}

@Composable
private fun UnlockSheet(desk: String, reason: String, onUnlock: () -> Unit, onDecline: () -> Unit) {
    val t = tokens
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable(onClick = onDecline, onClickLabel = stringResource(R.string.unlock_decline))) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(t.surface2, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .clickable(enabled = false) {}
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(32.dp, 4.dp).background(t.hairlineStrong, RoundedCornerShape(2.dp)))
            Text(
                stringResource(R.string.unlock_title, desk),
                fontSize = 26.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp).semantics { heading() },
            )
            Text(
                stringResource(if (reason == UnlockOffer.REASON_BUTTON) R.string.unlock_body_button else R.string.unlock_body_phone),
                fontSize = 15.sp,
                lineHeight = 21.sp,
                color = t.textDim,
                textAlign = TextAlign.Center,
            )
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .size(76.dp)
                    .background(t.accentContainer, CircleShape)
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.unlock_touch), onClick = onUnlock),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.sym_fingerprint), contentDescription = stringResource(R.string.unlock_touch), tint = t.onAccentContainer, modifier = Modifier.size(36.dp))
            }
            Text(stringResource(R.string.unlock_touch), fontSize = 13.sp, color = t.textDim)
            Box(Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onDecline), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.unlock_decline), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = t.red, modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
    }
}
