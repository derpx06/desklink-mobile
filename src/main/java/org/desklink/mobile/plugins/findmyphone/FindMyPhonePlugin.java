/*
 * SPDX-FileCopyrightText: 2015 David Edmundson <david@davidedmundson.co.uk>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/

package org.desklink.mobile.plugins.findmyphone;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import org.apache.commons.lang3.ArrayUtils;
import org.desklink.mobile.helpers.DeviceHelper;
import org.desklink.mobile.helpers.LifecycleHelper;
import org.desklink.mobile.helpers.NotificationHelper;
import org.desklink.mobile.NetworkPacket;
import org.desklink.mobile.plugins.Plugin;
import org.desklink.mobile.plugins.PluginFactory;
import org.desklink.mobile.ui.PluginSettingsFragment;
import org.desklink.mobile.R;
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol;

@PluginFactory.LoadablePlugin
public class FindMyPhonePlugin extends Plugin {
    public final static String PACKET_TYPE_FINDMYPHONE_REQUEST = DeskLinkProtocol.PACKET_TYPE_FINDMYPHONE_REQUEST;

    private NotificationManager notificationManager;
    private int notificationId;
    private AudioManager audioManager;
    private MediaPlayer mediaPlayer;
    private int previousVolume = -1;
    private FlashlightManager flashlightManager;
    private Vibrator vibrator;
    /** The ringtone could not be played on the last ring: vibrate and flash instead. */
    private boolean silent;
    private boolean audioFailureLogged;

    @Override
    public @NonNull String getDisplayName() {
        switch (DeviceHelper.getDeviceType()) {
            case TV:
                return context.getString(R.string.findmyphone_title_tv);
            case TABLET:
                return context.getString(R.string.findmyphone_title_tablet);
            case PHONE:
            default:
                return context.getString(R.string.findmyphone_title);
        }
    }

    @Override
    public @NonNull String getDescription() {
        return context.getString(R.string.findmyphone_description);
    }

    @Override
    public boolean onCreate() {
        notificationManager = ContextCompat.getSystemService(context, NotificationManager.class);
        notificationId = (int) System.currentTimeMillis();
        audioManager = ContextCompat.getSystemService(context, AudioManager.class);
        flashlightManager = new FlashlightManager(context);

        vibrator = ContextCompat.getSystemService(context, Vibrator.class);
        // The player is built when the phone rings, never here: a ringtone that cannot be prepared
        // (no audio device, a missing or not-yet-unlocked file) must not unload the plugin.
        return true;
    }

    @Override
    public void onDestroy() {
        stopPlaying();
        stopFlashing();
        audioManager = null;
        flashlightManager = null;
    }

    @Override
    public boolean onPacketReceived(@NonNull NetworkPacket np) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || LifecycleHelper.isInForeground()) {
            Intent intent = new Intent(context, FindMyPhoneActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(FindMyPhoneActivity.EXTRA_DEVICE_ID, getDevice().getDeviceId());
            context.startActivity(intent);
        } else {
            // Ring from here, screen on or off: a full-screen intent would need
            // USE_FULL_SCREEN_INTENT, which Play grants only to calling and alarm apps.
            startPlaying();
            startFlashing();
            showBroadcastNotification();
        }
        return true;
    }

    private void showBroadcastNotification() {
        Intent intent = new Intent(context, FindMyPhoneReceiver.class);
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        intent.setAction(FindMyPhoneReceiver.ACTION_FOUND_IT);
        intent.putExtra(FindMyPhoneReceiver.EXTRA_DEVICE_ID, getDevice().getDeviceId());

        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        createNotification(pendingIntent);
    }

    private void createNotification(PendingIntent pendingIntent) {
        NotificationCompat.Builder notification = new NotificationCompat.Builder(context, NotificationHelper.Channels.HIGHPRIORITY);
        notification
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(false)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setOngoing(true)
                .setContentTitle(context.getString(R.string.findmyphone_found));
        notification.setGroup("BackgroundService");

        notificationManager.notify(notificationId, notification.build());
    }

    void startPlaying() {
        if (audioManager == null || mediaPlayer != null || silent) {
            return; // destroyed, or already ringing
        }
        mediaPlayer = preparePlayer();
        if (mediaPlayer == null) {
            silent = true;
            startVibrating();
            return;
        }
        // Make sure we are heard even when the phone is silent, restore original volume later
        previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
        mediaPlayer.start();
    }

    private MediaPlayer preparePlayer() {
        MediaPlayer player = new MediaPlayer();
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            String ringtoneString = prefs.getString(context.getString(R.string.findmyphone_preference_key_ringtone), "");
            Uri ringtone = ringtoneString.isEmpty() ? Settings.System.DEFAULT_RINGTONE_URI : Uri.parse(ringtoneString);
            player.setDataSource(context, ringtone);
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                .build();
            player.setWakeMode(context, PowerManager.SCREEN_DIM_WAKE_LOCK); // Prevent screen turning off, requires WAKE_LOCK permission
            player.setAudioAttributes(audioAttributes);
            player.setLooping(true);
            player.prepare();
            return player;
        } catch (Exception e) {
            player.release();
            if (!audioFailureLogged) {
                audioFailureLogged = true;
                Log.e("FindMyPhonePlugin", "Ringtone cannot be played; vibrating and flashing instead", e);
            }
            return null;
        }
    }

    @SuppressWarnings("deprecation") // vibrate(long[], int) is the only option below API 26
    private void startVibrating() {
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        long[] pattern = {0, 800, 400};
        AudioAttributes alarm = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0), alarm);
        } else {
            vibrator.vibrate(pattern, 0, alarm);
        }
    }

    void startFlashing() {
        // Without sound the flashlight is how the phone is found, so it flashes even when not opted in.
        if ((silent || isFlashlightEnabledInSettings()) && isPermissionGranted(Manifest.permission.CAMERA)) {
            flashlightManager.startFlashing();
        }
    }

    void hideNotification() {
        notificationManager.cancel(notificationId);
    }

    void stopPlaying() {
        if (audioManager == null) {
            // The Plugin was destroyed (probably the device disconnected)
            return;
        }
        if (silent) {
            silent = false;
            if (vibrator != null) {
                vibrator.cancel();
            }
        }
        if (mediaPlayer == null) {
            return;
        }
        if (previousVolume != -1) {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, previousVolume, 0);
            previousVolume = -1;
        }
        mediaPlayer.stop();
        mediaPlayer.release();
        mediaPlayer = null;
    }

    void stopFlashing() {
        if (flashlightManager != null) {
            flashlightManager.stopFlashing();
        }
    }

    @Override
    public @NonNull String[] getSupportedPacketTypes() {
        return new String[]{PACKET_TYPE_FINDMYPHONE_REQUEST};
    }

    @Override
    public @NonNull String[] getOutgoingPacketTypes() {
        return ArrayUtils.EMPTY_STRING_ARRAY;
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public PluginSettingsFragment getSettingsFragment(Activity activity) {
        return FindMyPhoneSettingsFragment.newInstance(getPluginKey(), R.xml.findmyphoneplugin_preferences);
    }

    @NonNull
    @Override
    protected String[] getRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{Manifest.permission.POST_NOTIFICATIONS};
        } else {
            return ArrayUtils.EMPTY_STRING_ARRAY;
        }
    }

    @Override
    protected int getPermissionExplanation() {
        return R.string.findmyphone_notifications_explanation;
    }

    private boolean isFlashlightEnabledInSettings() {
       SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
       return prefs.getBoolean(context.getString(R.string.findmyphone_preference_key_flashlight), false);
    }
}
