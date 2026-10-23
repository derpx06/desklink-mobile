/*
 * SPDX-FileCopyrightText: 2015 Vineet Garg <grg.vineet@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.desklink.mobile.plugins.notifications;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import android.os.Looper;

import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

class AppDatabase {

    static final private HashSet<String> disabledByDefault = new HashSet<>();
    static {
        disabledByDefault.add("com.google.android.googlequicksearchbox"); //Google Now notifications re-spawn every few minutes
    }

    private static final String SETTINGS_NAME = "app_database";
    private static final String SETTINGS_KEY_ALL_ENABLED = "all_enabled";

    private static final int DATABASE_VERSION = 5;
    private static final String DATABASE_NAME = "Applications";
    private static final String TABLE_ENABLED = "Applications";
    private static final String TABLE_PRIVACY = "PrivacyOpts";
    private static final String KEY_PACKAGE_NAME = "packageName";
    private static final String KEY_IS_ENABLED = "isEnabled";
    private static final String KEY_PRIVACY_OPTIONS = "privacyOptions";


    private static final String DATABASE_CREATE_ENABLED = "CREATE TABLE "
            + TABLE_ENABLED + "(" + KEY_PACKAGE_NAME + " TEXT PRIMARY KEY NOT NULL, "
            + KEY_IS_ENABLED + " INTEGER NOT NULL ); ";
    private static final String DATABASE_CREATE_PRIVACY_OPTS = "CREATE TABLE "
            + TABLE_PRIVACY + "(" + KEY_PACKAGE_NAME + " TEXT PRIMARY KEY NOT NULL, "
            + KEY_PRIVACY_OPTIONS + " INTEGER NOT NULL); ";


    private final DbHelper ourHelper;
    private final SharedPreferences prefs;

    // Both tables, in memory: the notification listener asks about every notification on the main thread, where a
    // database query is a StrictMode violation and a stutter. Loaded once, off that thread (see preload()).
    private final ConcurrentHashMap<String, Boolean> enabledCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> privacyCache = new ConcurrentHashMap<>();
    private volatile boolean cacheLoaded = false;

    /** Reads both tables into memory. Call from a worker thread. */
    void preload() {
        loadCache();
        getAllEnabled(); // brings the preferences in as well
    }

    private synchronized void loadCache() {
        if (cacheLoaded) return;
        SQLiteDatabase db = ourHelper.getReadableDatabase();
        try (Cursor res = db.query(TABLE_ENABLED, new String[]{KEY_PACKAGE_NAME, KEY_IS_ENABLED}, null, null, null, null, null)) {
            while (res.moveToNext()) enabledCache.put(res.getString(0), res.getInt(1) != 0);
        }
        try (Cursor res = db.query(TABLE_PRIVACY, new String[]{KEY_PACKAGE_NAME, KEY_PRIVACY_OPTIONS}, null, null, null, null, null)) {
            while (res.moveToNext()) privacyCache.put(res.getString(0), res.getInt(1));
        }
        cacheLoaded = true;
    }

    /** True once both tables are in memory. */
    boolean isReady() {
        return cacheLoaded;
    }

    /** Every privacy option blocked: what an app is treated as having until its real options are known. */
    static int allBlocked() {
        return (1 << PrivacyOptions.values().length) - 1;
    }

    private static boolean onMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    static private AppDatabase _instance = null;

    static public AppDatabase getInstance(Context context) {
        if (_instance == null) {
            _instance = new AppDatabase(context.getApplicationContext());
        }
        return _instance;
    }

    private AppDatabase(Context context) {
        ourHelper = new DbHelper(context);
        prefs = context.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    @Override
    protected void finalize() throws Throwable {
        ourHelper.close();
        super.finalize();
    }

    private static class DbHelper extends SQLiteOpenHelper {

        DbHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL(DATABASE_CREATE_ENABLED);
            db.execSQL(DATABASE_CREATE_PRIVACY_OPTS);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 5) {
                db.execSQL(DATABASE_CREATE_PRIVACY_OPTS);
            }
        }

    }

    void setEnabled(String packageName, boolean isEnabled) {
        if (!cacheLoaded) loadCache(); // a write may be the first thing that happens
        enabledCache.put(packageName, isEnabled);
        String[] columns = new String[]{KEY_IS_ENABLED};
        SQLiteDatabase ourDatabase = ourHelper.getWritableDatabase();
        try (Cursor res = ourDatabase.query(TABLE_ENABLED, columns, KEY_PACKAGE_NAME + " =? ", new String[]{packageName}, null, null, null)) {
            ContentValues cv = new ContentValues();
            cv.put(KEY_IS_ENABLED, isEnabled ? 1 : 0);
            if (res.getCount() > 0) {
                ourDatabase.update(TABLE_ENABLED, cv, KEY_PACKAGE_NAME + "=?", new String[]{packageName});
            } else {
                cv.put(KEY_PACKAGE_NAME, packageName);
                long retVal = ourDatabase.insert(TABLE_ENABLED, null, cv);
                Log.i("AppDatabase", "SetEnabled retval = " + retVal);
            }
        }
    }

    boolean getAllEnabled() {
        return prefs.getBoolean(SETTINGS_KEY_ALL_ENABLED, true);
    }

    void setAllEnabled(boolean enabled) {
        if (!cacheLoaded) loadCache();
        prefs.edit().putBoolean(SETTINGS_KEY_ALL_ENABLED, enabled).apply();
        SQLiteDatabase ourDatabase = ourHelper.getWritableDatabase();
        ourDatabase.execSQL("UPDATE " + TABLE_ENABLED + " SET " + KEY_IS_ENABLED + "=" + (enabled? "1" : "0"));
        for (String key : enabledCache.keySet()) enabledCache.put(key, enabled);
    }

    boolean isEnabled(String packageName) {
        // Until the tables are in memory the main thread answers "no" rather than query the database: an app the
        // person switched off must never be mirrored because its setting had not been read yet.
        if (!cacheLoaded) {
            if (onMainThread()) return false;
            loadCache();
        }
        Boolean cached = enabledCache.get(packageName);
        return cached != null ? cached : getDefaultStatus(packageName);
    }

    private boolean getDefaultStatus(String packageName) {
        if (disabledByDefault.contains(packageName)) {
            return false;
        }
        return getAllEnabled();
    }

    public enum PrivacyOptions {
        BLOCK_CONTENTS,
        BLOCK_IMAGES
        // Just add new enum to add a new privacy option.
    }

    private int getPrivacyOptionsValue(String packageName)
    {
        if (!cacheLoaded) {
            if (onMainThread()) return allBlocked(); // unknown: contents and pictures blocked
            loadCache();
        }
        Integer cached = privacyCache.get(packageName);
        return cached != null ? cached : 0;
    }

    private void setPrivacyOptionsValue(String packageName, int value) {
        privacyCache.put(packageName, value);
        String[] columns = new String[]{KEY_PRIVACY_OPTIONS};
        SQLiteDatabase ourDatabase = ourHelper.getWritableDatabase();
        try (Cursor res = ourDatabase.query(TABLE_PRIVACY, columns, KEY_PACKAGE_NAME + " =? ", new String[]{packageName}, null, null, null)) {
            ContentValues cv = new ContentValues();
            cv.put(KEY_PRIVACY_OPTIONS, value);
            if (res.getCount() > 0) {
                ourDatabase.update(TABLE_PRIVACY, cv, KEY_PACKAGE_NAME + "=?", new String[]{packageName});
            } else {
                cv.put(KEY_PACKAGE_NAME, packageName);
                long retVal = ourDatabase.insert(TABLE_PRIVACY, null, cv);
                Log.i("AppDatabase", "SetPrivacyOptions retval = " + retVal);
            }
        }
    }

    /**
     * Set privacy option of an app.
     * @param packageName name of the app
     * @param option option of PrivacyOptions enum, that we set the value of.
     * @param isBlocked boolean, if user wants to block an option.
     */
    public void setPrivacy(String packageName, PrivacyOptions option, boolean isBlocked) {
        if (!cacheLoaded) loadCache(); // a write may be the first thing that happens: never start from a blank value
        // Bit, that we want to change
        int curBit = option.ordinal();
        // Current value of privacy options
        int value = getPrivacyOptionsValue(packageName);
        // Make the selected bit '1'
        value |= (1 << curBit);
        // If we want to block an option, we set the selected bit to '0'.
        value ^= isBlocked ? 0 : (1 << curBit);
        // Update the value
        setPrivacyOptionsValue(packageName, value);
    }

    /**
     * Get privacy option of an app.
     * @param packageName name of the app
     * @param option option of PrivacyOptions enum, that we set the value of.
     * @return returns true if the option is blocking.
     */
    public boolean getPrivacy(String packageName, PrivacyOptions option) {
        // Bit, that we want to change
        int curBit = option.ordinal();
        // Current value of privacy options
        int value = getPrivacyOptionsValue(packageName);
        // Read the bit
        int bit = value & (1 << curBit);
        // If that bit was 0, the bit variable is 0. If not, it's some power of 2.
        return bit != 0;
    }
}
