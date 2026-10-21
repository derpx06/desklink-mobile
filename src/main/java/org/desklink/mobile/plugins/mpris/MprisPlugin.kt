/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/
package org.desklink.mobile.plugins.mpris

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import org.desklink.mobile.helpers.NotificationHelper
import org.desklink.mobile.helpers.ThreadHelper
import org.desklink.mobile.helpers.VideoUrlsHelper
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.plugins.mpris.AlbumArtCache.deregisterPlugin
import org.desklink.mobile.plugins.mpris.AlbumArtCache.getAlbumArt
import org.desklink.mobile.plugins.mpris.AlbumArtCache.initializeDiskCache
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol
import org.desklink.mobile.plugins.mpris.AlbumArtCache.payloadToDiskCache
import org.desklink.mobile.plugins.mpris.AlbumArtCache.registerPlugin
import org.desklink.mobile.plugins.Plugin
import org.desklink.mobile.plugins.PluginFactory.LoadablePlugin
import org.desklink.mobile.ui.PluginSettingsFragment
import org.desklink.mobile.R
import java.net.MalformedURLException
import java.util.concurrent.ConcurrentHashMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@LoadablePlugin
class MprisPlugin : Plugin() {
    inner class MprisPlayer internal constructor() {
        var playerName: String = ""
            internal set
        var isPlaying: Boolean = false
            internal set(value) {
                if (value && !field) {
                    playStartTime = System.currentTimeMillis()
                }
                field = value
            }
        var playStartTime: Long = 0L
            internal set
        var title: String = ""
            internal set
        var artist: String = ""
            internal set
        var album: String = ""
            internal set
        internal var albumArtUrl: String = ""
        /** The desk's inline cover ([MprisArt]), by its `albumArtId`. */
        var artId: String? = null
            internal set
        internal var url: String = ""
        var loopStatus: String = ""
            internal set
        var isLoopStatusAllowed: Boolean = false
            internal set
        var shuffle: Boolean = false
            internal set
        var isShuffleAllowed: Boolean = false
            internal set
        var volume: Int = 50
            internal set
        var length: Long = -1
            internal set
        var lastPosition: Long = 0
            internal set
        var lastPositionTime: Long
            internal set
        var isPlayAllowed: Boolean = true
            internal set
        var isPauseAllowed: Boolean = true
            internal set
        var isGoNextAllowed: Boolean = true
            internal set
        var isGoPreviousAllowed: Boolean = true
            internal set
        var seekAllowed: Boolean = true
            internal set

        init {
            lastPositionTime = System.currentTimeMillis()
        }

        val isSpotify: Boolean
            get() = playerName.equals("spotify", ignoreCase = true)

        val isSeekAllowed: Boolean
            get() = seekAllowed && length >= 0 && position >= 0

        val hasAlbumArt: Boolean
            get() = albumArtUrl.isNotEmpty()

        /**
         * Returns the album art (if available). Note that this can return null even if hasAlbumArt() returns true.
         *
         * @return The album art, or null if not available
         */
        fun getAlbumArt(): Bitmap? {
            return getAlbumArt(albumArtUrl, this@MprisPlugin, playerName)
        }

        fun getHttpUrl(): String? {
            return url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }

        val isSetVolumeAllowed: Boolean
            get() = volume > -1

        val position: Long
            get() = if (isPlaying) {
                lastPosition + (System.currentTimeMillis() - lastPositionTime)
            } else {
                lastPosition
            }

        fun sendPlayPause() {
            if (isPauseAllowed || isPlayAllowed) {
                sendCommand(playerName, "action", "PlayPause")
            }
        }

        fun sendPlay() {
            if (isPlayAllowed) {
                sendCommand(playerName, "action", "Play")
            }
        }

        fun sendPause() {
            if (isPauseAllowed) {
                sendCommand(playerName, "action", "Pause")
            }
        }

        fun sendStop() {
            sendCommand(playerName, "action", "Stop")
        }

        fun sendPrevious() {
            if (isGoPreviousAllowed) {
                sendCommand(playerName, "action", "Previous")
            }
        }

        fun sendNext() {
            if (isGoNextAllowed) {
                sendCommand(playerName, "action", "Next")
            }
        }

        fun sendSetLoopStatus(loopStatus: String) {
            sendCommand(playerName, "setLoopStatus", loopStatus)
        }

        fun sendSetShuffle(shuffle: Boolean) {
            sendCommand(playerName, "setShuffle", shuffle)
        }

        fun sendSetVolume(volume: Int) {
            if (isSetVolumeAllowed) {
                sendCommand(playerName, "setVolume", volume)
            }
        }

        fun sendSetPosition(position: Int) {
            if (isSeekAllowed) {
                sendCommand(playerName, "SetPosition", position)

                lastPosition = position.toLong()
                lastPositionTime = System.currentTimeMillis()
            }
        }

        fun sendSeek(offset: Int) {
            if (isSeekAllowed) {
                sendCommand(playerName, "Seek", offset)
            }
        }
    }

