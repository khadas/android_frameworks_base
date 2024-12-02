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

package com.android.server;

import static android.content.Context.DEVICE_ID_INVALID;

import android.content.Context;
import android.content.IOnRkAiListener;
import android.content.RkAiData;
import android.os.Bundle;
import android.os.RkAiManager;
import android.os.IRkAiManagerService;
import android.os.RemoteCallbackList;
import android.util.Log;

import java.util.ArrayList;

/**
 * @hide
 */
public class RkAiManagerService extends IRkAiManagerService.Stub {
    private static final String TAG = "RkAiManagerService";

    private static native int nativeInit(boolean supportAsr);

    private Context mContext;
    private static final Object mLock = new Object();

    private static final RemoteCallbackList<IOnRkAiListener> mListeners
                = new RemoteCallbackList<IOnRkAiListener>();

    public RkAiManagerService(Context context) {
        Log.i(TAG, "RkAiManagerService()");
        mContext = context;

        nativeInit(RkAiManager.SUPPORT_RKAI_ASR);
    }

    private static void asrBufferFromNative(final short[] buffer, final int len) {
        Bundle bundle = new Bundle();
        bundle.putShortArray(RkAiManager.EXTRA_ASR_BUFFER, buffer);
        bundle.putInt(RkAiManager.EXTRA_ASR_BUFFER_LEN, len);
        sendRkAiMsg(new RkAiData(RkAiManager.RKAI_TYPE_ASR, bundle));
    }

    @Override
    public void addListener(
            IOnRkAiListener listener,
            String callingPackage,
            String attributionTag,
            int userId,
            int deviceId) {
        synchronized (mLock) {
            Log.w(TAG, callingPackage + " addListener");
            if (listener == null) {
                Log.w(TAG, "add null listener");
                return;
            }
            mListeners.register(listener);
        }
    }

    @Override
    public void removeListener(
            IOnRkAiListener listener,
            String callingPackage,
            String attributionTag,
            int userId,
            int deviceId) {
        synchronized (mLock) {
            Log.w(TAG, callingPackage + " removeListener");
            if (listener == null) {
                Log.w(TAG, "remove null listener");
                return;
            }
            mListeners.unregister(listener);
        }
    }

    @Override
    public void sendRkAiMsg(
            RkAiData data,
            String callingPackage,
            String attributionTag,
            int userId,
            int deviceId) {
        Log.v(TAG, callingPackage + " sendRkAiMsg " + data);
        sendRkAiMsg(data);
    }

    private static void sendRkAiMsg(RkAiData data) {
        if (data == null) {
            Log.e(TAG, "sendRkAiMsg data is NULL");
            return;
        }

        final int num = mListeners.beginBroadcast();
        try {
            for (int i=0; i < num; i++) {
                try {
                    mListeners.getBroadcastItem(i).dispatchRkAiListener(data);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } finally {
            mListeners.finishBroadcast();
        }
    }

}
