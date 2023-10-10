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

import static android.hardware.hdmi.DeviceFeatures.FEATURE_NOT_SUPPORTED;
import static android.hardware.hdmi.DeviceFeatures.FEATURE_SUPPORTED;
import static android.hardware.hdmi.HdmiControlManager.CLEAR_TIMER_STATUS_CEC_DISABLE;
import static android.hardware.hdmi.HdmiControlManager.CLEAR_TIMER_STATUS_CHECK_RECORDER_CONNECTION;
import static android.hardware.hdmi.HdmiControlManager.CLEAR_TIMER_STATUS_FAIL_TO_CLEAR_SELECTED_SOURCE;
import static android.hardware.hdmi.HdmiControlManager.ONE_TOUCH_RECORD_CEC_DISABLED;
import static android.hardware.hdmi.HdmiControlManager.ONE_TOUCH_RECORD_CHECK_RECORDER_CONNECTION;
import static android.hardware.hdmi.HdmiControlManager.ONE_TOUCH_RECORD_FAIL_TO_RECORD_DISPLAYED_SCREEN;
import static android.hardware.hdmi.HdmiControlManager.OSD_MESSAGE_ARC_CONNECTED_INVALID_PORT;
import static android.hardware.hdmi.HdmiControlManager.TIMER_RECORDING_RESULT_EXTRA_CEC_DISABLED;
import static android.hardware.hdmi.HdmiControlManager.TIMER_RECORDING_RESULT_EXTRA_CHECK_RECORDER_CONNECTION;
import static android.hardware.hdmi.HdmiControlManager.TIMER_RECORDING_RESULT_EXTRA_FAIL_TO_RECORD_SELECTED_SOURCE;
import static android.hardware.hdmi.HdmiControlManager.TIMER_RECORDING_TYPE_ANALOGUE;
import static android.hardware.hdmi.HdmiControlManager.TIMER_RECORDING_TYPE_DIGITAL;
import static android.hardware.hdmi.HdmiControlManager.TIMER_RECORDING_TYPE_EXTERNAL;

import android.annotation.Nullable;
import android.hardware.hdmi.DeviceFeatures;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiPortInfo;
import android.hardware.hdmi.HdmiRecordSources;
import android.hardware.hdmi.HdmiTimerRecordSources;
import android.hardware.hdmi.IHdmiControlCallback;
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.media.AudioDescriptor;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioProfile;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager.TvInputCallback;
import android.util.Slog;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.hdmi.DeviceDiscoveryAction.DeviceDiscoveryCallback;
import com.android.server.hdmi.HdmiAnnotations.ServiceThreadOnly;
import com.android.server.hdmi.HdmiControlService.SendMessageCallback;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Represent a logical device of type TV residing in Android system.
 */
public final class HdmiCecLocalDeviceTv extends HdmiCecLocalDevice {
    private static final String TAG = "HdmiCecLocalDeviceTv";

    // Whether ARC is available or not. "true" means that ARC is established between TV and
    // AVR as audio receiver.
    @ServiceThreadOnly
    private boolean mArcEstablished = false;

    // Stores whether ARC feature is enabled per port.
    // True by default for all the ARC-enabled ports.
    private final SparseBooleanArray mArcFeatureEnabled = new SparseBooleanArray();

    // Whether the System Audio Control feature is enabled or not. True by default.
    @GuardedBy("mLock")
    private boolean mSystemAudioControlFeatureEnabled;

    // The previous port id (input) before switching to the new one. This is remembered in order to
    // be able to switch to it upon receiving <Inactive Source> from currently active source.
    // This remains valid only when the active source was switched via one touch play operation
    // (either by TV or source device). Manual port switching invalidates this value to
    // Constants.PORT_INVALID, for which case <Inactive Source> does not do anything.
    @GuardedBy("mLock")
    private int mPrevPortId;

    @GuardedBy("mLock")
    private int mSystemAudioVolume = Constants.UNKNOWN_VOLUME;

    @GuardedBy("mLock")
    private boolean mSystemAudioMute = false;

    // If true, do not do routing control/send active source for internal source.
    // Set to true when the device was woken up by <Text/Image View On>.
    private boolean mSkipRoutingControl;

    // Message buffer used to buffer selected messages to process later. <Active Source>
    // from a source device, for instance, needs to be buffered if the device is not
    // discovered yet. The buffered commands are taken out and when they are ready to
    // handle.
    private final DelayedMessageBuffer mDelayedMessageBuffer = new DelayedMessageBuffer(this);