    private val players = ConcurrentHashMap<String, MprisPlayer>()

    /** Bumped on every state or list change, so Compose readers recompose at once. */
    var revision by mutableIntStateOf(0)
        private set

    /** The player the person picked in the player sheet; kept only while the desk lists it. */
    var selectedPlayer: String? by mutableStateOf(null)

    /** A play/pause tapped here and not yet confirmed by the desk ([MediaControl.shownPlaying]). */
    var pendingPlaying: Boolean? by mutableStateOf(null)
        private set
    var pendingSince = 0L
        private set

    /** The player every control goes to; see [MediaControl.pickPlayer]. */
    fun currentPlayerName(): String? = MediaControl.pickPlayer(selectedPlayer, playerList, playingPlayer?.playerName)

    fun currentPlayer(): MprisPlayer? = currentPlayerName()?.let { players[it] }

    /**
     * Exactly one `desklink.mpris.request` per call, to the current player (or to none, letting the
     * desk pick, before its list has arrived). Nothing here second-guesses the desk's `can*` flags:
     * the buttons are disabled from them, and a tap that reaches this is always sent.
     */
    private fun request(fill: NetworkPacket.() -> Unit) {
        val np = NetworkPacket(PACKET_TYPE_MPRIS_REQUEST).apply {
            currentPlayerName()?.let { this["player"] = it }
            fill()
        }
        device.sendPacket(np, object : org.desklink.mobile.Device.SendPacketStatusCallback() {
            override fun onSuccess() = Unit
            override fun onFailure(e: Throwable) {
                Log.w("MprisControl", "Media request not sent: ${e.message}")
                pendingPlaying = null
                revision++
            }
        })
    }

    /** Play/pause, shown at once as [pendingPlaying] until the desk's next state. */
    fun togglePlayPause() {
        val now = android.os.SystemClock.elapsedRealtime()
        val reported = currentPlayer()?.isPlaying ?: false
        pendingPlaying = !MediaControl.shownPlaying(reported, pendingPlaying, pendingSince, now)
        pendingSince = now
        revision++
        request { this["action"] = "PlayPause" }
    }

    fun next() = request { this["action"] = "Next" }

    fun previous() = request { this["action"] = "Previous" }

    fun seekTo(positionMs: Long) {
        currentPlayer()?.let {
            it.lastPosition = positionMs
            it.lastPositionTime = System.currentTimeMillis()
        }
        revision++
        request { this["SetPosition"] = positionMs }
    }

    fun setVolume(volume: Int) {
        currentPlayer()?.volume = volume
        revision++
        request { this["setVolume"] = volume.coerceIn(0, 100) }
    }
    private var supportAlbumArtPayload = false
    private val playerStatusUpdated = ConcurrentHashMap<String, () -> Unit>()
    private val playerListUpdated = ConcurrentHashMap<String, () -> Unit>()
    override val displayName: String
        get() = context.resources.getString(R.string.pref_plugin_mpris)

    override val description: String
        get() = context.resources.getString(R.string.pref_plugin_mpris_desc)

    override fun getUiButtons(): List<PluginUiButton> = listOf(
        PluginUiButton(
            context.getString(R.string.open_mpris_controls),
            R.drawable.mpris_plugin_action_24dp
        ) { parentActivity ->
            val intent = Intent(parentActivity, MprisActivity::class.java)
            intent.putExtra(DEVICE_ID_KEY, device.deviceId)
            parentActivity.startActivity(intent)
        }
    )

