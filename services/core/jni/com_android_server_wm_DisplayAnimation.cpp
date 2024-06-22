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

#define LOG_TAG "DisplayAnimationJni"

#include <DisplayAnimation.h>
#include <android_os_Parcel.h>
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/android_view_Surface.h>
#include <android_runtime/android_view_SurfaceSession.h>
#include <core_jni_helpers.h>
#include <gui/ISurfaceComposer.h>
#include <gui/Surface.h>
#include <gui/SurfaceComposerClient.h>
#include <jni.h>
#include <stdio.h>
#include <utils/Log.h>

#include <iostream>

namespace android {

static jobject nativePrepareSurfaceControl(JNIEnv* env, jclass clazz,
                                           jlong srcSurfaceNativeObject) {
    ALOGV("nativePrepareSurfaceControl srcSurfaceNativeObject = %lld",
          (long long)srcSurfaceNativeObject);
    jclass clazzSurfaceControl = FindClassOrDie(env, "android/view/SurfaceControl");
    jmethodID clazzSCCtor = GetMethodIDOrDie(env, clazzSurfaceControl, "<init>", "()V");
    jmethodID clazzSCAssignNativeObject =
            GetMethodIDOrDie(env, clazzSurfaceControl, "assignNativeObject",
                             "(JLjava/lang/String;)V");
    long prepareSurfaceHandle;
    SurfaceControl* srcSurfaceControl = reinterpret_cast<SurfaceControl*>(srcSurfaceNativeObject);
    DisplayAnimation::prepareSurface(srcSurfaceControl, &prepareSurfaceHandle);

    jobject javaSurfaceControl = env->NewObject(clazzSurfaceControl, clazzSCCtor);
    env->CallObjectMethod(javaSurfaceControl, clazzSCAssignNativeObject, prepareSurfaceHandle,
                          env->NewStringUTF("RockchipPrepareSurface"));
    return javaSurfaceControl;
}

static void nativeBindSurface(JNIEnv* env, jclass clazz, jlong transactionObj, jlong nativeObject,
                              jlong newParentObject) {
    ALOGV("nativeBindSurface transactionObj = %lld, nativeObject = %lld, "
          "newParentObject = %lld",
          (long long)transactionObj, (long long)nativeObject, (long long)newParentObject);
    auto ctrl = reinterpret_cast<SurfaceControl*>(nativeObject);
    auto newParent = reinterpret_cast<SurfaceControl*>(newParentObject);
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    DisplayAnimation::bindSurfaceControl(transaction, ctrl, newParent);
}

static void nativeInitSurface(JNIEnv* env, jclass clazz, jlong transactionObj, jobject nativeObject,
                              jint width, jint height) {
    jclass clazzSurfaceControl = FindClassOrDie(env, "android/view/SurfaceControl");
    jlong mNativeObject =
            env->GetLongField(nativeObject,
                              GetFieldIDOrDie(env, clazzSurfaceControl, "mNativeObject", "J"));
    ALOGV("nativeInitSurface transactionObj = %lld, nativeObject = %lld, width = %d, "
          "height = %d",
          (long long)transactionObj, (long long)mNativeObject, width, height);
    auto ctrl = reinterpret_cast<SurfaceControl*>(mNativeObject);
    // SetInitField need jobject
    env->SetIntField(nativeObject, GetFieldIDOrDie(env, clazzSurfaceControl, "mWidth", "I"), width);
    env->SetIntField(nativeObject, GetFieldIDOrDie(env, clazzSurfaceControl, "mHeight", "I"),
                     height);
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    DisplayAnimation::initSurface(transaction, ctrl, width, height);
}

static void nativeChangeSurfaceControlWithDistance(JNIEnv* env, jclass clazz, jlong transactionObj,
                                                   jlong nativeObject, jfloatArray distance,
                                                   jfloatArray scale, jfloatArray skew) {
    ALOGV("nativeChangeSurfaceControl transactionObj = %lld, nativeObject = %lld, "
          "distance = %f, scale = %f",
          (long long)transactionObj, (long long)nativeObject,
          *env->GetFloatArrayElements(distance, NULL), *env->GetFloatArrayElements(scale, NULL));
    auto ctrl = reinterpret_cast<SurfaceControl*>(nativeObject);
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    if (ctrl == nullptr || transaction == nullptr) {
        ALOGE("nullptr!nativeChangeSurfaceControl transactionObj = %lld, nativeObject = %lld,",
              (long long)transactionObj, (long long)nativeObject);
        return;
    }
    float* distanceArray = env->GetFloatArrayElements(distance, NULL);
    float* scaleArray = env->GetFloatArrayElements(scale, NULL);
    float* skewArray = env->GetFloatArrayElements(skew, NULL);
    DisplayAnimation::changeSurfaceControl(transaction, ctrl, distanceArray, scaleArray, skewArray);
}

#define FIND_CLASS(var, className)   \
    var = env->FindClass(className); \
    LOG_FATAL_IF(!var, "Unable to find class " className);

static const JNINativeMethod method_table[] = {
        {"nativePrepareSurfaceControl", "(J)Landroid/view/SurfaceControl;",
         (void*)nativePrepareSurfaceControl},
        {"nativeBindSurface", "(JJJ)V", (void*)nativeBindSurface},
        {"nativeInitSurface", "(JLandroid/view/SurfaceControl;II)V", (void*)nativeInitSurface},
        {"nativeChangeSurfaceControl", "(JJ[F[F[F)V",
         (void*)nativeChangeSurfaceControlWithDistance},
};

int register_android_server_wm_DisplayAnimation(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env,
                                       "com/android/server/wm/FingersDisplayAnimationEventListener",
                                       method_table, NELEM(method_table));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");
    (void)res; // Don't complain about unused variable in the LOG_NDEBUG case

    jclass clazz;
    FIND_CLASS(clazz, "com/android/server/wm/FingersDisplayAnimationEventListener");
    return res;
}
}; // namespace android
