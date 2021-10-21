/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.hdmi;


import android.hardware.hdmi.HdmiDeviceInfo;
import android.os.UEventObserver;
import android.os.FileUtils;
import android.os.SystemProperties;

import android.util.Slog;

import java.io.File;

public abstract class EarcObserver extends UEventObserver {
    private static final String TAG = "EarcObserver";

    private static final String PROP_SUPPORT_EARC = "ro.vendor.media.support_earc";

    public static final String EARC_TX_NONE_STATE = "0";//"EARCTX-ARC=0EARCTX-EARC=0";
    public static final String ARC_TX_ENABLE_STATE = "1";//"EARCTX-ARC=1EARCTX-EARC=0";
    public static final String EARC_TX_ENABLE_STATE = "2";//"EARCTX-ARC=0EARCTX-EARC=1";

    public static final String EARC_RX_ENABLE_STATE = "EARCRX-ARC=0EARCRX-EARC=1";
    public static final String ARC_RX_ENABLE_STATE = "EARCRX-ARC=1EARCRX-EARC=0";

    public static final String PATH_EARC_UEVENT = "/devices/platform/soc/";

    private static final String KEY_STATE = "EARCTX_ARC_STATE";

    public static final int EARC_ARC_UNCONNECT_TYPE = 0;
    public static final int ARC_CONNECT_TYPE = 1;
    public static final int EARC_CONNECT_TYPE = 2;

    private static final boolean SUPPORT_EARC = SystemProperties.getBoolean(PROP_SUPPORT_EARC, false);

    private int mCurrentConnectType;
    private HdmiControlService mService;

    public EarcObserver(HdmiControlService service) {
        mService = service;
        startObserving(getEvent());
    }

    @Override
    public void onUEvent(UEvent event) {
        Slog.d(TAG, "onUEvent =" + event);
        String state = event.get(KEY_STATE);
        Slog.d(TAG, "onUEvent state=" + state);

        if (null == state) {
            return;
        }

        int connectType = getCurrentConnectType(state.replaceAll("\n", ""));
        Slog.d(TAG, "onUEvent new=" + connectType + " current=" + mCurrentConnectType);
        if (mCurrentConnectType == connectType) {
            Slog.d(TAG, "connect type no change");
            return;
        }
        mCurrentConnectType = connectType;
        mService.onEarcStateChanged(isEarcOn());
    }

    public static EarcObserver ceateEarcObserver(HdmiControlService service, int deviceType) {
        switch (deviceType) {
            case HdmiDeviceInfo.DEVICE_TV:
                return new TvEarcObserver(service);
            case HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM:
                return new AudioSystemEarcObserver(service);
            default:
                Slog.e(TAG, "ceateEarcObserver should never be here");
                return null;
        }
    }

    public static boolean isEarcSupport() {
        return SUPPORT_EARC;
    }

    public boolean isEarcOn() {
        return mCurrentConnectType == EARC_CONNECT_TYPE;
    }

    protected abstract int getCurrentConnectType(String currentConnectState);

    protected abstract String getEvent();
}

