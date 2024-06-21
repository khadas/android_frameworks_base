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

package android.os;

import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.IBinder;
import android.os.IClientCallback;
import android.os.IRkHandWriteManagementService;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Log;

/**
 * @hide
 */
public class RkHandWriteManager {
    private static final String TAG = "RkHandWriteManager";

    private static final String RKHANDWRITE_STATUS_PROPERTY = "vendor.hwc.accelerate_matched";
    private static final String RKHANDWRITE_STATUS_TRUE = "1";
    private IRkHandWriteManagementService mService;
    private long mPid;

    public RkHandWriteManager() {
        IBinder b = ServiceManager.getService("rkhandwrite_management");
        if(b == null) {
            Log.e(TAG, "Unable to connect to RkHandWriteManager service! - is it running yet?");
            return;
        }
        mService = IRkHandWriteManagementService.Stub.asInterface(b);
        mPid = (long)android.os.Process.myPid();
        IClientCallback clientCallback = new IClientCallback.Stub() {
            @Override
            public void onClients(IBinder registered, boolean hasClients) {
            }
        };
        try {
            mService.registerClientCallback(clientCallback, mPid);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    /*
    * enableRkHandWrite(): Client apply the HardwareBuffer for RkHandWrite layer by onResume.
    * return HardwareBuffer for success, null for failed.
    */
    public HardwareBuffer enableRkHandWrite(int layerStack) {
        try {
            HardwareBuffer hardwareBuffer = mService.enableRkHandWrite(mPid, layerStack);
            if (null == hardwareBuffer) {
                Log.e(TAG, "getHardwareBuffer null");
                return null;
            } else {
                return hardwareBuffer;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error enableRkHandWrite: " + e);
            return null;
        }
    }

    /*
    * disableRkHandWrite(): Client disable RkHandWrite by onPause.
    * return true for success, false for failed.
    */
    public boolean disableRkHandWrite() {
        try {
            return mService.disableRkHandWrite(mPid);
        } catch (Exception e) {
            Log.e(TAG, "Error disableRkHandWrite: " + e);
            return false;
        }
    }

    /*
    * exitRkHandWrite(): Client force to exit RkHandWrite in some cases,
    * such as some third-party app require the layer.
    * return true for success, false for failed.
    */
    public void exitRkHandWrite() {
        try {
            mService.exitRkHandWrite();
        } catch (Exception e) {
            Log.e(TAG, "Error exitRkHandWrite: " + e);
        }
    }

    /*
    * getRkHandWriteLayerStatus(): Client get the RkHandWrite layer status.
    * return true for available, false for unavailable.
    */
    public boolean getRkHandWriteLayerStatus() {
        if (RKHANDWRITE_STATUS_TRUE.equals(SystemProperties.get(RKHANDWRITE_STATUS_PROPERTY))) {
            return true;
        }
        return false;
    }
}