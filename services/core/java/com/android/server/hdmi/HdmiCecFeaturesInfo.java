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
import android.util.Slog;


import java.util.List;


/**
 * All information of deivce's features which is used for CEC2.0 or later version.
 * It's used by a device to broadcast its features through message <Report Features>.
 * Features is a combination of its CEC version, the collection of Device Types in the device,
 * several other characteristics(operands [RC Profile]) and [Device Features].
 */
public class HdmiCecFeaturesInfo {
    private static final String TAG = "HdmiCecFeaturesInfo";

    private final HdmiControlService mService;
    private int mCecVersion;
    private int mAllDeviceTypes = 0;

    //default value is 4 for cec2.0
    private int mFeaturesSize;

    private int mRCProfileSize;
    private int mDeviceFeaturesSize;

    private byte[] mRcProfiles;
    private byte[] mDeviceFeatures;

    public HdmiCecFeaturesInfo(HdmiControlService service, int version, List<Integer> localDevices) {
        mService = service;
        mCecVersion = version;
        initAllDeviceTypes(localDevices);
        initOthers();
    }

    private void initOthers() {
        initFeaturesSize();
        initRCProfiles();
        initDeviceFeatures();
        Slog.d(TAG, "" + this);
    }

    private void initAllDeviceTypes(List<Integer> localDevices) {
        for (int type : localDevices) {
            switch (type) {
                case HdmiDeviceInfo.DEVICE_PURE_CEC_SWITCH:
                    mAllDeviceTypes |= 1 << 2;
                    break;
                case HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM:
                    mAllDeviceTypes |= 1 << 3;
                    break;
                case HdmiDeviceInfo.DEVICE_PLAYBACK:
                    mAllDeviceTypes |= 1 << 4;
                    break;
                case HdmiDeviceInfo.DEVICE_TUNER:
                    mAllDeviceTypes |= 1 << 5;
                    break;
                case HdmiDeviceInfo.DEVICE_RECORDER:
                    mAllDeviceTypes |= 1 << 6;
                    break;
                case HdmiDeviceInfo.DEVICE_TV:
                    mAllDeviceTypes |= 1 << 7;
                    break;
                default:
                    break;
            }
        }
    }

    private void initRCProfiles() {
        mRcProfiles = new byte[mRCProfileSize];
    }

    private void initDeviceFeatures() {
        mDeviceFeatures = new byte[mDeviceFeaturesSize];
        switch (mCecVersion) {
            case Constants.CEC_VERSION_2_0:
                mDeviceFeatures[0] = 0;
                if (isTvDevice()) {
                    mDeviceFeatures[0] |= 1 << 6;//record tv screen
                    mDeviceFeatures[0] |= 1 << 5;//set osd string
                    mDeviceFeatures[0] |= 1 << 2;//support arc
                }
                break;
            default:
                break;
        }
    }

    private void initFeaturesSize() {
        switch (mCecVersion) {
            case Constants.CEC_VERSION_2_0:
                mFeaturesSize = 4;
                mRCProfileSize = 1;
                mDeviceFeaturesSize = 1;
                break;
            default:
                break;
        }
    }

    private boolean isTvDevice() {
        return ((mAllDeviceTypes >> 7) & 1) == 1;
    }

    public byte[] getCecFeatures() {
        byte[] params = new byte[mFeaturesSize];
        switch (mCecVersion) {
            case Constants.CEC_VERSION_2_0:
                params[0] = (byte) (mCecVersion & 0xFF);
                params[1] = (byte) (mAllDeviceTypes & 0xFF);
                params[2] = (byte) (mRcProfiles[0] & 0xFF);
                params[3] = (byte) (mDeviceFeatures[0] & 0xFF);
                break;
            default:
                break;
        }
        return params;
    }

    public int getCecVersion() {
        return mCecVersion;
    }

    public int getAllDeviceTypes() {
        return mAllDeviceTypes;
    }

    public boolean canRecordTvScreen() {
        return (1 & (mDeviceFeatures[0] >> 6)) == 1;
    }

    public boolean canSetOsdString() {
        return (1 & (mDeviceFeatures[0] >> 5)) == 1;
    }

    public boolean canControlledByDeckControl() {
        return (1 & (mDeviceFeatures[0] >> 4)) == 1;
    }

    public boolean canSetAudioRate() {
        return (1 & (mDeviceFeatures[0] >> 3)) == 1;
    }

    public boolean canSupportARC() {
        return (1 & (mDeviceFeatures[0] >> 2)) == 1;
    }

    @Override
    public String toString() {
        StringBuffer s = new StringBuffer();
        s.append("Features: ");
        s.append("cec_version: ").append(String.format("0x%02X", mCecVersion));
        s.append(", ");
        s.append("all_device_types: ").append(String.format("0x%02X", mAllDeviceTypes));
        s.append(", ");
        s.append("rc_profiles:");
        for (byte data : mRcProfiles) {
            s.append(String.format(" 0x%02X", data));
        }
        s.append(", ");
        s.append("device_features:");
        for (byte data : mDeviceFeatures) {
            s.append(String.format(" 0x%02X", data));
        }
        return s.toString();
    }


}

