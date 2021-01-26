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

import android.annotation.Nullable;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.IHdmiControlCallback;
import android.os.RemoteException;
import android.util.Slog;

import static android.hardware.hdmi.HdmiControlManager.RESULT_SUCCESS;

import com.android.server.hdmi.HdmiControlService.SendMessageCallback;

/**
 * Feature action for routing control. Exchanges routing-related commands with other devices
 * to determine the new active source.
 *
 * <p>This action is initiated by various cases:
 * <ul>
 * <li> Manual TV input switching
 * <li> Routing change of a CEC switch other than TV
 * <li> New CEC device at the tail of the active routing path
 * <li> Removed CEC device from the active routing path
 * <li> Routing at CEC enable time
 * </ul>
 */
final class RoutingControlAction extends HdmiCecFeatureAction {
    private static final String TAG = "RoutingControlAction";

    // State in which we wait for <Routing Information> to arrive. If timed out, we use the
    // latest routing path to set the new active source.
    private static final int STATE_WAIT_FOR_ROUTING_INFORMATION = 1;

    // true if <Give Power Status> should be sent once the new active routing path is determined.
    private final boolean mQueryDevicePowerStatus;

    // If set to true, call {@link HdmiControlService#invokeInputChangeListener()} when
    // the routing control/active source change happens. The listener should be called if
    // the events are triggered by external events such as manual switch port change or incoming
    // <Inactive Source> command.
    private final boolean mNotifyInputChange;

    @Nullable private final IHdmiControlCallback mCallback;

    // The latest routing path. Updated by each <Routing Information> from CEC switches.
    private int mCurrentRoutingPath;
    private int mOldPath;

    RoutingControlAction(HdmiCecLocalDevice localDevice, int oldPath, int newPath, boolean queryDevicePowerStatus,
            IHdmiControlCallback callback) {
        super(localDevice);
        mCallback = callback;
        mOldPath = oldPath;
        mCurrentRoutingPath = newPath;
        mQueryDevicePowerStatus = queryDevicePowerStatus;
        // Callback is non-null when routing control action is brought up by binder API. Use
        // this as an indicator for the input change notification. These API calls will get
        // the result through this callback, not through notification. Any other events that
        // trigger the routing control is external, for which notifcation is used.
        mNotifyInputChange = (callback == null);
    }

    @Override
    public boolean start() {
        mState = STATE_WAIT_FOR_ROUTING_INFORMATION;
        HdmiDeviceInfo targetDevice = tv().getDeviceInfoByPath(mCurrentRoutingPath);
        if (targetDevice != null) {
            // We should try to make sure RoutingControlAction is called when the DiscoveryAction
            // has been finished. Or else it's difficult to work with only knowledge of routing path.
            if (!HdmiUtils.isPowerOnOrTransient(targetDevice.getDevicePowerStatus())) {
                // Just send a turn on message if the device's power status is not on.
                // Sending power query messages and so on is much too protracted, and might
                // introduce unexpected power control issues.
                turnOnDevice(targetDevice.getLogicalAddress());
            }
            if (targetDevice.isSourceType()) {
                // If there is a source device, just send another routing control message
                // to make sure it 100 percent responds.
                sendSetStreamPath();
                finishWithCallback(HdmiControlManager.RESULT_SUCCESS);
                return true;
            } else {
                sendRoutingChange();
                addTimer(STATE_WAIT_FOR_ROUTING_INFORMATION, HdmiConfig.TIMEOUT_MS);
            }
        } else {
                sendRoutingChange();
        }
        return true;
    }

    @Override
    public boolean processCommand(HdmiCecMessage cmd) {
        int opcode = cmd.getOpcode();
        byte[] params = cmd.getParams();
        // There is an audio system on this port.
        if (mState == STATE_WAIT_FOR_ROUTING_INFORMATION
                && opcode == Constants.MESSAGE_ROUTING_INFORMATION) {
            // Keep updating the physicalAddress as we receive <Routing Information>.
            // If the routing path doesn't belong to the currently active one, we should
            // ignore it since it might have come from other routing change sequence.
            int routingPath = HdmiUtils.twoBytesToInt(params);
            if (!HdmiUtils.isInActiveRoutingPath(mCurrentRoutingPath, routingPath)) {
                HdmiLogger.error("routing path is not in accord " + routingPath);
                return true;
            }
            mCurrentRoutingPath = routingPath;
            sendSetStreamPath();
            HdmiDeviceInfo targetDevice = tv().getDeviceInfoByPath(mCurrentRoutingPath);
            if (targetDevice != null
                && !HdmiUtils.isPowerOnOrTransient(targetDevice.getDevicePowerStatus())) {
                turnOnDevice(targetDevice.getLogicalAddress());
            }
            finishWithCallback(HdmiControlManager.RESULT_SUCCESS);
            return true;
        }
        return false;
    }

    private void turnOnDevice(int logicalAddress) {
        HdmiLogger.debug("turnOnDevice " + logicalAddress);
        sendUserControlPressedAndReleased(logicalAddress,
                HdmiCecKeycode.CEC_KEYCODE_POWER_ON_FUNCTION);
    }

    private void sendSetStreamPath() {
        sendCommand(HdmiCecMessageBuilder.buildSetStreamPath(getSourceAddress(),
                mCurrentRoutingPath));
    }

    private void sendRoutingChange() {
        sendCommand(HdmiCecMessageBuilder.buildRoutingChange(getSourceAddress(),
                mOldPath, mCurrentRoutingPath));
    }

    private void finishWithCallback(int result) {
        invokeCallback(result);
        finish();
    }

    @Override
    public void handleTimerEvent(int timeoutState) {
        if (mState != timeoutState || mState == STATE_NONE) {
            Slog.w("CEC", "Timer in a wrong state. Ignored.");
            return;
        }
        switch (timeoutState) {
            case STATE_WAIT_FOR_ROUTING_INFORMATION:
                HdmiLogger.info("No ROUTING_INFORMATION received, just finish");
                finishWithCallback(HdmiControlManager.RESULT_SUCCESS);
                break;
        }
    }

    private void invokeCallback(int result) {
        if (mCallback == null) {
            return;
        }
        try {
            mCallback.onComplete(result);
        } catch (RemoteException e) {
            // Do nothing.
        }
    }
}
