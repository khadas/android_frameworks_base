/*
 * Copyright 2025 Rockchip Limited
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

package com.android.systemui.util;

//------rk-code---------
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

public class RemoteControlUtil {

    /**
     * enable remote control support
     */
    public static boolean isSupportRemoteControl(Context context) {
        boolean result = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_APP_FOCUS);
        Log.d("RemoteControlUtil", "isSupportRemoteControl ***** result = " + result);
        return result;
    }
}
//----------------------