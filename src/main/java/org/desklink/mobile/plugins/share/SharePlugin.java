/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.plugins.share;

import android.Manifest;
import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.content.ContextCompat;
import androidx.core.content.IntentCompat;
import androidx.core.content.LocusIdCompat;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.core.os.BundleCompat;
import androidx.preference.PreferenceManager;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.desklink.mobile.helpers.FilesHelper;
import org.desklink.mobile.NetworkPacket;
import org.desklink.mobile.plugins.Plugin;
import org.desklink.mobile.plugins.PluginFactory;
import org.desklink.mobile.ui.MainActivity;
import org.desklink.mobile.async.BackgroundJob;
import org.desklink.mobile.async.BackgroundJobHandler;
import org.desklink.mobile.R;
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol;

import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import kotlin.Unit;

/**
 * A Plugin for sharing and receiving files and uris.
 * <p>
 *     All of the associated I/O work is scheduled on background
 *     threads by {@link BackgroundJobHandler}.
 * </p>
 */
@PluginFactory.LoadablePlugin
public class SharePlugin extends Plugin {
    final static String ACTION_CANCEL_SHARE = "org.desklink.mobile.plugins.share.CancelShare";
    final static String CANCEL_SHARE_DEVICE_ID_EXTRA = "deviceId";
    final static String CANCEL_SHARE_BACKGROUND_JOB_ID_EXTRA = "backgroundJobId";

    private final static String PACKET_TYPE_SHARE_REQUEST = DeskLinkProtocol.PACKET_TYPE_SHARE_REQUEST;

    final static String KEY_NUMBER_OF_FILES = "numberOfFiles";
    final static String KEY_TOTAL_PAYLOAD_SIZE = "totalPayloadSize";

    private final BackgroundJobHandler backgroundJobHandler;
    private final Handler handler;

    private CompositeReceiveFileJob receiveFileJob;
    private CompositeUploadFileJob uploadFileJob;
    private final Callback receiveFileJobCallback;

    public static final String KEY_UNREACHABLE_URL_LIST = "key_unreachable_url_list";
    private SharedPreferences mSharedPrefs;

    public SharePlugin() {
        backgroundJobHandler = BackgroundJobHandler.newFixedThreadPoolBackgroundJobHandler(5);
        handler = new Handler(Looper.getMainLooper());
        receiveFileJobCallback = new Callback();
    }

    @Override
    public boolean onCreate() {
        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        createOrUpdateDynamicShortcut(null);
        // Deliver URLs previously shared to this device now that it's connected
        deliverPreviouslySentIntents();
        return true;
    }

    /** Files of a share that a drop or a restart interrupted go on from the queue (the one in flight resumes by itself). */
    @Override
    public void onFeatureReady() {
        if (uploadFileJob != null) return;
        if (ShareQueue.pending(context, device.getDeviceId()).isEmpty()) return;
        new Thread(() -> {
            // Decided under the same lock sendUriList builds under: a share being built or running is never started twice.
            synchronized (buildLock) {
                if (uploadFileJob != null) return;
                List<String> pending = ShareQueue.pending(context, device.getDeviceId());
                if (pending.isEmpty()) return;
                ArrayList<Uri> uris = new ArrayList<>();
                for (String text : pending) uris.add(Uri.parse(text));
                sendUriList(uris);
            }
        }, "desklink-share-queue").start();
    }

    private final Object buildLock = new Object();

    @Override
    public void onDestroy() {
        for (ShortcutInfoCompat shortcut : ShortcutManagerCompat.getDynamicShortcuts(context)) {
            if (!shortcut.getId().equals(device.getDeviceId())) continue;
            if (!device.isReachable() && shortcut.isPinned()) {
                // Create an updated shortcut with the same ID
                createOrUpdateDynamicShortcut(shortcut);
                break;
            } else {
                ShortcutManagerCompat.removeLongLivedShortcuts(context, List.of(shortcut.getId()));
            }
        }
        super.onDestroy();
    }

