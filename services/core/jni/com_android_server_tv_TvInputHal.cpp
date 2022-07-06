/*
 * Copyright 2014 The Android Open Source Project
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

#define LOG_TAG "TvInputHal"

#define LOG_NDEBUG 0

#include "android_os_MessageQueue.h"
#include "android_runtime/AndroidRuntime.h"
#include "android_runtime/android_view_Surface.h"
#include <nativehelper/JNIHelp.h>
#include "jni.h"

#include <android/hardware/tv/input/1.0/ITvInputCallback.h>
#include <android/hardware/tv/input/1.0/ITvInput.h>
#include <android/hardware/tv/input/1.0/types.h>
#include <gui/Surface.h>
#include <utils/Errors.h>
#include <utils/KeyedVector.h>
#include <utils/Log.h>
#include <utils/Looper.h>
#include <utils/NativeHandle.h>
#include <hardware/tv_input.h>
#include <hardware/gralloc_rockchip.h>
#include <cutils/properties.h>
#define LOGV LOGD
using ::android::hardware::audio::common::V2_0::AudioDevice;
using ::android::hardware::tv::input::V1_0::ITvInput;
using ::android::hardware::tv::input::V1_0::ITvInputCallback;
using ::android::hardware::tv::input::V1_0::Result;
using ::android::hardware::tv::input::V1_0::TvInputDeviceInfo;
using ::android::hardware::tv::input::V1_0::TvInputEvent;
using ::android::hardware::tv::input::V1_0::TvInputEventType;
using ::android::hardware::tv::input::V1_0::TvInputType;
using ::android::hardware::tv::input::V1_0::TvStreamConfig;
using ::android::hardware::tv::input::V1_0::PreviewRequest;
using ::android::hardware::tv::input::V1_0::PrivAppCmdInfo;
using ::android::hardware::Return;
using ::android::hardware::Void;
using ::android::hardware::hidl_vec;
using ::android::hardware::hidl_string;

namespace android {

static struct {
    jmethodID deviceAvailable;
    jmethodID deviceUnavailable;
    jmethodID streamConfigsChanged;
    jmethodID firstFrameCaptured;
    jmethodID privCmdToApp;
} gTvInputHalClassInfo;

static struct {
    jclass clazz;
} gTvStreamConfigClassInfo;

static struct {
    jclass clazz;

    jmethodID constructor;
    jmethodID streamId;
    jmethodID type;
    jmethodID maxWidth;
    jmethodID maxHeight;
    jmethodID generation;
    jmethodID build;
} gTvStreamConfigBuilderClassInfo;

static struct {
    jclass clazz;

    jmethodID constructor;
    jmethodID deviceId;
    jmethodID type;
    jmethodID hdmiPortId;
    jmethodID cableConnectionStatus;
    jmethodID audioType;
    jmethodID audioAddress;
    jmethodID build;
} gTvInputHardwareInfoBuilderClassInfo;

enum {
    BUFF_STATUS_QUEUED,
    BUFF_STATUS_DEQUEUED,
    BUFF_STATUS_QUEUED_FAILED,
    BUFF_STATUS_DEQUEUED_FAILED,
};

typedef struct tvhal_preview_buff {
    uint64_t buffId;
    int bufferFenceFd;
    int buffStatus;
    sp<ANativeWindowBuffer_t> anwbPtr;
    sp<GraphicBuffer> mGraphicBuffer;
} tvhal_preview_buff_t;

////////////////////////////////////////////////////////////////////////////////


////////////////////////////////////////////////////////////////////////////////

class JTvInputHal {
public:
    ~JTvInputHal();

    static JTvInputHal* createInstance(JNIEnv* env, jobject thiz, const sp<Looper>& looper);

    int privCmdFromApp(const PrivAppCmdInfo& cmdInfo);
    int addOrUpdateStream(int deviceId, int streamId, const sp<Surface>& surface);
    int removeStream(int deviceId, int streamId);
    const hidl_vec<TvStreamConfig> getStreamConfigs(int deviceId);

    void onDeviceAvailable(const TvInputDeviceInfo& info);
    void onDeviceUnavailable(int deviceId);
    void onStreamConfigurationsChanged(int deviceId, int cableConnectionStatus);
    void onCaptured(int deviceId, int streamId, uint64_t buffId, int buffSeq, buffer_handle_t handle, bool succeeded);
    void onPrivCmdToApp(int deviceId, const PrivAppCmdInfo& cmdInfo);

    std::vector<hardware::tv::input::V1_0::PreviewBuffer> mPreviewBuffer;
private:
    class BufferProducerThread : public Thread {
    public:
        BufferProducerThread(JTvInputHal* hal, sp<ITvInput> tvinput, int deviceId);

        int initPreviewBuffPoll(const sp<Surface>& surface, const tv_stream_config_t* stream);
        void setSurface(const sp<Surface>& surface);
        void onCaptured(uint64_t buffId, int buffSeq, buffer_handle_t handle, bool succeeded);
        void shutdown();

    private:
        Mutex mLock;
        Condition mCondition;
        sp<Surface> mSurface;
        JTvInputHal* mParentHal;
        sp<ITvInput> mTvInputPtr;
        int mDeviceId;
        tv_stream_config_t mStream;
        std::vector<tvhal_preview_buff_t> mTvHalPreviewBuff;
        sp<ANativeWindowBuffer_t> mBuffer;
        enum {
            CAPTURING,
            CAPTURED,
            RELEASED,
        } mBufferState;
        uint32_t mSeq;
        uint64_t mCurrBuffId;
        bool mFirstCaptured;
        bool mShutdown;

        virtual status_t readyToRun();
        virtual bool threadLoop();
        status_t configPreviewBuff();
        void surfaceBuffDestory();
    };
    // Connection between a surface and a stream.
    class Connection {
    public:
        Connection() {}

        sp<Surface> mSurface;
        tv_stream_type_t mStreamType;

        // Only valid when mStreamType == TV_STREAM_TYPE_INDEPENDENT_VIDEO_SOURCE
        sp<NativeHandle> mSourceHandle;
        // Only valid when mStreamType == TV_STREAM_TYPE_BUFFER_PRODUCER
        sp<BufferProducerThread> mThread;
    };

    class NotifyHandler : public MessageHandler {
    public:
        NotifyHandler(JTvInputHal* hal, const TvInputEvent& event);

        virtual void handleMessage(const Message& message);

    private:
        TvInputEvent mEvent;
        JTvInputHal* mHal;
    };

    class TvInputCallback : public ITvInputCallback {
    public:
        explicit TvInputCallback(JTvInputHal* hal);
        Return<void> notify(const TvInputEvent& event) override;
    private:
        JTvInputHal* mHal;
    };

    JTvInputHal(JNIEnv* env, jobject thiz, sp<ITvInput> tvInput, const sp<Looper>& looper);

    Mutex mLock;
    Mutex mStreamLock;
    jweak mThiz;
    sp<Looper> mLooper;

    KeyedVector<int, KeyedVector<int, Connection> > mConnections;

    sp<ITvInput> mTvInput;
    sp<ITvInputCallback> mTvInputCallback;
};

JTvInputHal::JTvInputHal(JNIEnv* env, jobject thiz, sp<ITvInput> tvInput,
        const sp<Looper>& looper) {
    mThiz = env->NewWeakGlobalRef(thiz);
    mTvInput = tvInput;
    mLooper = looper;
    mTvInputCallback = new TvInputCallback(this);
    mTvInput->setCallback(mTvInputCallback);
}

JTvInputHal::~JTvInputHal() {
    mTvInput->setCallback(nullptr);
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->DeleteWeakGlobalRef(mThiz);
    mThiz = NULL;
}

JTvInputHal* JTvInputHal::createInstance(JNIEnv* env, jobject thiz, const sp<Looper>& looper) {
    // TODO(b/31632518)
    sp<ITvInput> tvInput = ITvInput::getService();
    if (tvInput == nullptr) {
        ALOGE("Couldn't get tv.input service.");
        return nullptr;
    }

    return new JTvInputHal(env, thiz, tvInput, looper);
}

int JTvInputHal::privCmdFromApp(const PrivAppCmdInfo& cmdInfo) {
    if (mTvInput->privCmdFromApp(cmdInfo) != Result::OK) {
        ALOGE("Couldn't %s", __FUNCTION__);
        return BAD_VALUE;
    }
    ALOGD("%s end.", __FUNCTION__);
    return NO_ERROR;
}

int JTvInputHal::addOrUpdateStream(int deviceId, int streamId, const sp<Surface>& surface) {
    ALOGW("%s deviceId=%d, streamId=%d", __FUNCTION__, deviceId, streamId);

    Mutex::Autolock autoLock(&mStreamLock);
    KeyedVector<int, Connection>& connections = mConnections.editValueFor(deviceId);
    if (connections.indexOfKey(streamId) < 0) {
        connections.add(streamId, Connection());
    }
    Connection& connection = connections.editValueFor(streamId);
    if (connection.mSurface == surface) {
        // Nothing to do
        return NO_ERROR;
    }
    //wgh test
    char prop_value[PROPERTY_VALUE_MAX] = {0};
    property_get("vendor.tvinput.buff_type", prop_value, "0");

    connection.mStreamType = ((int)atoi(prop_value) == 1) ? TV_STREAM_TYPE_BUFFER_PRODUCER : TV_STREAM_TYPE_INDEPENDENT_VIDEO_SOURCE;
    // Clear the surface in the connection.
    if (connection.mSurface != NULL) {
        if (connection.mStreamType == TV_STREAM_TYPE_INDEPENDENT_VIDEO_SOURCE) {
            if (Surface::isValid(connection.mSurface)) {
                connection.mSurface->setSidebandStream(NULL);
            }
        }
        connection.mSurface.clear();
    }
    int32_t extInfo = 2;
    bool bSidebandFlow = connection.mStreamType == TV_STREAM_TYPE_INDEPENDENT_VIDEO_SOURCE ? true : false;
    tv_stream_config_t streamConfig;
    streamConfig.device_id = deviceId;
    streamConfig.stream_id = streamId;
    if (!bSidebandFlow) {
        streamConfig.type = TV_STREAM_TYPE_BUFFER_PRODUCER;
    } else {
        extInfo = 1;
        streamConfig.type = TV_STREAM_TYPE_INDEPENDENT_VIDEO_SOURCE;
    }
    if (connection.mSourceHandle == NULL) {
        // Need to configure stream
        Result result = Result::UNKNOWN;
        hidl_vec<TvStreamConfig> list;
        mTvInput->getStreamConfigurations(deviceId,
                [&result, &list](Result res, hidl_vec<TvStreamConfig> configs) {
                    result = res;
                    if (res == Result::OK) {
                        list = configs;
                    }
                });
        if (result != Result::OK) {
            ALOGE("Couldn't get stream configs for device id:%d result:%d", deviceId, result);
            return UNKNOWN_ERROR;
        }
        int configIndex = -1;
        for (size_t i = 0; i < list.size(); ++i) {
            if (list[i].streamId == streamId) {
                configIndex = i;
                break;
            }
        }
        if (configIndex == -1) {
            ALOGE("Cannot find a config with given stream ID: %d", streamId);
            return BAD_VALUE;
        }

        Result infores = mTvInput->setPreviewInfo(deviceId, streamId, 0, 0, 0, 0, extInfo);
        if (infores != Result::OK) {
            ALOGE("initPreviewBuffPoll setPreviewInfo failed");
            return BAD_VALUE;
        }
        if (!bSidebandFlow) {
            streamConfig.width = list[configIndex].width;
            streamConfig.height = list[configIndex].height;
            streamConfig.usage = list[configIndex].usage;
            streamConfig.format = list[configIndex].format;//HAL_PIXEL_FORMAT_YCrCb_NV12;
            streamConfig.buffCount = list[configIndex].buffCount;
            ALOGD("stream config info: width=%d, height=%d, format=%d, usage=%" PRIu64", buffCount=%d", streamConfig.width, streamConfig.height, streamConfig.format, streamConfig.usage, streamConfig.buffCount);
            if (connection.mThread == NULL) {
                connection.mThread = new BufferProducerThread(this, mTvInput, deviceId);
            }
            int buffSize = connection.mThread->initPreviewBuffPoll(surface, &streamConfig);
            if (buffSize != list[configIndex].buffCount) {
                ALOGE("initPreviewBuffPoll size failed");
                return BAD_VALUE;
            }
        }

        result = Result::UNKNOWN;
        const native_handle_t* sidebandStream;
        mTvInput->openStream(deviceId, streamId, connection.mStreamType,
                [&result, &sidebandStream](Result res, const native_handle_t* handle) {
                    result = res;
                    if (res == Result::OK) {
                        if (handle) {
                            sidebandStream = native_handle_clone(handle);
                        }
                        result = Result::OK;
                    }
                });
        if (result != Result::OK && bSidebandFlow) {
            ALOGE("Couldn't open stream. device id:%d stream id:%d result:%d", deviceId, streamId,
                    result);
            return UNKNOWN_ERROR;
        } else if (bSidebandFlow) {
            connection.mSourceHandle = NativeHandle::create((native_handle_t*)sidebandStream, true);
        }
    }
    connection.mSurface = surface;
    if (connection.mSurface != nullptr && bSidebandFlow) {
        connection.mSurface->setSidebandStream(connection.mSourceHandle);
    } else if (connection.mSurface != nullptr) {
        ALOGD("start TvInputBufferProducerThread");
        connection.mThread->run("TvInputBufferProducerThread");
        connection.mThread->setSurface(surface);
    }
    return NO_ERROR;
}

int JTvInputHal::removeStream(int deviceId, int streamId) {
    Mutex::Autolock autoLock(&mStreamLock);
    KeyedVector<int, Connection>& connections = mConnections.editValueFor(deviceId);
    if (connections.indexOfKey(streamId) < 0) {
        return BAD_VALUE;
    }
    Connection& connection = connections.editValueFor(streamId);
    if (connection.mSurface == NULL) {
        // Nothing to do
        return NO_ERROR;
    }
    if (Surface::isValid(connection.mSurface)) {
        if (connection.mStreamType == TV_STREAM_TYPE_INDEPENDENT_VIDEO_SOURCE) {
            connection.mSurface->setSidebandStream(NULL);
        }
    }
    if (mTvInput->closeStream(deviceId, streamId) != Result::OK) {
        ALOGE("Couldn't close stream. device id:%d stream id:%d", deviceId, streamId);
        return BAD_VALUE;
    }
    if (connection.mThread != NULL) {
        connection.mThread->shutdown();
        connection.mThread->requestExit();
        connection.mThread->requestExitAndWait();
    }
    connection.mSurface.clear();
    connection.mSurface = NULL;
    if (connection.mSourceHandle != NULL) {
        connection.mSourceHandle.clear();
    }
    ALOGD("%s end.", __FUNCTION__);
    return NO_ERROR;
}

const hidl_vec<TvStreamConfig> JTvInputHal::getStreamConfigs(int deviceId) {
    Result result = Result::UNKNOWN;
    hidl_vec<TvStreamConfig> list;
    mTvInput->getStreamConfigurations(deviceId,
            [&result, &list](Result res, hidl_vec<TvStreamConfig> configs) {
                result = res;
                if (res == Result::OK) {
                    list = configs;
                }
            });
    if (result != Result::OK) {
        ALOGE("Couldn't get stream configs for device id:%d result:%d", deviceId, result);
    }
    return list;
}

void JTvInputHal::onDeviceAvailable(const TvInputDeviceInfo& info) {
    ALOGW("onDeviceAvailable %d", info.deviceId);

    {
        Mutex::Autolock autoLock(&mLock);
        mConnections.add(info.deviceId, KeyedVector<int, Connection>());
    }
    JNIEnv* env = AndroidRuntime::getJNIEnv();

    jobject builder = env->NewObject(
            gTvInputHardwareInfoBuilderClassInfo.clazz,
            gTvInputHardwareInfoBuilderClassInfo.constructor);
    env->CallObjectMethod(
            builder, gTvInputHardwareInfoBuilderClassInfo.deviceId, info.deviceId);
    env->CallObjectMethod(
            builder, gTvInputHardwareInfoBuilderClassInfo.type, info.type);
    if (info.type == TvInputType::HDMI) {
        env->CallObjectMethod(
                builder, gTvInputHardwareInfoBuilderClassInfo.hdmiPortId, info.portId);
    }
    env->CallObjectMethod(
            builder, gTvInputHardwareInfoBuilderClassInfo.cableConnectionStatus,
            info.cableConnectionStatus);
    env->CallObjectMethod(
            builder, gTvInputHardwareInfoBuilderClassInfo.audioType, info.audioType);
    if (info.audioType != AudioDevice::NONE) {
        uint8_t buffer[info.audioAddress.size() + 1];
        memcpy(buffer, info.audioAddress.data(), info.audioAddress.size());
        buffer[info.audioAddress.size()] = '\0';
        jstring audioAddress = env->NewStringUTF(reinterpret_cast<const char *>(buffer));
        env->CallObjectMethod(
                builder, gTvInputHardwareInfoBuilderClassInfo.audioAddress, audioAddress);
        env->DeleteLocalRef(audioAddress);
    }

    jobject infoObject = env->CallObjectMethod(builder, gTvInputHardwareInfoBuilderClassInfo.build);

    env->CallVoidMethod(
            mThiz,
            gTvInputHalClassInfo.deviceAvailable,
            infoObject);

    env->DeleteLocalRef(builder);
    env->DeleteLocalRef(infoObject);
}

void JTvInputHal::onDeviceUnavailable(int deviceId) {
    ALOGW("onDeviceUnavailable deviceId=%d", deviceId);

    {
        Mutex::Autolock autoLock(&mLock);
        KeyedVector<int, Connection>& connections = mConnections.editValueFor(deviceId);
        for (size_t i = 0; i < connections.size(); ++i) {
            removeStream(deviceId, connections.keyAt(i));
        }
        connections.clear();
        mConnections.removeItem(deviceId);
    }
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->CallVoidMethod(
            mThiz,
            gTvInputHalClassInfo.deviceUnavailable,
            deviceId);
}

void JTvInputHal::onStreamConfigurationsChanged(int deviceId, int cableConnectionStatus) {
    {
        Mutex::Autolock autoLock(&mLock);
        KeyedVector<int, Connection>& connections = mConnections.editValueFor(deviceId);
        for (size_t i = 0; i < connections.size(); ++i) {
            removeStream(deviceId, connections.keyAt(i));
        }
        connections.clear();
    }
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->CallVoidMethod(mThiz, gTvInputHalClassInfo.streamConfigsChanged, deviceId,
                        cableConnectionStatus);
}

void JTvInputHal::onCaptured(int deviceId, int streamId, uint64_t buffId, int buffSeq, buffer_handle_t handle, bool succeeded) {
    {
        Mutex::Autolock autoLock(&mLock);
        KeyedVector<int, Connection>& connections = mConnections.editValueFor(deviceId);
        Connection& connection = connections.editValueFor(streamId);
        if (connection.mThread == NULL) {
            ALOGE("capture thread not existing.");
            return;
        }
        connection.mThread->onCaptured(buffId, buffSeq, handle, succeeded);
    }
    // if (seq == 0) {
    //     JNIEnv* env = AndroidRuntime::getJNIEnv();
    //     env->CallVoidMethod(
    //             mThiz,
    //             gTvInputHalClassInfo.firstFrameCaptured,
    //             deviceId,
    //             streamId);
    // }
}

void JTvInputHal::onPrivCmdToApp(int deviceId, const PrivAppCmdInfo& cmdInfo) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();

    jclass bundleClass = env->FindClass("android/os/Bundle");
    jmethodID initMID = env->GetMethodID(bundleClass, "<init>", "()V");
    jobject jBundleObj = env->NewObject(bundleClass, initMID);

    //TODO
    /*jmethodID putObjectMID = env->GetMethodID(bundleClass, "putObject", "(Ljava/lang/String;Ljava/lang/Object;)V");
    if (cmdInfo.data && !cmdInfo.data.empty()) {
        for (int i=0; i<cmdInfo.data.size(); i++) {
            env->CallVoidMethod(jBundleObj, putObjectMID, cmdInfo.data[i].key, cmdInfo.data[i].value);
        }
    }*/
    //std::vector<hardware::tv::input::V1_0::PrivAppCmdBundle> cmdData;
    //cmdData.clear();
    env->CallVoidMethod(mThiz, gTvInputHalClassInfo.privCmdToApp, deviceId,
            env->NewStringUTF(cmdInfo.action.c_str()), jBundleObj);
}

