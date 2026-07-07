/*
 * SPDX-FileCopyrightText: 2019 Erik Duisters <e.duisters1@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.ui;

import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;

import org.desklink.mobile.R;

public class PermissionsAlertDialogFragment extends AlertDialogFragment {
    private static final String TAG = "PermissionsDialog";
    private static final String KEY_PERMISSIONS = "Permissions";
    private static final String KEY_REQUEST_CODE = "RequestCode";

    private String[] permissions;
    private int requestCode;

    public PermissionsAlertDialogFragment() {
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle args = getArguments();

        if (args == null || !args.containsKey(KEY_PERMISSIONS)) {
            throw new RuntimeException("You must call Builder.setPermission() to set the array of needed permissions");
        }

        permissions = args.getStringArray(KEY_PERMISSIONS);
        if (permissions == null) {
            permissions = new String[0];
        }
        requestCode = args.getInt(KEY_REQUEST_CODE, 0);

        setCallback(new Callback() {
            @Override
            public boolean onPositiveButtonClicked() {
                if (!hasValidPermissions(permissions)) {
                    Log.w(TAG, "Ignoring permission request with no runtime permissions");
                    return true;
                }
                ActivityCompat.requestPermissions(requireActivity(), permissions, requestCode);
                return true;
            }
        });
    }

    static boolean hasValidPermissions(String[] permissions) {
        if (permissions.length == 0) {
            return false;
        }

        for (String permission : permissions) {
            if (permission == null || permission.trim().isEmpty()) {
                return false;
            }
        }

        return true;
    }

    public static class Builder extends AlertDialogFragment.AbstractBuilder<Builder, PermissionsAlertDialogFragment> {

        public Builder() {
            setPositiveButton(R.string.ok);
            setNegativeButton(R.string.cancel);
        }

        @Override
        public Builder getThis() {
            return this;
        }

        public Builder setPermissions(String[] permissions) {
            args.putStringArray(KEY_PERMISSIONS, permissions);

            return getThis();
        }

        public Builder setRequestCode(int requestCode) {
            args.putInt(KEY_REQUEST_CODE, requestCode);

            return getThis();
        }

        @Override
        protected PermissionsAlertDialogFragment createFragment() {
            return new PermissionsAlertDialogFragment();
        }
    }
}
