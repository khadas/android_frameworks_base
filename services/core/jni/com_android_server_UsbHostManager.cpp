/*
 * Copyright (C) 2010 The Android Open Source Project
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

#define LOG_TAG "UsbHostManagerJNI"
#include "utils/Log.h"

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include "android_runtime/AndroidRuntime.h"
#include "android_runtime/Log.h"

#include <stdio.h>
#include <asm/byteorder.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <usbhost/usbhost.h>
#include <dirent.h>
#include <ctype.h>

#define MAX_DESCRIPTORS_LENGTH 4096

#define USB_DEV_DIR     "/sys/bus/usb/devices"
#define USB_DEV_PID     "idProduct"
#define USB_DEV_VID     "idVendor"
#define USB_DEV_NUM     "devnum"

enum value_type {
    ATTR_VAL_HEX = 0,
    ATTR_VAL_DEC = 1,
};

namespace android
{

static struct parcel_file_descriptor_offsets_t
{
    jclass mClass;
    jmethodID mConstructor;
} gParcelFileDescriptorOffsets;

static jmethodID method_usbDeviceAdded;
static jmethodID method_usbDeviceRemoved;

static void checkAndClearExceptionFromCallback(JNIEnv* env, const char* methodName) {
    if (env->ExceptionCheck()) {
        ALOGE("An exception was thrown by callback '%s'.", methodName);
        LOGE_EX(env);
        env->ExceptionClear();
    }
}

static int str_to_digit(const char *string, enum value_type type)
{
    int value = 0;
    char buf[100];

    strncpy(buf, string, sizeof(buf));
    if (type == ATTR_VAL_HEX) {
        while(*string) {
            if(!isxdigit(*string++))
                return -1;
        }
        if (sscanf(buf, "%x", &value) < 1)
            return -1;
    } else {
        while(*string) {
            if(!isdigit(*string++))
                return -1;
        }
        value = atoi(buf);
    }

    return value;
}

static char* usb_device_get_attr_str(const char *devpath, const char *attr)
{
    char buf[100];
    char node_name[50];
    FILE *fp;
    char str_len;

    snprintf(node_name, sizeof(node_name), "%s/%s", devpath, attr);
    fp = fopen(node_name, "r");
    if (fp == NULL) {
        ALOGE("Failed to open %s", node_name);
        return NULL;
    }

    if (!fgets(buf, sizeof(buf), fp)) {
        ALOGE("Failed to get  %s", node_name);
        fclose(fp);
        return NULL;
    }

    str_len = strlen(buf);
    if (buf[str_len-1] == '\n')
        buf[str_len-1] = '\0';

    fclose(fp);

    return strdup(buf);
}

static int usb_device_get_attr_val(const char *devpath, const char *attr,
				   enum value_type type)
{
    char *attr_str;
    int value = 0;

    attr_str = usb_device_get_attr_str(devpath, attr);
    if (!attr_str)
        return -1;

    value = str_to_digit(attr_str, type);
    if (value < 0)
        ALOGE("\"%s\" is illegal string!", attr_str);

    free(attr_str);

    return value;
}

static char* usb_device_get_attr_str_from_name(const char *devname,
					       const char *attr)
{
    int id;
    int bus;
    int devnum;
    char portname[5];
    char subportname[5];
    char portdir[50];
    DIR *devdir;
    struct dirent *de;

    id = usb_device_get_unique_id_from_name(devname);
    bus = id / 1000;
    devnum = id % 1000;

    snprintf(subportname, sizeof(subportname), "%d-1", bus);
    snprintf(portname, sizeof(portname), "usb%d", bus);

    devdir = opendir(USB_DEV_DIR);
    if(devdir == 0) {
        ALOGE ("%s does not exist!", USB_DEV_DIR);
        return NULL;
    }

    while ((de = readdir(devdir))) {
        if ((!strncmp(de->d_name, subportname, 3) &&
             !strchr(de->d_name, ':')) ||
            !strncmp(de->d_name, portname, 4)) {

            snprintf(portdir, sizeof(portdir), USB_DEV_DIR "/%s", de->d_name);
            if (devnum == usb_device_get_attr_val(portdir, USB_DEV_NUM,
                                                  ATTR_VAL_DEC)) {
                closedir(devdir);
                return usb_device_get_attr_str(portdir, attr);
            }
        }
    }
    closedir(devdir);
    ALOGE ("%s do not have usb device!", devname);

    return NULL;
}

static int usb_device_get_attr_val_from_name(const char *devname,
					     const char *attr,
					     enum value_type type)
{
    char *attr_str;
    int value = 0;

    attr_str = usb_device_get_attr_str_from_name(devname, attr);
    if (!attr_str)
        return -1;

    value = str_to_digit(attr_str, type);
    if (value < 0)
        ALOGE("\"%s\" is illegal string!", attr_str);
    free(attr_str);

    return value;
}

static int usb_device_is_rockusb(const char *devname)
{
    int idProduct = 0;
    int idVendor = 0;

    idProduct = usb_device_get_attr_val_from_name(devname, USB_DEV_PID, ATTR_VAL_HEX);
    idVendor = usb_device_get_attr_val_from_name(devname, USB_DEV_VID, ATTR_VAL_HEX);


    if (idVendor < 0 || idProduct < 0) {
        ALOGE ("Get USB device idVendor or idProduct failed");
        return 0;
    }

    if (idVendor == 0x2207 && idProduct == 0x180a) {
        ALOGE ("USB device (idVendor:0x%4x idProduct:0x%4x) is NPU rockusb", idVendor, idProduct);
        return 1;
    }

    return 0;
}

static int usb_device_added(const char *devAddress, void* clientData) {
    if (usb_device_is_rockusb(devAddress)) {
        ALOGE("%s is Rockchip rockusb, don't open it!", devAddress);
        return 0;
    }

    struct usb_device *device = usb_device_open(devAddress);
    if (!device) {
        ALOGE("usb_device_open failed\n");
        return 0;
    }

    const usb_device_descriptor* deviceDesc = usb_device_get_device_descriptor(device);
    int classID = deviceDesc->bDeviceClass;
    int subClassID = deviceDesc->bDeviceSubClass;

    // get the raw descriptors
    int numBytes = usb_device_get_descriptors_length(device);
    if (numBytes > 0) {
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        jobject thiz = (jobject)clientData;
        jstring deviceAddress = env->NewStringUTF(devAddress);

        jbyteArray descriptorsArray = env->NewByteArray(numBytes);
        const jbyte* rawDescriptors = (const jbyte*)usb_device_get_raw_descriptors(device);
        env->SetByteArrayRegion(descriptorsArray, 0, numBytes, rawDescriptors);

        env->CallBooleanMethod(thiz, method_usbDeviceAdded,
                deviceAddress, classID, subClassID, descriptorsArray);

        env->DeleteLocalRef(descriptorsArray);
        env->DeleteLocalRef(deviceAddress);

        checkAndClearExceptionFromCallback(env, __FUNCTION__);
    } else {
        // TODO return an error code here?
        ALOGE("error reading descriptors\n");
    }

    usb_device_close(device);

    return 0;
}

static int usb_device_removed(const char *devAddress, void* clientData) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jobject thiz = (jobject)clientData;

    jstring deviceAddress = env->NewStringUTF(devAddress);
    env->CallVoidMethod(thiz, method_usbDeviceRemoved, deviceAddress);
    env->DeleteLocalRef(deviceAddress);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return 0;
}

static void android_server_UsbHostManager_monitorUsbHostBus(JNIEnv* /* env */, jobject thiz)
{
    struct usb_host_context* context = usb_host_init();
    if (!context) {
        ALOGE("usb_host_init failed");
        return;
    }
    // this will never return so it is safe to pass thiz directly
    usb_host_run(context, usb_device_added, usb_device_removed, NULL, (void *)thiz);
}