JTvInputHal::NotifyHandler::NotifyHandler(JTvInputHal* hal, const TvInputEvent& event) {
    mHal = hal;
    mEvent = event;
}

void JTvInputHal::NotifyHandler::handleMessage(const Message& message) {
    ALOGV("%s in, mEvent.type = %d", __FUNCTION__, mEvent.type);
    switch (mEvent.type) {
        case TvInputEventType::DEVICE_AVAILABLE: {
            mHal->onDeviceAvailable(mEvent.deviceInfo);
        } break;
        case TvInputEventType::DEVICE_UNAVAILABLE: {
            mHal->onDeviceUnavailable(mEvent.deviceInfo.deviceId);
        } break;
        case TvInputEventType::STREAM_CONFIGURATIONS_CHANGED: {
            int cableConnectionStatus = static_cast<int>(mEvent.deviceInfo.cableConnectionStatus);
            mHal->onStreamConfigurationsChanged(mEvent.deviceInfo.deviceId, cableConnectionStatus);
        } break;
       case TvInputEventType::STREAM_CAPTURE_SUCCEEDED:
        {
            mHal->onCaptured(mEvent.deviceInfo.deviceId,
                             mEvent.deviceInfo.streamId,
                             mEvent.capture_result.buffId,
                             mEvent.capture_result.buffSeq,
                             mEvent.capture_result.buffer,
                             true /* succeeded */);

        } break;
        case TvInputEventType::STREAM_CAPTURE_FAILED:
        {
            mHal->onCaptured(mEvent.deviceInfo.deviceId,
                             mEvent.deviceInfo.streamId,
                             mEvent.capture_result.buffId,
                             mEvent.capture_result.buffSeq,
                             mEvent.capture_result.buffer,
                             false  /*failed*/ );
        } break;
        case TvInputEventType::PRIV_CMD_TO_APP:
        {
            mHal->onPrivCmdToApp(mEvent.deviceInfo.deviceId,
                               mEvent.priv_app_cmd);
        } break;
        default:
            ALOGE("Unrecognizable event");
    }
}

