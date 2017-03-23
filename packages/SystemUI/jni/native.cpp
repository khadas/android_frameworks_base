/*
* Copyright (C) 2016 The Android Open Source Project
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
#define LOG_TAG "rockchip-pip-jni native.cpp"
#include <utils/Log.h>
#include <stdio.h>
#include "jni.h"
#include <media/IMediaPlayerService.h>
#include <binder/IServiceManager.h>
#include "JNIHelp.h"
using namespace android;

static const char *classPathName = "com/android/systemui/tv/pip/MediaUtils";

/**
 * play multiplies videos?.
 */
static jboolean IsMulMediaClient(JNIEnv *env, jobject thiz) {
    sp <IBinder> binder = defaultServiceManager()->getService(String16("media.player"));
    sp <IMediaPlayerService> service = interface_cast <IMediaPlayerService> (binder);
    if (service.get() == NULL) {
        jniThrowException(env, "java/lang/RuntimeException",
                          "cannot get MediaPlayerService");
        return UNKNOWN_ERROR;
    }

    if(service->getMediaClientSize() > 1){
        return true;
    }else{
        return false;
    }
}

static JNINativeMethod methods[] = {
  {"IsMulMediaClient", "()Z", (void*)IsMulMediaClient }
};

/*
 * Register several native methods for one class.
 */
static int registerNativeMethods(JNIEnv* env, const char* className,
    JNINativeMethod* gMethods, int numMethods)
{
    jclass clazz;

    clazz = env->FindClass(className);
    if (clazz == NULL) {
        ALOGE("Native registration unable to find class '%s'", className);
        return JNI_FALSE;
    }
    if (env->RegisterNatives(clazz, gMethods, numMethods) < 0) {
        ALOGE("RegisterNatives failed for '%s'", className);
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

/*
 * Register native methods for all classes we know about.
 *
 * returns JNI_TRUE on success.
 */
static int registerNatives(JNIEnv* env)
{
  if (!registerNativeMethods(env, classPathName,
                 methods, sizeof(methods) / sizeof(methods[0]))) {
    return JNI_FALSE;
  }

  return JNI_TRUE;
}


// ----------------------------------------------------------------------------

/*
 * This is called by the VM when the shared library is first loaded.
 */

typedef union {
    JNIEnv* env;
    void* venv;
} UnionJNIEnvToVoid;

jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    UnionJNIEnvToVoid uenv;
    uenv.venv = NULL;
    jint result = -1;
    JNIEnv* env = NULL;

    ALOGI("JNI_OnLoad");

    if (vm->GetEnv(&uenv.venv, JNI_VERSION_1_4) != JNI_OK) {
        ALOGE("ERROR: GetEnv failed");
        goto bail;
    }
    env = uenv.env;

    if (registerNatives(env) != JNI_TRUE) {
        ALOGE("ERROR: registerNatives failed");
        goto bail;
    }

    result = JNI_VERSION_1_4;

bail:
    return result;
}
