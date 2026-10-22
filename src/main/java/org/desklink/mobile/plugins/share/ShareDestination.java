/*
 * SPDX-FileCopyrightText: 2016 Richard Wagler <riwag@posteo.de>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.plugins.share;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;
import androidx.preference.PreferenceManager;

import java.io.File;

/** Where received files go: the Downloads folder, or one the person picked (Settings, Files). */
public final class ShareDestination {

    private final static String PREFERENCE_CUSTOMIZE_DESTINATION = "share_destination_custom";
    private final static String PREFERENCE_DESTINATION = "share_destination_folder_uri";

    private ShareDestination() {}

    public static File getDefaultDestinationDirectory() {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
    }

    public static boolean isCustomDestinationEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREFERENCE_CUSTOMIZE_DESTINATION, false);
    }

    //Will return the appropriate directory, whether it is customized or not
    public static DocumentFile getDestinationDirectory(Context context) {
        if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREFERENCE_CUSTOMIZE_DESTINATION, false)) {
            String path = PreferenceManager.getDefaultSharedPreferences(context).getString(PREFERENCE_DESTINATION, null);
            if (path != null) {
                DocumentFile treeDocumentFile = DocumentFile.fromTreeUri(context, Uri.parse(path));
                if (treeDocumentFile.canWrite()) { //Checks for FLAG_DIR_SUPPORTS_CREATE on directories
                    return treeDocumentFile;
                } else {
                    //Maybe permission was revoked
                    Log.w("SharePlugin", "Share destination is not writable, falling back to default path.");
                }
            }
        }
        try {
            getDefaultDestinationDirectory().mkdirs();
        } catch (Exception e) {
            Log.e("DeskLink", "Exception", e);
        }
        return DocumentFile.fromFile(getDefaultDestinationDirectory());
    }

    public static void saveStorageLocationPreference(Context context, Uri uri) {
        context.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putString(PREFERENCE_DESTINATION, uri.toString()).apply();
        prefs.edit().putBoolean(PREFERENCE_CUSTOMIZE_DESTINATION, true).apply();
    }
}