JTvInputHal::BufferProducerThread::BufferProducerThread(
        JTvInputHal* hal, sp<ITvInput> tvinput, int deviceId)
    : Thread(false),
      mParentHal(hal),
      mTvInputPtr(tvinput){
}

status_t JTvInputHal::BufferProducerThread::readyToRun() {
      mBuffer = NULL;
      mBufferState = RELEASED;
      mSeq = 0;
      mCurrBuffId = 0;
      mFirstCaptured = true;
      mShutdown = false;
      return OK;
}

status_t JTvInputHal::BufferProducerThread::configPreviewBuff() {
    sp<ANativeWindow> anw(mSurface);
    ALOGV("%s in", __FUNCTION__);
    status_t err = native_window_api_connect(anw.get(), NATIVE_WINDOW_API_CAMERA);
    if (err != NO_ERROR) {
        ALOGE("%s native_window_api_connect failed. err=%d", __FUNCTION__, err);
        return err;
    } else {
        ALOGV("native_window_api_connect succeed");
    }

    int consumerUsage = 0;
    err = anw.get()->query(anw.get(), NATIVE_WINDOW_CONSUMER_USAGE_BITS, &consumerUsage);
    if (err != NO_ERROR) {
        ALOGW("failed to get consumer usage bits. ignoring");
        err = NO_ERROR;
    }
    ALOGD("consumerUsage=%d, usage_project=%d", consumerUsage, (consumerUsage & GRALLOC_USAGE_PROTECTED));

    err = native_window_set_usage(anw.get(), (mStream.usage|consumerUsage));
    if (err != NO_ERROR) {
        ALOGE("%s native_window_set_usage failed.", __FUNCTION__);
        return err;
    }

    // dequeueBuffer cannot time out
    mSurface->setDequeueTimeout(-1);

    err = native_window_set_buffers_dimensions(
            anw.get(), mStream.width, mStream.height);
    if (err != NO_ERROR) {
        ALOGE("%s native_window_set_buffers_dimensions failed.", __FUNCTION__);
        return err;
    }
    err = native_window_set_buffers_format(anw.get(), mStream.format);
    if (err != NO_ERROR) {
        ALOGE("%s native_window_set_buffers_format failed.", __FUNCTION__);
        return err;
    }
    err = native_window_set_scaling_mode(anw.get(), NATIVE_WINDOW_SCALING_MODE_SCALE_TO_WINDOW);
    if (err != NO_ERROR) {
        ALOGE("%s native_window_set_scaling_mode failed.", __FUNCTION__);
        return err;
    }
    int transform = 0;
    err = native_window_set_buffers_transform(anw.get(), transform);
    if (err != NO_ERROR) {
        ALOGE("%s native_window_set_buffers_transform failed.", __FUNCTION__);
        return err;
    }

    int minUndequeuedBufs = 0;
    err = anw.get()->query(anw.get(),
            NATIVE_WINDOW_MIN_UNDEQUEUED_BUFFERS, &minUndequeuedBufs);
    if (err != NO_ERROR) {
        ALOGE("error pushing blank frames: MIN_UNDEQUEUED_BUFFERS query "
                "failed: %s (%d)", strerror(-err), -err);
        return err;
    }
    ALOGD("minUndequeuedBufs = %d", minUndequeuedBufs);
    err = native_window_set_buffer_count(anw.get(), mStream.buffCount);//todo wgh
    if (err != NO_ERROR) {
        ALOGE("%s native_window_set_buffer_count failed.", __FUNCTION__);
        return err;
    }
    return NO_ERROR;
}

