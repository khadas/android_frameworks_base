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

    public class EbookMode {

        private EbookMode() {}

        public static final String EPD_NULL ="-1";
        public static final String EPD_AUTO ="0";
        public static final String EPD_OVERLAY ="1";
        public static final String EPD_FULL_GC16 ="2";
        public static final String EPD_FULL_GL16 ="3";
        public static final String EPD_FULL_GLR16 ="4";
        public static final String EPD_FULL_GLD16 ="5";
        public static final String EPD_FULL_GCC16 ="6";
        public static final String EPD_PART_GC16 ="7";
        public static final String EPD_PART_GL16 ="8";
        public static final String EPD_PART_GLR16 ="9";
        public static final String EPD_PART_GLD16 ="10";
        public static final String EPD_PART_GCC16 ="11";
        public static final String EPD_A2 ="12";
        public static final String EPD_A2_DITHER ="13";
        public static final String EPD_DU ="14";
        public static final String EPD_DU4 ="15";
        public static final String EPD_A2_ENTER ="16";
        public static final String EPD_RESET ="17";
        public static final String EPD_AUTO_DU ="22";
        public static final String EPD_AUTO_DU4 ="23";
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
            mService.setProperty("sys.eink.one_full_mode_timeline",numStr);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setMode(@Nullable String ebookMode){
        try {
            Log.i(TAG, "setMode " + ebookMode);
            mService.setProperty("sys.eink.mode",ebookMode);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Nullable
    public String getMode() {
        String mode = SystemProperties.get("sys.eink.mode", EbookMode.EPD_PART_GLR16);
        Log.i(TAG, "getMode " + mode);
        return mode;
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
