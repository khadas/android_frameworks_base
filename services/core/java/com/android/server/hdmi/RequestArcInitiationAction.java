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
import android.hardware.hdmi.IHdmiControlCallback;
import android.hardware.tv.cec.V1_0.SendMessageResult;

/**
 * Feature action that handles ARC action initiated by TV devices.
 *
 * <p>This action is created by TV's hot plug event of ARC port.
 */
final class RequestArcInitiationAction extends RequestArcAction {
    private static final String TAG = "RequestArcInitiationAction";

    private static final int MAX_SEND_RETRY_COUNT = 2;
    private int mSendRetryCount = 0;
    /**
     * @Constructor
     *
     * For more details look at {@link RequestArcAction#RequestArcAction}.
     */
    RequestArcInitiationAction(HdmiCecLocalDevice source, int avrAddress) {
        super(source, avrAddress);
    }

    /**
     * @Constructor
     *
     * For more details look at {@link RequestArcAction#RequestArcAction}.
     */
    RequestArcInitiationAction(HdmiCecLocalDevice source, int avrAddress,
            IHdmiControlCallback callback) {
        super(source, avrAddress, callback);
    }

    @Override
    boolean start() {
        // Seq #38
        mState = STATE_WAITING_FOR_REQUEST_ARC_REQUEST_RESPONSE;
        addTimer(mState, HdmiConfig.TIMEOUT_MS);

        sendArcInitiation();
        return true;
    }

    private void sendArcInitiation() {
        HdmiCecMessage command = HdmiCecMessageBuilder.buildRequestArcInitiation(
                getSourceAddress(), mAvrAddress);
        sendCommand(command, new HdmiControlService.SendMessageCallback() {
            @Override
            public void onSendCompleted(int error) {
                if (error != SendMessageResult.SUCCESS) {
                    if (mSendRetryCount++ >= MAX_SEND_RETRY_COUNT) {
                        HdmiLogger.debug("Failed to send <Request Arc Initiation>:" + error);
                        tv().disableArc();
                        finish();
                        return;
                    }
                    HdmiLogger.debug("send <Request Arc Initiation> retry:" + mSendRetryCount);
                    sendArcInitiation();
                }
            }
        });
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        if (mState != STATE_WAITING_FOR_REQUEST_ARC_REQUEST_RESPONSE
                || !HdmiUtils.checkCommandSource(cmd, mAvrAddress, TAG)) {
            return false;
        }
        int opcode = cmd.getOpcode();
        switch (opcode) {
            case Constants.MESSAGE_FEATURE_ABORT:
                int originalOpcode = cmd.getParams()[0] & 0xFF;
                if (originalOpcode == Constants.MESSAGE_REQUEST_ARC_INITIATION) {
                    HdmiLogger.warning("Receive abort for REQUEST_ARC_INITIATION");
                    // For avrs which support both earc and arc, it may respond <Feature Abort> in
                    // cases where it is in earc mode and where it is switching its arc status.
                    // Don't disableArc in here.
                    // tv().disableArc();
                    finishWithCallback(HdmiControlManager.RESULT_TARGET_NOT_AVAILABLE);
                    return true;
                }
                return false;
            case Constants.MESSAGE_INITIATE_ARC:
                finishWithCallback(HdmiControlManager.RESULT_SUCCESS);
                // This message still needs to be handled in HdmiCecLocalDeviceTv as well.
                return false;
        }
        return false;
    }

    @Override
    final void handleTimerEvent(int state) {
        if (mState != state || state != STATE_WAITING_FOR_REQUEST_ARC_REQUEST_RESPONSE) {
            return;
        }
        HdmiLogger.debug("[T] RequestArcInitiationAction.");
        if (mSendRetryCount++ >= MAX_SEND_RETRY_COUNT) {
            HdmiLogger.debug("No response for <Request Arc Initiation>");
            finishWithCallback(HdmiControlManager.RESULT_TIMEOUT);
            return;
        }
        addTimer(mState, HdmiConfig.TIMEOUT_MS);
        sendArcInitiation();
    }
}