int JTvInputHal::BufferProducerThread::initPreviewBuffPoll(const sp<Surface>& surface, const tv_stream_config_t* stream)
{
    Mutex::Autolock autoLock(&mLock);
    ALOGD("%s in", __FUNCTION__);
    status_t err = NO_ERROR;

    mSurface = surface;
    mDeviceId = stream->device_id;
    memcpy(&mStream, stream, sizeof(mStream));

    if(configPreviewBuff() != NO_ERROR) {
        ALOGE("failed configPreviewBuff, please check your config.");
        return -1;
    }

    sp<ANativeWindow> anw(mSurface);
    mTvHalPreviewBuff.resize(mStream.buffCount);
    mParentHal->mPreviewBuffer.resize(mStream.buffCount);
    static_cast<Surface*>(anw.get())->getIGraphicBufferProducer()->allowAllocation(true);
    for (int i=0; i<mStream.buffCount; i++) {
        ANativeWindowBuffer_t* buffer = NULL;
        err = native_window_dequeue_buffer_and_wait(anw.get(), &buffer);
        // err = anw->dequeueBuffer(anw.get(), &buffer, &mTvHalPreviewBuff[i].bufferFenceFd);
        if (err != NO_ERROR) {
            ALOGE("error %d while dequeueing buffer to surface", err);
            return -1;
        }
        sp<GraphicBuffer> gbPtr(GraphicBuffer::from(buffer));
        ALOGD("%s handle = %p, buffId = %" PRIu64, __FUNCTION__, buffer->handle, gbPtr->getId());
        mTvHalPreviewBuff[i].buffId = gbPtr->getId();
        mTvHalPreviewBuff[i].buffStatus = BUFF_STATUS_DEQUEUED;
        mTvHalPreviewBuff[i].mGraphicBuffer = gbPtr;
        mTvHalPreviewBuff[i].anwbPtr = buffer;
        mParentHal->mPreviewBuffer[i].bufferId = gbPtr->getId();
        mParentHal->mPreviewBuffer[i].buffer = buffer->handle;
        mTvInputPtr->setSinglePreviewBuffer(mParentHal->mPreviewBuffer[i]);
        buffer = NULL;
        gbPtr = NULL;
    }
    static_cast<Surface*>(anw.get())->getIGraphicBufferProducer()->allowAllocation(false);
    return mTvHalPreviewBuff.size();
}

