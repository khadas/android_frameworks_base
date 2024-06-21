/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.app.ActivityManager;
import android.app.Service;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.hardware.HardwareBuffer;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.os.IClientCallback;
import android.os.IRkHandWriteManagementService;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Log;
import android.view.WindowManager;
import com.android.server.RkHandWriteClientInfo;
import java.lang.reflect.Method;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * @hide
 */
class RkHandWriteManagementService extends IRkHandWriteManagementService.Stub {
    private static final String TAG = "RkHandWriteManagementService";

    private static native int nativeInit(int left, int top, int screenWidth, int screenHeight,
            int layerStack);
    private static native HardwareBuffer nativeGetHardwareBuffer();
    private static native void nativeExit();

    /**
     * Binder context for this service
     */
    private Context mContext;
    private int mRotation;
    private List<RkHandWriteClientInfo> mRkHandWriteClientInfoList = new ArrayList<>();
    private static final String RKHANDWRITE_STATUS_PROPERTY = "vendor.hwc.accelerate_matched";
    private static final String RKHANDWRITE_STATUS_TRUE = "1";
    private static final String RKHANDWRITE_LOG_PROPERTY = "sys.rkhandwrite.log";
    private static final String RKHANDWRITE_LOG_TRUE = "1";
    private String mRkHandWriteStatusProperty = "null";

    public RkHandWriteManagementService(Context context) {
        mContext = context;
    }

