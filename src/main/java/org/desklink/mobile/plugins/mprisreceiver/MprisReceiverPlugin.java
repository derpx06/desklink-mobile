/*
 * SPDX-FileCopyrightText: 2018 Nicolas Fella <nicolas.fella@gmx.de>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.plugins.mprisreceiver;

import android.content.ComponentName;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;

import org.apache.commons.lang3.StringUtils;
import org.desklink.mobile.helpers.AppsHelper;
import org.desklink.mobile.helpers.ThreadHelper;
import org.desklink.mobile.NetworkPacket;
import org.desklink.mobile.plugins.notifications.NotificationReceiver;
import org.desklink.mobile.plugins.Plugin;
import org.desklink.mobile.plugins.PluginFactory;
import org.desklink.mobile.ui.MainActivity;
import org.desklink.mobile.ui.StartActivityAlertDialogFragment;
import org.desklink.mobile.R;
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@PluginFactory.LoadablePlugin
public class MprisReceiverPlugin extends Plugin {
    private final static String PACKET_TYPE_MPRIS = DeskLinkProtocol.PACKET_TYPE_MPRIS;
    private final static String PACKET_TYPE_MPRIS_REQUEST = DeskLinkProtocol.PACKET_TYPE_MPRIS_REQUEST;

    private static final String TAG = "MprisReceiver";

    // TODO: Those two are always accessed together, merge them
    private HashMap<String, MprisReceiverPlayer> players;
    private HashMap<String, MprisReceiverCallback> playerCbs;

    private MediaSessionChangeListener mediaSessionChangeListener;

    public @NonNull String getDeviceId() {
        return device.getDeviceId();
    }

    /** A relative `Seek` (microseconds, KDE Connect's unit) from [positionMs], never before the start. */
    public static long seekTarget(long positionMs, long seekMicros) {
        return Math.max(0, positionMs + seekMicros / 1000);
    }

    /** Off until the person turns it on (Settings → this computer): it needs notification access. */
    @Override
    public boolean isEnabledByDefault() {
        return false;
    }

    /** At most two state packets a second per player; the last change always goes out. */
    private static final long MIN_INTERVAL_MS = 500;
    /** While playing, the position is re-sent this often so the desk's progress bar stays true. */
    private static final long POSITION_INTERVAL_MS = 5_000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final HashMap<String, Long> lastSent = new HashMap<>();
    private final HashMap<String, Runnable> deferred = new HashMap<>();
    private final Runnable positionTick = new Runnable() {
        @Override
        public void run() {
            if (players != null) {
                for (MprisReceiverPlayer player : new ArrayList<>(players.values())) {
                    if (player.isPlaying()) sendMetadata(player);
                }
            }
            handler.postDelayed(this, POSITION_INTERVAL_MS);
        }
    };

    @Override
    public boolean onCreate() {
        if (!NotificationReceiver.hasReadNotificationsPermission(context)) {
            return false;
        }
        players = new HashMap<>();
        playerCbs = new HashMap<>();
        try {
            MediaSessionManager manager = ContextCompat.getSystemService(context, MediaSessionManager.class);
            if (null == manager)
                return false;

            assert(mediaSessionChangeListener == null);
            mediaSessionChangeListener = new MediaSessionChangeListener();
            manager.addOnActiveSessionsChangedListener(mediaSessionChangeListener, new ComponentName(context, NotificationReceiver.class), new Handler(Looper.getMainLooper()));

            createPlayers(manager.getActiveSessions(new ComponentName(context, NotificationReceiver.class)));
            sendPlayerList();
            handler.postDelayed(positionTick, POSITION_INTERVAL_MS);
        } catch (Exception e) {
            Log.e(TAG, "Exception", e);
        }

        return true;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        MediaSessionManager manager = ContextCompat.getSystemService(context, MediaSessionManager.class);
        if (manager != null && mediaSessionChangeListener != null) {
            manager.removeOnActiveSessionsChangedListener(mediaSessionChangeListener);
            mediaSessionChangeListener = null;
        }
    }

    private void createPlayers(List<MediaController> sessions) {
        for (MediaController controller : sessions) {
            createPlayer(controller);
        }
    }

    @Override
    public @NonNull String getDisplayName() {
        return context.getResources().getString(R.string.pref_plugin_mprisreceiver);
    }

    @Override
    public @NonNull String getDescription() {
        return context.getResources().getString(R.string.pref_plugin_mprisreceiver_desc);
    }

    @Override
    public boolean onPacketReceived(@NonNull NetworkPacket np) {
        if (players == null || playerCbs == null) {
            Log.w(TAG, "Ignoring MPRIS request because media receiver is not initialized");
            return false;
        }

        if (np.getBoolean("requestPlayerList")) {
            sendPlayerList();
            return true;
        }

        String playerName = resolvePlayerNameForRequest(np, players.keySet());
        if (playerName == null) {
            Log.w(TAG, "Ignoring MPRIS request because no matching media player is available");
            return false;
        }

        MprisReceiverPlayer player = players.get(playerName);

        if (null == player) {
            return false;
        }
        String artUrl = np.getString("albumArtUrl", "");
        if (!artUrl.isEmpty()) {
            String albumArtPlayerName = player.getName();
            MprisReceiverCallback cb = playerCbs.get(albumArtPlayerName);
            if (cb == null) {
                Log.e(TAG, "no callback for " + albumArtPlayerName + " (player likely stopped)");
                return false;
            }
            // run it on a different thread to avoid blocking
            ThreadHelper.execute(() -> sendAlbumArt(albumArtPlayerName, cb, artUrl));
            return true;
        }

        if (np.getBoolean("requestNowPlaying", false)) {
            sendMetadata(player);
            return true;
        }

        if (np.has("SetPosition")) {
            long position = np.getLong("SetPosition", 0);
            player.setPosition(position);
        }

        // A relative seek, in microseconds (the desk's skip buttons).
        if (np.has("Seek")) {
            player.setPosition(seekTarget(player.getPosition(), np.getLong("Seek", 0)));
        }

        if (np.has("setVolume")) {
            int volume = np.getInt("setVolume", 100);
            player.setVolume(volume);
            //Setting volume doesn't seem to always trigger the callback
            sendMetadata(player);
        }

        if (np.has("action")) {
            String action = np.getString("action");

            switch (action) {
                case "Play":
                    player.play();
                    break;
                case "Pause":
                    player.pause();
                    break;
                case "PlayPause":
                    player.playPause();
                    break;
                case "Next":
                    player.next();
                    break;
                case "Previous":
                    player.previous();
                    break;
                case "Stop":
                    player.stop();
                    break;
            }
        }

        return true;
    }

    @Override
    public @NonNull String[] getSupportedPacketTypes() {
        return new String[]{PACKET_TYPE_MPRIS_REQUEST};
    }

    @Override
    public @NonNull String[] getOutgoingPacketTypes() {
        return new String[]{PACKET_TYPE_MPRIS};
    }

    private final class MediaSessionChangeListener implements MediaSessionManager.OnActiveSessionsChangedListener {
        @Override
        public void onActiveSessionsChanged(@Nullable List<MediaController> controllers) {

            if (null == controllers) {
                return;
            }

            // Make a copy to avoid ConcurrentModificationException
            ArrayList<MprisReceiverPlayer> playersCopy = new ArrayList<>(players.values());
            for (MprisReceiverPlayer p : playersCopy) {
                p.getController().unregisterCallback(Objects.requireNonNull(playerCbs.get(p.getName())));
            }
            playerCbs.clear();
            players.clear();

            createPlayers(controllers);
            sendPlayerList();

        }
    }

    private void createPlayer(MediaController controller) {
        // Skip the media session we created ourselves as DeskLink
        if (controller.getPackageName().equals(context.getPackageName())) return;

        MprisReceiverPlayer player = new MprisReceiverPlayer(controller, AppsHelper.appNameLookup(context, controller.getPackageName()));
        MprisReceiverCallback cb = new MprisReceiverCallback(this, player);
        controller.registerCallback(cb, new Handler(Looper.getMainLooper()));
        playerCbs.put(player.getName(), cb);
        players.put(player.getName(), player);
    }

    /** Feature-ready: the whole state, list and each player, past the throttle (contract S2). */
    @Override
    public void onFeatureReady() {
        if (players == null) {
            sendEmptyPlayerList();
            return;
        }
        sendPlayerList();
        lastSent.clear();
        for (MprisReceiverPlayer player : new ArrayList<>(players.values())) sendMetadata(player);
    }

    /** Turned off: the desk stops showing this phone's media. */
    @Override
    public void onTurnedOff() {
        sendEmptyPlayerList();
    }

    private void sendEmptyPlayerList() {
        NetworkPacket np = new NetworkPacket(PACKET_TYPE_MPRIS);
        np.set("playerList", new ArrayList<String>());
        getDevice().sendPacket(np);
    }

    private void sendPlayerList() {
        NetworkPacket np = new NetworkPacket(PACKET_TYPE_MPRIS);
        np.set("playerList", players.keySet());
        // Artwork bytes are not transferred in DeskLink v4.
        np.set("supportAlbumArtPayload", false);
        getDevice().sendPacket(np);
    }

    void sendAlbumArt(String playerName, @NonNull MprisReceiverCallback cb, @Nullable String requestedUrl) {
        // NOTE: It is possible that the player gets killed in the middle of this method.
        // The proper thing to do this case would be to abort the send - but that gets into the
        //   territory of async cancellation or putting a lock.
        // For now, we just continue to send the art- cb stores the bitmap, so it will be valid.
        //   cb will get GC'd after this method completes.
        String localArtUrl = cb.getArtUrl();
        if (localArtUrl == null) {
            Log.w(TAG, "art not found!");
            return;
        }
        String artUrl = requestedUrl == null ? localArtUrl : requestedUrl;
        if (requestedUrl != null && !requestedUrl.contentEquals(localArtUrl)) {
            Log.w(TAG, "sendAlbumArt: Doesn't match current url");
            Log.d(TAG, "current:   " + localArtUrl);
            Log.d(TAG, "requested: " + requestedUrl);
            return;
        }
        byte[] p = cb.getArtAsArray();
        if (p == null) {
            Log.w(TAG, "sendAlbumArt: Failed to get art stream");
            return;
        }
        NetworkPacket np = new NetworkPacket(PACKET_TYPE_MPRIS);
        np.setPayload(new NetworkPacket.Payload(p));
        np.set("player", playerName);
        np.set("transferringAlbumArt", true);
        np.set("albumArtUrl", artUrl);
        getDevice().sendPacket(np);
    }

    void sendMetadata(MprisReceiverPlayer player) {
        String key = String.valueOf(player.getName());
        long now = android.os.SystemClock.elapsedRealtime();
        Long last = lastSent.get(key);
        if (last != null && now - last < MIN_INTERVAL_MS) {
            if (!deferred.containsKey(key)) {
                Runnable later = () -> {
                    deferred.remove(key);
                    sendMetadata(player);
                };
                deferred.put(key, later);
                handler.postDelayed(later, MIN_INTERVAL_MS - (now - last));
            }
            return;
        }
        lastSent.put(key, now);
        NetworkPacket np = new NetworkPacket(MprisReceiverPlugin.PACKET_TYPE_MPRIS);
        np.set("player", player.getName());
        np.set("title", player.getTitle());
        np.set("artist", player.getArtist());
        String nowPlaying = Stream.of(player.getArtist(), player.getTitle())
            .filter(StringUtils::isNotEmpty).collect(Collectors.joining(" - "));
        np.set("nowPlaying", nowPlaying); // GSConnect 50 (so, Ubuntu 22.04) needs this
        np.set("album", player.getAlbum());
        np.set("isPlaying", player.isPlaying());
        np.set("pos", player.getPosition());
        np.set("length", player.getLength());
        np.set("canPlay", player.canPlay());
        np.set("canPause", player.canPause());
        np.set("canGoPrevious", player.canGoPrevious());
        np.set("canGoNext", player.canGoNext());
        np.set("canSeek", player.canSeek());
        np.set("volume", player.getVolume());
        String artUrl = "";
        MprisReceiverCallback cb = playerCbs.get(player.getName());
        if (cb != null) {
            String url = cb.getArtUrl();
            if (url != null) {
                artUrl = url;
            }
        }
        np.set("albumArtUrl", artUrl);
        getDevice().sendPacket(np);
    }

    static @Nullable String resolvePlayerNameForRequest(@NonNull NetworkPacket np, @NonNull Set<String> availablePlayers) {
        if (np.has("player")) {
            String requestedPlayer = np.getString("player").trim();
            if (!requestedPlayer.isEmpty()) {
                return availablePlayers.contains(requestedPlayer) ? requestedPlayer : null;
            }
        }

        return availablePlayers.stream().findFirst().orElse(null);
    }

    @Override
    public boolean checkRequiredPermissions() {
        return NotificationReceiver.hasReadNotificationsPermission(context);
    }

    @Override
    public @NonNull DialogFragment getPermissionExplanationDialog() {
        return new StartActivityAlertDialogFragment.Builder()
                .setTitle(R.string.pref_plugin_mpris)
                .setMessage(R.string.no_permission_mprisreceiver)
                .setPositiveButton(R.string.open_settings)
                .setNegativeButton(R.string.cancel)
                .setIntentAction("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                .setStartForResult(true)
                .setRequestCode(MainActivity.RESULT_NEEDS_RELOAD)
                .create();
    }
}