void JTvInputHal::BufferProducerThread::setSurface(const sp<Surface>& surface) {
    Mutex::Autolock autoLock(&mLock);
    ALOGD("%s in", __FUNCTION__);
    mCondition.broadcast();
}

void JTvInputHal::BufferProducerThread::surfaceBuffDestory() {
    status_t err = NO_ERROR;
    sp<ANativeWindow> anw(mSurface);
    if (!mTvHalPreviewBuff.empty()) {
        for (int i=0; i< mTvHalPreviewBuff.size(); i++) {
            ALOGD("buff[%d], bufferId[%" PRIu64"] buffStatus = %d", i, mTvHalPreviewBuff[i].buffId, mTvHalPreviewBuff[i].buffStatus);
            if (mTvHalPreviewBuff[i].buffStatus == BUFF_STATUS_DEQUEUED) {
                err = anw->cancelBuffer(anw.get(), mTvHalPreviewBuff[i].mGraphicBuffer.get(), -1);
                if (err != NO_ERROR) {
                    ALOGE("%s cancelBuffer failed.", __FUNCTION__);
                }
            }
            mTvHalPreviewBuff[i].mGraphicBuffer.clear();
            mTvHalPreviewBuff[i].mGraphicBuffer = NULL;
            mTvHalPreviewBuff[i].anwbPtr.clear();
            mTvHalPreviewBuff[i].anwbPtr = NULL;

            mParentHal->mPreviewBuffer[i].buffer = NULL;
        }
        mTvHalPreviewBuff.clear();
        mParentHal->mPreviewBuffer.clear();
    }
    mBuffer.clear();
    mBuffer = NULL;
    err = native_window_api_disconnect(anw.get(), NATIVE_WINDOW_API_CAMERA);
    if (err != NO_ERROR) {
        ALOGE("%s native_window_api_disconnect failed.", __FUNCTION__);
    } else {
        ALOGD("%s native_window_api_disconnect succeeful.", __FUNCTION__);
    }
    mSurface.clear();
    mSurface = NULL;
}

