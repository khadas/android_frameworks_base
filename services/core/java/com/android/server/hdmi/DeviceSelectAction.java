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

import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiTvClient;
import android.hardware.hdmi.IHdmiControlCallback;
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.os.RemoteException;
import android.util.Slog;
import com.android.server.hdmi.HdmiControlService.SendMessageCallback;

/**
 * Handles an action that selects a logical device as a new active source.
 *
 * Triggered by {@link HdmiTvClient}, attempts to select the given target device
 * for a new active source. It does its best to wake up the target in standby mode
 * before issuing the command &gt;Set Stream path&lt;.
 */
final class DeviceSelectAction extends HdmiCecFeatureAction {
    private static final String TAG = "DeviceSelect";


    // The number of times we try to wake up the target device before we give up
    // and just send <Set Stream Path>.
    private static final int LOOP_COUNTER_MAX = 2;

    // State in which we wait for <Report Power Status> to come in response to the command
    // <Give Device Power Status> we have sent.
    private static final int STATE_WAIT_FOR_REPORT_POWER_STATUS = 1;

    private final HdmiDeviceInfo mTarget;
    private final IHdmiControlCallback mCallback;
    private final HdmiCecMessage mGivePowerStatus;

    private int mPowerStatusCounter = 0;

    /**
     * Constructor.
     *
     * @param source {@link HdmiCecLocalDevice} instance
     * @param target target logical device that will be a new active source
     * @param callback callback object
     */
    public DeviceSelectAction(HdmiCecLocalDeviceTv source,
            HdmiDeviceInfo target, IHdmiControlCallback callback) {
        super(source);
        mCallback = callback;
        mTarget = target;
        mGivePowerStatus = HdmiCecMessageBuilder.buildGiveDevicePowerStatus(
                getSourceAddress(), getTargetAddress());
    }

    int getTargetAddress() {
        return mTarget.getLogicalAddress();
    }

    @Override
    public boolean start() {
        HdmiLogger.debug("device select start " + mTarget);
        // Directly send the routing messages to make sure the source switch
        // could be finished asap.
        sendSetStreamPath();
        // We should send as few as cec messsages in a cec action to shorten
        // the time of a feature process and to lighten the pressure on cec line.
        if (!HdmiUtils.isPowerOnOrTransient(mTarget.getDevicePowerStatus())) {
            // Just send a turn on message if the device's power status is not on.
            // Sending power query messages and so on is much too protracted.
            turnOnDevice();
        }
        invokeCallback(HdmiControlManager.RESULT_SUCCESS);
        finish();
        return true;
    }

    @Override
    public boolean processCommand(HdmiCecMessage cmd) {
        return false;
    }

    private void turnOnDevice() {
        HdmiLogger.debug("turnOnDevice");
        sendUserControlPressedAndReleased(mTarget.getLogicalAddress(),
                HdmiCecKeycode.CEC_KEYCODE_POWER_ON_FUNCTION);
    }

    private void sendSetStreamPath() {
        if (mTarget.isSourceType()) {
            sendCommand(HdmiCecMessageBuilder.buildSetStreamPath(
                    getSourceAddress(), mTarget.getPhysicalAddress()));
        } else {
            HdmiLogger.debug("send <Routing Change> for no source device");
            sendCommand(HdmiCecMessageBuilder.buildRoutingChange(getSourceAddress(),
                    localDevice().getActivePath(), mTarget.getPhysicalAddress()));
        }
    }

    @Override
    public void handleTimerEvent(int timeoutState) {
        if (mState != timeoutState) {
            Slog.w(TAG, "Timer in a wrong state. Ignored.");
            return;
        }
    }

    private void invokeCallback(int result) {
        if (mCallback == null) {
            return;
        }
        try {
            mCallback.onComplete(result);
        } catch (RemoteException e) {
            Slog.e(TAG, "Callback failed:" + e);
        }
    }
}
