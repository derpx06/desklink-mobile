/*
 * SPDX-FileCopyrightText: 2017 Matthijs Tijink <matthijstijink@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.mpris

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.preference.PreferenceManager
import android.service.notification.StatusBarNotification
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.Device
import org.desklink.mobile.R
import org.desklink.mobile.helpers.NotificationHelper
import org.desklink.mobile.plugins.mpris.MprisPlugin.MprisPlayer
import org.desklink.mobile.plugins.notifications.NotificationReceiver
import org.desklink.mobile.plugins.systemvolume.SystemVolumePlugin
import org.desklink.mobile.plugins.systemvolume.SystemVolumeProvider
import org.desklink.mobile.plugins.systemvolume.SystemVolumeProvider.Companion.currentProvider
import org.desklink.mobile.plugins.systemvolume.SystemVolumeProvider.ProviderStateListener
import org.desklink.mobile.ui.MainActivity
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * The desk's player as an Android media notification and media session: the shade, the lock
 * screen and (Android 13+, drawn from the session) Quick Settings' media controls, with the same
 * player, cover and stale rules as the Home media card. One notification at most, for every desk.
 */
class MprisMediaSession : OnSharedPreferenceChangeListener, NotificationReceiver.NotificationListener,
    ProviderStateListener {
    private var spotifyRunning = false

    // Holds the device and player displayed in the notification
    private var notificationDeviceId: String? = null
    private var notificationPlayer: MprisPlayer? = null

    // Holds the device ids for which we can display a notification
    private val mprisDevices: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    private var context: Context? = null
    private var mediaSession: MediaSessionCompat? = null

    // The cover downscaled for the notification, kept while the track's cover is the same.
    private var artSource: Bitmap? = null
    private var art: Bitmap? = null

    /** What the notification and the session show: the desk's current player, or the debug preview's. */
    class NowPlaying(
        val deviceId: String,
        val player: String,
        val desk: String,
        val title: String,
        val artist: String,
        val album: String,
        val lengthMs: Long,
        val transport: MediaControl.Transport,
        val stale: Boolean,
        val art: Bitmap?,
        val color: Int?,
    )

    // Callback for control via the media session API (Android 13+ media controls, headsets, the lock screen)
    private val mediaSessionCallback: MediaSessionCompat.Callback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            notificationPlayer?.sendPlay()
        }

        override fun onPause() {
            notificationPlayer?.sendPause()
        }

        override fun onSkipToNext() {
            notificationPlayer?.sendNext()
        }

        override fun onSkipToPrevious() {
            notificationPlayer?.sendPrevious()
        }

        override fun onSeekTo(pos: Long) {
            notificationPlayer?.sendSetPosition(pos.toInt())
            // Shown where it was dropped, not snapped back until the desk confirms.
            updateMediaNotification()
        }
    }

    // A WebRTC link that comes or goes reloads the device's plugins: the notification turns stale or live.
    private val pluginsChanged = Device.PluginsChangedListener { updateMediaNotification() }

    /**
     * Called by the mpris plugin when it wants media control notifications for its device
     *
     *
     * Can be called multiple times, once for each device
     *
     * @param context The context
     * @param plugin  The mpris plugin
     * @param device  The device id
     */
    fun onCreate(context: Context?, plugin: MprisPlugin, device: String) {
        if (mprisDevices.isEmpty()) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            prefs.registerOnSharedPreferenceChangeListener(this)
        }
        this.context = context
        mprisDevices.add(device)
        DeskLinkApplication.getInstance().getDevice(device)?.run {
            removePluginsChangedListener(pluginsChanged)
            addPluginsChangedListener(pluginsChanged)
        }

        plugin.setPlayerListUpdatedHandler(
            "media_notification"
        ) { this.updateMediaNotification() }
        plugin.setPlayerStatusUpdatedHandler(
            "media_notification"
        ) { this.updateMediaNotification() }

        NotificationReceiver.WithService(context) { service: NotificationReceiver ->
            service.addListener(this@MprisMediaSession)
            val serviceReady = service.isConnected
            if (serviceReady) {
                onListenerConnected(service)
            }
        }
    }

    /**
     * Called when a device disconnects/does not want notifications anymore
     *
     *
     * Can be called multiple times, once for each device
     *
     * @param plugin  The mpris plugin
     * @param device The device id
     */
    fun onDestroy(plugin: MprisPlugin, device: String) {
        mprisDevices.remove(device)
        DeskLinkApplication.getInstance().getDevice(device)?.removePluginsChangedListener(pluginsChanged)
        plugin.removePlayerStatusUpdatedHandler("media_notification")
        plugin.removePlayerListUpdatedHandler("media_notification")
        updateMediaNotification()

        val systemVolumeProvider = SystemVolumeProvider.getInstance()
        systemVolumeProvider.setPlugin(null)
        systemVolumeProvider.removeStateListener( this)

        if (mprisDevices.isEmpty()) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            prefs.unregisterOnSharedPreferenceChangeListener(this)
        }
    }

    /**
     * The Home card's player of some desk ([MprisPlugin.currentPlayer]): the one already shown
     * while it plays, else a playing one, else the one already shown while paused (so it can be
     * resumed from here). A player that never played since the link came up shows nothing.
     */
    private fun findPlayer(): Pair<Device, MprisPlayer>? {
        val app = DeskLinkApplication.getInstance()
        val candidates = mprisDevices.mapNotNull { id ->
            val device = app.getDevice(id) ?: return@mapNotNull null
            val player = device.getPlugin(MprisPlugin::class.java)?.currentPlayer()?.takeIf(::shouldShowPlayer)
            player?.let { device to it }
        }
        val shown = candidates.firstOrNull { it.second === notificationPlayer }
        return shown?.takeIf { it.second.isPlaying } ?: candidates.firstOrNull { it.second.isPlaying } ?: shown
    }

    private fun shouldShowPlayer(player: MprisPlayer): Boolean {
        return !(player.isSpotify && spotifyRunning)
    }

    private fun updateRemoteDeviceVolumeControl() {
        val plugin = DeskLinkApplication.getInstance().getDevicePlugin(notificationDeviceId, SystemVolumePlugin::class.java)
            ?: return
        val systemVolumeProvider = SystemVolumeProvider.getInstance()
        systemVolumeProvider.setPlugin(plugin)
        systemVolumeProvider.addStateListener( this)
    }

    private fun mayNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "No permission to post notifications, closed.")
            return false
        }
        // The person may have turned the media notification off
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getString(R.string.mpris_notification_key), true)
    }

    /**
     * Update the media control notification
     */
    private fun updateMediaNotification(): Unit = synchronized(this) {
        val context = context ?: return
        val found = if (mayNotify(context)) findPlayer() else null
        if (found == null) {
            closeMediaNotification()
            return
        }
        val (device, player) = found
        notificationDeviceId = device.deviceId
        notificationPlayer = player
        updateRemoteDeviceVolumeControl()

        // Without the feature link the desk's report may be out of date: shown paused, without controls.
        val stale = !device.hasActiveWebRtcFeatureTransport
        val cover = player.art()
        show(
            context,
            NowPlaying(
                deviceId = device.deviceId,
                player = player.playerName,
                desk = device.name,
                title = player.title.ifBlank { player.playerName },
                artist = player.artist,
                album = player.album,
                lengthMs = player.length,
                transport = MediaControl.transport(
                    playing = player.isPlaying, stale = stale,
                    lastPositionMs = player.lastPosition, lastPositionAt = player.lastPositionTime,
                    lengthMs = player.length, now = System.currentTimeMillis(),
                    canPlay = player.isPlayAllowed, canPause = player.isPauseAllowed,
                    canPrevious = player.isGoPreviousAllowed, canNext = player.isGoNextAllowed, canSeek = player.seekAllowed,
                ),
                stale = stale,
                art = cover?.image?.asAndroidBitmap(),
                color = cover?.vibrant,
            ),
        )
    }

    /** The debug build's preview: [nowPlaying] as if a desk had sent it. Its buttons do nothing. */
    fun preview(context: Context, nowPlaying: NowPlaying): Unit = synchronized(this) {
        this.context = context.applicationContext
        notificationPlayer = null
        show(this.context!!, nowPlaying)
    }

    /** The cover at most [MAX_ART_SIDE] px: the session and the notification each carry it over binder. */
    private fun notificationArt(source: Bitmap?): Bitmap? {
        if (source == null) return null
        if (source !== artSource) {
            val scale = MAX_ART_SIDE.toFloat() / maxOf(source.width, source.height)
            art = if (scale >= 1f) source else Bitmap.createScaledBitmap(
                source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true,
            )
            artSource = source
        }
        return art
    }

    private fun show(context: Context, np: NowPlaying) {
        val cover = notificationArt(np.art)
        val transport = np.transport
        // Documentation says that this was added in Lollipop (21) but it seems to cause crashes on < Pie (28)
        val actions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) transport.actions else transport.actions and PlaybackStateCompat.ACTION_SEEK_TO.inv()
        val text = if (np.stale) context.getString(R.string.settings_waiting_for, np.desk) else MediaControl.notificationText(np.artist, np.album, np.player)

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, np.title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, np.title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, text)
        // Android 13+ draws the artist under the title: while stale it says what the controls wait for.
        val artist = if (np.stale) text else np.artist
        if (artist.isNotBlank()) {
            metadata.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            metadata.putString(MediaMetadataCompat.METADATA_KEY_AUTHOR, artist)
        }
        if (np.album.isNotBlank()) metadata.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, np.album)
        if (np.lengthMs > 0) metadata.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, np.lengthMs)
        if (cover != null) {
            metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, cover)
            metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, cover)
            metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, cover)
        }

        // The update time is now, with the position worked out for now: Android advances the bar by itself.
        val playbackState = PlaybackStateCompat.Builder()
            .setState(transport.state, transport.positionMs, transport.speed, SystemClock.elapsedRealtime())
            .setActions(actions)
            .build()

        // Home, on this desk: its media card is the app's Now Playing.
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_DEVICE_ID, np.deviceId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, NotificationHelper.Channels.MEDIA_CONTROL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(np.title)
            .setContentText(text)
            .setSubText(np.desk)
            .setLargeIcon(cover)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setAutoCancel(false)
            // Only dismissible while nothing plays
            .setOngoing(transport.state == PlaybackStateCompat.STATE_PLAYING)
        if (np.color != null) notification.setColor(np.color).setColorized(true)
        if (transport.state != PlaybackStateCompat.STATE_PLAYING) {
            notification.setDeleteIntent(button(context, np, MprisMediaNotificationReceiver.ACTION_CLOSE_NOTIFICATION))
        }

        // Before Android 13 the shade draws these; from 13 it draws the session's actions instead.
        var shown = 0
        fun add(action: Long, @DrawableRes icon: Int, @StringRes label: Int, receiverAction: String) {
            if (actions and action == 0L) return
            notification.addAction(icon, context.getString(label), button(context, np, receiverAction))
            shown++
        }
        add(PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS, R.drawable.ic_previous_white, R.string.mpris_previous, MprisMediaNotificationReceiver.ACTION_PREVIOUS)
        add(PlaybackStateCompat.ACTION_PAUSE, R.drawable.ic_pause_white, R.string.mpris_pause, MprisMediaNotificationReceiver.ACTION_PAUSE)
        add(PlaybackStateCompat.ACTION_PLAY, R.drawable.ic_play_white, R.string.mpris_play, MprisMediaNotificationReceiver.ACTION_PLAY)
        add(PlaybackStateCompat.ACTION_SKIP_TO_NEXT, R.drawable.ic_next_white, R.string.mpris_next, MprisMediaNotificationReceiver.ACTION_NEXT)

        val session = mediaSession ?: MediaSessionCompat(context, MPRIS_MEDIA_SESSION_TAG).apply {
            setCallback(mediaSessionCallback, Handler(context.mainLooper))
            this@MprisMediaSession.mediaSession = this
        }
        session.setMetadata(metadata.build())
        session.setPlaybackState(playbackState)
        session.setSessionActivity(open)
        session.isActive = true

        notification.setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(session.sessionToken)
                .setShowActionsInCompactView(*IntArray(shown) { it }),
        )
        ContextCompat.getSystemService(context, NotificationManager::class.java)?.notify(MPRIS_MEDIA_NOTIFICATION_ID, notification.build())
    }

    private fun button(context: Context, np: NowPlaying, action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, MprisMediaNotificationReceiver::class.java)
            .setAction(action)
            .putExtra(MprisMediaNotificationReceiver.EXTRA_DEVICE_ID, np.deviceId)
            .putExtra(MprisMediaNotificationReceiver.EXTRA_MPRIS_PLAYER, np.player),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun closeMediaNotification(): Unit = synchronized(this) {
        // Remove the notification
        context?.let { ContextCompat.getSystemService(it, NotificationManager::class.java)?.cancel(MPRIS_MEDIA_NOTIFICATION_ID) }

        // Clear the current player and media session
        notificationPlayer = null
        artSource = null
        art = null
        mediaSession?.apply {
            setPlaybackState(PlaybackStateCompat.Builder().build())
            setMetadata(MediaMetadataCompat.Builder().build())
            isActive = false
            release()
        }
        mediaSession = null
        currentProvider?.release()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        updateMediaNotification()
    }

    /** The legacy Now Playing screen's pick: the Home card and the notification follow it. */
    fun playerSelected(player: MprisPlayer?) {
        player ?: return
        mprisDevices.firstNotNullOfOrNull { id ->
            DeskLinkApplication.getInstance().getDevicePlugin(id, MprisPlugin::class.java)?.takeIf { it.hasPlayer(player) }
        }?.selectedPlayer = player.playerName
        notificationPlayer = player
        updateMediaNotification()
    }

    override fun onNotificationPosted(n: StatusBarNotification) {
        if (n.isSpotify()) {
            spotifyRunning = true
            updateMediaNotification()
        }
    }

    override fun onNotificationRemoved(n: StatusBarNotification) {
        if (n.isSpotify()) {
            spotifyRunning = false
            updateMediaNotification()
        }
    }

    override fun onListenerConnected(service: NotificationReceiver) {
        try {
            service.activeNotifications.find { n -> n.isSpotify() }?.let {
                spotifyRunning = true
                updateMediaNotification()
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Failed to get active notifications", e)
        }
    }

    override fun onProviderStateChanged(systemVolumeProvider: SystemVolumeProvider, isActive: Boolean) {
        val mediaSession = mediaSession ?: return

        if (isActive) {
            mediaSession.setPlaybackToRemote(systemVolumeProvider)
        } else {
            mediaSession.setPlaybackToLocal(AudioManager.STREAM_MUSIC)
        }
    }

    private fun StatusBarNotification.isSpotify(): Boolean =
        this.packageName == SPOTIFY_PACKAGE_NAME

    companion object {
        const val TAG = "MprisMediaSession"

        private const val MPRIS_MEDIA_NOTIFICATION_ID =
            0x91b70463.toInt() // echo MprisNotification | md5sum | head -c 8
        private const val MPRIS_MEDIA_SESSION_TAG = "org.desklink.mobile.media_session"

        private const val SPOTIFY_PACKAGE_NAME = "com.spotify.music"

        /** Largest side of the cover the notification and session carry. */
        private const val MAX_ART_SIDE = 512

        val instance: MprisMediaSession by lazy { MprisMediaSession() }

        fun getMediaSession(): MediaSessionCompat? {
            return instance.mediaSession
        }
    }
}