void JTvInputHal::BufferProducerThread::onCaptured(uint64_t buffId, int buffSeq, buffer_handle_t handle, bool succeeded) {
    status_t err = NO_ERROR;
    if (mShutdown) {
        return;
    }
    sp<ANativeWindow> anw(mSurface);
    if (buffSeq != mSeq) {
        ALOGW("Incorrect sequence value: expected %u actual %u", mSeq, buffSeq);
        mSeq = buffSeq;
    }
    // mCondition.broadcast();
    if (succeeded && anw != NULL) {
        ALOGV("%s buffSeq=%d, buffId = %" PRIu64, __FUNCTION__, buffSeq, buffId);
        for (int i=0; i<mTvHalPreviewBuff.size(); i++) {
            if (buffId == mTvHalPreviewBuff[i].buffId) {
                err = anw->queueBuffer(anw.get(), mTvHalPreviewBuff[i].mGraphicBuffer.get(), -1);
                if (err != NO_ERROR) {
                    ALOGE("error %d while queueing buffer to surface", err);
                    return;
                } else {
                    ALOGV("queueBuffer succeed mFirstCaptured=%d buff i=%d id = %" PRIu64,
                        mFirstCaptured, i, mTvHalPreviewBuff[i].buffId);
                    mTvHalPreviewBuff[i].buffStatus = BUFF_STATUS_QUEUED;
                    mCurrBuffId = buffId;
                    if (!mFirstCaptured) {
                        mBufferState = RELEASED;
                        mCondition.broadcast();
                    } else {
                        mFirstCaptured = false;
                        mBufferState = CAPTURING;
                    }
                    break;
                }
            }
        }
    }
}

void JTvInputHal::BufferProducerThread::shutdown() {
    Mutex::Autolock autoLock(&mLock);
    mShutdown = true;
    surfaceBuffDestory();
}

bool JTvInputHal::BufferProducerThread::threadLoop() {
    Mutex::Autolock autoLock(&mLock);
    if (mShutdown) {
        return false;
    }
    status_t err = NO_ERROR;
    if (mSurface == NULL) {
        err = mCondition.waitRelative(mLock, s2ns(1));
        // It's OK to time out here.
        if (err != NO_ERROR && err != TIMED_OUT) {
            ALOGE("error %d while wating for non-null surface to be set", err);
            return false;
        }
        return true;
    }
    sp<ANativeWindow> anw(mSurface);
    if ((mBuffer == NULL || mBufferState == RELEASED) && !mShutdown && anw != NULL) {
        ANativeWindowBuffer_t* buffer = NULL;
        if (mCurrBuffId != 0) {
            int index = -1;
            uint64_t dequeueBuffId = 0;
            //err = native_window_dequeue_buffer_and_wait(anw.get(), &buffer);
            int fenceFd = -1;
            err = anw->dequeueBuffer(anw.get(), &buffer, &fenceFd);
            if (err != NO_ERROR) {
                ALOGE("error %d while dequeueing buffer to surface", err);
                return false;
            } else {
                dequeueBuffId = GraphicBuffer::from(buffer)->getId();
                ALOGV("%s native_window_dequeue_buffer_and_wait succeeded buffId=%" PRIu64, __FUNCTION__, dequeueBuffId);
            }
            for (int i=0; i<mTvHalPreviewBuff.size(); i++) {
                ALOGV("i=%d, buffId=%" PRIu64 ", buffhandle=%p", i, mTvHalPreviewBuff[i].buffId, mTvHalPreviewBuff[i].mGraphicBuffer->getNativeBuffer());
                if (dequeueBuffId == mTvHalPreviewBuff[i].buffId) {
                    index = i;
                    mBuffer = mTvHalPreviewBuff.at(i).anwbPtr;
                    mCurrBuffId = mTvHalPreviewBuff.at(i).buffId;
                    mTvHalPreviewBuff[i].buffStatus = BUFF_STATUS_DEQUEUED;
                    ALOGV("find the %d right buff %" PRIu64,i, mCurrBuffId);
                    break;
                }
            }
            sp<Fence> hwcFence(new Fence(fenceFd));
            //ALOGE("wait fence start %" PRIu64 , dequeueBuffId);
            //err = hwcFence->waitForever("dequeueBuffer_tv");
            err = hwcFence->wait(100);
            //ALOGE("wait fence end %" PRIu64 , dequeueBuffId);
            if (err != OK) {
                ALOGE("Fence wait err fenceFd %d err=%d, dequeueBuffId %" PRIx64, fenceFd, err, dequeueBuffId);
                //anw->cancelBuffer(anw.get(), buffer, fenceFd);
                //return false;
            }
            if (index == -1) {
                ALOGE("ERROR hapened, after dequeueing, there is no right buff id");
                return false;
            }
        } else {
            mBuffer = mTvHalPreviewBuff.front().anwbPtr;
            mCurrBuffId = mTvHalPreviewBuff.front().buffId;
        }
        if (mBuffer == NULL) {
            ALOGE("no buff dequeue");
            return false;
        }
        ALOGV("before request: capture hanele=%p mCurrBuffId=%" PRIu64, mBuffer.get()->handle, mCurrBuffId);
        mBufferState = CAPTURING;
        mTvInputPtr->requestCapture(mDeviceId, mStream.stream_id, mCurrBuffId, mBuffer.get()->handle, ++mSeq);
        buffer = NULL;
    }

    return true;
}

JTvInputHal::TvInputCallback::TvInputCallback(JTvInputHal* hal) {
    mHal = hal;
}

Return<void> JTvInputHal::TvInputCallback::notify(const TvInputEvent& event) {
    mHal->mLooper->sendMessage(new NotifyHandler(mHal, event), static_cast<int>(event.type));
    return Void();
}

////////////////////////////////////////////////////////////////////////////////

static jlong nativeOpen(JNIEnv* env, jobject thiz, jobject messageQueueObj) {
    sp<MessageQueue> messageQueue =
            android_os_MessageQueue_getMessageQueue(env, messageQueueObj);
    return (jlong)JTvInputHal::createInstance(env, thiz, messageQueue->getLooper());
}

