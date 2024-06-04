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

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

public class EbookSettingsDataBaseHelper extends SQLiteOpenHelper {
    private static final String TAG = "EbookSettingsDBH";
    public static final int INIT_REFRESH_FREQUENCY = 20;
    public static final String PACKAGE_NAME = "package_name";
    public static final String IS_REFRESH_SETTING = "is_refresh_setting";
    public static final String REFRESH_MODE = "refresh_mode";
    public static final String REFRESH_FREQUENCY = "refresh_frequency";
    public static final String APP_ANIM_FILTER = "app_anim_filter";

    public EbookSettingsDataBaseHelper(Context context, String name, SQLiteDatabase.CursorFactory factory, int version) {
        super(context, name, factory, 2);
        Log.d(TAG, "EbookSettingsDataBaseHelper version: " + version);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        Log.d(TAG, "onCreate: ");
        final String CREATE_EBOOKSETTINGS = "create table EbookSettings (" +
                "id integer primary key autoincrement, " +
                PACKAGE_NAME + " text, " +
                /** 刷新设置*/
                IS_REFRESH_SETTING + " integer default '0', " +
                REFRESH_MODE + " integer default '-1', " +
                REFRESH_FREQUENCY + " integer default '-1' " +
                ")";
        db.execSQL(CREATE_EBOOKSETTINGS);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.d(TAG, "onUpgrade  oldVersion:" + oldVersion + "newVersion:" + newVersion);
    }
}
