/*
 * Copyright 2024 The Android Open Source Project
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

#define LOG_TAG "RkNativeHandWriteManager"

#define LOG_NDEBUG 0

#include <nativehelper/JNIHelp.h>
#include "jni.h"
#include <android/bitmap.h>
#include "android_runtime/AndroidRuntime.h"
#include "android_runtime/android_view_Surface.h"
#include <cstring>
#include <gui/Surface.h>
#include <gui/SurfaceComposerClient.h>
#include <android/hardware_buffer_jni.h>
#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <ui/GraphicBuffer.h>

namespace android {

class JRkHandWrite {
public:
    JRkHandWrite();
    ~JRkHandWrite();
    int Init(int left, int top, int screenWidth, int screenHeight, int layerStack);
    void Clear();
    void Exit();
    sp<GraphicBuffer> getGraphicBuffer();
private:
    int mSurfaceWidth;
    int mSurfaceHeight;
    sp<GraphicBuffer> mOutGraphicBuffer;
    sp<SurfaceControl> mSurfaceControl;
};

JRkHandWrite* mHandWrite = nullptr;

JRkHandWrite::JRkHandWrite() {
    ALOGD("%s", __FUNCTION__);
}

JRkHandWrite::~JRkHandWrite() {
    ALOGD("%s", __FUNCTION__);
    Clear();
    Exit();
}

int JRkHandWrite::Init(int left, int top, int screenWidth, int screenHeight, int layerStack) {
    ALOGD("%s left:%d, top:%d, screenWidth:%d, screenHeight:%d, layerStack=%d",
        __FUNCTION__, left, top, screenWidth, screenHeight, layerStack);
    int err = 0;
    // The width/height of the RkHandWrite layer are full screen.
    mSurfaceWidth = screenWidth;
    mSurfaceHeight = screenHeight;
    sp<SurfaceComposerClient> composerClient = new SurfaceComposerClient;
    err = composerClient->initCheck();
    if (err != NO_ERROR) {
        ALOGE("SurfaceComposerClient initCheck err....");
        return err;
    }
    // Make RkHandWrite layer is at the top of all.
    int z_order = std::numeric_limits<int32_t>::max();
    mSurfaceControl = composerClient->createSurface(
            String8("rk_handwrite_win"), mSurfaceWidth, mSurfaceHeight,
            PIXEL_FORMAT_RGBA_8888);
    // Double screen or more
    SurfaceComposerClient::Transaction t;
    if (layerStack > 0) {
        t.setLayer(mSurfaceControl, z_order);
        t.setPosition(mSurfaceControl, left, top);
        t.setSize(mSurfaceControl, mSurfaceWidth, mSurfaceHeight);
        // Android13+
        t.setLayerStack(mSurfaceControl, ui::LayerStack::fromValue(layerStack));
        t.show(mSurfaceControl);
        t.apply();
    } else {
        t.setLayer(mSurfaceControl, z_order);
        t.setPosition(mSurfaceControl, left, top);
        t.setSize(mSurfaceControl, mSurfaceWidth, mSurfaceHeight);
        t.show(mSurfaceControl);
        t.apply();
    }
    // Set layer name for 'rk_handwrite_sf' by RK interface in SurfaceControl.
    mSurfaceControl->setDefaultBbqName("rk_handwrite_sf");
    mSurfaceControl->setDefaultBbqChildName("rk_handwrite_sf");

    ANativeWindow* nativeWindow = mSurfaceControl->getSurface().get();
    native_window_api_connect(nativeWindow, NATIVE_WINDOW_API_CPU);
    native_window_set_buffers_user_dimensions(nativeWindow, mSurfaceWidth, mSurfaceHeight);
    native_window_set_buffers_format(nativeWindow, PIXEL_FORMAT_RGBA_8888);
    native_window_set_usage(nativeWindow, GRALLOC_USAGE_SW_WRITE_OFTEN);
    int numBufs = 0;
    int minUndequeuedBufs = 0;
    nativeWindow->query(nativeWindow,
            NATIVE_WINDOW_MIN_UNDEQUEUED_BUFFERS, &minUndequeuedBufs);
    numBufs = minUndequeuedBufs + 1;
    native_window_set_buffer_count(nativeWindow, numBufs);

    sp<Surface> surface = mSurfaceControl->getSurface();
    ANativeWindow_Buffer outBuffer;
    ARect rect;
    surface->lock(&outBuffer, &rect);
    ALOGE("%s surface lock done.", __FUNCTION__);
    surface->unlockAndPost();
    ALOGE("%s surface unlockAndPost done.", __FUNCTION__);
    sp<Fence> outFence;
    float outTransformMatrix[16];
    surface->getLastQueuedBuffer(&mOutGraphicBuffer, &outFence, outTransformMatrix);
    if (err != NO_ERROR) {
        ALOGE("%s Init err:%d", __FUNCTION__, err);
    }
    return err;
}

void JRkHandWrite::Clear() {
    if (mOutGraphicBuffer) {
        ALOGD("%s", __FUNCTION__);
        void* vAddr;
        status_t lock_ret = mOutGraphicBuffer->lock(GraphicBuffer::USAGE_SW_WRITE_OFTEN, &vAddr);
        if (lock_ret == NO_ERROR && vAddr != nullptr) {
            ALOGD("%s Clear graphic buffer", __FUNCTION__);
            memset(vAddr, 0, mSurfaceWidth * mSurfaceHeight * 4);
        }
        mOutGraphicBuffer->unlock();
    } else {
        ALOGE("%s mOutGraphicBuffer error.", __FUNCTION__);
    }
}

void JRkHandWrite::Exit() {
    if (mOutGraphicBuffer != NULL ) {
        ALOGD("%s mOutGraphicBuffer release", __FUNCTION__);
        mOutGraphicBuffer = NULL;
    } else {
        ALOGE("%s mOutGraphicBuffer error.", __FUNCTION__);
    }
    if (mSurfaceControl != NULL) {
        ALOGD("%s mSurfaceControl release", __FUNCTION__);
        if (mSurfaceControl->getSurface() != NULL) {
            ANativeWindow* nativeWindow = mSurfaceControl->getSurface().get();
            native_window_api_disconnect(nativeWindow, NATIVE_WINDOW_API_CPU);
        }
        mSurfaceControl = NULL;
    } else {
        ALOGE("%s mSurfaceControl error.", __FUNCTION__);
    }
}

sp<GraphicBuffer> JRkHandWrite::getGraphicBuffer() {
    return mOutGraphicBuffer;
}

////////////////////////////////////////////////////////////////////////////////

static int nativeInit(JNIEnv* env, jobject obj,
        jint left, jint top, jint screenWidth, jint screenHeight, jint layerStack) {
    int ret = -1;
    mHandWrite = new JRkHandWrite();
    ret = mHandWrite->Init(left, top, screenWidth, screenHeight, layerStack);
    return static_cast<jint>(ret);
}

static jobject nativeGetHardwareBuffer(JNIEnv* env) {
    AHardwareBuffer* hardwareBuffer = mHandWrite->getGraphicBuffer()->toAHardwareBuffer();
    if (hardwareBuffer == nullptr) {
        ALOGD("hardwareBuffer is nullptr");
    }
    jobject javaHardwareBuffer = AHardwareBuffer_toHardwareBuffer(env, hardwareBuffer);
    if (javaHardwareBuffer == nullptr) {
        ALOGE("Failed to convert AHardwareBuffer to Java HardwareBuffer");
    }
    ALOGD("%s javaHardwareBuffer is : %p", __FUNCTION__, javaHardwareBuffer);
    return javaHardwareBuffer;
}

static void nativeExit(JNIEnv* env, jobject obj) {
    delete mHandWrite;
    mHandWrite = NULL;
}

// ----------------------------------------------------------------------------

static const JNINativeMethod sRkHandWriteManagementServiceMethods[] = {
    /* name, signature, funcPtr */
    { "nativeInit", "(IIIII)I",
            (void*) nativeInit },
    { "nativeGetHardwareBuffer", "()Landroid/hardware/HardwareBuffer;",
            (void*) nativeGetHardwareBuffer },
    { "nativeExit", "()V",
            (void*) nativeExit },
};

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! (var), "Unable to find class " className)

#define GET_METHOD_ID(var, clazz, methodName, fieldDescriptor) \
        var = env->GetMethodID(clazz, methodName, fieldDescriptor); \
        LOG_FATAL_IF(! (var), "Unable to find method" methodName)

int register_com_android_server_RkHandWriteManagementService(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "com/android/server/RkHandWriteManagementService",
            sRkHandWriteManagementServiceMethods, NELEM(sRkHandWriteManagementServiceMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");
    (void)res; // Don't complain about unused variable in the LOG_NDEBUG case

    jclass clazz;
    FIND_CLASS(clazz, "com/android/server/RkHandWriteManagementService");
    return 0;
}

} /* namespace android */