static int nativePrivCmdFromApp(JNIEnv* env, jclass clazz, jlong ptr, jstring action, jobject data) {
    JTvInputHal* tvInputHal = (JTvInputHal*)ptr;
    const char* maction = env->GetStringUTFChars(action, NULL);

    jclass bundleClass = env->FindClass("android/os/Bundle");
    jmethodID keySetMID = env->GetMethodID(bundleClass, "keySet", "()Ljava/util/Set;");
    jobject ketSetObj = env->CallObjectMethod(data, keySetMID);
    jmethodID bundleStrMID = env->GetMethodID(bundleClass, "getString", "(Ljava/lang/String;)Ljava/lang/String;");

    jclass setClass = env->FindClass("java/util/Set");
    jmethodID iteratorMID = env->GetMethodID(setClass, "iterator", "()Ljava/util/Iterator;");
    jobject iteratorObj = env->CallObjectMethod(ketSetObj, iteratorMID);
    jclass iteratorClass = env->FindClass("java/util/Iterator");
    jmethodID hasNextMID = env->GetMethodID(iteratorClass, "hasNext", "()Z");
    jmethodID nextMID = env->GetMethodID(iteratorClass, "next", "()Ljava/lang/Object;");

    hardware::tv::input::V1_0::PrivAppCmdInfo cmdInfo;
    std::vector<hardware::tv::input::V1_0::PrivAppCmdBundle> cmdData;
    cmdData.clear();
    while (env->CallBooleanMethod(iteratorObj, hasNextMID)) {
        jstring keyJS = (jstring)env->CallObjectMethod(iteratorObj, nextMID);
        const char *keyStr = env->GetStringUTFChars(keyJS, NULL);
        jstring valueJS = (jstring)env->CallObjectMethod(data, bundleStrMID, keyJS);
        const char *valueStr = env->GetStringUTFChars(valueJS, NULL);
        hardware::tv::input::V1_0::PrivAppCmdBundle bundle;
        bundle.key = keyStr;
        bundle.value = valueStr;
        cmdData.push_back(bundle);

        env->ReleaseStringUTFChars(keyJS, keyStr);
        env->DeleteLocalRef(keyJS);
        env->ReleaseStringUTFChars(valueJS, valueStr);
        env->DeleteLocalRef(valueJS);
    }
    env->DeleteLocalRef(bundleClass);
    env->DeleteLocalRef(ketSetObj);
    env->DeleteLocalRef(setClass);
    env->DeleteLocalRef(iteratorObj);
    env->DeleteLocalRef(iteratorClass);

    cmdInfo.action = maction;
    cmdInfo.data = cmdData;
    tvInputHal->privCmdFromApp(cmdInfo);
    env->ReleaseStringUTFChars(action, maction);
    return 1;
}

static int nativeAddOrUpdateStream(JNIEnv* env, jclass clazz,
        jlong ptr, jint deviceId, jint streamId, jobject jsurface) {
    JTvInputHal* tvInputHal = (JTvInputHal*)ptr;
    if (!jsurface) {
        return BAD_VALUE;
    }
    sp<Surface> surface(android_view_Surface_getSurface(env, jsurface));
    if (!Surface::isValid(surface)) {
        return BAD_VALUE;
    }
    return tvInputHal->addOrUpdateStream(deviceId, streamId, surface);
}

static int nativeRemoveStream(JNIEnv* env, jclass clazz,
        jlong ptr, jint deviceId, jint streamId) {
    JTvInputHal* tvInputHal = (JTvInputHal*)ptr;
    return tvInputHal->removeStream(deviceId, streamId);
}

static jobjectArray nativeGetStreamConfigs(JNIEnv* env, jclass clazz,
        jlong ptr, jint deviceId, jint generation) {
    JTvInputHal* tvInputHal = (JTvInputHal*)ptr;
    const hidl_vec<TvStreamConfig> configs = tvInputHal->getStreamConfigs(deviceId);

    jobjectArray result = env->NewObjectArray(configs.size(), gTvStreamConfigClassInfo.clazz, NULL);
    for (size_t i = 0; i < configs.size(); ++i) {
        jobject builder = env->NewObject(
                gTvStreamConfigBuilderClassInfo.clazz,
                gTvStreamConfigBuilderClassInfo.constructor);
        env->CallObjectMethod(
                builder, gTvStreamConfigBuilderClassInfo.streamId, configs[i].streamId);
        env->CallObjectMethod(
                builder, gTvStreamConfigBuilderClassInfo.type,
                        TV_STREAM_TYPE_INDEPENDENT_VIDEO_SOURCE);
        env->CallObjectMethod(
                builder, gTvStreamConfigBuilderClassInfo.maxWidth, configs[i].maxVideoWidth);
        env->CallObjectMethod(
                builder, gTvStreamConfigBuilderClassInfo.maxHeight, configs[i].maxVideoHeight);
        env->CallObjectMethod(
                builder, gTvStreamConfigBuilderClassInfo.generation, generation);

        jobject config = env->CallObjectMethod(builder, gTvStreamConfigBuilderClassInfo.build);

        env->SetObjectArrayElement(result, i, config);

        env->DeleteLocalRef(config);
        env->DeleteLocalRef(builder);
    }
    return result;
}

static void nativeClose(JNIEnv* env, jclass clazz, jlong ptr) {
    JTvInputHal* tvInputHal = (JTvInputHal*)ptr;
    delete tvInputHal;
}

static const JNINativeMethod gTvInputHalMethods[] = {
    /* name, signature, funcPtr */
    { "nativeOpen", "(Landroid/os/MessageQueue;)J",
            (void*) nativeOpen },
    { "nativeAddOrUpdateStream", "(JIILandroid/view/Surface;)I",
            (void*) nativeAddOrUpdateStream },
    { "nativeRemoveStream", "(JII)I",
            (void*) nativeRemoveStream },
    { "nativeGetStreamConfigs", "(JII)[Landroid/media/tv/TvStreamConfig;",
            (void*) nativeGetStreamConfigs },
    { "nativeClose", "(J)V",
            (void*) nativeClose },
    { "nativePrivCmdFromApp", "(JLjava/lang/String;Landroid/os/Bundle;)I",
            (void*) nativePrivCmdFromApp },

};

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! (var), "Unable to find class " className)