static jobject android_server_UsbHostManager_openDevice(JNIEnv *env, jobject /* thiz */,
                                                        jstring deviceAddress)
{
    const char *deviceAddressStr = env->GetStringUTFChars(deviceAddress, NULL);
    struct usb_device* device = usb_device_open(deviceAddressStr);
    env->ReleaseStringUTFChars(deviceAddress, deviceAddressStr);

    if (!device)
        return NULL;

    int fd = usb_device_get_fd(device);
    if (fd < 0) {
        usb_device_close(device);
        return NULL;
    }
    int newFD = dup(fd);
    usb_device_close(device);

    jobject fileDescriptor = jniCreateFileDescriptor(env, newFD);
    if (fileDescriptor == NULL) {
        return NULL;
    }
    return env->NewObject(gParcelFileDescriptorOffsets.mClass,
        gParcelFileDescriptorOffsets.mConstructor, fileDescriptor);
}

static const JNINativeMethod method_table[] = {
    { "monitorUsbHostBus", "()V", (void*)android_server_UsbHostManager_monitorUsbHostBus },
    { "nativeOpenDevice",  "(Ljava/lang/String;)Landroid/os/ParcelFileDescriptor;",
                                  (void*)android_server_UsbHostManager_openDevice },
};

int register_android_server_UsbHostManager(JNIEnv *env)
{
    jclass clazz = env->FindClass("com/android/server/usb/UsbHostManager");
    if (clazz == NULL) {
        ALOGE("Can't find com/android/server/usb/UsbHostManager");
        return -1;
    }
    method_usbDeviceAdded =
            env->GetMethodID(clazz, "usbDeviceAdded", "(Ljava/lang/String;II[B)Z");
    if (method_usbDeviceAdded == NULL) {
        ALOGE("Can't find beginUsbDeviceAdded");
        return -1;
    }
    method_usbDeviceRemoved = env->GetMethodID(clazz, "usbDeviceRemoved",
            "(Ljava/lang/String;)V");
    if (method_usbDeviceRemoved == NULL) {
        ALOGE("Can't find usbDeviceRemoved");
        return -1;
    }

    clazz = env->FindClass("android/os/ParcelFileDescriptor");
    LOG_FATAL_IF(clazz == NULL, "Unable to find class android.os.ParcelFileDescriptor");
    gParcelFileDescriptorOffsets.mClass = (jclass) env->NewGlobalRef(clazz);
    gParcelFileDescriptorOffsets.mConstructor = env->GetMethodID(clazz, "<init>",
            "(Ljava/io/FileDescriptor;)V");
    LOG_FATAL_IF(gParcelFileDescriptorOffsets.mConstructor == NULL,
                 "Unable to find constructor for android.os.ParcelFileDescriptor");

    return jniRegisterNativeMethods(env, "com/android/server/usb/UsbHostManager",
            method_table, NELEM(method_table));
}

};