    private void createOrUpdateDynamicShortcut(@Nullable ShortcutInfoCompat shortcutToUpdate) {
        final boolean isNewShortcut = shortcutToUpdate == null;
        IconCompat icon = IconCompat.createWithResource(
                context, device.getDeviceType().toShortcutDrawableId());
        Intent shortcutIntent = null;
        if (isNewShortcut) {
            shortcutIntent = new Intent(context, MainActivity.class);
            shortcutIntent.setAction(Intent.ACTION_VIEW);
            shortcutIntent.putExtra(MainActivity.EXTRA_DEVICE_ID, device.getDeviceId());
        }
        ShortcutInfoCompat shortcut = new ShortcutInfoCompat
                .Builder(context, device.getDeviceId())
                .setIntent(isNewShortcut ? shortcutIntent : shortcutToUpdate.getIntent())
                .setIcon(icon)
                .setShortLabel(isNewShortcut ? device.getName()
                        : context.getString(
                                R.string.unreachable_device_dynamic_shortcut,
                                shortcutToUpdate.getShortLabel()))
                .setCategories(isNewShortcut ? Set.of("org.desklink.mobile.category.SHARE_TARGET")
                        : shortcutToUpdate.getCategories())
                .setLocusId(isNewShortcut ? new LocusIdCompat(device.getDeviceId())
                        : shortcutToUpdate.getLocusId())
                .build();
        if (isNewShortcut) {
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut);
        } else {
            ShortcutManagerCompat.updateShortcuts(context, List.of(shortcut));
        }
    }

    private void deliverPreviouslySentIntents() {
        Set<String> currentUrlSet = mSharedPrefs.getStringSet(KEY_UNREACHABLE_URL_LIST + device.getDeviceId(), null);
        if (currentUrlSet != null) {
            for (String url : currentUrlSet) {
                Intent intent;
                try {
                    intent = Intent.parseUri(url, 0);
                    intent.putExtra(Intent.EXTRA_TEXT, url);
                } catch (URISyntaxException ex) {
                    Log.e("SharePlugin", "Malformed URI");
                    continue;
                }
                if (intent != null) {
                    share(intent);
                }
            }
            mSharedPrefs.edit().putStringSet(KEY_UNREACHABLE_URL_LIST + device.getDeviceId(), null).apply();
        }
    }

    @Override
    protected int getOptionalPermissionExplanation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return R.string.share_notifications_explanation;
        } else {
            return R.string.share_optional_permission_explanation;
        }
    }

    @Override
    public @NonNull String getDisplayName() {
        return context.getResources().getString(R.string.pref_plugin_sharereceiver);
    }

    @Override
    public @NonNull String getDescription() {
        return context.getResources().getString(R.string.pref_plugin_sharereceiver_desc);
    }

    @Override
    public @NotNull List<@NotNull PluginUiButton> getUiButtons() {
        return List.of(new PluginUiButton(context.getString(R.string.send_files), R.drawable.share_plugin_action_24dp, parentActivity -> {
            Intent intent = new Intent(parentActivity, SendFileActivity.class);
            intent.putExtra("deviceId", getDevice().getDeviceId());
            parentActivity.startActivity(intent);
            return Unit.INSTANCE;
        }));
    }

    private static void closeUnusedPayload(@NonNull NetworkPacket np) {
        if (np.getPayload() != null) {
            np.getPayload().close();
        }
    }

    @Override
    @WorkerThread
    public boolean onPacketReceived(@NonNull NetworkPacket np) {
        try {
            // Only receiveFile consumes a payload. The other three branches used
            // to return with one still open, which over WebRTC leaks a file
            // descriptor and leaves the backing temp file undeleted, because
            // Payload.close() is what removes it.
            if (np.has("filename") && org.desklink.mobile.plugins.clipboard.ClipboardImages.INSTANCE.isClipboardImage(np)) {
                // C3: the desk's image copy goes on the clipboard, never into Downloads.
                org.desklink.mobile.plugins.clipboard.ClipboardImages.INSTANCE.receive(context, np);
            } else if (np.has("filename")) {
                // The v1 payload-on-the-packet path is gone: files arrive only as v2 transfers. A bare packet like this used to
                // make an empty file in Downloads.
                Log.w("SharePlugin", "Ignoring a v1 file packet");
                closeUnusedPayload(np);
            } else if (np.has("text")) {
                Log.i("SharePlugin", "hasText");
                receiveText(np);
                closeUnusedPayload(np);
            } else if (np.has("url")) {
                receiveUrl(np);
                closeUnusedPayload(np);
            } else {
                Log.e("SharePlugin", "Error: Nothing attached!");
                closeUnusedPayload(np);
            }

        } catch (Exception e) {
            Log.e("SharePlugin", "Exception");
            e.printStackTrace();
        }

        return true;
    }

    /**
     * Schemes a paired peer may ask this phone to open.
     *
     * The URL comes off the wire and used to be handed to ACTION_VIEW as-is, so
     * a paired desktop could drive an arbitrary activity with attacker-chosen
     * data through {@code content://}, {@code file://} or a vendor deep link.
     * Sharing a link is the actual feature, so the web schemes are enough.
     */
    private static boolean isShareableUrl(@Nullable Uri uri) {
        if (uri == null || uri.getScheme() == null) {
            return false;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        return scheme.equals("http") || scheme.equals("https") || scheme.equals("mailto")
                || scheme.equals("tel") || scheme.equals("geo") || scheme.equals("sms");
    }

    private void receiveUrl(NetworkPacket np) {
        String url = np.getString("url");

        Uri uri = Uri.parse(url);
        if (!isShareableUrl(uri)) {
            Log.w("SharePlugin", "Refusing to open a shared URL with an unsupported scheme");
            return;
        }

        // Android 10+ forbids opening it from the background: a heads-up with Open and Copy (C3).
        Log.i("SharePlugin", "Offering a shared URL");
        LinkFromDesk.INSTANCE.show(context, getDevice().getName(), url);
    }

    private void receiveText(NetworkPacket np) {
        String text = np.getString("text");
        ClipboardManager cm = ContextCompat.getSystemService(context, ClipboardManager.class);
        cm.setText(text);
        handler.post(() -> Toast.makeText(context, R.string.shareplugin_text_saved, Toast.LENGTH_LONG).show());
    }


    void sendUriList(final ArrayList<Uri> uriList) {
        synchronized (buildLock) {
            sendUriListLocked(uriList);
        }
    }

    private void sendUriListLocked(final ArrayList<Uri> uriList) {
        CompositeUploadFileJob job;
        int unreadable = 0;

        if (uploadFileJob == null) {
            job = new CompositeUploadFileJob(getDevice(), this.receiveFileJobCallback);
        } else {
            job = uploadFileJob;
        }

        // Queued by source, so a drop or a restart keeps the files after the one in flight (see onFeatureReady).
        ArrayList<String> asStrings = new ArrayList<>();
        for (Uri uri : uriList) asStrings.add(uri.toString());
        int dropped = ShareQueue.add(context, device.getDeviceId(), asStrings);
        if (dropped > 0) {
            handler.post(() -> Toast.makeText(context, context.getString(R.string.share_queue_full, ShareQueue.MAX_PER_DEVICE), Toast.LENGTH_LONG).show());
        }
        //Read all the data early, as we only have permissions to do it while the activity is alive
        for (Uri uri : uriList) {
            NetworkPacket np = FilesHelper.uriToNetworkPacket(context, uri, PACKET_TYPE_SHARE_REQUEST);

            if (np != null) {
                TransferHistory.rememberSource(np, uri);
                job.addNetworkPacket(np);
            } else {
                ShareQueue.remove(context, device.getDeviceId(), uri.toString());
                // Remembered per file, announced once below (a queue left from before a reboot would otherwise post one each).
                SendAgain.add(context, null, java.util.UUID.randomUUID().toString(), device.getDeviceId(), uri.getLastPathSegment() == null ? "file" : uri.getLastPathSegment(), true);
                unreadable++;
            }
        }
        if (unreadable > 0) {
            SendAgain.announce(context, device, context.getResources().getQuantityString(R.plurals.share_queue_unreadable, unreadable, unreadable));
        }

        if (job != uploadFileJob) {
            uploadFileJob = job;
            backgroundJobHandler.runJob(uploadFileJob);
        }
    }

    public void share(Intent intent) {
        Bundle extras = intent.getExtras();
        ArrayList<Uri> streams = streamsFromIntent(intent, extras);
        if (streams != null && !streams.isEmpty()) {
            sendUriList(streams);
            return;
        }
        if (extras != null) {
            String text = extras.getString(Intent.EXTRA_TEXT);
            if (StringUtils.isNotEmpty(text)) {
                Log.i("SharePlugin", "Intent contains text to share");

                //Hack: Detect shared youtube videos, so we can open them in the browser instead of as text
                String subject = extras.getString(Intent.EXTRA_SUBJECT);
                if (subject != null && subject.endsWith("YouTube")) {
                    int index = text.indexOf(": http://youtu.be/");
                    if (index > 0) {
                        text = text.substring(index + 2); //Skip ": "
                    }
                }

                boolean isUrl;
                try {
                    new URL(text);
                    isUrl = true;
                } catch (MalformedURLException e) {
                    isUrl = false;
                }
                // "Open on <desk>" (C3): the link inside "Title https://…" is what the desk opens.
                String link = LinkFromDesk.INSTANCE.openOnDeskLink(intent, text);
                if (link != null) {
                    text = link;
                    isUrl = true;
                }
                NetworkPacket np = new NetworkPacket(SharePlugin.PACKET_TYPE_SHARE_REQUEST);
                np.set(isUrl ? "url" : "text", text);
                device.sendPacket(np);
                return;
            }
        }
        Log.e("SharePlugin", "There's nothing we know how to share");
    }

    private ArrayList<Uri> streamsFromIntent(Intent intent, Bundle extras) {
        if (extras == null || !extras.containsKey(Intent.EXTRA_STREAM)) {
            return null;
        }
        Log.i("SharePlugin", "Intent contains streams to share");
        ArrayList<Uri> uriList;
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            uriList = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri.class);
        } else {
            uriList = new ArrayList<>();
            uriList.add(BundleCompat.getParcelable(extras, Intent.EXTRA_STREAM, Uri.class));
        }
        // From another app: content URIs of other apps only (never file: paths or this app's own provider).
        uriList = new ArrayList<>(ShareUris.INSTANCE.fromOutside(context, uriList));
        if (uriList.isEmpty()) {
            Log.w("SharePlugin", "All streams were null");
        }
        return uriList;
    }

    @Override
    public @NonNull String[] getSupportedPacketTypes() {
        return new String[]{PACKET_TYPE_SHARE_REQUEST};
    }

    @Override
    public @NonNull String[] getOutgoingPacketTypes() {
        return new String[]{PACKET_TYPE_SHARE_REQUEST};
    }

    @Override
    public @NonNull String[] getOptionalPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{Manifest.permission.POST_NOTIFICATIONS};
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return ArrayUtils.EMPTY_STRING_ARRAY;
        } else {
            return new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE};
        }
    }

    private class Callback implements BackgroundJob.Callback<Void> {
        @Override
        public void onResult(@NonNull BackgroundJob job, Void result) {
            if (job == receiveFileJob) {
                receiveFileJob = null;
            } else if (job == uploadFileJob) {
                uploadFileJob = null;
            }
        }

        @Override
        public void onError(@NonNull BackgroundJob job, @NonNull Throwable error) {
            if (job == receiveFileJob) {
                receiveFileJob = null;
            } else if (job == uploadFileJob) {
                uploadFileJob = null;
            }
        }
    }

    void cancelJob(long jobId) {
        if (backgroundJobHandler.isRunning(jobId)) {
            BackgroundJob job = backgroundJobHandler.getJob(jobId);

            if (job != null) {
                job.cancel();

                if (job == receiveFileJob) {
                    receiveFileJob = null;
                } else if (job == uploadFileJob) {
                    uploadFileJob = null;
                }
            }
        }
    }

    @Override
    public void onDeviceUnpaired(Context context, String deviceId) {
        Log.i("DeskLink/SharePlugin", "onDeviceUnpaired deviceId = " + deviceId);
        if (mSharedPrefs == null) {
            mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        }
        mSharedPrefs.edit().remove(KEY_UNREACHABLE_URL_LIST + deviceId).apply();
    }
}