#define GET_METHOD_ID(var, clazz, methodName, fieldDescriptor) \
        var = env->GetMethodID(clazz, methodName, fieldDescriptor); \
        LOG_FATAL_IF(! (var), "Unable to find method" methodName)

int register_android_server_tv_TvInputHal(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "com/android/server/tv/TvInputHal",
            gTvInputHalMethods, NELEM(gTvInputHalMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");
    (void)res; // Don't complain about unused variable in the LOG_NDEBUG case

    jclass clazz;
    FIND_CLASS(clazz, "com/android/server/tv/TvInputHal");

    GET_METHOD_ID(
            gTvInputHalClassInfo.deviceAvailable, clazz,
            "deviceAvailableFromNative", "(Landroid/media/tv/TvInputHardwareInfo;)V");
    GET_METHOD_ID(
            gTvInputHalClassInfo.deviceUnavailable, clazz, "deviceUnavailableFromNative", "(I)V");
    GET_METHOD_ID(gTvInputHalClassInfo.streamConfigsChanged, clazz,
                  "streamConfigsChangedFromNative", "(II)V");
    GET_METHOD_ID(
            gTvInputHalClassInfo.firstFrameCaptured, clazz,
            "firstFrameCapturedFromNative", "(II)V");
    GET_METHOD_ID(
                gTvInputHalClassInfo.privCmdToApp, clazz,
                "privCmdToAppFromNative", "(ILjava/lang/String;Landroid/os/Bundle;)V");

    FIND_CLASS(gTvStreamConfigClassInfo.clazz, "android/media/tv/TvStreamConfig");
    gTvStreamConfigClassInfo.clazz = jclass(env->NewGlobalRef(gTvStreamConfigClassInfo.clazz));

    FIND_CLASS(gTvStreamConfigBuilderClassInfo.clazz, "android/media/tv/TvStreamConfig$Builder");
    gTvStreamConfigBuilderClassInfo.clazz =
            jclass(env->NewGlobalRef(gTvStreamConfigBuilderClassInfo.clazz));

    GET_METHOD_ID(
            gTvStreamConfigBuilderClassInfo.constructor,
            gTvStreamConfigBuilderClassInfo.clazz,
            "<init>", "()V");
    GET_METHOD_ID(
            gTvStreamConfigBuilderClassInfo.streamId,
            gTvStreamConfigBuilderClassInfo.clazz,
            "streamId", "(I)Landroid/media/tv/TvStreamConfig$Builder;");
    GET_METHOD_ID(
            gTvStreamConfigBuilderClassInfo.type,
            gTvStreamConfigBuilderClassInfo.clazz,
            "type", "(I)Landroid/media/tv/TvStreamConfig$Builder;");
    GET_METHOD_ID(
            gTvStreamConfigBuilderClassInfo.maxWidth,
            gTvStreamConfigBuilderClassInfo.clazz,
            "maxWidth", "(I)Landroid/media/tv/TvStreamConfig$Builder;");
    GET_METHOD_ID(
            gTvStreamConfigBuilderClassInfo.maxHeight,
            gTvStreamConfigBuilderClassInfo.clazz,
            "maxHeight", "(I)Landroid/media/tv/TvStreamConfig$Builder;");
    GET_METHOD_ID(
            gTvStreamConfigBuilderClassInfo.generation,
            gTvStreamConfigBuilderClassInfo.clazz,
            "generation", "(I)Landroid/media/tv/TvStreamConfig$Builder;");
    GET_METHOD_ID(
            gTvStreamConfigBuilderClassInfo.build,
            gTvStreamConfigBuilderClassInfo.clazz,
            "build", "()Landroid/media/tv/TvStreamConfig;");

    FIND_CLASS(gTvInputHardwareInfoBuilderClassInfo.clazz,
            "android/media/tv/TvInputHardwareInfo$Builder");
    gTvInputHardwareInfoBuilderClassInfo.clazz =
            jclass(env->NewGlobalRef(gTvInputHardwareInfoBuilderClassInfo.clazz));

    GET_METHOD_ID(
            gTvInputHardwareInfoBuilderClassInfo.constructor,
            gTvInputHardwareInfoBuilderClassInfo.clazz,
            "<init>", "()V");
    GET_METHOD_ID(
            gTvInputHardwareInfoBuilderClassInfo.deviceId,
            gTvInputHardwareInfoBuilderClassInfo.clazz,
            "deviceId", "(I)Landroid/media/tv/TvInputHardwareInfo$Builder;");
    GET_METHOD_ID(
            gTvInputHardwareInfoBuilderClassInfo.type,
            gTvInputHardwareInfoBuilderClassInfo.clazz,
            "type", "(I)Landroid/media/tv/TvInputHardwareInfo$Builder;");
    GET_METHOD_ID(
            gTvInputHardwareInfoBuilderClassInfo.hdmiPortId,
            gTvInputHardwareInfoBuilderClassInfo.clazz,
            "hdmiPortId", "(I)Landroid/media/tv/TvInputHardwareInfo$Builder;");
    GET_METHOD_ID(
            gTvInputHardwareInfoBuilderClassInfo.cableConnectionStatus,
            gTvInputHardwareInfoBuilderClassInfo.clazz,
            "cableConnectionStatus", "(I)Landroid/media/tv/TvInputHardwareInfo$Builder;");
    GET_METHOD_ID(
            gTvInputHardwareInfoBuilderClassInfo.audioType,
            gTvInputHardwareInfoBuilderClassInfo.clazz,
            "audioType", "(I)Landroid/media/tv/TvInputHardwareInfo$Builder;");
    GET_METHOD_ID(
            gTvInputHardwareInfoBuilderClassInfo.audioAddress,
            gTvInputHardwareInfoBuilderClassInfo.clazz,
            "audioAddress", "(Ljava/lang/String;)Landroid/media/tv/TvInputHardwareInfo$Builder;");
    GET_METHOD_ID(
            gTvInputHardwareInfoBuilderClassInfo.build,
            gTvInputHardwareInfoBuilderClassInfo.clazz,
            "build", "()Landroid/media/tv/TvInputHardwareInfo;");

    return 0;
}

} /* namespace android */
