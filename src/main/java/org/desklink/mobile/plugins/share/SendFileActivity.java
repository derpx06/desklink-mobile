/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.plugins.share;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.desklink.mobile.helpers.ThreadHelper;
import org.desklink.mobile.DeskLinkApplication;
import org.desklink.mobile.R;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.concurrent.Executor;


public class SendFileActivity extends AppCompatActivity {

    private String mDeviceId;

    /** The activity that is on screen: a rotation while the files are read replaces the one that picked them. */
    private static WeakReference<SendFileActivity> live = new WeakReference<>(null);

    /** The picked files, each once: a picker that returns the first as `data` and again in the clip would send it twice. */
    static ArrayList<Uri> uniqueUris(Uri data, ClipData clip) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        ArrayList<Uri> uris = new ArrayList<>();
        if (data != null && seen.add(data.toString())) uris.add(data);
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null && seen.add(uri.toString())) uris.add(uri);
            }
        }
        return uris;
    }

    /**
     * Runs [work] (the reads) on [executor] and finishes the live activity after it, on the main thread: the picker's read
     * grant on the files ends with the activity that received them, and the reads open one file after another.
     */
    static void sendThenFinish(SendFileActivity from, Executor executor, Runnable work) {
        live = new WeakReference<>(from);
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        executor.execute(() -> {
            try {
                work.run();
            } finally {
                main.post(() -> {
                    SendFileActivity activity = live.get();
                    if (activity != null) activity.finish();
                });
            }
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mDeviceId = getIntent().getStringExtra("deviceId");
        live = new WeakReference<>(this);
        // Recreated (a rotation) while the picker is up or the files are being read: the picker is already there.
        if (savedInstanceState != null) return;

        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(
                    Intent.createChooser(intent, getString(R.string.send_files)), Activity.RESULT_FIRST_USER);
        } catch (android.content.ActivityNotFoundException ex) {
            Toast.makeText(this, R.string.no_file_browser, Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case Activity.RESULT_FIRST_USER:
                if (resultCode == RESULT_OK) {

                    final ArrayList<Uri> uris = uniqueUris(data.getData(), data.getClipData());
                    if (uris.isEmpty()) {
                        Log.w("SendFileActivity", "No files to send?");
                    } else {
                        // Stays until the reads are done (finished by sendThenFinish).
                        sendThenFinish(this, ThreadHelper::execute, () -> {
                            SharePlugin plugin = DeskLinkApplication.getInstance().getDevicePlugin(mDeviceId, SharePlugin.class);
                            if (plugin != null) plugin.sendUriList(uris);
                        });
                        break;
                    }
                }
                finish();
                break;
            default:
                super.onActivityResult(requestCode, resultCode, data);
        }
    }

}
