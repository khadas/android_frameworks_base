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

package com.android.systemui.ebook;

import android.content.Context;
import android.os.SystemProperties;
import android.os.EbookManager;
import android.content.Context;

public class EbookSettingsManager {
    private static final String TAG = "EbookSettingsManager";

    public static final int MIN_COLOR_CFG_RKCFA_VALUE = 0;
    public static final int MAX_COLOR_CFG_RKCFA_VALUE = 128;
    public static final int COLOR_CFG_RKCFA_INIT_VALUE = 64;
    public static final int DEFAULT_REFRESH_FREQUENCY = 0;
    public static final int DEFAULT_REFRESH_MODE = EbookManager.EbookRefreshMode.EPD_A2_FAST;

    private static EbookManager mEbookManager;
    private Context mContext;
    public EbookSettingsManager(Context context) {
        mContext = context;
    }

    public static String getProperty(String key) {
        String ss = SystemProperties.get(key);
        return null == ss?"":ss;
    }

    public void setProperty(String key, String refrshFrequency) {
        SystemProperties.set(key, refrshFrequency);
    }

    public void setRefreshMode(int mode) {
        if (mEbookManager == null){
            mEbookManager = (EbookManager)mContext.getSystemService(Context.EBOOK_SERVICE);
        }
        mEbookManager.setRefreshMode(mode);
    }

    public int getRefreshMode() {
        if (mEbookManager == null){
            mEbookManager = (EbookManager)mContext.getSystemService(Context.EBOOK_SERVICE);
        }
        return mEbookManager.getRefreshMode();
    }

    public void refreshAll() {
        if (mEbookManager == null){
            mEbookManager = (EbookManager)mContext.getSystemService(Context.EBOOK_SERVICE);
        }
        mEbookManager.sendOneFullFrame();
    }

    public void setFullModeCnt(int cnt) {
        if (null == mEbookManager){
            mEbookManager = (EbookManager)mContext.getSystemService(Context.EBOOK_SERVICE);
        }
        mEbookManager.setFullModeCnt(cnt);
    }

    public int getFullModeCnt() {
        if (null == mEbookManager){
            mEbookManager = (EbookManager)mContext.getSystemService(Context.EBOOK_SERVICE);
        }
        return mEbookManager.getFullModeCnt();
    }

    public boolean setColorDep(int value) {
        if (null == mEbookManager){
            mEbookManager = (EbookManager)mContext.getSystemService(Context.EBOOK_SERVICE);
        }
        return mEbookManager.setColorDep(value);
    }

    public int getColorDep() {
        if (null == mEbookManager){
            mEbookManager = (EbookManager)mContext.getSystemService(Context.EBOOK_SERVICE);
        }
        return mEbookManager.getColorDep();
    }

    public boolean setContrast(int value) {
        if (null == mEbookManager){
            mEbookManager = (EbookManager)mContext.getSystemService(Context.EBOOK_SERVICE);
        }
        return mEbookManager.setContrast(value);
    }

    public int getContrast() {
        if (null == mEbookManager){
            mEbookManager = (EbookManager)mContext.getSystemService(Context.EBOOK_SERVICE);
        }
        return mEbookManager.getContrast();
    }

    public boolean setSaturation(int value) {
        if (null == mEbookManager){
            mEbookManager = (EbookManager)mContext.getSystemService(Context.EBOOK_SERVICE);
        }
        return mEbookManager.setSaturation(value);
    }

    public int getSaturation() {
        if (null == mEbookManager){
            mEbookManager = (EbookManager)mContext.getSystemService(Context.EBOOK_SERVICE);
        }
        return mEbookManager.getSaturation();
    }

    public boolean setBrightness(int value) {
        if (null == mEbookManager){
            mEbookManager = (EbookManager)mContext.getSystemService(Context.EBOOK_SERVICE);
        }
        return mEbookManager.setBrightness(value);
    }

    public int getBrightness() {
        if (null == mEbookManager){
            mEbookManager = (EbookManager)mContext.getSystemService(Context.EBOOK_SERVICE);
        }
        return mEbookManager.getBrightness();
    }

    public int[] convertLevelToArray(int contrastLevel) {
        int contrast[] = new int[16];
        int mWhiteCount;
        int mBlackCount;
        if(contrastLevel < 80) {
            mWhiteCount = 2 + contrastLevel / 20;
            mBlackCount = 3 + contrastLevel / 10;
        }else {
            mWhiteCount = 5;
            mBlackCount = 11;
        }
        int whiteIndex = 16 - mWhiteCount;
        int remainder = 16 - mWhiteCount - mBlackCount;
        int remainderLevel = 14 / (remainder + 1);
        for(int i = 0, j = 1; i < 16; i++) {
            if(i < mBlackCount) {
                contrast[i] = 0;
            }else if(i >= whiteIndex) {
                contrast[i] = 15;
            }else {
                contrast[i] = j * remainderLevel;
                j++;
            }
        }
        //only one step X: 0xfffffX0000000000, fine adjust
        if (contrastLevel >= 70 && contrastLevel < 77)
            contrast[mBlackCount] = remainderLevel - (contrastLevel % remainderLevel);
        else if (contrastLevel >= 77)
            contrast[mBlackCount] = 0;
        return contrast;
    }

    public String convertArrayToString(int contrast[]) {
        String strContrast = "0x";
        for(int i = 15; i >= 0; i--) {
            if(contrast[i] <= 9) {
                strContrast += contrast[i];
            }else {
                switch (contrast[i]) {
                    case 10:
                        strContrast += "a";
                        break;
                    case 11:
                        strContrast += "b";
                        break;
                    case 12:
                        strContrast += "c";
                        break;
                    case 13:
                        strContrast += "d";
                        break;
                    case 14:
                        strContrast += "e";
                        break;
                    case 15:
                        strContrast += "f";
                        break;
                }
            }
        }
        return strContrast;
    }
}
