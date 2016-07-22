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

/**
 * Base feature action class for &lt;Request ARC Initiation&gt;/&lt;Request ARC Termination&gt;.
 */
class ShortAudioDescriptorAction extends HdmiCecFeatureAction {
    private static final String TAG = "ShortAudioDescriptorAction";

    // according CEA-861-D
    private static final int AUDIO_FORMAT_RESERVED = 0;
    private static final int AUDIO_FORMAT_LINEAR_PCM = 1;
    private static final int AUDIO_FORMAT_AC_3 = 2;
    private static final int AUDIO_FORMAT_MPEG1 = 3;
    private static final int AUDIO_FORMAT_MP3 = 4;
    private static final int AUDIO_FORMAT_MPEG2 = 5;
    private static final int AUDIO_FORMAT_AAC = 6;
    private static final int AUDIO_FORMAT_DTS = 7;
    private static final int AUDIO_FORMAT_ATRAC = 8;
    private static final int AUDIO_FORMAT_ONE_BIT_AUDIO = 9;
    private static final int AUDIO_FORMAT_DOLBY_DIGITAL_PLUS = 10;
    private static final int AUDIO_FORMAT_DTS_HD = 11;
    private static final int AUDIO_FORMAT_MAT = 12;
    private static final int AUDIO_FORMAT_DST = 13;
    private static final int AUDIO_FORMAT_WMA_PRO = 14;
    private static final int AUDIO_FORMAT_RESERVED1 = 15;

    // State in which waits for ARC response.
    private static final int STATE_WAITING_REPORT = 1;

    private int mFormatStart;

    // Logical address of AV Receiver.
    private final int mDevAddr;
    private int mState = 0;

    private String mAudioDataBlock;

    /**
     * @Constructor
     *
     * @param source {@link HdmiCecLocalDevice} instance
     * @param devAddr address of AV receiver. It should be AUDIO_SYSTEM type
     * @throw IllegalArugmentException if device type of sourceAddress and devAddr
     *                      is invalid
     */
    ShortAudioDescriptorAction(HdmiCecLocalDevice source, int devAddr) {
        super(source);
        HdmiUtils.verifyAddressType(getSourceAddress(), HdmiDeviceInfo.DEVICE_TV);
        HdmiUtils.verifyAddressType(devAddr, HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);
        mDevAddr = devAddr;
        Slog.d(TAG, "devAddr:" + devAddr);
    }

    @Override
    boolean start() {
        mState = STATE_WAITING_REPORT;
        mFormatStart = AUDIO_FORMAT_LINEAR_PCM;
        mAudioDataBlock = "";
        SendAudioDescriptorQueryMsg();
        return true;
    }

    private boolean SendAudioDescriptorQueryMsg() {
        byte[] params;
        if (mFormatStart < AUDIO_FORMAT_RESERVED1) {
            params = new byte[] {(byte)((mFormatStart) & 0xFF)};
            mFormatStart += 1;
        } else {
            exitAction();
            return true;
        }

        mActionTimer.clearTimerMessage();
        mState = STATE_WAITING_REPORT;
        addTimer(mState, HdmiConfig.TIMEOUT_MS);

        HdmiCecMessage command = HdmiCecMessageBuilder.
                                    buildRequestShortAudioDescriptor(getSourceAddress(),
                                        mDevAddr, params);
        sendCommand(command, new HdmiControlService.SendMessageCallback() {
            @Override
            public void onSendCompleted(int error) {
                if (error != Constants.SEND_RESULT_SUCCESS) {
                    Slog.e(TAG, "Failed to send RequestShortAudioDescriptor, error=" + error);
                    finish();
                }
            }
        });
        return true;
    }

    private void exitAction() {
        tv().setArcAudioDescriptor(mAudioDataBlock);
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        if (mState != STATE_WAITING_REPORT) {
            return false;
        }
        int opcode = cmd.getOpcode();
        int nextCode = 0;
        switch (opcode) {
            case Constants.MESSAGE_FEATURE_ABORT:
                int originalOpcode = cmd.getParams()[0] & 0xFF;;
                if (originalOpcode == Constants.MESSAGE_REQUEST_SHORT_AUDIO_DESCRIPTOR) {
                    Slog.d(TAG, "Feature aborted for <ShortAudioDescriptorAction>");
                    return SendAudioDescriptorQueryMsg();
                }
                break;
            case Constants.MESSAGE_REPORT_SHORT_AUDIO_DESCRIPTOR:
                byte params[] = cmd.getParams();
                for (byte param : params) {
                    mAudioDataBlock += "" + String.format("%02x", param & 0xFF);
                }
                Slog.d(TAG, "mAudioDataBlock = " + mAudioDataBlock);
                return SendAudioDescriptorQueryMsg();
        }
        return false;
    }

    @Override
    final void handleTimerEvent(int state) {
        if (mState != state || mState != STATE_WAITING_REPORT) {
            return;
        }
        Slog.d(TAG, "end query");
        finish();
    }
}
