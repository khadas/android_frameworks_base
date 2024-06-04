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

#define LOG_TAG "com_android_server_ebook_EbookService"

#define LOG_NDEBUG 0
#define DEBUG_EBOOK 1

#include "android_runtime/AndroidRuntime.h"
#include <nativehelper/JNIHelp.h>
#include <jni.h>
#include <utils/Log.h>
// ----------------------------------------------------------------------------

namespace android {

/*
 * Field/method IDs and class object references.
 *
 * You should not need to store the JNIEnv pointer in here.  It is
 * thread-specific and will be passed back in on every call.
 */
static struct {
    jclass      platformLibraryClass;
} gCachedState;

// ---------------------------------------------------------------------------

jint com_android_server_ebook_EbookService_init(JNIEnv *env, jclass clazz)
{
    int result = 0;
    ALOGW("%s %d", __FUNCTION__, __LINE__);
    return result;
}

// ----------------------------------------------------------------------------

static const JNINativeMethod gMethods[] = {
    { "init_native", "()I", (void *)com_android_server_ebook_EbookService_init },
};

/*
 * Do some (slow-ish) lookups now and save the results.
 *
 * Returns 0 on success.
 */
static int cacheIds(JNIEnv* env, jclass clazz)
{
    /*
     * Save the class in case we want to use it later.  Because this is a
     * reference to the Class object, we need to convert it to a JNI global
     * reference.
     */
    gCachedState.platformLibraryClass = (jclass) env->NewGlobalRef(clazz);
    if (clazz == NULL) {
        ALOGE("Can't create new global ref\n");
        return -1;
    }

    return 0;
}

/*
 * Explicitly register all methods for our class.
 *
 * While we're at it, cache some class references and method/field IDs.
 *
 * Returns 0 on success.
 */
int register_android_server_EbookService(JNIEnv* env)
{
    static const char* const kClassName = "com/android/server/ebook/EbookService";
    jclass clazz;

    /* look up the class */
    clazz = env->FindClass(kClassName);
    if (clazz == NULL) {
        ALOGE("Can't find class %s\n", kClassName);
        return -1;
    }
    ALOGI("have find class %s\n", kClassName);

    /* register all the methods */
    if (env->RegisterNatives(clazz, gMethods,
                             sizeof(gMethods) / sizeof(gMethods[0])) != JNI_OK) {
        ALOGE("Failed registering methods for %s\n", kClassName);
        return -1;
    }
    ALOGI("registering methods for %s\n", kClassName);

    /* fill out the rest of the ID cache */
    return cacheIds(env, clazz);
}
};