    override fun hasSettings(): Boolean = true

    override fun getSettingsFragment(activity: Activity): PluginSettingsFragment {
        return PluginSettingsFragment.newInstance(pluginKey, R.xml.mprisplugin_preferences)
    }

    override fun onCreate(): Boolean {
        MprisMediaSession.instance.onCreate(context.applicationContext, this, device.deviceId)

        // Always request the player list so the data is up-to-date
        requestPlayerList()

        initializeDiskCache(context)
        registerPlugin(this)

        return true
    }

    /** A new link: the desk sends its whole state (S3); nothing tapped before it is still pending. */
    override fun onFeatureReady() {
        pendingPlaying = null
        requestPlayerList()
    }

    override fun onDestroy() {
        players.clear()
        deregisterPlugin(this)
        MprisMediaSession.instance.onDestroy(this, device.deviceId)
    }

    private fun sendCommand(player: String, method: String, value: String) {
        val np = NetworkPacket(PACKET_TYPE_MPRIS_REQUEST).apply {
            this["player"] = player
            this[method] = value
        }
        device.sendPacket(np)
    }

    private fun sendCommand(player: String, method: String, value: Boolean) {
        val np = NetworkPacket(PACKET_TYPE_MPRIS_REQUEST).apply {
            this["player"] = player
            this[method] = value
        }
        device.sendPacket(np)
    }

    private fun sendCommand(player: String, method: String, value: Int) {
        val np = NetworkPacket(PACKET_TYPE_MPRIS_REQUEST).apply {
            this["player"] = player
            this[method] = value
        }
        device.sendPacket(np)
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        if (np.getBoolean("transferringAlbumArt", false)) {
            payloadToDiskCache(np.getString("albumArtUrl"), np.payload)
            return true
        }

        // Any other mpris packet that happens to carry a payload would leave it
        // open — a leaked descriptor and an undeleted temp file per packet.
        np.payload?.close()

        // The list first: after a reconnect the first packet names a player this side does not
        // know yet, and its state used to be dropped until the next push (none while paused).
        val newPlayerList = np.getStringList("playerList")
        if (newPlayerList != null) {
            var equals = true
            newPlayerList.stream().filter { !players.containsKey(it) }.forEach { newPlayer ->
                equals = false
                val player = MprisPlayer().apply {
                    playerName = newPlayer
                }
                players[newPlayer] = player
                // Immediately ask for the data of this player
                requestPlayerStatus(newPlayer)
            }
            val iter = players.entries.iterator()
            iter.forEach {
                val oldPlayer = it.key
                val found = newPlayerList.stream().anyMatch { newPlayer -> newPlayer == oldPlayer }
                if (!found) {
                    // Player got removed
                    equals = false
                    iter.remove()
                    val playerStatus = it.value
                    if (playerStatus.isPlaying) {
                        showContinueWatchingNotification(playerStatus)
                    }
                }
            }
            if (!equals) {
                notifyPlayerListUpdated()
            }
        }

        if (np.has("player")) {
            val playerStatus = players[np.getString("player")]
            if (playerStatus != null) {
                val wasPlaying = playerStatus.isPlaying
                val previousTitle = playerStatus.title
                //Note: title, artist and album will not be available for all desktop clients
                playerStatus.title = np.getString("title", playerStatus.title)
                playerStatus.artist = np.getString("artist", playerStatus.artist)
                playerStatus.album = np.getString("album", playerStatus.album)
                playerStatus.url = np.getString("url", playerStatus.url)
                if (isInvalidPlayerUrl(playerStatus.url)) {
                    playerStatus.url = ""
                }
                if (np.has("loopStatus")) {
                    playerStatus.loopStatus = np.getString("loopStatus", playerStatus.loopStatus)
                    playerStatus.isLoopStatusAllowed = true
                }
                if (np.has("shuffle")) {
                    playerStatus.shuffle = np.getBoolean("shuffle", playerStatus.shuffle)
                    playerStatus.isShuffleAllowed = true
                }
                playerStatus.volume = np.getInt("volume", playerStatus.volume)
                playerStatus.length = np.getLong("length", playerStatus.length)
                if (np.has("pos")) {
                    playerStatus.lastPosition = np.getLong("pos", playerStatus.lastPosition)
                    playerStatus.lastPositionTime = System.currentTimeMillis()
                }
                playerStatus.isPlaying = np.getBoolean("isPlaying", playerStatus.isPlaying)
                // The desk answered (or reported on its own): the optimistic play/pause is settled.
                if (np.has("isPlaying") && playerStatus.playerName == currentPlayerName()) pendingPlaying = null
                playerStatus.isPlayAllowed = np.getBoolean("canPlay", playerStatus.isPlayAllowed)
                playerStatus.isPauseAllowed = np.getBoolean("canPause", playerStatus.isPauseAllowed)
                playerStatus.isGoNextAllowed = np.getBoolean("canGoNext", playerStatus.isGoNextAllowed)
                playerStatus.isGoPreviousAllowed = np.getBoolean("canGoPrevious", playerStatus.isGoPreviousAllowed)
                playerStatus.seekAllowed = np.getBoolean("canSeek", playerStatus.seekAllowed)
                val newAlbumArtUrlString = np.getString("albumArtUrl", playerStatus.albumArtUrl)
                val newAlbumArtUrl = newAlbumArtUrlString.toUri()
                if (newAlbumArtUrl.scheme in AlbumArtCache.ALLOWED_SCHEMES) {
                    playerStatus.albumArtUrl = newAlbumArtUrl.toString()
                } else {
                    Log.w("MprisControl", "Invalid album art URL: $newAlbumArtUrlString")
                    playerStatus.albumArtUrl = ""
                }

                // The desk's inline cover: its id on every state, its data when the track changes.
                // A new track without an id has no cover.
                val artId = np.getStringOrNull("albumArtId")?.takeIf(MprisArt::validId)
                if (artId != null) {
                    playerStatus.artId = artId
                    val data = np.getStringOrNull("albumArtData")
                    if (data != null && MprisArt.get(artId) == null) {
                        ThreadHelper.execute {
                            MprisArt.decode(data)?.let { MprisArt.put(artId, it) } ?: Log.w("MprisControl", "Refused the desk's album art")
                            notifyPlayerStatusUpdated()
                        }
                    }
                } else if (playerStatus.title != previousTitle) {
                    playerStatus.artId = null
                }

                notifyPlayerStatusUpdated()

                // Check to see if a stream has stopped playing and we should deliver a notification
                if (np.has("isPlaying") && !playerStatus.isPlaying && wasPlaying) {
                    showContinueWatchingNotification(playerStatus)
                }
            }
        }

        // Remember if the connected device support album art payloads
        supportAlbumArtPayload = np.getBoolean("supportAlbumArtPayload", supportAlbumArtPayload)

        return true
    }