    // Defines the callback invoked when TV input framework is updated with input status.
    // We are interested in the notification for HDMI input addition event, in order to
    // process any CEC commands that arrived before the input is added.
    private final TvInputCallback mTvInputCallback = new TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            TvInputInfo tvInfo = mService.getTvInputManager().getTvInputInfo(inputId);
            if (tvInfo == null) return;
            HdmiDeviceInfo info = tvInfo.getHdmiDeviceInfo();
            if (info == null) return;
            addTvInput(inputId, info.getId());
            if (info.isCecDevice()) {
                processDelayedActiveSource(info.getLogicalAddress());
            }
        }

        @Override
        public void onInputRemoved(String inputId) {
            removeTvInput(inputId);
        }
    };

    // Keeps the mapping (TV input ID, HDMI device ID) to keep track of the TV inputs ready to
    // accept input switching request from HDMI devices. Requests for which the corresponding
    // input ID is not yet registered by TV input framework need to be buffered for delayed
    // processing.
    private final HashMap<String, Integer> mTvInputs = new HashMap<>();

    // Whether TV has  has finished DeviceDiscoveryAction. We should try to device select
    // or port select when the specified device has been added to list.
    private boolean mDeviceDiscoveryFinished;

    boolean isDeviceDiscoveryFinished() {
        return mDeviceDiscoveryFinished;
    }

    @ServiceThreadOnly
    void processDelayedActiveSource() {
        assertRunOnServiceThread();
        mDelayedMessageBuffer.processActiveSource();
    }

    HdmiDeviceInfo getCecDeviceInfo(int logicalAddress) {
        return mService.getHdmiCecNetwork().getCecDeviceInfo(logicalAddress);
    }


    @ServiceThreadOnly
    boolean isArcFeatureEnabled(HdmiDeviceInfo avr) {
        assertRunOnServiceThread();
        if (!isSystemAudioControlFeatureEnabled()) {
            HdmiLogger.debug("isArcFeatureEnabled system audio control disabled.");
            return false;
        }
        if (avr == null) {
            return false;
        }
        boolean isArcPort = mArcFeatureEnabled.get(avr.getPortId());
        if (!isArcPort && Constants.OSD_NAME_HDMI_TEST.equals(avr.getDisplayName())) {
            return avr.getPortId() == Constants.PORT_ARC_HDMI_TEST;
        }
        if (!isArcPort && Constants.OSD_NAME_EARC_TEST.equals(avr.getDisplayName())) {
            return avr.getPortId() == Constants.PORT_ARC_EARC_TEST;
        }
        return isArcPort;
    }

    /**
     * Start routing to the new device if it's on the active path.
     *
     * @param the physical address of the added device
     */
    @ServiceThreadOnly
    void checkNewDeviceOnRoutingPath(int logical, int path) {
        assertRunOnServiceThread();
        int activePath = getActivePath();
        if (path == activePath) {
            // For CTS android.hdmicec.cts.tv.HdmiCecRemoteControlPassThroughTest.
            HdmiDeviceInfo activeDevice = mService.getHdmiCecNetwork().getDeviceInfoByPath(path);
            if (activeDevice != null
                && activeDevice.getLogicalAddress() != logical
                && activeDevice.isSourceType()) {
                HdmiLogger.warning("checkNewDeviceOnRoutingPath but there is " + activeDevice);
                return;
            }

            HdmiLogger.warning("checkNewDeviceOnRoutingPath device 0x%x is on the active path 0x%x",
                path, activePath);
            HdmiCecMessage routingChange =
                    HdmiCecMessageBuilder.buildRoutingChange(
                            getDeviceInfo().getLogicalAddress(), activePath, path);
            mService.sendCecCommand(routingChange);
            removeAction(RoutingControlAction.class);
            addAndStartAction(
                    new RoutingControlAction(this, path, null));
        }
    }

    /**
     * find the source device if it's on the active path.
     *
     * @param current routing path
     */
    @ServiceThreadOnly
    HdmiDeviceInfo findDeviceOnPath(int path) {
        assertRunOnServiceThread();
        if (getAvrDeviceInfo()!= null) {
            // No need to route to the none adjacent device when avr exists.
            return null;
        }
        List<HdmiDeviceInfo> devices = mService.getHdmiCecNetwork().getDeviceInfoList(false);
        for (HdmiDeviceInfo device: devices) {
            if (device.isSourceType() && isTailOfActivePath(device.getPhysicalAddress(), path)) {
                HdmiLogger.warning("findDeviceOnPath device 0x%x is on the active path 0x%x",
                    device.getPhysicalAddress(), path);
                return device;
            }
        }
        return null;
    }

    @ServiceThreadOnly
    private void addTvInput(String inputId, int deviceId) {
        assertRunOnServiceThread();
        mTvInputs.put(inputId, deviceId);
    }

    @ServiceThreadOnly
    private void removeTvInput(String inputId) {
        assertRunOnServiceThread();
        mTvInputs.remove(inputId);
    }

    @Override
    @ServiceThreadOnly
    protected boolean isInputReady(int deviceId) {
        assertRunOnServiceThread();
        return mTvInputs.containsValue(deviceId);
    }

    private SelectRequestBuffer mSelectRequestBuffer;

    HdmiCecLocalDeviceTv(HdmiControlService service) {
        super(service, HdmiDeviceInfo.DEVICE_TV);
        mPrevPortId = Constants.INVALID_PORT_ID;
        mSystemAudioControlFeatureEnabled = service.getHdmiCecConfig().getIntValue(
                HdmiControlManager.CEC_SETTING_NAME_SYSTEM_AUDIO_CONTROL)
                    == HdmiControlManager.SYSTEM_AUDIO_CONTROL_ENABLED;
        mStandbyHandler = new HdmiCecStandbyModeHandler(service, this);
    }

    /**
     * Called after logical address allocation is finished, allowing a local device to react to
     * messages in the buffer before they are processed. This method may be used to cancel deferred
     * actions.
     */
    @Override
    protected void preprocessBufferedMessages(List<HdmiCecMessage> bufferedMessages) {
        for (HdmiCecMessage message: bufferedMessages) {
            // Prevent the device from broadcasting <Active Source> message if the active path
            // changed during address allocation.
            if (message.getOpcode() == Constants.MESSAGE_ACTIVE_SOURCE) {
                HdmiLogger.debug("preprocessBufferedMessages " + message);
                mDelayedMessageBuffer.add(message);
                return;
            }
        }
    }

    @Override
    @ServiceThreadOnly
    protected void onAddressAllocated(int logicalAddress, int reason) {
        assertRunOnServiceThread();
        super.onAddressAllocated(logicalAddress, reason);
        List<HdmiPortInfo> ports = mService.getPortInfo();
        for (HdmiPortInfo port : ports) {
            mArcFeatureEnabled.put(port.getId(), port.isArcSupported());
        }
        mService.registerTvInputCallback(mTvInputCallback);
        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                        getDeviceInfo().getLogicalAddress(),
                        mService.getPhysicalAddress(),
                        mDeviceType));
        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildDeviceVendorIdCommand(
                        getDeviceInfo().getLogicalAddress(), mService.getVendorId()));
        mService.getHdmiCecNetwork().addCecSwitch(
                mService.getHdmiCecNetwork().getPhysicalAddress());  // TV is a CEC switch too.
        mTvInputs.clear();
        mSkipRoutingControl = (reason == HdmiControlService.INITIATED_BY_WAKE_UP_MESSAGE);
        resetSelectRequestBuffer();
        launchDeviceDiscovery();
        startQueuedActions();
        final boolean routingForBootup = reason != HdmiControlService.INITIATED_BY_ENABLE_CEC
                && reason != HdmiControlService.INITIATED_BY_BOOT_UP;
        if (!mDelayedMessageBuffer.isBuffered(Constants.MESSAGE_ACTIVE_SOURCE)
            && (!mService.getSelectRequest().isValid())) {
            addAndStartAction(new RequestActiveSourceAction(this, new IHdmiControlCallback.Stub() {
                @Override
                public void onComplete(int result) {
                    HdmiLogger.debug("RequestActiveSourceAction ends:" + result + " " + mService.getLocalActiveSource());
                    if (result != HdmiControlManager.RESULT_SUCCESS) {
                        launchRoutingControl(routingForBootup);
                    }
                }
            }));
        }
    }

    @ServiceThreadOnly
    public void setSelectRequestBuffer(SelectRequestBuffer requestBuffer) {
        assertRunOnServiceThread();
        mSelectRequestBuffer = requestBuffer;
    }

    @ServiceThreadOnly
    private void resetSelectRequestBuffer() {
        assertRunOnServiceThread();
        setSelectRequestBuffer(SelectRequestBuffer.EMPTY_BUFFER);
    }

    @Override
    protected int getPreferredAddress() {
        return Constants.ADDR_TV;
    }

    @Override
    protected void setPreferredAddress(int addr) {
        Slog.w(TAG, "Preferred addres will not be stored for TV");
    }

    @Override
    @ServiceThreadOnly
    @VisibleForTesting
    @Constants.HandleMessageResult
    protected int dispatchMessage(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (mService.isPowerStandby() && !mService.isWakeUpMessageReceived()
                && mStandbyHandler.handleCommand(message)) {
            return Constants.HANDLED;
        }
        return super.onMessage(message);
    }

    /**
     * Performs the action 'device select', or 'one touch play' initiated by TV.
     *
     * @param id id of HDMI device to select
     * @param callback callback object to report the result with
     */
    @ServiceThreadOnly
    void deviceSelect(int id, IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        HdmiDeviceInfo targetDevice = mService.getHdmiCecNetwork().getDeviceInfo(id);
        if (targetDevice == null) {
            HdmiLogger.error("deviceSelect find no device: " + id);
            invokeCallback(callback, HdmiControlManager.RESULT_TARGET_NOT_AVAILABLE);
            return;
        }
        int targetAddress = targetDevice.getLogicalAddress();
        if (isAlreadyActiveSource(targetDevice, targetAddress, callback)
                // Use active path for the concern of CTS HdmiCecRemoteControlPassThroughTest, which
                // could be interrupted <Active Source> message sent by the simulated device "recorder".
                || (targetDevice.getPhysicalAddress() == getActiveSource().physicalAddress)) {
            HdmiLogger.info("deviceSelect no need as it has been active source:" + targetDevice);
            return;
        }
        removeAction(RequestActiveSourceAction.class);
        if (targetAddress == Constants.ADDR_INTERNAL) {
            handleSelectInternalSource();
            // Switching to internal source is always successful even when CEC control is disabled.
            setActiveSource(targetAddress, mService.getPhysicalAddress(),
                    "HdmiCecLocalDeviceTv#deviceSelect()");
            setActivePath(mService.getPhysicalAddress());
            invokeCallback(callback, HdmiControlManager.RESULT_SUCCESS);
            return;
        }
        if (!mService.isCecControlEnabled()) {
            HdmiLogger.error("deviceSelect cec not enabled!");
            setActiveSource(targetDevice, "HdmiCecLocalDeviceTv#deviceSelect()");
            invokeCallback(callback, HdmiControlManager.RESULT_INCORRECT_MODE);
            return;
        }
        // For CECT 11.1.2-5, <Routing Change> message should be sent even there are source
        // devices discovered in certain test environment.
        HdmiCecMessage routingChange =
                HdmiCecMessageBuilder.buildRoutingChange(
                        getDeviceInfo().getLogicalAddress(),
                        getActivePath(),
                        targetDevice.getPhysicalAddress());
        mService.sendCecCommand(routingChange);

        removeAction(DeviceSelectActionFromTv.class);
        addAndStartAction(new DeviceSelectActionFromTv(this, targetDevice, callback));
    }

    @ServiceThreadOnly
    private void handleSelectInternalSource() {
        assertRunOnServiceThread();
        // Seq #18
        if (mService.isCecControlEnabled()
                && getActiveSource().logicalAddress != getDeviceInfo().getLogicalAddress()) {
            updateActiveSource(
                    getDeviceInfo().getLogicalAddress(),
                    mService.getPhysicalAddress(),
                    "HdmiCecLocalDeviceTv#handleSelectInternalSource()");
            if (mSkipRoutingControl) {
                mSkipRoutingControl = false;
                return;
            }
            HdmiCecMessage activeSource =
                    HdmiCecMessageBuilder.buildActiveSource(
                            getDeviceInfo().getLogicalAddress(), mService.getPhysicalAddress());
            mService.sendCecCommand(activeSource);
        }
    }

    @ServiceThreadOnly
    void updateActiveSource(int logicalAddress, int physicalAddress, String caller) {
        assertRunOnServiceThread();
        updateActiveSource(ActiveSource.of(logicalAddress, physicalAddress), caller);
    }

    @ServiceThreadOnly
    void updateActiveSource(ActiveSource newActive, String caller) {
        assertRunOnServiceThread();
        // Seq #14
        if (getActiveSource().equals(newActive)) {
            return;
        }
        setActiveSource(newActive, caller);
        int logicalAddress = newActive.logicalAddress;
        if (mService.getHdmiCecNetwork().getCecDeviceInfo(logicalAddress) != null
                && logicalAddress != getDeviceInfo().getLogicalAddress()) {
            if (mService.pathToPortId(newActive.physicalAddress) == getActivePortId()) {
                setPrevPortId(getActivePortId());
            }
            // TODO: Show the OSD banner related to the new active source device.
        } else {
            // TODO: If displayed, remove the OSD banner related to the previous
            //       active source device.
        }
    }

    /**
     * Returns the previous port id kept to handle input switching on <Inactive Source>.
     */
    int getPrevPortId() {
        synchronized (mLock) {
            return mPrevPortId;
        }
    }

    /**
     * Sets the previous port id. INVALID_PORT_ID invalidates it, hence no actions will be
     * taken for <Inactive Source>.
     */
    void setPrevPortId(int portId) {
        synchronized (mLock) {
            mPrevPortId = portId;
        }
    }

    @ServiceThreadOnly
    void updateActiveInput(int path, boolean notifyInputChange) {
        assertRunOnServiceThread();
        // Seq #15
        setActivePath(path);
        // TODO: Handle PAP/PIP case.
        // Show OSD port change banner
        if (notifyInputChange) {
            ActiveSource activeSource = getActiveSource();
            HdmiDeviceInfo info = mService.getHdmiCecNetwork().getCecDeviceInfo(
                    activeSource.logicalAddress);
            if (info == null) {
                info = mService.getDeviceInfoByPort(getActivePortId());
                if (info == null) {
                    // No CEC/MHL device is present at the port. Attempt to switch to
                    // the hardware port itself for non-CEC devices that may be connected.
                    info = HdmiDeviceInfo.hardwarePort(path, getActivePortId());
                }
            }
            HdmiLogger.debug("updateActiveInput " + info);
            mService.invokeInputChangeListener(info);
        }
    }

    @ServiceThreadOnly
    void doManualPortSwitching(int portId, IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        // Seq #20
        if (!mService.isValidPortId(portId)) {
            HdmiLogger.error("doManualPortSwitching invalid port id " + portId);
            invokeCallback(callback, HdmiControlManager.RESULT_INCORRECT_MODE);
            return;
        }
        if (portId == getActivePortId()) {
            HdmiLogger.info("doManualPortSwitching no need for the same port " + portId);
            invokeCallback(callback, HdmiControlManager.RESULT_SUCCESS);
            return;
        }
        removeAction(RequestActiveSourceAction.class);
        getActiveSource().invalidate();
        if (!mService.isCecControlEnabled()) {
            HdmiLogger.error("doManualPortSwitching cec not enabled!");
            setActivePortId(portId);
            invokeCallback(callback, HdmiControlManager.RESULT_INCORRECT_MODE);
            return;
        }
        int oldPath = getDeviceInfo().getPhysicalAddress();
        if (getActivePortId() != Constants.INVALID_PORT_ID
            && (getActivePortId() != 0)) {
            oldPath = mService.portIdToPath(getActivePortId());
        }
        if (mSkipRoutingControl) {
            mSkipRoutingControl = false;
            //return;
        }
        // Related with CECT 11.1.2-1. The adjacently connected tester device's address is like
        // 0x1100 or 0x1200. TV needs to route to the tester device in this case.
        int newPath = mService.portIdToPath(portId);
        HdmiDeviceInfo sourceDevice = mService.getHdmiCecNetwork().getDeviceInfoByPath(newPath);
        if (sourceDevice == null) {
            sourceDevice = findDeviceOnPath(newPath);
        }
        if (sourceDevice != null) {
            newPath = sourceDevice.getPhysicalAddress();
            HdmiLogger.warning("doManualPortSwitching update routing path to 0x%x", newPath);
        }
        setActivePath(newPath);
        setPrevPortId(portId);
        startRoutingControl(oldPath, newPath, callback);
    }

    @ServiceThreadOnly
    void startRoutingControl(int oldPath, int newPath, IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        HdmiLogger.debug("startRoutingControl old:%x new:%x", oldPath, newPath);
        if (oldPath == newPath) {
            return;
        }
        HdmiCecMessage routingChange =
                HdmiCecMessageBuilder.buildRoutingChange(
                        getDeviceInfo().getLogicalAddress(), oldPath, newPath);
        mService.sendCecCommand(routingChange);
        // Send set stream path message always for cect and compatibility concern.
        // Some source devices can't wake up with <Routing Control> messages.
        mService.sendCecCommand(HdmiCecMessageBuilder.buildSetStreamPath(
                getDeviceInfo().getLogicalAddress(), newPath));

        removeAction(RoutingControlAction.class);
        addAndStartAction(
                new RoutingControlAction(this, newPath, callback));
    }

    @ServiceThreadOnly
    int getPowerStatus() {
        assertRunOnServiceThread();
        return mService.getPowerStatus();
    }

    @Override
    protected int findKeyReceiverAddress() {
        if (getActiveSource().isValid()) {
            return getActiveSource().logicalAddress;
        }
        HdmiDeviceInfo info = mService.getHdmiCecNetwork().getDeviceInfoByPath(getActivePath());
        if (info != null) {
            return info.getLogicalAddress();
        }
        return Constants.ADDR_INVALID;
    }

    @Override
    protected int findAudioReceiverAddress() {
        return Constants.ADDR_AUDIO_SYSTEM;
    }

    @Override
    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleActiveSource(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int logicalAddress = message.getSource();
        int physicalAddress = HdmiUtils.twoBytesToInt(message.getParams());
        HdmiDeviceInfo info = mService.getHdmiCecNetwork().getCecDeviceInfo(logicalAddress);
        // Update the power status of the device in cec network.
        mService.getHdmiCecNetwork().updateDevicePowerStatus(logicalAddress,
                HdmiControlManager.POWER_STATUS_ON);
        if (info == null) {
            if (!handleNewDeviceAtTheTailOfActivePath(physicalAddress)) {
                HdmiLogger.debug("Device info %X not found; buffering the command", logicalAddress);
                mDelayedMessageBuffer.add(message);
                if (mDeviceDiscoveryFinished) {
                    HdmiLogger.debug("Start new device action to add the active source device");
                    startNewDeviceAction(ActiveSource.of(logicalAddress, physicalAddress),
                        HdmiUtils.getTypeFromAddress(logicalAddress).get(0));
                }
            }
        } else if (isInputReady(info.getId())
                || info.getDeviceType() == HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM) {
            mService.getHdmiCecNetwork().updateDevicePowerStatus(logicalAddress,
                    HdmiControlManager.POWER_STATUS_ON);
            // For android.hdmicec.cts.tv.HdmiCecRemoteControlPassThroughTest.
            if (physicalAddress != info.getPhysicalAddress()) {
                HdmiLogger.warning("active source's address 0x%x is not in accord with 0x%x",
                        physicalAddress, info.getPhysicalAddress());
                if (info.getPhysicalAddress() != Constants.INVALID_PHYSICAL_ADDRESS) {
                    physicalAddress = info.getPhysicalAddress();
                }
            }
            ActiveSource activeSource = ActiveSource.of(logicalAddress, physicalAddress);
            ActiveSourceHandler.create(this, null).process(activeSource, info.getDeviceType());
        } else {
            HdmiLogger.debug("Input not ready for device: %X; buffering the command", info.getId());
            mDelayedMessageBuffer.add(message);
        }
        return Constants.HANDLED;
    }

    @Override
    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleStandby(HdmiCecMessage message) {
        assertRunOnServiceThread();

        // Ignore <Standby> from non-active source device.
        if (mService.readBooleanSystemProperty(Constants.PROPERTY_STANDBY_CHECK_SOURCE, false)
            && getActiveSource().logicalAddress != message.getSource()) {
            Slog.d(TAG, "<Standby> was not sent by the current active source, ignoring."
                    + " Current active source has logical address "
                    + getActiveSource().logicalAddress);
            return Constants.HANDLED;
        }
        return super.handleStandby(message);
    }

    @Override
    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleInactiveSource(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // Seq #10

        // Ignore <Inactive Source> from non-active source device.
        if (getActiveSource().logicalAddress != message.getSource()) {
            return Constants.HANDLED;
        }
        if (isProhibitMode()) {
            return Constants.HANDLED;
        }
        int portId = getPrevPortId();
        if (portId != Constants.INVALID_PORT_ID) {
            // TODO: Do this only if TV is not showing multiview like PIP/PAP.

            HdmiDeviceInfo inactiveSource = mService.getHdmiCecNetwork().getCecDeviceInfo(
                    message.getSource());
            if (inactiveSource == null) {
                return Constants.HANDLED;
            }
            if (mService.pathToPortId(inactiveSource.getPhysicalAddress()) == portId) {
                return Constants.HANDLED;
            }
            // TODO: Switch the TV freeze mode off

            // We should give user the chance to choose which source he wants to tune to.
            //doManualPortSwitching(portId, null);
            setPrevPortId(Constants.INVALID_PORT_ID);
        } else {
            // No HDMI port to switch to was found. Notify the input change listers to
            // switch to the lastly shown internal input.
            getActiveSource().invalidate();
            setActivePath(Constants.INVALID_PHYSICAL_ADDRESS);
            mService.invokeInputChangeListener(HdmiDeviceInfo.INACTIVE_DEVICE);
        }
        return Constants.HANDLED;
    }

    @Override
    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleRequestActiveSource(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // Seq #19
        if (getDeviceInfo().getLogicalAddress() == getActiveSource().logicalAddress) {
            mService.sendCecCommand(
                    HdmiCecMessageBuilder.buildActiveSource(
                            getDeviceInfo().getLogicalAddress(), getActivePath()));
        }
        return Constants.HANDLED;
    }

    @Override
    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleGetMenuLanguage(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (!broadcastMenuLanguage(mService.getLanguage())) {
            Slog.w(TAG, "Failed to respond to <Get Menu Language>: " + message.toString());
        }
        return Constants.HANDLED;
    }

    @ServiceThreadOnly
    boolean broadcastMenuLanguage(String language) {
        assertRunOnServiceThread();
        HdmiCecMessage command =
                HdmiCecMessageBuilder.buildSetMenuLanguageCommand(
                        getDeviceInfo().getLogicalAddress(), language);
        if (command != null) {
            mService.sendCecCommand(command);
            return true;
        }
        return false;
    }

    @Override
    @Constants.HandleMessageResult
    protected int handleReportPhysicalAddress(HdmiCecMessage message) {
        super.handleReportPhysicalAddress(message);
        int path = HdmiUtils.twoBytesToInt(message.getParams());
        int address = message.getSource();
        int type = message.getParams()[2];

        if (!mService.getHdmiCecNetwork().isInDeviceList(address, path)) {
            handleNewDeviceAtTheTailOfActivePath(path);
        }
        startNewDeviceAction(ActiveSource.of(address, path), type);
        // Update this compatibility action for cect_11_1_2_1_DutSendsSetStreamPathMessage.
        // Sending routing messages to notify the connected device on routing path.
        checkNewDeviceOnRoutingPath(message.getSource(), path);

        return Constants.HANDLED;
    }

    @Override
    @Constants.HandleMessageResult
    protected int handleTimerStatus(HdmiCecMessage message) {
        // Do nothing.
        return Constants.HANDLED;
    }

    @Override
    @Constants.HandleMessageResult
    protected int handleRecordStatus(HdmiCecMessage message) {
        // Do nothing.
        return Constants.HANDLED;
    }

    @Override
    @Constants.HandleMessageResult
    protected int handleReportPowerStatus(HdmiCecMessage message) {
        int logicalAddress = message.getSource();
        int newStatus = message.getParams()[0] & 0xFF;
        if (mService.isSystemAudioActivated()
            && (logicalAddress == Constants.ADDR_AUDIO_SYSTEM)
            && (newStatus == HdmiControlManager.POWER_STATUS_STANDBY)) {
            HdmiLogger.debug("send a power on key to avr");
            sendUserControlPressedAndReleased(Constants.ADDR_AUDIO_SYSTEM,
                    HdmiCecKeycode.CEC_KEYCODE_POWER_ON_FUNCTION);
        }
        return Constants.HANDLED;
    }

    void startNewDeviceAction(ActiveSource activeSource, int deviceType) {
        for (NewDeviceAction action : getActions(NewDeviceAction.class)) {
            // If there is new device action which has the same logical address and path
            // ignore new request.
            // NewDeviceAction is created whenever it receives <Report Physical Address>.
            // And there is a chance starting NewDeviceAction for the same source.
            // Usually, new device sends <Report Physical Address> when it's plugged
            // in. However, TV can detect a new device from HotPlugDetectionAction,
            // which sends <Give Physical Address> to the source for newly detected
            // device.
            if (action.isActionOf(activeSource)) {
                return;
            }
        }

        addAndStartAction(new NewDeviceAction(this, activeSource.logicalAddress,
                activeSource.physicalAddress, deviceType));
    }

    private boolean handleNewDeviceAtTheTailOfActivePath(int path) {
        // Seq #22
        if (isTailOfActivePath(path, getActivePath())) {
            int newPath = mService.portIdToPath(getActivePortId());
            setActivePath(newPath);
            startRoutingControl(getActivePath(), newPath, null);
            return true;
        }
        return false;
    }

    /**
     * Whether the given path is located in the tail of current active path.
     *
     * @param path to be tested
     * @param activePath current active path
     * @return true if the given path is located in the tail of current active path; otherwise,
     *         false
     */
    static boolean isTailOfActivePath(int path, int activePath) {
        // If active routing path is internal source, return false.
        if (activePath == 0) {
            return false;
        }
        for (int i = 12; i >= 0; i -= 4) {
            int curActivePath = (activePath >> i) & 0xF;
            if (curActivePath == 0) {
                return true;
            } else {
                int curPath = (path >> i) & 0xF;
                if (curPath != curActivePath) {
                    return false;
                }
            }
        }
        return false;
    }

    @Override
    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleRoutingChange(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // Seq #21
        byte[] params = message.getParams();
        int currentPath = HdmiUtils.twoBytesToInt(params);
        if (HdmiUtils.isAffectingActiveRoutingPath(getActivePath(), currentPath)) {
            getActiveSource().invalidate();
            removeAction(RoutingControlAction.class);
            int newPath = HdmiUtils.twoBytesToInt(params, 2);
            addAndStartAction(new RoutingControlAction(this, newPath, null));
        }
        return Constants.HANDLED;
    }

    @Override
    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleReportAudioStatus(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (mService.getHdmiCecVolumeControl()
                == HdmiControlManager.VOLUME_CONTROL_DISABLED) {
            return Constants.ABORT_REFUSED;
        }

        boolean mute = HdmiUtils.isAudioStatusMute(message);
        int volume = HdmiUtils.getAudioStatusVolume(message);
        setAudioStatus(mute, volume);
        return Constants.HANDLED;
    }

    @Override
    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleTextViewOn(HdmiCecMessage message) {
        assertRunOnServiceThread();

        // Note that if the device is in sleep mode, the <Text View On> (and <Image View On>)
        // command won't be handled here in most cases. A dedicated microcontroller should be in
        // charge while the Android system is in sleep mode, and the command doesn't need to be
        // passed up to this service.
        // The only situations where the command reaches this handler are
        // 1. if sleep mode is implemented in such a way that Android system is not really put to
        // standby mode but only the display is set to blank. Then the command leads to
        // turning on the display by the invocation of PowerManager.wakeUp().
        // 2. if the device is in dream mode, not sleep mode. Then this command leads to
        // waking up the device from dream mode by the invocation of PowerManager.wakeUp().
        if (getAutoWakeup()) {
            mService.wakeUp();
        }
        return Constants.HANDLED;
    }

    @Override
    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleImageViewOn(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // Currently, it's the same as <Text View On>.
        return handleTextViewOn(message);
    }

    @ServiceThreadOnly
    private void launchDeviceDiscovery() {
        assertRunOnServiceThread();
        DeviceDiscoveryAction action = new DeviceDiscoveryAction(this,
                new DeviceDiscoveryCallback() {
                    @Override
                    public void onDeviceDiscoveryDone(List<HdmiDeviceInfo> deviceInfos) {
                        for (HdmiDeviceInfo info : deviceInfos) {
                            mService.getHdmiCecNetwork().addCecDevice(info);
                        }

                        mDeviceDiscoveryFinished = true;

                        // If there are no delayed <Active Source> message, then process it.
                        // In the scenario of waking up, otp is prior to tv app routing.
                        if (!mDelayedMessageBuffer.isBuffered(Constants.MESSAGE_ACTIVE_SOURCE)) {
                            mService.getSelectRequest().process();
                        }

                        resetSelectRequestBuffer();

                        List<HotplugDetectionAction> hotplugActions
                                = getActions(HotplugDetectionAction.class);
                        if (hotplugActions.isEmpty()) {
                            addAndStartAction(
                                    new HotplugDetectionAction(HdmiCecLocalDeviceTv.this));
                        }

                        /* little meaning
                        List<PowerStatusMonitorAction> powerStatusActions
                                = getActions(PowerStatusMonitorAction.class);
                        if (powerStatusActions.isEmpty()) {
                            addAndStartAction(
                                    new PowerStatusMonitorAction(HdmiCecLocalDeviceTv.this));
                        }*/

                        HdmiDeviceInfo avr = getAvrDeviceInfo();
                        if (avr != null) {
                            onNewAvrAdded(avr);
                        } else {
                            setSystemAudioMode(false);
                        }
                    }
                });
        addAndStartAction(action);
    }

    @ServiceThreadOnly
    void onNewAvrAdded(HdmiDeviceInfo avr) {
        assertRunOnServiceThread();
        HdmiLogger.debug("onNewAvrAdded " + avr);
        if (avr.getPhysicalAddress() == Constants.INVALID_PHYSICAL_ADDRESS) {
            HdmiLogger.warning("Not received avr's <Report Physical Address> yet");
            return;
        }
        if (isDirectConnectAddress(avr.getPhysicalAddress())) {
            addAndStartAction(new SystemAudioAutoInitiationAction(this, avr.getLogicalAddress()));
            if (isArcFeatureEnabled(avr)) {
                startArcAction(true);
            } else {
                HdmiLogger.error("Arc condition is not ready");
            }
        } else {
            HdmiLogger.error("Avr is not directly connected " + avr.getPhysicalAddress());
            setSystemAudioMode(false);
            startArcAction(false);
        }
    }

    @ServiceThreadOnly
    // Seq #32
    void changeSystemAudioMode(boolean enabled, IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        if (!mService.isCecControlEnabled() || hasAction(DeviceDiscoveryAction.class)) {
            HdmiLogger.debug("changeSystemAudioMode while cec is disabled!");
            setSystemAudioMode(false);
            invokeCallback(callback, HdmiControlManager.RESULT_INCORRECT_MODE);
            return;
        }
        HdmiDeviceInfo avr = getAvrDeviceInfo();
        if (avr == null) {
            setSystemAudioMode(false);
            invokeCallback(callback, HdmiControlManager.RESULT_TARGET_NOT_AVAILABLE);
            return;
        }

        if (isDirectConnectAddress(avr.getPhysicalAddress())) {
            addAndStartAction(
                new SystemAudioActionFromTv(this, avr.getLogicalAddress(), enabled, callback));
        }
    }

    // # Seq 25
    void setSystemAudioMode(boolean on) {
        if (!isSystemAudioControlFeatureEnabled() && on) {
            HdmiLogger.debug("Cannot turn on system audio mode "
                    + "because the System Audio Control feature is disabled.");
            return;
        }
        HdmiLogger.info("System Audio Mode change[old:%b new:%b] arc:%b",
                mService.isSystemAudioActivated(), on, mArcEstablished);
        synchronized (mLock) {
            if (mService.isSystemAudioActivated() != on) {
                updateAudioManagerForSystemAudio(on);
                mService.setSystemAudioActivated(on);
                mService.announceSystemAudioModeChange(on);
            }
            if (on && !mArcEstablished) {
                startArcAction(true);
            } else if (!on) {
                startArcAction(false);
            }
        }
    }

    private void updateAudioManagerForSystemAudio(boolean on) {
        int device = mService.getAudioManager().setHdmiSystemAudioSupported(on);
        HdmiLogger.debug("[A]UpdateSystemAudio mode[on=%b] output=[%X]", on, device);
    }

    boolean isSystemAudioActivated() {
        if (!hasSystemAudioDevice()) {
            return false;
        }
        return mService.isSystemAudioActivated();
    }

    @ServiceThreadOnly
    void setSystemAudioControlFeatureEnabled(boolean enabled) {
        assertRunOnServiceThread();
        HdmiLogger.debug("setSystemAudioControlFeatureEnabled old:"
            + mSystemAudioControlFeatureEnabled + " new:" + enabled);
        synchronized (mLock) {
            mSystemAudioControlFeatureEnabled = enabled;
        }
        if (hasSystemAudioDevice()) {
            changeSystemAudioMode(enabled, null);
        }
    }

    boolean isSystemAudioControlFeatureEnabled() {
        synchronized (mLock) {
            return mService.getHdmiCecConfig().getIntValue(
                HdmiControlManager.CEC_SETTING_NAME_SYSTEM_AUDIO_CONTROL)
                == HdmiControlManager.SYSTEM_AUDIO_CONTROL_ENABLED;
        }
    }

    @ServiceThreadOnly
    void enableArc(List<byte[]> supportedSads) {
        assertRunOnServiceThread();
        HdmiLogger.debug("Set Arc Status[old:%b new:true]", mArcEstablished);

        if (mService.earcBlocksArcConnection()) {
            HdmiLogger.warning("ARC connection blocked because eARC connection"
                + " is established or being established.");
            return;
        }

        enableAudioReturnChannel(true);
        notifyArcStatusToAudioService(true, supportedSads);
        mArcEstablished = true;
    }

    @ServiceThreadOnly
    void disableArc() {
        assertRunOnServiceThread();
        HdmiLogger.debug("Set Arc Status[old:%b new:false]", mArcEstablished);

        enableAudioReturnChannel(false);
        notifyArcStatusToAudioService(false, new ArrayList<>());
        mArcEstablished = false;
    }

    /**
     * Switch hardware ARC circuit in the system.
     */
    @ServiceThreadOnly
    void enableAudioReturnChannel(boolean enabled) {
        assertRunOnServiceThread();
        HdmiDeviceInfo avr = getAvrDeviceInfo();
        if (avr != null && avr.getPortId() != Constants.INVALID_PORT_ID) {
            mService.enableAudioReturnChannel(avr.getPortId(), enabled);
        }
    }

    @ServiceThreadOnly
    boolean isConnected(int portId) {
        assertRunOnServiceThread();
        return mService.isConnected(portId);
    }

    private void notifyArcStatusToAudioService(boolean enabled, List<byte[]> supportedSads) {
        // Note that if it's switched from EARC to ARC.
        mService.switchToArc(enabled);

        // Note that we don't set any name to ARC.
        AudioDeviceAttributes attributes = new AudioDeviceAttributes(
                AudioDeviceAttributes.ROLE_OUTPUT, AudioDeviceInfo.TYPE_HDMI_ARC, "", "",
                new ArrayList<AudioProfile>(), supportedSads.stream()
                .map(sad -> new AudioDescriptor(AudioDescriptor.STANDARD_EDID,
                        AudioProfile.AUDIO_ENCAPSULATION_TYPE_NONE, sad))
                .collect(Collectors.toList()));
        mService.getAudioManager().setWiredDeviceConnectionState(attributes, enabled ? 1 : 0);
    }

    /**
     * Returns true if ARC is currently established on a certain port.
     */
    @ServiceThreadOnly
    boolean isArcEstablished() {
        assertRunOnServiceThread();
        if (mArcEstablished) {
            for (int i = 0; i < mArcFeatureEnabled.size(); i++) {
                if (mArcFeatureEnabled.valueAt(i)) return true;
            }
        }
        return false;
    }

    @ServiceThreadOnly
    void changeArcFeatureEnabled(int portId, boolean enabled) {
        assertRunOnServiceThread();
        if (mArcFeatureEnabled.get(portId) == enabled) {
            return;
        }
        mArcFeatureEnabled.put(portId, enabled);
        HdmiDeviceInfo avr = getAvrDeviceInfo();
        if (avr == null || avr.getPortId() != portId) {
            return;
        }
        if (enabled && !mArcEstablished) {
            startArcAction(true);
        } else if (!enabled && mArcEstablished) {
            startArcAction(false);
        }
    }

    @ServiceThreadOnly
    boolean isArcFeatureEnabled(int portId) {
        assertRunOnServiceThread();
        return mArcFeatureEnabled.get(portId);
    }

    @ServiceThreadOnly
    void startArcAction(boolean enabled) {
        startArcAction(enabled, null);
    }

    @ServiceThreadOnly
    void startArcAction(boolean enabled, IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        HdmiLogger.info("startArcAction [old:%b new:%b]", mArcEstablished, enabled);

        HdmiDeviceInfo info = getAvrDeviceInfo();
        if (info == null) {
            if (!enabled) {
                removeAction(RequestArcInitiationAction.class);
                disableArc();
                return;
            }
            HdmiLogger.warning("Failed to start arc action; No AVR device.");
            return;
        }
        if (!canStartArcUpdateAction(info.getLogicalAddress(), enabled)) {
            HdmiLogger.warning("Failed to start arc action; ARC configuration check failed.");
            if (enabled && !isConnectedToArcPort(info.getPhysicalAddress())
                // Don't show warning if it has not got avr's physical address.
                && (info.getPhysicalAddress() != Constants.INVALID_PHYSICAL_ADDRESS)) {
                // For CTS cect_11_1_17_5_NonAdjacentDeviceArcInitiation
                removeArcActions();
                displayOsd(OSD_MESSAGE_ARC_CONNECTED_INVALID_PORT);
            }
            invokeCallback(callback, HdmiControlManager.RESULT_INCORRECT_MODE);
            return;
        }
        if (enabled && mService.earcBlocksArcConnection()) {
            Slog.i(TAG,
                    "ARC connection blocked because eARC connection is established or being "
                            + "established.");
            invokeCallback(callback, HdmiControlManager.RESULT_INCORRECT_MODE);
            return;
        }

        // Terminate opposite action and create an action with callback.
        if (enabled) {
            removeAction(RequestArcTerminationAction.class);
            if (hasAction(RequestArcInitiationAction.class)) {
                RequestArcInitiationAction existingInitiationAction =
                        getActions(RequestArcInitiationAction.class).get(0);
                existingInitiationAction.addCallback(callback);
            } else {
                addAndStartAction(
                        new RequestArcInitiationAction(this, info.getLogicalAddress(), callback));
            }
        } else {
            removeAction(RequestArcInitiationAction.class);
            if (hasAction(RequestArcTerminationAction.class)) {
                RequestArcTerminationAction existingTerminationAction =
                        getActions(RequestArcTerminationAction.class).get(0);
                existingTerminationAction.addCallback(callback);
            } else {
                addAndStartAction(
                        new RequestArcTerminationAction(this, info.getLogicalAddress(), callback));
            }
        }
    }

    void removeArcActions() {
        removeAction(RequestArcInitiationAction.class);
        removeAction(SetArcTransmissionStateAction.class);
        removeAction(RequestSadAction.class);
    }

    private boolean isDirectConnectAddress(int physicalAddress) {
        return (physicalAddress & Constants.ROUTING_PATH_TOP_MASK) == physicalAddress;
    }

    void setAudioStatus(boolean mute, int volume) {
        HdmiLogger.debug("setAudioStatus mute:" + mute + " vol:" + volume);
        if (!isSystemAudioActivated() || mService.getHdmiCecVolumeControl()
                == HdmiControlManager.VOLUME_CONTROL_DISABLED) {
            HdmiLogger.error("setAudioStatus return for no audio control or disabled");
            return;
        }
        synchronized (mLock) {
            mSystemAudioMute = mute;
            mSystemAudioVolume = volume;
            int maxVolume = mService.getAudioManager().getStreamMaxVolume(
                    AudioManager.STREAM_MUSIC);

            boolean useAndroidVolumeBar = mService.isUseAndroidVolumeUi();
            HdmiLogger.debug("Use android volume bar ? " + useAndroidVolumeBar);
            if (useAndroidVolumeBar) {
                mService.setAudioStatus(mute,
                    VolumeControlAction.scaleToCustomVolume(volume, maxVolume));
            } else {
                displayOsd(HdmiControlManager.OSD_MESSAGE_AVR_VOLUME_CHANGED,
                        mute ? HdmiControlManager.AVR_VOLUME_MUTED : volume);
            }
        }
    }

    @ServiceThreadOnly
    void changeVolume(int curVolume, int delta, int maxVolume) {
        assertRunOnServiceThread();
        if (getAvrDeviceInfo() == null) {
            // On initialization process, getAvrDeviceInfo() may return null and cause exception
            return;
        }
        if (delta == 0 || !isSystemAudioActivated() || mService.getHdmiCecVolumeControl()
                == HdmiControlManager.VOLUME_CONTROL_DISABLED) {
            return;
        }

        int targetVolume = curVolume + delta;
        int cecVolume = VolumeControlAction.scaleToCecVolume(targetVolume, maxVolume);
        synchronized (mLock) {
            // If new volume is the same as current system audio volume, just ignore it.
            // Note that UNKNOWN_VOLUME is not in range of cec volume scale.
            if (cecVolume == mSystemAudioVolume) {
                // Update tv volume with system volume value.
                mService.setAudioStatus(false,
                        VolumeControlAction.scaleToCustomVolume(mSystemAudioVolume, maxVolume));
                return;
            }
        }

        List<VolumeControlAction> actions = getActions(VolumeControlAction.class);
        if (actions.isEmpty()) {
            addAndStartAction(new VolumeControlAction(this,
                    getAvrDeviceInfo().getLogicalAddress(), delta > 0));
        } else {
            actions.get(0).handleVolumeChange(delta > 0);
        }
    }

    @ServiceThreadOnly
    void changeMute(boolean mute) {
        assertRunOnServiceThread();
        if (getAvrDeviceInfo() == null || mService.getHdmiCecVolumeControl()
                == HdmiControlManager.VOLUME_CONTROL_DISABLED) {
            // On initialization process, getAvrDeviceInfo() may return null and cause exception
            return;
        }
        HdmiLogger.debug("[A]:Change mute:%b", mute);
        synchronized (mLock) {
            if (mSystemAudioMute == mute) {
                HdmiLogger.debug("No need to change mute.");
                return;
            }
        }
        if (!isSystemAudioActivated()) {
            HdmiLogger.debug("[A]:System audio is not activated.");
            return;
        }

        // Remove existing volume action.
        removeAction(VolumeControlAction.class);
        sendUserControlPressedAndReleased(getAvrDeviceInfo().getLogicalAddress(),
                HdmiCecKeycode.getMuteKey(mute));
    }

    @Override
    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleInitiateArc(HdmiCecMessage message) {
        assertRunOnServiceThread();

        if (mService.earcBlocksArcConnection()) {
            Slog.i(TAG,
                    "ARC connection blocked because eARC connection is established or being "
                            + "established.");
            return Constants.ABORT_NOT_IN_CORRECT_MODE;
        }

        if (!canStartArcUpdateAction(message.getSource(), true)) {
            HdmiDeviceInfo avrDeviceInfo = getAvrDeviceInfo();
            HdmiLogger.debug("handleInitiateArc but can't with avr:" + avrDeviceInfo);
            if (avrDeviceInfo == null) {
                // AVR may not have been discovered yet. Delay the message processing.
                mDelayedMessageBuffer.add(message);
                return Constants.HANDLED;
            }
            if (avrDeviceInfo.getPhysicalAddress() == Constants.INVALID_PHYSICAL_ADDRESS) {
                HdmiLogger.debug("handleInitiateArc but avr's physical address is not got yet.");
                mService.sendCecCommand(HdmiCecMessageBuilder.buildGivePhysicalAddress(
                    getDeviceInfo().getLogicalAddress(), avrDeviceInfo.getLogicalAddress()));
                return Constants.HANDLED;
            }
            mService.maySendFeatureAbortCommand(message, Constants.ABORT_REFUSED);
            mService.sendCecCommand(HdmiCecMessageBuilder.buildRequestArcTermination(
                getDeviceInfo().getLogicalAddress(), message.getSource()));
            if (!isConnectedToArcPort(avrDeviceInfo.getPhysicalAddress())
                // Don't show warning if it has not got avr's physical address.
                && (avrDeviceInfo.getPhysicalAddress() != Constants.INVALID_PHYSICAL_ADDRESS)) {
                displayOsd(OSD_MESSAGE_ARC_CONNECTED_INVALID_PORT);
            }
            return Constants.ABORT_REFUSED;
        }

        if (mArcEstablished) {
            HdmiLogger.debug("handleInitiateArc while arc is already established.");
            HdmiCecMessage command = HdmiCecMessageBuilder.buildReportArcInitiated(
                getDeviceInfo().getLogicalAddress(), message.getSource());
            mService.sendCecCommand(command);
            return Constants.HANDLED;
        }

        List<SetArcTransmissionStateAction> actions = getActions(SetArcTransmissionStateAction.class);
        if (!actions.isEmpty() && actions.get(0).enabled()) {
            HdmiLogger.debug("handleInitiateArc but transmission is already going");
            return Constants.HANDLED;
        }

        // In case where <Initiate Arc> is started by <Request ARC Initiation>
        // and need to clean up the duplicated RequestArcInitiationAction.
        removeAction(RequestArcInitiationAction.class);
        SetArcTransmissionStateAction action = new SetArcTransmissionStateAction(this,
                message.getSource(), true);
        addAndStartAction(action);
        return Constants.HANDLED;
    }

    private boolean canStartArcUpdateAction(int avrAddress, boolean enabled) {
        HdmiDeviceInfo avr = getAvrDeviceInfo();
        if (avr != null
                && (avrAddress == avr.getLogicalAddress())) {
            if (enabled) {
                return //isConnected(avr.getPortId()) Compatibility concern.
                    isArcFeatureEnabled(avr)
                    && isDirectConnectAddress(avr.getPhysicalAddress());
            } else {
                return true;
            }
        } else {
            return false;
        }
    }

    @Override
    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleTerminateArc(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (mService .isPowerStandbyOrTransient()) {
            disableArc();
            return Constants.HANDLED;
        }
        if (!mArcEstablished) {
            HdmiLogger.debug("handleTerminateArc while arc is already disconnected.");
            HdmiCecMessage command = HdmiCecMessageBuilder.buildReportArcTerminated(
                getDeviceInfo().getLogicalAddress(), message.getSource());
            mService.sendCecCommand(command);
            return Constants.HANDLED;
        }
        // Do not check ARC configuration since the AVR might have been already removed.
        // Clean up RequestArcTerminationAction in case <Terminate Arc> was started by
        // <Request ARC Termination>.
        removeAction(RequestArcTerminationAction.class);
        SetArcTransmissionStateAction action = new SetArcTransmissionStateAction(this,
                message.getSource(), false);
        addAndStartAction(action);
        return Constants.HANDLED;
    }

    @Override
    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleSetSystemAudioMode(HdmiCecMessage message) {
        assertRunOnServiceThread();
        boolean systemAudioStatus = HdmiUtils.parseCommandParamSystemAudioStatus(message);
        if (!isMessageForSystemAudio(message)) {
            if (getAvrDeviceInfo() == null) {
                // AVR may not have been discovered yet. Delay the message processing.
                mDelayedMessageBuffer.add(message);
            } else {
                HdmiLogger.warning("Invalid <Set System Audio Mode> message:" + message);
                return Constants.ABORT_REFUSED;
            }
        } else if (systemAudioStatus && !isSystemAudioControlFeatureEnabled()) {
            HdmiLogger.debug("Ignoring <Set System Audio Mode> message "
                    + "because the System Audio Control feature is disabled: %s", message);
            addAndStartAction(new SystemAudioActionFromTv(this, message.getSource(),
                    false, null));
            return Constants.ABORT_REFUSED;
        }
        HdmiDeviceInfo avr = getAvrDeviceInfo();
        if (avr == null) {
            if (systemAudioStatus) {
                mDelayedMessageBuffer.add(message);
            }
            return Constants.HANDLED;
        }
        if (avr.getPhysicalAddress() == Constants.INVALID_PHYSICAL_ADDRESS) {
            HdmiLogger.debug("handleSetSystemAudioMode but avr's physical address is not got yet.");
            mService.sendCecCommand(HdmiCecMessageBuilder.buildGivePhysicalAddress(
                getDeviceInfo().getLogicalAddress(), avr.getLogicalAddress()));
            return Constants.HANDLED;
        }
        if (systemAudioStatus && !isDirectConnectAddress(avr.getPhysicalAddress())) {
            HdmiLogger.error("handleSetSystemAudioMode avr is not adjacent " + avr);
            mService.maySendFeatureAbortCommand(message, Constants.ABORT_REFUSED);
            addAndStartAction(new SystemAudioActionFromTv(this, message.getSource(),
                    false, null));
            return Constants.ABORT_REFUSED;
        }
        removeAction(SystemAudioAutoInitiationAction.class);
        SystemAudioActionFromAvr action = new SystemAudioActionFromAvr(this,
                message.getSource(), systemAudioStatus, null);
        addAndStartAction(action);
        return Constants.HANDLED;
    }

    @Override
    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleSystemAudioModeStatus(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (!isMessageForSystemAudio(message)) {
            HdmiLogger.warning("Invalid <System Audio Mode Status> message:" + message);
            // Ignore this message.
            return Constants.HANDLED;
        }
        /* Don't change audio mode status in here for compatibility concern.
        boolean tvSystemAudioMode = isSystemAudioControlFeatureEnabled();
        boolean avrSystemAudioMode = HdmiUtils.parseCommandParamSystemAudioStatus(message);
        // Set System Audio Mode according to TV's settings.
        // Handle <System Audio Mode Status> here only when
        // SystemAudioAutoInitiationAction timeout
        HdmiDeviceInfo avr = getAvrDeviceInfo();
        if (avr == null) {
            setSystemAudioMode(false);
        } else if (avrSystemAudioMode != tvSystemAudioMode) {
            addAndStartAction(new SystemAudioActionFromTv(this, avr.getLogicalAddress(),
                    tvSystemAudioMode, null));
        } else {
            setSystemAudioMode(tvSystemAudioMode);
        }*/

        return Constants.HANDLED;
    }

    // Seq #53
    @Override
    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleRecordTvScreen(HdmiCecMessage message) {
        List<OneTouchRecordAction> actions = getActions(OneTouchRecordAction.class);
        if (!actions.isEmpty()) {
            // Assumes only one OneTouchRecordAction.
            OneTouchRecordAction action = actions.get(0);
            if (action.getRecorderAddress() != message.getSource()) {
                announceOneTouchRecordResult(
                        message.getSource(),
                        HdmiControlManager.ONE_TOUCH_RECORD_PREVIOUS_RECORDING_IN_PROGRESS);
            }
            // The default behavior of <Record TV Screen> is replying <Feature Abort> with
            // "Cannot provide source".
            return Constants.ABORT_CANNOT_PROVIDE_SOURCE;
        }

        int recorderAddress = message.getSource();
        byte[] recordSource = mService.invokeRecordRequestListener(recorderAddress);
        return startOneTouchRecord(recorderAddress, recordSource);
    }

    @Override
    @Constants.HandleMessageResult
    protected int handleTimerClearedStatus(HdmiCecMessage message) {
        byte[] params = message.getParams();
        int timerClearedStatusData = params[0] & 0xFF;
        announceTimerRecordingResult(message.getSource(), timerClearedStatusData);
        return Constants.HANDLED;
    }

    @Override
    @Constants.HandleMessageResult
    protected int handleSetAudioVolumeLevel(SetAudioVolumeLevelMessage message) {
        int level = message.getAudioVolumeLevel();
        HdmiLogger.debug("handleSetAudioVolumeLevel sam:%b level:%x",
                mService.isSystemAudioActivated(), level);
        // <Set Audio Volume Level> should only be sent to the System Audio device, so we don't
        // handle it when System Audio Mode is enabled.
        if (mService.isSystemAudioActivated()) {
            return Constants.ABORT_NOT_IN_CORRECT_MODE;
        } else {
            int audioVolumeLevel = message.getAudioVolumeLevel();
            if (audioVolumeLevel >= AudioStatus.MIN_VOLUME
                    && audioVolumeLevel <= AudioStatus.MAX_VOLUME) {
                mService.setStreamMusicVolume(audioVolumeLevel, 0);
            }
            return Constants.HANDLED;
        }
    }

    void announceOneTouchRecordResult(int recorderAddress, int result) {
        mService.invokeOneTouchRecordResult(recorderAddress, result);
    }

    void announceTimerRecordingResult(int recorderAddress, int result) {
        mService.invokeTimerRecordingResult(recorderAddress, result);
    }

    void announceClearTimerRecordingResult(int recorderAddress, int result) {
        mService.invokeClearTimerRecordingResult(recorderAddress, result);
    }

    private boolean isMessageForSystemAudio(HdmiCecMessage message) {
        return mService.isCecControlEnabled()
                && message.getSource() == Constants.ADDR_AUDIO_SYSTEM
                && (message.getDestination() == Constants.ADDR_TV
                        || message.getDestination() == Constants.ADDR_BROADCAST)
                && getAvrDeviceInfo() != null;
    }

    @Nullable
    @ServiceThreadOnly
    HdmiDeviceInfo getAvrDeviceInfo() {
        assertRunOnServiceThread();
        return mService.getHdmiCecNetwork().getCecDeviceInfo(Constants.ADDR_AUDIO_SYSTEM);
    }

    boolean hasSystemAudioDevice() {
        return getSafeAvrDeviceInfo() != null;
    }

    @Nullable
    HdmiDeviceInfo getSafeAvrDeviceInfo() {
        return mService.getHdmiCecNetwork().getSafeCecDeviceInfo(Constants.ADDR_AUDIO_SYSTEM);
    }

    @ServiceThreadOnly
    void handleRemoveActiveRoutingPath(int path) {
        assertRunOnServiceThread();
        // Seq #23
        if (isTailOfActivePath(path, getActivePath())) {
            int newPath = mService.portIdToPath(getActivePortId());
            startRoutingControl(getActivePath(), newPath, null);
        }
    }

    /**
     * Launch routing control process.
     *
     * @param routingForBootup true if routing control is initiated due to One Touch Play
     *        or TV power on
     */
    @ServiceThreadOnly
    void launchRoutingControl(boolean routingForBootup) {
        assertRunOnServiceThread();
        if (mService.getSelectRequest().isValid()
            || mDelayedMessageBuffer.isBuffered(Constants.MESSAGE_ACTIVE_SOURCE)) {
            HdmiLogger.debug("launchRoutingControl %b no need", routingForBootup);
            return;
        }
        HdmiLogger.debug("launchRoutingControl active port:" + getActivePortId());
        // Seq #24
        if (getActivePortId() != Constants.INVALID_PORT_ID
                && getActivePortId() != Constants.CEC_SWITCH_HOME) {
            if (!routingForBootup && !isProhibitMode()) {
                int newPath = mService.portIdToPath(getActivePortId());
                setActivePath(newPath);
                startRoutingControl(getActivePath(), newPath, null);
            }
        } else {
            mService.sendCecCommand(HdmiCecMessageBuilder.buildActiveSource(
                    getDeviceInfo().getLogicalAddress(),
                    getDeviceInfo().getPhysicalAddress()));
            updateActiveSource(getDeviceInfo().getLogicalAddress(),
                    getDeviceInfo().getPhysicalAddress(),
                    "launchRoutingControl");
        }
    }

    @Override
    @ServiceThreadOnly
    void onHotplug(int portId, boolean connected) {
        assertRunOnServiceThread();

        if (!connected) {
            mService.getHdmiCecNetwork().removeCecSwitches(portId);
        }

        // Turning System Audio Mode off when the AVR is unlugged or standby.
        // When the device is not unplugged but reawaken from standby, we check if the System
        // Audio Control Feature is enabled or not then decide if turning SAM on/off accordingly.
        if (getAvrDeviceInfo() != null && portId == getAvrDeviceInfo().getPortId()) {
            HdmiLogger.debug("Port ID:%d, 5v=%b", portId, connected);
            if (!connected) {
                // In hdmi test, HEACT 5-15, cec could still poll address 5 success
                // even though 5v is down.
                setSystemAudioMode(false);
            } else {
                mService.runOnServiceThreadDelayed(()->{
                    // Delay 1 second and then check the arc status for earc status update.
                    onNewAvrAdded(getAvrDeviceInfo());
                }, HdmiConfig.TIMEOUT_MS / 2);
            }
        }

        // Tv device will have permanent HotplugDetectionAction.
        List<HotplugDetectionAction> hotplugActions = getActions(HotplugDetectionAction.class);
        if (!hotplugActions.isEmpty()) {
            // Note that hotplug action is single action running on a machine.
            // "pollAllDevicesNow" cleans up timer and start poll action immediately.
            // It covers seq #40, #43.
            hotplugActions.get(0).pollAllDevicesNow();
        }
    }

    @ServiceThreadOnly
    boolean getAutoWakeup() {
        assertRunOnServiceThread();
        return mService.getHdmiCecConfig().getIntValue(
                  HdmiControlManager.CEC_SETTING_NAME_TV_WAKE_ON_ONE_TOUCH_PLAY)
                    == HdmiControlManager.TV_WAKE_ON_ONE_TOUCH_PLAY_ENABLED;
    }

    @Override
    @ServiceThreadOnly
    protected void disableDevice(boolean initiatedByCec, PendingActionClearedCallback callback) {
        assertRunOnServiceThread();
        HdmiLogger.debug("disableDevice " + this);
        mService.unregisterTvInputCallback(mTvInputCallback);
        mDeviceDiscoveryFinished = false;
        // Remove any working actions.
        removeAllActions();

        disableSystemAudioIfExist();
        disableArcIfExist();

        super.disableDevice(initiatedByCec, callback);
        clearDeviceInfoList();
        getActiveSource().invalidate();
        setActivePath(Constants.INVALID_PHYSICAL_ADDRESS);
        // The cleaning arc actions could cause handleDisableDeviceTimeout is called after the
        // device wakes up or cec enabled.
        callback.onCleared(this);
        mPendingActionClearedCallback = null;
    }

    @ServiceThreadOnly
    private void disableSystemAudioIfExist() {
        assertRunOnServiceThread();
        if (getAvrDeviceInfo() == null) {
            return;
        }

        // Seq #31.
        removeAction(SystemAudioActionFromAvr.class);
        removeAction(SystemAudioActionFromTv.class);
        removeAction(SystemAudioAutoInitiationAction.class);
        removeAction(VolumeControlAction.class);

        addAndStartAction(
            new SystemAudioActionFromTv(this, getAvrDeviceInfo().getLogicalAddress(), false, null));
    }

    @ServiceThreadOnly
    private void forceDisableArcOnAllPins() {
        List<HdmiPortInfo> ports = mService.getPortInfo();
        for (HdmiPortInfo port : ports) {
            if (isArcFeatureEnabled(port.getId())) {
                mService.enableAudioReturnChannel(port.getId(), false);
            }
        }
    }

    @ServiceThreadOnly
    private void disableArcIfExist() {
        assertRunOnServiceThread();
        HdmiDeviceInfo avr = getAvrDeviceInfo();
        if (avr == null) {
            return;
        }

        // Seq #44.
        removeAllRunningArcAction();
        if (!hasAction(RequestArcTerminationAction.class) && isArcEstablished()) {
            addAndStartAction(new RequestArcTerminationAction(this, avr.getLogicalAddress()));
        }

        // Disable ARC Pin earlier, prevent the case where AVR doesn't send <Terminate ARC> in time
        forceDisableArcOnAllPins();
    }

    @ServiceThreadOnly
    private void removeAllRunningArcAction() {
        // Running or pending actions make TV fail to broadcast <Standby> to connected devices
        removeAction(RequestArcTerminationAction.class);
        removeAction(RequestArcInitiationAction.class);
        removeAction(SetArcTransmissionStateAction.class);
    }

    @Override
    @ServiceThreadOnly
    protected void onStandby(boolean initiatedByCec, int standbyAction,
            StandbyCompletedCallback callback) {
        assertRunOnServiceThread();
        // Seq #11
        if (!mService.isCecControlEnabled()) {
            invokeStandbyCompletedCallback(callback);
            return;
        }
        boolean sendStandbyOnSleep =
                mService.getHdmiCecConfig().getIntValue(
                    HdmiControlManager.CEC_SETTING_NAME_TV_SEND_STANDBY_ON_SLEEP)
                        == HdmiControlManager.TV_SEND_STANDBY_ON_SLEEP_ENABLED;
        if (!initiatedByCec && sendStandbyOnSleep) {
            mService.sendCecCommand(
                    HdmiCecMessageBuilder.buildStandby(
                            getDeviceInfo().getLogicalAddress(), Constants.ADDR_BROADCAST),
                    new SendMessageCallback() {
                        @Override
                        public void onSendCompleted(int error) {
                            HdmiLogger.debug("<Standby> message is sent " + error);
                            invokeStandbyCompletedCallback(callback);
                        }
                    });
        } else {
            HdmiLogger.debug("<Standby> message is not needed");
            invokeStandbyCompletedCallback(callback);
        }
    }

    boolean isProhibitMode() {
        return mService.isProhibitMode();
    }

    boolean isPowerStandbyOrTransient() {
        return mService.isPowerStandbyOrTransient();
    }

    @ServiceThreadOnly
    void displayOsd(int messageId) {
        assertRunOnServiceThread();
        mService.displayOsd(messageId);
    }

    @ServiceThreadOnly
    void displayOsd(int messageId, int extra) {
        assertRunOnServiceThread();
        mService.displayOsd(messageId, extra);
    }

    // Seq #54 and #55
    @ServiceThreadOnly
    @Constants.HandleMessageResult
    int startOneTouchRecord(int recorderAddress, byte[] recordSource) {
        assertRunOnServiceThread();
        if (!mService.isCecControlEnabled()) {
            Slog.w(TAG, "Can not start one touch record. CEC control is disabled.");
            announceOneTouchRecordResult(recorderAddress, ONE_TOUCH_RECORD_CEC_DISABLED);
            return Constants.ABORT_NOT_IN_CORRECT_MODE;
        }

        if (!checkRecorder(recorderAddress)) {
            Slog.w(TAG, "Invalid recorder address:" + recorderAddress);
            announceOneTouchRecordResult(recorderAddress,
                    ONE_TOUCH_RECORD_CHECK_RECORDER_CONNECTION);
            return Constants.ABORT_NOT_IN_CORRECT_MODE;
        }

        if (!checkRecordSource(recordSource)) {
            Slog.w(TAG, "Invalid record source." + Arrays.toString(recordSource));
            announceOneTouchRecordResult(recorderAddress,
                    ONE_TOUCH_RECORD_FAIL_TO_RECORD_DISPLAYED_SCREEN);
            return Constants.ABORT_CANNOT_PROVIDE_SOURCE;
        }

        addAndStartAction(new OneTouchRecordAction(this, recorderAddress, recordSource));
        Slog.i(TAG, "Start new [One Touch Record]-Target:" + recorderAddress + ", recordSource:"
                + Arrays.toString(recordSource));
        return Constants.HANDLED;
    }

    @ServiceThreadOnly
    void stopOneTouchRecord(int recorderAddress) {
        assertRunOnServiceThread();
        if (!mService.isCecControlEnabled()) {
            Slog.w(TAG, "Can not stop one touch record. CEC control is disabled.");
            announceOneTouchRecordResult(recorderAddress, ONE_TOUCH_RECORD_CEC_DISABLED);
            return;
        }

        if (!checkRecorder(recorderAddress)) {
            Slog.w(TAG, "Invalid recorder address:" + recorderAddress);
            announceOneTouchRecordResult(recorderAddress,
                    ONE_TOUCH_RECORD_CHECK_RECORDER_CONNECTION);
            return;
        }

        // Remove one touch record action so that other one touch record can be started.
        removeAction(OneTouchRecordAction.class);
        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildRecordOff(
                        getDeviceInfo().getLogicalAddress(), recorderAddress));
        Slog.i(TAG, "Stop [One Touch Record]-Target:" + recorderAddress);
    }

    private boolean checkRecorder(int recorderAddress) {
        HdmiDeviceInfo device = mService.getHdmiCecNetwork().getCecDeviceInfo(recorderAddress);
        return (device != null) && (HdmiUtils.isEligibleAddressForDevice(
                HdmiDeviceInfo.DEVICE_RECORDER, recorderAddress));
    }

    private boolean checkRecordSource(byte[] recordSource) {
        return (recordSource != null) && HdmiRecordSources.checkRecordSource(recordSource);
    }

    @ServiceThreadOnly
    void startTimerRecording(int recorderAddress, int sourceType, byte[] recordSource) {
        assertRunOnServiceThread();
        if (!mService.isCecControlEnabled()) {
            Slog.w(TAG, "Can not start one touch record. CEC control is disabled.");
            announceTimerRecordingResult(recorderAddress,
                    TIMER_RECORDING_RESULT_EXTRA_CEC_DISABLED);
            return;
        }

        if (!checkRecorder(recorderAddress)) {
            Slog.w(TAG, "Invalid recorder address:" + recorderAddress);
            announceTimerRecordingResult(recorderAddress,
                    TIMER_RECORDING_RESULT_EXTRA_CHECK_RECORDER_CONNECTION);
            return;
        }

        if (!checkTimerRecordingSource(sourceType, recordSource)) {
            Slog.w(TAG, "Invalid record source." + Arrays.toString(recordSource));
            announceTimerRecordingResult(
                    recorderAddress,
                    TIMER_RECORDING_RESULT_EXTRA_FAIL_TO_RECORD_SELECTED_SOURCE);
            return;
        }

        addAndStartAction(
                new TimerRecordingAction(this, recorderAddress, sourceType, recordSource));
        Slog.i(TAG, "Start [Timer Recording]-Target:" + recorderAddress + ", SourceType:"
                + sourceType + ", RecordSource:" + Arrays.toString(recordSource));
    }

    private boolean checkTimerRecordingSource(int sourceType, byte[] recordSource) {
        return (recordSource != null)
                && HdmiTimerRecordSources.checkTimerRecordSource(sourceType, recordSource);
    }

    @ServiceThreadOnly
    void clearTimerRecording(int recorderAddress, int sourceType, byte[] recordSource) {
        assertRunOnServiceThread();
        if (!mService.isCecControlEnabled()) {
            Slog.w(TAG, "Can not start one touch record. CEC control is disabled.");
            announceClearTimerRecordingResult(recorderAddress, CLEAR_TIMER_STATUS_CEC_DISABLE);
            return;
        }

        if (!checkRecorder(recorderAddress)) {
            Slog.w(TAG, "Invalid recorder address:" + recorderAddress);
            announceClearTimerRecordingResult(recorderAddress,
                    CLEAR_TIMER_STATUS_CHECK_RECORDER_CONNECTION);
            return;
        }

        if (!checkTimerRecordingSource(sourceType, recordSource)) {
            Slog.w(TAG, "Invalid record source." + Arrays.toString(recordSource));
            announceClearTimerRecordingResult(recorderAddress,
                    CLEAR_TIMER_STATUS_FAIL_TO_CLEAR_SELECTED_SOURCE);
            return;
        }

        sendClearTimerMessage(recorderAddress, sourceType, recordSource);
    }

    private void sendClearTimerMessage(final int recorderAddress, int sourceType,
            byte[] recordSource) {
        HdmiCecMessage message = null;
        switch (sourceType) {
            case TIMER_RECORDING_TYPE_DIGITAL:
                message =
                        HdmiCecMessageBuilder.buildClearDigitalTimer(
                                getDeviceInfo().getLogicalAddress(), recorderAddress, recordSource);
                break;
            case TIMER_RECORDING_TYPE_ANALOGUE:
                message =
                        HdmiCecMessageBuilder.buildClearAnalogueTimer(
                                getDeviceInfo().getLogicalAddress(), recorderAddress, recordSource);
                break;
            case TIMER_RECORDING_TYPE_EXTERNAL:
                message =
                        HdmiCecMessageBuilder.buildClearExternalTimer(
                                getDeviceInfo().getLogicalAddress(), recorderAddress, recordSource);
                break;
            default:
                Slog.w(TAG, "Invalid source type:" + recorderAddress);
                announceClearTimerRecordingResult(recorderAddress,
                        CLEAR_TIMER_STATUS_FAIL_TO_CLEAR_SELECTED_SOURCE);
                return;

        }
        mService.sendCecCommand(message, new SendMessageCallback() {
            @Override
            public void onSendCompleted(int error) {
                if (error != SendMessageResult.SUCCESS) {
                    announceClearTimerRecordingResult(recorderAddress,
                            CLEAR_TIMER_STATUS_FAIL_TO_CLEAR_SELECTED_SOURCE);
                }
            }
        });
    }

    @Override
    @Constants.HandleMessageResult
    protected int handleMenuStatus(HdmiCecMessage message) {
        // Do nothing and just return true not to prevent from responding <Feature Abort>.
        return Constants.HANDLED;
    }

    @Constants.RcProfile
    @Override
    protected int getRcProfile() {
        return Constants.RC_PROFILE_TV;
    }

    @Override
    protected List<Integer> getRcFeatures() {
        List<Integer> features = new ArrayList<>();
        @HdmiControlManager.RcProfileTv int profile = mService.getHdmiCecConfig().getIntValue(
                        HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_TV);
        features.add(profile);
        return features;
    }

    @Override
    protected DeviceFeatures computeDeviceFeatures() {
        boolean hasArcPort = false;
        List<HdmiPortInfo> ports = mService.getPortInfo();
        for (HdmiPortInfo port : ports) {
            if (isArcFeatureEnabled(port.getId())) {
                hasArcPort = true;
                break;
            }
        }

        return DeviceFeatures.NO_FEATURES_SUPPORTED.toBuilder()
                .setRecordTvScreenSupport(FEATURE_SUPPORTED)
                .setArcTxSupport(hasArcPort ? FEATURE_SUPPORTED : FEATURE_NOT_SUPPORTED)
                .setSetAudioVolumeLevelSupport(FEATURE_SUPPORTED)
                .build();
    }

    @Override
    protected void sendStandby(int deviceId) {
        HdmiDeviceInfo targetDevice = mService.getHdmiCecNetwork().getDeviceInfo(deviceId);
        if (targetDevice == null) {
            return;
        }
        int targetAddress = targetDevice.getLogicalAddress();
        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildStandby(
                        getDeviceInfo().getLogicalAddress(), targetAddress));
    }

    @ServiceThreadOnly
    void processAllDelayedMessages() {
        assertRunOnServiceThread();
        mDelayedMessageBuffer.processAllMessages();
    }

    @ServiceThreadOnly
    void processDelayedMessages(int address) {
        assertRunOnServiceThread();
        mDelayedMessageBuffer.processMessagesForDevice(address);
    }

    @ServiceThreadOnly
    void processDelayedActiveSource(int address) {
        assertRunOnServiceThread();
        mDelayedMessageBuffer.processActiveSource(address);
    }

    @Override
    protected void dump(final IndentingPrintWriter pw) {
        super.dump(pw);
        pw.println("mArcEstablished: " + mArcEstablished);
        pw.println("mArcFeatureEnabled: " + mArcFeatureEnabled);
        pw.println("mSystemAudioMute: " + mSystemAudioMute);
        pw.println("mSystemAudioControlFeatureEnabled: " + mSystemAudioControlFeatureEnabled);
        pw.println("mSkipRoutingControl: " + mSkipRoutingControl);
        pw.println("mPrevPortId: " + mPrevPortId);
    }
}
