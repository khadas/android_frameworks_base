/*
 * Copyright 2024 Rockchip Electronics S.LSI Co. LTD
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.util.Log;
import android.os.EbookManager;

import com.android.systemui.navigationbar.NavigationBar;
import com.android.systemui.util.Utils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class EbookSettingsProvider extends ContentProvider {
    private static final String TAG = "EbookSettingsProvider";
    public static final int EBOOKSETTINGS = 0;
    public static final int EBOOKSETTINGS_UPDATE = 1;
    public static String packageName = "";
    public static final String EBOOK_REFRESH_FREQUENCY = "persist.vendor.fullmode_cnt";
    public static final String AUTHORITY = "com.android.systemui.ebook";
    public static final String EBOOKSETTINGS_TABLE = "EbookSettings";
    public static final Uri URI_EBOOK_SETTINGS = Uri.parse("content://com.android.systemui.ebook/ebooksettings");
    public static int refreshMode;
    public static int refreshFrequency;
    public static boolean isRefreshSetting;
    public static int mAppAnimFilter;//动画过滤

    private static UriMatcher mUriMatcher;
    private EbookSettingsDataBaseHelper mEbookSettingsDataBaseHelper;
    private SQLiteDatabase mDB;
    private EbookSettingsManager mEbookSettingsManager;
    private static final Set<String> mBlackListSet = new HashSet<>(Arrays.asList(NavigationBar.BLACK_EBOOK_CONFIG_APP));
    static {
        mUriMatcher = new UriMatcher((UriMatcher.NO_MATCH));
        mUriMatcher.addURI(AUTHORITY, "ebooksettings", EBOOKSETTINGS);
        mUriMatcher.addURI(AUTHORITY, "ebooksettingsupdate", EBOOKSETTINGS_UPDATE);
    }
    private boolean isEbookProduct = Utils.isEbookProduct();

    public EbookSettingsProvider() {

    }

    @Override
    public boolean onCreate() {
        Log.i(TAG, "onCreate isEbookProduct=" + isEbookProduct);
        if (!isEbookProduct) {
            return true;
        }
        mEbookSettingsDataBaseHelper = new EbookSettingsDataBaseHelper(getContext(), "Ebook", null, 1);
        if (mEbookSettingsDataBaseHelper != null) {
            mDB = mEbookSettingsDataBaseHelper.getWritableDatabase();
        }
        if(mEbookSettingsManager == null) {
            mEbookSettingsManager = new EbookSettingsManager(getContext());
        }
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (!isEbookProduct) {
            return null;
        }
        Log.i(TAG, "query uri: " + uri);
        mDB = mEbookSettingsDataBaseHelper.getReadableDatabase();
        Cursor cursor = null;
        switch (mUriMatcher.match(uri)) {
            case EBOOKSETTINGS:
                cursor = mDB.query(EBOOKSETTINGS_TABLE, projection, selection,
                        selectionArgs, null, null, sortOrder);
                break;
            case EBOOKSETTINGS_UPDATE:
                packageName = selectionArgs[0];
                Log.d(TAG, "packageName: " + packageName);
                if (!mBlackListSet.contains(packageName)) {
                    //query(URI_EBOOK_SETTINGS_UPDATE,       null,      "package_name = ?",
                    cursor = mDB.query(EBOOKSETTINGS_TABLE, projection, selection,
                            // new String[]{null}, null);
                            selectionArgs, null, null, sortOrder);
                    if(cursor.getCount() > 0) {
                        if(cursor.moveToFirst()) {
                            //动画过滤
                            mAppAnimFilter = cursor.getInt(cursor.getColumnIndex(
                                    EbookSettingsDataBaseHelper.APP_ANIM_FILTER));
                        }
                    } else {
                        isRefreshSetting = false;
                        refreshMode = Integer.valueOf(EbookManager.EbookMode.EPD_PART_GLR16);
                        refreshFrequency = EbookSettingsDataBaseHelper.INIT_REFRESH_FREQUENCY;
                        mAppAnimFilter = 0;
                    }
                    //刷新设置开启下才设置
                    if(isRefreshSetting && mEbookSettingsManager != null) {
                        mEbookSettingsManager.setEbookMode(String.valueOf(refreshMode));
                        mEbookSettingsManager.setProperty(EBOOK_REFRESH_FREQUENCY, String.valueOf(refreshFrequency));
                    } else {
                        mEbookSettingsManager.setEbookMode(String.valueOf(EbookManager.EbookMode.EPD_PART_GLR16));
                        mEbookSettingsManager.setProperty(EbookSettingsProvider.EBOOK_REFRESH_FREQUENCY,
                                String.valueOf(EbookSettingsDataBaseHelper.INIT_REFRESH_FREQUENCY));
                    }
                    break;
                } else {
                    Log.d(TAG, "It's in the blacklist with " + packageName);
                }
        }
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (!isEbookProduct) {
            return null;
        }
        Log.i(TAG, "insert ");
        mDB = mEbookSettingsDataBaseHelper.getWritableDatabase();
        switch (mUriMatcher.match(uri)) {
            case EBOOKSETTINGS:
                mDB.insert(EBOOKSETTINGS_TABLE, null, values);
                break;
        }
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        if (!isEbookProduct) {
            return 0;
        }
        mDB = mEbookSettingsDataBaseHelper.getWritableDatabase();
        int updatedRows = 0;
        switch (mUriMatcher.match(uri)) {
            case EBOOKSETTINGS:
                updatedRows = mDB.delete(EBOOKSETTINGS_TABLE, selection, selectionArgs);
                mDB.close();
                break;
        }
        return updatedRows;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        if (!isEbookProduct) {
            return 0;
        }
        mDB = mEbookSettingsDataBaseHelper.getWritableDatabase();
        int updatedRows = 0;
        switch (mUriMatcher.match(uri)) {
            case EBOOKSETTINGS:
                updatedRows = mDB.update(EBOOKSETTINGS_TABLE, values, selection, selectionArgs);
                Log.d(TAG, "updatedRows: " + updatedRows);
                //mDB.close();
                break;
        }
        return updatedRows;
    }
}