    private fun isInvalidPlayerUrl(url: String): Boolean {
        // Not a valid video URL. Can happen when an on-hover preview is playing.
        return url == "https://www.youtube.com/" || url == "https://www.youtube.com/tv#/"
    }

    private fun showContinueWatchingNotification(playerStatus: MprisPlayer) {
        if (playerStatus.playStartTime + 5000 > System.currentTimeMillis()) {
            // Playback was too short
            return
        }
        ThreadHelper.execute {
            Thread.sleep(500)
            if (playerStatus.isPlaying) {
                // Pause was too short. Probably just the gap between songs
                return@execute
            }
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val httpUrl = playerStatus.getHttpUrl()
            if (prefs.getBoolean(context.getString(R.string.mpris_keepwatching_key), true) && httpUrl != null) {
                try {
                    val transformedUrl = httpUrl
                        .let { VideoUrlsHelper.convertToAndFromYoutubeTvLinks(it) }
                        .let { VideoUrlsHelper.formatUriWithSeek(it, playerStatus.position) }
                        .toUri()
                    val browserIntent = Intent(Intent.ACTION_VIEW, transformedUrl)
                    val pendingIntent = PendingIntent.getActivity(context, 0, browserIntent, PendingIntent.FLAG_IMMUTABLE)

                    Handler(Looper.getMainLooper()).post {
                        val notificationManager = context.getSystemService<NotificationManager>()!!
                        val builder = NotificationCompat.Builder(context, NotificationHelper.Channels.CONTINUEWATCHING)
                            .setContentTitle(context.resources.getString(R.string.app_name))
                            .setSmallIcon(R.drawable.ic_play_white)
                            .setTimeoutAfter(3000)
                            .setContentIntent(pendingIntent)
                            .setContentText(context.resources.getString(R.string.mpris_keepwatching) + " " + playerStatus.title)
                        notificationManager.notify(
                            System.currentTimeMillis().toInt(),
                            builder.build()
                        )
                    }
                } catch (e: MalformedURLException) {
                    e.printStackTrace()
                }
            }
        }
    }

