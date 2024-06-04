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

package com.android.server.ebook;

import android.content.Context;
import android.os.Handler;
import android.os.EbookManager;
import android.os.IBinder;
import android.os.IEbookManager;
import android.os.Message;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;

public class EbookService extends IEbookManager.Stub {
    private static final String TAG = "EbookService";
    private static final int SYSTEM_PROPERTY_MAX_LENGTH = 92;

    private static Handler mPolicyHandler;
    public static IBinder  mBinder;
    private Context mContext;
    private Listener binderListener;

    private class PolicyHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
        }
    }
    private final class Listener implements IBinder.DeathRecipient {

        public void binderDied() {
            mBinder.unlinkToDeath(binderListener, 0);
            binderListener = null;
        }
    }

    public static IBinder getBinder() {
        return mBinder;
    }

    /*-------------------------------------------------------*/

    public EbookService(Context context) {
        Log.i(TAG, "EbookService() : EbookService starting!.");
        mContext = context;
        mPolicyHandler = new PolicyHandler();
        return;
    }

    //from frameworks/base/services/core/java/com/android/server/am/SettingsToPropertiesMapper.java
    public void setProperty(String key, String value) {
        // Check if need to clear the property
        if (value == null) {
            // It's impossible to remove system property, therefore we check previous value to
            // avoid setting an empty string if the property wasn't set.
            if (TextUtils.isEmpty(SystemProperties.get(key))) {
                return;
            }
            value = "";
        } else if (value.length() > SYSTEM_PROPERTY_MAX_LENGTH) {
            Log.e(TAG, value + " exceeds system property max length.");
            return;
        }

        try {
            SystemProperties.set(key, value);
        } catch (Exception e) {
            // Failure to set a property can be caused by SELinux denial. This usually indicates
            // that the property wasn't allowlisted in sepolicy.
            // No need to report it on all user devices, only on debug builds.
            Log.e(TAG, "Unable to set property " + key + " value '" + value + "'");
        }
    }

    public int init() {
        init_native();
        return 0;
    }


    /*jni interface*/
    public native int init_native();
}
