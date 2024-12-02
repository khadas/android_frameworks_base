/*
 * Copyright (C) 2024 Rockchip Limited
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

package android.content;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;

/**
  * @hide
  */
public abstract class RKFeatureManager {
    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device includes rockchip AI.
     * @hide
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_ROCKCHIP_AI = "rockchip.software.ai";
}
