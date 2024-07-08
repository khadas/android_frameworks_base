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

package android.os;

import android.annotation.Nullable;
import android.annotation.SystemService;
import android.content.Context;
import android.util.Log;
import android.os.IEbookManager;
import android.os.SystemProperties;

@SystemService(Context.EBOOK_SERVICE)
public class EbookManager {
    private static final String TAG = "EbookManager";
    private static final boolean DEBUG = true;
    private static int num =1;
    private static final int MIN_COLOR_CFG_RKCFA_VALUE = 0;
    private static final int MAX_COLOR_CFG_RKCFA_VALUE = 128;
    private static final int DEFAULT_COLOR_CFG_RKCFA_VALUE = 64;

    public class EbookRefreshMode {

        private EbookRefreshMode() {}

        public static final int EPD_PART_GC16           = 7;
        public static final int EPD_PART_GLR16          = 9;
        public static final int EPD_A2                  = 12;
        public static final int EPD_A2_FAST             = 13;
        public static final int EPD_DU                  = 14;
    }

    /*-------------------------------------------------------*/
    IEbookManager mService;
    final Context mContext;
    final Handler mHandler;

    /*-------------------------------------------------------*/

    /**
     * {@hide}
     */
    public EbookManager(@Nullable Context context,@Nullable IEbookManager service,@Nullable Handler handler) {
        Log.i(TAG, "EbookManager constructor");
        mContext = context;
        mService = service;
        mHandler = handler;
    }

    public void sendOneFullFrame(){
        try {
            Log.i(TAG, "sendOneFullFrame");
            num = ++num;
            if(num > Integer.MAX_VALUE-100){
                num =1;
            }
            String numStr = num +"";
            mService.setProperty("sys.ebook.one_full_mode_timeline",numStr);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setRefreshMode(int mode) {
        try {
            Log.i(TAG, "setRefreshMode " + mode);
            mService.setProperty("sys.ebook.mode", String.valueOf(mode));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public int getRefreshMode() {
        int mode = SystemProperties.getInt("sys.ebook.mode", EbookRefreshMode.EPD_PART_GLR16);
        Log.i(TAG, "getMode value: " + mode);
        return mode;
    }

    public void setFullModeCnt(int cnt) {
        try {
            Log.i(TAG, "setFullModeCnt " + cnt);
            mService.setProperty("persist.ebook.fullmode_cnt", String.valueOf(cnt));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public int getFullModeCnt() {
        int cnt = SystemProperties.getInt("persist.ebook.fullmode_cnt", 0);
        Log.i(TAG, "getFullModeCnt value: " + cnt);
        return cnt;
    }

    public boolean setColorDep(int value) {
        Log.i(TAG, "setColorDep " + value);
        if (value < MIN_COLOR_CFG_RKCFA_VALUE || value > MAX_COLOR_CFG_RKCFA_VALUE) {
            Log.e(TAG, "setColorDep value need in [" + MIN_COLOR_CFG_RKCFA_VALUE
                + ", " + MAX_COLOR_CFG_RKCFA_VALUE + "]");
            return false;
        }
        try {
            mService.setProperty("persist.ebook.colordep", String.valueOf(value));
            return true;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    public int getColorDep() {
        int value = SystemProperties.getInt("persist.ebook.colordep", DEFAULT_COLOR_CFG_RKCFA_VALUE);
        Log.i(TAG, "getColorDep value: " + value);
        return value;
    }

    public boolean setContrast(int value) {
        Log.i(TAG, "setContrast " + value);
        if (value < MIN_COLOR_CFG_RKCFA_VALUE || value > MAX_COLOR_CFG_RKCFA_VALUE) {
            Log.e(TAG, "setContrast value need in [" + MIN_COLOR_CFG_RKCFA_VALUE
                + ", " + MAX_COLOR_CFG_RKCFA_VALUE + "]");
            return false;
        }
        try {
            mService.setProperty("persist.ebook.contgain", String.valueOf(value));
            return true;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    public int getContrast() {
        int value = SystemProperties.getInt("persist.ebook.contgain", DEFAULT_COLOR_CFG_RKCFA_VALUE);
        Log.i(TAG, "getContrast value: " + value);
        return value;
    }

    public boolean setSaturation(int value) {
        Log.i(TAG, "setSaturation " + value);
        if (value < MIN_COLOR_CFG_RKCFA_VALUE || value > MAX_COLOR_CFG_RKCFA_VALUE) {
            Log.e(TAG, "setSaturation value need in [" + MIN_COLOR_CFG_RKCFA_VALUE
                + ", " + MAX_COLOR_CFG_RKCFA_VALUE + "]");
            return false;
        }
        try {
            mService.setProperty("persist.ebook.satugain", String.valueOf(value));
            return true;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    public int getSaturation() {
        int value = SystemProperties.getInt("persist.ebook.satugain", DEFAULT_COLOR_CFG_RKCFA_VALUE);
        Log.i(TAG, "getSaturation value: " + value);
        return value;
    }

    public boolean setBrightness(int value) {
        Log.i(TAG, "setBrightness " + value);
        if (value < MIN_COLOR_CFG_RKCFA_VALUE || value > MAX_COLOR_CFG_RKCFA_VALUE) {
            Log.e(TAG, "setBrightness value need in [" + MIN_COLOR_CFG_RKCFA_VALUE
                + ", " + MAX_COLOR_CFG_RKCFA_VALUE + "]");
            return false;
        }
        try {
            mService.setProperty("persist.ebook.lumagain", String.valueOf(value));
            return true;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    public int getBrightness() {
        int value = SystemProperties.getInt("persist.ebook.lumagain", DEFAULT_COLOR_CFG_RKCFA_VALUE);
        Log.i(TAG, "getBrightness value: " + value);
        return value;
    }

    public int init() {
        try {
            Log.i(TAG, "init()");
            return mService.init();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return -1;
    }

}
