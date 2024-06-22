/*
 * Copyright (c) 2024 Rockchip Electronics Co., Ltd
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

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/android_view_Surface.h>
#include <android_runtime/android_view_SurfaceSession.h>
#include <gui/ISurfaceComposer.h>
#include <gui/Surface.h>
#include <gui/SurfaceComposerClient.h>
#include <utils/RefBase.h>

#include "Matrix.h"

namespace android {

class DisplayAnimation {
private:
    /* data */
public:
    DisplayAnimation(/* args */);
    ~DisplayAnimation();
    static void prepareSurface(SurfaceControl *srcSurface, long *prepareSurface);
    static void bindSurfaceControl(SurfaceComposerClient::Transaction *mTransaction,
                                   SurfaceControl *surface, SurfaceControl *newParent);
    static void initSurface(SurfaceComposerClient::Transaction *mTransaction,
                            SurfaceControl *mSrcSurface, int width, int height);
    static void changeSurfaceControl(SurfaceComposerClient::Transaction *mTransaction,
                                     SurfaceControl *mSrcSurface, const bool isXAxis,
                                     float distance, float *scale);
    static void changeSurfaceControl(SurfaceComposerClient::Transaction *mTransaction,
                                     SurfaceControl *mSrcSurface, const bool isXAxis,
                                     float distance, float *scale, float *skew);
    static void changeSurfaceControl(SurfaceComposerClient::Transaction *mTransaction,
                                     SurfaceControl *mSrcSurface, float *distance, float *scale,
                                     float *skew);
};
} // namespace android