    public void registerClientCallback(IClientCallback callback, long pid) {
        if (callback != null) {
            try {
                IBinder clientBinder = callback.asBinder();
                clientBinder.linkToDeath(new IBinder.DeathRecipient() {
                    @Override
                    public void binderDied() {
                        if (RKHANDWRITE_LOG_TRUE.equals(SystemProperties.get(RKHANDWRITE_LOG_PROPERTY)))
                            Log.e(TAG, "binderDied pid:" + pid);
                        removeClientInfo(pid);
                        if (!isClientInfoListEmpty()) {
                        if (RKHANDWRITE_LOG_TRUE.equals(SystemProperties.get(RKHANDWRITE_LOG_PROPERTY)))
                            Log.e(TAG, "ClientInfoList isn't empty, keep.");
                        } else {
                            mRkHandWriteStatusProperty = SystemProperties.get(RKHANDWRITE_STATUS_PROPERTY);
                            if (RKHANDWRITE_LOG_TRUE.equals(SystemProperties.get(RKHANDWRITE_LOG_PROPERTY)))
                                Log.e(TAG, "checkProcesses property:" + mRkHandWriteStatusProperty);
                            if (RKHANDWRITE_STATUS_TRUE.equals(mRkHandWriteStatusProperty)) {
                                exit();
                            }
                        }
                        if (clientBinder != null) {
                            clientBinder.unlinkToDeath(this, 0);
                        }
                    }
                }, 0);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    public HardwareBuffer enableRkHandWrite(long pid, int layerStack) {
        Log.d(TAG, "enableRkHandWrite pid:" + pid);
        // First, check if the mRkHandWriteClientInfoList already contains the record of client's pid.
        if (getClientInfoStatus(pid)) {
            if (RKHANDWRITE_LOG_TRUE.equals(SystemProperties.get(RKHANDWRITE_LOG_PROPERTY)))
                Log.d(TAG, "getClientInfoStatus");
            // Check the RkHandWrite layer status.
            mRkHandWriteStatusProperty = SystemProperties.get(RKHANDWRITE_STATUS_PROPERTY);
            if (RKHANDWRITE_LOG_TRUE.equals(SystemProperties.get(RKHANDWRITE_LOG_PROPERTY)))
                Log.d(TAG, "getClientInfoStatus property:" + mRkHandWriteStatusProperty);
            // Get the HardwareBuffer.
            if (RKHANDWRITE_STATUS_TRUE.equals(mRkHandWriteStatusProperty)) {
                return getHardwareBuffer();
            } else {
                return null;
            }
        } else {
            // Check if the current mRkHandWriteClientInfoList is empty.
            if (isClientInfoListEmpty()) {
                // Add the client’s pid to the mRkHandWriteClientInfoList.
                addClientInfo(pid);
                // Apply for RkHandWrite layer.
                Point screenSize = getCurrentScreenSize(mContext);
                int screenWidth = ALIGN(screenSize.x, 16);
                int screenHeight = ALIGN(screenSize.y, 16);;
                Log.d(TAG, "RkHandWriteManagementService screenWidth:" + screenWidth +
                        ",screenHeight:" + screenHeight + ",mRotation:" + mRotation);
                int initStatus = init(0, 0, screenWidth, screenHeight, layerStack);
                int count = 0;
                if (initStatus == 0) {
                    while(!SystemProperties.get(RKHANDWRITE_STATUS_PROPERTY).equals(RKHANDWRITE_STATUS_TRUE)) {
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        }
                        if (count++ > 20) {
                            Log.e(TAG, "get RkHandWrite layer match overtime.");
                            return null;
                        }
                    }
                    mRkHandWriteStatusProperty = SystemProperties.get(RKHANDWRITE_STATUS_PROPERTY);
                    if (RKHANDWRITE_LOG_TRUE.equals(SystemProperties.get(RKHANDWRITE_LOG_PROPERTY)))
                        Log.d(TAG, "ClientInfoListEmpty property:" + mRkHandWriteStatusProperty);
                    if (mRkHandWriteStatusProperty.equals(RKHANDWRITE_STATUS_TRUE)) {
                        // Get the HardwareBuffer.
                        if (RKHANDWRITE_LOG_TRUE.equals(SystemProperties.get(RKHANDWRITE_LOG_PROPERTY)))
                            Log.d(TAG, "init RkHandWrite layer success.");
                        return getHardwareBuffer();
                    } else {
                        Log.e(TAG, "match RkHandWrite layer failed.");
                        return null;
                    }
                } else {
                    Log.e(TAG, "init RkHandWrite layer failed.");
                    return null;
                }
            // If there are records in the current mRkHandWriteClientInfoList,
            // Don't need to init.
            } else {
                // Add the client’s pid to the mRkHandWriteClientInfoList.
                addClientInfo(pid);
                mRkHandWriteStatusProperty = SystemProperties.get(RKHANDWRITE_STATUS_PROPERTY);
                if (RKHANDWRITE_LOG_TRUE.equals(SystemProperties.get(RKHANDWRITE_LOG_PROPERTY)))
                    Log.d(TAG, "ClientInfoList property:" + mRkHandWriteStatusProperty);
                // Check the RkHandWrite layer status.
                if (mRkHandWriteStatusProperty.equals(RKHANDWRITE_STATUS_TRUE)) {
                    // Get the HardwareBuffer.
                    return getHardwareBuffer();
                } else {
                    Log.e(TAG, "mRkHandWriteClientInfoList isn't empty, but RkHandWrite layer is uncreated.");
                    return null;
                }
            }
        }
    }

    public boolean disableRkHandWrite(long pid) {
        Log.d(TAG, "disableRkHandWrite");
        // Check if the mRkHandWriteClientInfoList already contains the record of client's pid.
        if (getClientInfoStatus(pid)) {
            // Remove client's pid from mRkHandWriteClientInfoList.
            removeClientInfo(pid);
            // Check if the current mRkHandWriteClientInfoList is empty.
            if (isClientInfoListEmpty()) {
                mRkHandWriteStatusProperty = SystemProperties.get(RKHANDWRITE_STATUS_PROPERTY);
                if (RKHANDWRITE_LOG_TRUE.equals(SystemProperties.get(RKHANDWRITE_LOG_PROPERTY)))
                    Log.d(TAG, "disableRkHandWrite property:" + mRkHandWriteStatusProperty);
                // Check the RkHandWrite layer status.
                if (RKHANDWRITE_STATUS_TRUE.equals(mRkHandWriteStatusProperty)) {
                    // Release the RkHandWrite layer.
                    exit();
                }
            } else {
                if (RKHANDWRITE_LOG_TRUE.equals(SystemProperties.get(RKHANDWRITE_LOG_PROPERTY)))
                    Log.d(TAG, "ClientInfoList isn't empty.");
            }
            return true;
        // No client'pid in mRkHandWriteClientInfoList, do not release the RkHandWrite layer.
        } else {
            Log.d(TAG, "ClientInfoList isn't empty.");
            return false;
        }
    }

    public void exitRkHandWrite() {
        Log.d(TAG, "exitRkHandWrite");
        exit();
        clearClientInfoList();
    }

    public int init(int left, int top, int screenWidth, int screenHeight, int layerStack) {
        return nativeInit(left, top, screenWidth, screenHeight, layerStack);
    }

    public HardwareBuffer getHardwareBuffer() {
        return nativeGetHardwareBuffer();
    }

    public void exit() {
        nativeExit();
    }

    private synchronized boolean getClientInfoStatus(long pid) {
        if (RKHANDWRITE_LOG_TRUE.equals(SystemProperties.get(RKHANDWRITE_LOG_PROPERTY)))
            Log.d(TAG, "getClientInfoStatus pid:" + pid);
        for (RkHandWriteClientInfo clientInfo : mRkHandWriteClientInfoList) {
            if (clientInfo.getPid() == pid) {
                if (RKHANDWRITE_LOG_TRUE.equals(SystemProperties.get(RKHANDWRITE_LOG_PROPERTY)))
                    Log.d(TAG, "getClientInfoStatus found");
                return true;
            }
        }
        if (RKHANDWRITE_LOG_TRUE.equals(SystemProperties.get(RKHANDWRITE_LOG_PROPERTY)))
            Log.d(TAG, "getClientInfoStatus none");
        return false;
    }

    private synchronized boolean addClientInfo(long pid) {
        if (RKHANDWRITE_LOG_TRUE.equals(SystemProperties.get(RKHANDWRITE_LOG_PROPERTY)))
            Log.d(TAG, "addClientInfo pid:" + pid);
        if (!getClientInfoStatus(pid)) {
            RkHandWriteClientInfo newClientInfo = new RkHandWriteClientInfo(pid);
            mRkHandWriteClientInfoList.add(newClientInfo);
            if (RKHANDWRITE_LOG_TRUE.equals(SystemProperties.get(RKHANDWRITE_LOG_PROPERTY)))
                Log.d(TAG, "addClientInfo add");
            return true;
        } else {
            return false;
        }
    }

    private synchronized boolean removeClientInfo(long pid) {
        if (RKHANDWRITE_LOG_TRUE.equals(SystemProperties.get(RKHANDWRITE_LOG_PROPERTY)))
            Log.d(TAG, "removeClientInfo pid:" + pid);
        List<RkHandWriteClientInfo> clientsToRemove = new ArrayList<>();
        for (RkHandWriteClientInfo clientInfo : mRkHandWriteClientInfoList) {
            if (clientInfo.getPid() == pid) {
                clientsToRemove.add(clientInfo);
                if (RKHANDWRITE_LOG_TRUE.equals(SystemProperties.get(RKHANDWRITE_LOG_PROPERTY)))
                    Log.d(TAG, "removeClientInfo add");
            }
        }
        boolean removed = !clientsToRemove.isEmpty();
        if (RKHANDWRITE_LOG_TRUE.equals(SystemProperties.get(RKHANDWRITE_LOG_PROPERTY)))
            Log.d(TAG, "removeClientInfo removed:" + removed);
        mRkHandWriteClientInfoList.removeAll(clientsToRemove);
        return removed;
    }

    private synchronized boolean isClientInfoListEmpty() {
        return mRkHandWriteClientInfoList.isEmpty();
    }

    private synchronized void clearClientInfoList() {
        mRkHandWriteClientInfoList.clear();
    }

    private Point getCurrentScreenSize(Context context) {
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager != null) {
            android.view.Display display = windowManager.getDefaultDisplay();
            Point point = new Point();
            display.getRealSize(point);
            Log.d(TAG, "getCurrentScreenSize real point:" + point.x + "," + point.y);
            mRotation = display.getRotation();
            Log.d(TAG, "getCurrentScreenSize rotation:" + mRotation);
            return point;
        }
        return null;
    }

    private int ALIGN(int x, int a) {
        return (((x) + ((a) - 1)) & (~((a) - 1)));
    }
}