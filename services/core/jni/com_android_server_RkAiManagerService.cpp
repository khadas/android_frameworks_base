/*
 * Copyright 2024 Rockchip Electronics S.LSI Co. LTD
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

#define LOG_TAG "RkAiManagerNative"

#define LOG_NDEBUG 0

#include "android_runtime/AndroidRuntime.h"
#include <nativehelper/JNIHelp.h>
#include <jni.h>
#include <utils/Log.h>
#include <media/AudioSystem.h>
#include <android/media/BnRkAiCallback.h>
namespace android {

////////////////////////////////////////////////////////////////////////////////
static struct {
    jmethodID asrBufferFromNative;
} gClassInfo;

class JRkAiAsrCallback : public media::BnRkAiCallback {
public:
    JRkAiAsrCallback() {
        ALOGW("%s", __FUNCTION__);
        env = AndroidRuntime::getJNIEnv();
        clazz = env->FindClass("com/android/server/RkAiManagerService");
    }

    virtual ~JRkAiAsrCallback() {
        ALOGW("%s", __FUNCTION__);
        env->DeleteLocalRef(clazz);
        //del env;//do it?
        env = nullptr;
    }

    binder::Status onAsrBuffer(const std::vector<int>& buffer, int32_t len) override {
        //ALOGW("%s %d", __FUNCTION__, len);
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        jshortArray jArray = env->NewShortArray(len);
        jshort *jBuffer = new jshort[len];

        if (jArray != NULL && jBuffer != NULL) {
            for (int i = 0; i < len; ++i) {
                jBuffer[i] = static_cast<jshort>(buffer[i]);
            }
            env->SetShortArrayRegion(jArray, 0, len, jBuffer);
            jint jLen = static_cast<jint>(len);
            env->CallStaticVoidMethod(clazz, gClassInfo.asrBufferFromNative, jArray, jLen);
            env->DeleteLocalRef(jArray);
        }

        delete[] jBuffer;
        return binder::Status::ok();
    }

private:
    JNIEnv* env = nullptr;
    jclass clazz;

};

////////////////////////////////////////////////////////////////////////////////
sp<JRkAiAsrCallback> mRkAiAsrCallback;

static int nativeInit(JNIEnv* env, jobject obj, jboolean supportAsr) {
    ALOGW("%s supportAsr=%d", __FUNCTION__, supportAsr);
    int ret = 1;
    if (supportAsr) {
        //mRkAiAsrCallback = new JRkAiAsrCallback(env);
        mRkAiAsrCallback = sp<JRkAiAsrCallback>::make();
        AudioSystem::setRkAiCallback(mRkAiAsrCallback);
    }

    return static_cast<jint>(ret);
}

// ----------------------------------------------------------------------------

static const JNINativeMethod sServiceMethods[] = {
    /* name, signature, funcPtr */
    { "nativeInit", "(Z)I",
            (void*) nativeInit },
};

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! (var), "Unable to find class " className)

#define GET_METHOD_ID(var, clazz, methodName, fieldDescriptor) \
        var = env->GetMethodID(clazz, methodName, fieldDescriptor); \
        LOG_FATAL_IF(! (var), "Unable to find method" methodName)

#define GET_STATIC_METHOD_ID(var, clazz, methodName, fieldDescriptor) \
        var = env->GetStaticMethodID(clazz, methodName, fieldDescriptor); \
        LOG_FATAL_IF(! (var), "Unable to find static method" methodName)

int register_com_android_server_RkAiManagerService(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "com/android/server/RkAiManagerService",
            sServiceMethods, NELEM(sServiceMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");
    (void)res; // Don't complain about unused variable in the LOG_NDEBUG case

    jclass clazz;
    FIND_CLASS(clazz, "com/android/server/RkAiManagerService");

    GET_STATIC_METHOD_ID(
            gClassInfo.asrBufferFromNative, clazz,
            "asrBufferFromNative", "([SI)V");

    return 0;
}

} /* namespace android */