    override val supportedPacketTypes: Array<String> = arrayOf(PACKET_TYPE_MPRIS)

    override val outgoingPacketTypes: Array<String> = arrayOf(PACKET_TYPE_MPRIS_REQUEST)

    fun setPlayerStatusUpdatedHandler(id: String, callback: () -> Unit) {
        playerStatusUpdated[id] = callback
        callback()
    }

    fun removePlayerStatusUpdatedHandler(id: String) {
        playerStatusUpdated.remove(id)
    }

    fun notifyPlayerStatusUpdated() {
        revision++
        for ((key, callback) in playerStatusUpdated) {
            try {
                callback()
            } catch(e: Exception) {
                Log.e("MprisControl", "Exception", e)
                playerStatusUpdated.remove(key)
            }
        }
    }

    fun setPlayerListUpdatedHandler(id: String, callback: () -> Unit) {
        playerListUpdated[id] = callback
        callback()
    }

    fun removePlayerListUpdatedHandler(id: String) {
        playerListUpdated.remove(id)
    }

    fun notifyPlayerListUpdated() {
        revision++
        for ((key, callback) in playerListUpdated) {
            try {
                callback()
            } catch(e: Exception) {
                Log.e("MprisControl", "Exception", e)
                playerListUpdated.remove(key)
            }
        }
    }

    val playerList: List<String>
        get() = players.keys.sorted()

    fun getPlayerStatus(player: String?): MprisPlayer? = if (player == null) {
        null
    } else players[player]

    fun getEmptyPlayer(): MprisPlayer = MprisPlayer()

    val playingPlayer: MprisPlayer?
        /**
         * Returns a playing mpris player if any exist
         *
         * @return null if no players are playing, a playing player otherwise
         */
        get() = players.values.stream().filter(MprisPlayer::isPlaying).findFirst().orElse(null)

    fun hasPlayer(player: MprisPlayer): Boolean = players.containsValue(player)

    private fun requestPlayerList() {
        val np = NetworkPacket(PACKET_TYPE_MPRIS_REQUEST).apply {
            this["requestPlayerList"] = true
        }
        device.sendPacket(np)
    }

    private fun requestPlayerStatus(player: String) {
        val np = NetworkPacket(PACKET_TYPE_MPRIS_REQUEST).apply {
            this["player"] = player
            this["requestNowPlaying"] = true
            this["requestVolume"] = true
        }
        device.sendPacket(np)
    }

    fun fetchedAlbumArt(url: String) {
        if (players.values.stream().anyMatch { player -> url == player.albumArtUrl }) {
            notifyPlayerStatusUpdated()
        }
    }

    fun askTransferAlbumArt(url: String, playerName: String?): Boolean {
        // First check if the remote supports transferring album art
        if (!supportAlbumArtPayload) return false
        if (url.isEmpty()) return false

        val player = getPlayerStatus(playerName) ?: return false

        if (player.albumArtUrl == url) {
            val np = NetworkPacket(PACKET_TYPE_MPRIS_REQUEST)
            np["player"] = player.playerName
            np["albumArtUrl"] = url
            device.sendPacket(np)
            return true
        }
        return false
    }

    override val optionalPermissions: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf()
    }

    override val optionalPermissionExplanation: Int = R.string.mpris_notifications_explanation

    companion object {
        const val DEVICE_ID_KEY: String = "deviceId"
        private const val PACKET_TYPE_MPRIS = DeskLinkProtocol.PACKET_TYPE_MPRIS
        private const val PACKET_TYPE_MPRIS_REQUEST = DeskLinkProtocol.PACKET_TYPE_MPRIS_REQUEST
    }
}
