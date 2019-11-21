#define LOG_TAG "RkNativeAudioSetting"

#include "android_os_Parcel.h"
#include "android_util_Binder.h"
#include "android/graphics/Bitmap.h"
#include "android/graphics/GraphicsJNI.h"
#include "core_jni_helpers.h"

#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedUtfChars.h>
#include <jni.h>
#include <memory>
#include <stdio.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>

#include <cutils/log.h>
#include <cutils/properties.h>
#include <string>
#include <map>
#include <vector>
#include <iostream>
#include <inttypes.h>
#include <sstream>

#include <linux/netlink.h>
#include <sys/socket.h>

#include <rockchip/hardware/rkaudiosetting/1.0/IRkAudioSetting.h>
#include <unordered_map>

namespace android {

using namespace rockchip::hardware::rkaudiosetting::V1_0;

using ::rockchip::hardware::rkaudiosetting::V1_0::IRkAudioSetting;
using ::rockchip::hardware::rkaudiosetting::V1_0::Status;

using android::hardware::hidl_handle;
using android::hardware::hidl_string;
using android::hardware::hidl_vec;
using android::hardware::Return;
using android::hardware::Void;

sp<IRkAudioSetting> mAudioSetting = nullptr;

///////////////////////////////////////////////////////////////////////////////////////////////
static void nativeSetSelect(JNIEnv* env, jobject obj, jint device) {
    if (mAudioSetting == nullptr) {
        mAudioSetting = IRkAudioSetting::getService();
        if (mAudioSetting != nullptr) {
            //ALOGD("get mAudioSetting point");
        } else {
            ALOGD("get mAudioSetting is NULL");
        }
    }

    if (mAudioSetting != nullptr) {
        mAudioSetting->setSelect(device);
    } else {
        ALOGD("get mAudioSetting is NULL");
    }
}

static void nativeupdataFormatForEdid(JNIEnv* env, jobject obj) {
    if (mAudioSetting == nullptr) {
        mAudioSetting = IRkAudioSetting::getService();
        if (mAudioSetting != nullptr) {
            //ALOGD("get mAudioSetting point");
        } else {
            ALOGD("get mAudioSetting is NULL");
        }
    }

    if (mAudioSetting != nullptr) {
        mAudioSetting->updataFormatForEdid();
    } else {
        ALOGD("get mAudioSetting is NULL");
    }
}

static void nativeSetFormat(JNIEnv* env, jobject obj, jint device, jint close, jstring format) {
    const char* mformat = env->GetStringUTFChars(format, NULL);
     if (mAudioSetting == nullptr) {
         mAudioSetting = IRkAudioSetting::getService();
         if (mAudioSetting != nullptr) {
            //ALOGD("get mAudioSetting point");
         } else {
             ALOGD("get mAudioSetting is NULL");
         }
    }

    if (mAudioSetting != nullptr) {
        mAudioSetting->setFormat(device, close, mformat);
    } else {
        ALOGD("get mAudioSetting is NULL");
    }
    env->ReleaseStringUTFChars(format, mformat);
}

static void nativeSetMode(JNIEnv* env, jobject obj, jint device, jint mode) {
    if (mAudioSetting == nullptr) {
        mAudioSetting = IRkAudioSetting::getService();
        if (mAudioSetting != nullptr) {
            //ALOGD("get mAudioSetting point");
        } else {
            ALOGD("get mAudioSetting is NULL");
        }
    }

    if (mAudioSetting != nullptr){
        mAudioSetting->setMode(device, mode);
    } else {
        ALOGD("get mAudioSetting is NULL");
    }
}

static jint nativeGetSelect(JNIEnv* env, jobject obj, jint device) {
    int value = 0;
    if (mAudioSetting == nullptr) {
        mAudioSetting = IRkAudioSetting::getService();
        if (mAudioSetting != nullptr) {
            //ALOGD("get mAudioSetting point");
        } else {
            ALOGD("get mAudioSetting is NULL");
        }
    }
    if (mAudioSetting != nullptr) {
        mAudioSetting->getSelect(device,
                [&](const auto& tmpResult, const auto& tmpState) {
                    if (tmpResult == Status::OK) {
                        value = tmpState;
                    }
                });
    } else{
        ALOGD("get mAudioSetting is NULL");
    }

    return static_cast<jint>(value);
}

static jint nativeGetFormat(JNIEnv* env, jobject obj, jint device, jstring format) {
    int value = 0;
    const char* mformat = env->GetStringUTFChars(format, NULL);
    if (mAudioSetting == nullptr) {
        mAudioSetting = IRkAudioSetting::getService();
        if (mAudioSetting != nullptr) {
            //ALOGD("get mAudioSetting point");
        } else {
            ALOGD("get mAudioSetting is NULL");
        }
    }

    if (mAudioSetting != nullptr) {
        mAudioSetting->getFormat(device, mformat,
                [&](const auto& tmpResult, const auto& tmpState) {
                    if (tmpResult == Status::OK) {
                        value = tmpState;
                    }
                });
    } else {
        ALOGD("get mAudioSetting is NULL");
    }

    env->ReleaseStringUTFChars(format, mformat);
    return static_cast<jint>(value);
}

static jint nativeGetMode(JNIEnv* env, jobject obj, jint device) {
    int value = 0;
    if (mAudioSetting == nullptr) {
        mAudioSetting = IRkAudioSetting::getService();
        if (mAudioSetting != nullptr) {
            //ALOGD("get mAudioSetting point");
        } else {
            ALOGD("get mAudioSetting is NULL");
        }
    }

    if (mAudioSetting != nullptr) {
        mAudioSetting->getMode(device,
                [&](const auto& tmpResult, const auto& tmpState) {
                    if (tmpResult == Status::OK) {
                        value = tmpState;
                    }
                });
    } else {
        ALOGD("get mAudioSetting is NULL");
    }

    return static_cast<jint>(value);
}

// ----------------------------------------------------------------------------
//com.android.server.AudioSetting
static const JNINativeMethod sRkAudioSettingMethods[] = {
    {"nativeGetSelect", "(I)I",
        (void*)nativeGetSelect},
    {"nativeGetMode", "(I)I",
        (void*) nativeGetMode},
    {"nativeGetFormat", "(ILjava/lang/String;)I",
        (void*) nativeGetFormat},

    {"nativeSetFormat", "(IILjava/lang/String;)V",
        (void*)nativeSetFormat},
    {"nativeSetMode", "(II)V",
        (void*)nativeSetMode},
    {"nativeSetSelect", "(I)V",
        (void*)nativeSetSelect},
    {"nativeupdataFormatForEdid", "()V",
        (void*)nativeupdataFormatForEdid},
};

#define FIND_CLASS(var, className) \
    var = env->FindClass(className); \
    LOG_FATAL_IF(! var, "Unable to find class " className);

#define GET_METHOD_ID(var, clazz, methodName, methodDescriptor) \
    var = env->GetMethodID(clazz, methodName, methodDescriptor); \
    LOG_FATAL_IF(! var, "Unable to find method " methodName);

#define GET_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
    var = env->GetFieldID(clazz, fieldName, fieldDescriptor); \
    LOG_FATAL_IF(! var, "Unable to find field " fieldName);

int register_com_android_server_audio_RkAudioSetting(JNIEnv* env)
{
    int res = jniRegisterNativeMethods(env, "com/android/server/audio/RkAudioSetting",
            sRkAudioSettingMethods, NELEM(sRkAudioSettingMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods register_com_android_server_audio_RkAudioSetting");
    (void)res; // Don't complain about unused variable in the LOG_NDEBUG case

    jclass clazz;
    FIND_CLASS(clazz, "com/android/server/audio/RkAudioSetting");

    return 0;
}
};
