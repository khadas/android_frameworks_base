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

import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.IOnRkAiListener;
import android.content.RkAiData;
import android.content.RKContext;
import android.os.Handler;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IRkAiManagerService;
import android.util.Log;

import java.util.ArrayList;

/**
 * @hide
 */
public class RkAiManager {
    private static final String TAG = "RkAiManager";

    public static final boolean SUPPORT_RKAI_LLM = false;
    public static final boolean SUPPORT_RKAI_ASR = true;
    public static final int RKAI_TYPE_LLM = 1;
    public static final int RKAI_TYPE_ASR = 2;

    public static final String EXTRA_SELECT_TEXT = "select_text";
    public static final String EXTRA_CONTEXT_TEXT = "context_text";
    public static final String EXTRA_ASR_BUFFER = "asr_buffer";
    public static final String EXTRA_ASR_BUFFER_LEN = "asr_buffer_len";

    private Context mContext;
    private final Handler mHandler;
    private IRkAiManagerService mService;

    private final ArrayList<OnRkAiListener> mListeners = new ArrayList<OnRkAiListener>();
    private final IOnRkAiListener.Stub mServiceListener
            = new IOnRkAiListener.Stub() {
        @Override
        public void dispatchRkAiListener(RkAiData data) {
            mHandler.post(() -> {
                reportRkAiMsg(data);
            });
        }
    };

    public interface OnRkAiListener {
        void onRkAiLlmMsg(String selectText, String contextText);
        void onRkAiAsrBuffer(short[] buffer, int len);
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public RkAiManager(Context context) {
        Log.i(TAG, "RkAiManager()");
        mContext = context;
        mHandler = new Handler();

        IBinder iBinder = ServiceManager.getService(RKContext.PLATFORM_AI_MANAGEMENT);
        if(iBinder == null) {
            Log.e(TAG, "Unable to connect to RkAiManager service");
        } else {
            mService = IRkAiManagerService.Stub.asInterface(iBinder);
        }
    }

    /**
     * @hide
     */
    public void addListener(OnRkAiListener listener) {
        synchronized (mListeners) {
            if (listener == null) {
                Log.w(TAG, "add null listener");
                return;
            }
            if (mListeners.isEmpty()) {
                try {
                    mService.addListener(
                            mServiceListener,
                            mContext.getOpPackageName(),
                            mContext.getAttributionTag(),
                            mContext.getUserId(),
                            mContext.getDeviceId());
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
            mListeners.add(listener);
        }
    }

    /**
     * @hide
     */
    public void removeListener(OnRkAiListener listener) {
        synchronized (mListeners) {
            if (listener == null) {
                Log.w(TAG, "remove null listener");
                return;
            }
            mListeners.remove(listener);
            if (mListeners.isEmpty()) {
                try {
                    mService.removeListener(
                            mServiceListener,
                            mContext.getOpPackageName(),
                            mContext.getAttributionTag(),
                            mContext.getUserId(),
                            mContext.getDeviceId());
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    /**
     * @hide
     */
    public void sendRkAiLlmMsg(String selectText, String contextText) {
        Bundle bundle = new Bundle();
        bundle.putString(EXTRA_SELECT_TEXT, selectText);
        bundle.putString(EXTRA_CONTEXT_TEXT, contextText);
        RkAiData data = new RkAiData(RKAI_TYPE_LLM, bundle);
        try {
            mService.sendRkAiMsg(
                    data,
                    mContext.getOpPackageName(),
                    mContext.getAttributionTag(),
                    mContext.getUserId(),
                    mContext.getDeviceId());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * @hide
     */
    public void sendRkAiMsg(RkAiData data) {
        try {
            mService.sendRkAiMsg(
                    data,
                    mContext.getOpPackageName(),
                    mContext.getAttributionTag(),
                    mContext.getUserId(),
                    mContext.getDeviceId());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @UnsupportedAppUsage
    void reportRkAiMsg(RkAiData data) {
        Object[] listeners;

        synchronized (mListeners) {
            final int N = mListeners.size();
            if (N <= 0) {
                return;
            }
            listeners = mListeners.toArray();
        }

        for (int i=0; i < listeners.length; i++) {
            switch (data.getType()) {
                case RKAI_TYPE_LLM: {
                    Bundle info = data.getInfo();
                    if (null != info) {
                        String selectText = info.getString(EXTRA_SELECT_TEXT, "");
                        String contextText = info.getString(EXTRA_CONTEXT_TEXT, "");
                        ((OnRkAiListener)listeners[i]).onRkAiLlmMsg(
                            selectText, contextText);
                    }
                    break;
                }
                case RKAI_TYPE_ASR: {
                    Bundle info = data.getInfo();
                    if (null != info) {
                        short[] buffer = info.getShortArray(EXTRA_ASR_BUFFER);
                        int len = info.getInt(EXTRA_ASR_BUFFER_LEN, 0);
                        ((OnRkAiListener)listeners[i]).onRkAiAsrBuffer(
                            buffer, len);
                    }
                    break;
                }
                default:
                    Log.e(TAG, "unknown info " + data);
                    break;
            }
        }
    }

}
