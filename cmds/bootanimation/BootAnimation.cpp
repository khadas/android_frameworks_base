/*
 * Copyright (C) 2007 The Android Open Source Project
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

#define LOG_NDEBUG 0
#define LOG_TAG "BootAnimation"

#include <vector>

#include <stdint.h>
#include <inttypes.h>
#include <sys/inotify.h>
#include <sys/poll.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <math.h>
#include <fcntl.h>
#include <utils/misc.h>
#include <signal.h>
#include <time.h>

#include <cutils/atomic.h>
#include <cutils/properties.h>

#include <androidfw/AssetManager.h>
#include <binder/IPCThreadState.h>
#include <utils/Errors.h>
#include <utils/Log.h>
#include <utils/SystemClock.h>

#include <android-base/properties.h>

#include <ui/DisplayConfig.h>
#include <ui/PixelFormat.h>
#include <ui/Rect.h>
#include <ui/Region.h>

#include <gui/ISurfaceComposer.h>
#include <gui/DisplayEventReceiver.h>
#include <gui/Surface.h>
#include <gui/SurfaceComposerClient.h>

// TODO: Fix Skia.
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wunused-parameter"
#include <SkBitmap.h>
#include <SkImage.h>
#include <SkStream.h>
#pragma GCC diagnostic pop

#include <GLES/gl.h>
#include <GLES/glext.h>
#include <EGL/eglext.h>

#include "BootAnimation.h"

#define ANIM_PATH_MAX 255
#define STR(x)   #x
#define STRTO(x) STR(x)

namespace android {

static const char OEM_BOOTANIMATION_FILE[] = "/oem/media/bootanimation.zip";
static const char PRODUCT_BOOTANIMATION_DARK_FILE[] = "/product/media/bootanimation-dark.zip";
static const char PRODUCT_BOOTANIMATION_FILE[] = "/product/media/bootanimation.zip";
static const char SYSTEM_BOOTANIMATION_FILE[] = "/system/media/bootanimation.zip";
static const char APEX_BOOTANIMATION_FILE[] = "/apex/com.android.bootanimation/etc/bootanimation.zip";
static const char PRODUCT_ENCRYPTED_BOOTANIMATION_FILE[] = "/product/media/bootanimation-encrypted.zip";
static const char SYSTEM_ENCRYPTED_BOOTANIMATION_FILE[] = "/system/media/bootanimation-encrypted.zip";
static const char OEM_SHUTDOWNANIMATION_FILE[] = "/oem/media/shutdownanimation.zip";
static const char PRODUCT_SHUTDOWNANIMATION_FILE[] = "/product/media/shutdownanimation.zip";
static const char SYSTEM_SHUTDOWNANIMATION_FILE[] = "/system/media/shutdownanimation.zip";

static constexpr const char* PRODUCT_USERSPACE_REBOOT_ANIMATION_FILE = "/product/media/userspace-reboot.zip";
static constexpr const char* OEM_USERSPACE_REBOOT_ANIMATION_FILE = "/oem/media/userspace-reboot.zip";
static constexpr const char* SYSTEM_USERSPACE_REBOOT_ANIMATION_FILE = "/system/media/userspace-reboot.zip";

static const char SYSTEM_DATA_DIR_PATH[] = "/data/system";
static const char SYSTEM_TIME_DIR_NAME[] = "time";
static const char SYSTEM_TIME_DIR_PATH[] = "/data/system/time";
static const char CLOCK_FONT_ASSET[] = "images/clock_font.png";
static const char CLOCK_FONT_ZIP_NAME[] = "clock_font.png";
static const char LAST_TIME_CHANGED_FILE_NAME[] = "last_time_change";
static const char LAST_TIME_CHANGED_FILE_PATH[] = "/data/system/time/last_time_change";
static const char ACCURATE_TIME_FLAG_FILE_NAME[] = "time_is_accurate";
static const char ACCURATE_TIME_FLAG_FILE_PATH[] = "/data/system/time/time_is_accurate";
static const char TIME_FORMAT_12_HOUR_FLAG_FILE_PATH[] = "/data/system/time/time_format_12_hour";
// Java timestamp format. Don't show the clock if the date is before 2000-01-01 00:00:00.
static const long long ACCURATE_TIME_EPOCH = 946684800000;
static constexpr char FONT_BEGIN_CHAR = ' ';
static constexpr char FONT_END_CHAR = '~' + 1;
static constexpr size_t FONT_NUM_CHARS = FONT_END_CHAR - FONT_BEGIN_CHAR + 1;
static constexpr size_t FONT_NUM_COLS = 16;
static constexpr size_t FONT_NUM_ROWS = FONT_NUM_CHARS / FONT_NUM_COLS;
static const int TEXT_CENTER_VALUE = INT_MAX;
static const int TEXT_MISSING_VALUE = INT_MIN;
static const char EXIT_PROP_NAME[] = "service.bootanim.exit";
static const char DISPLAYS_PROP_NAME[] = "persist.service.bootanim.displays";
static const int ANIM_ENTRY_NAME_MAX = ANIM_PATH_MAX + 1;
static constexpr size_t TEXT_POS_LEN_MAX = 16;

// ---------------------------------------------------------------------------
static const char DEVICE_INPUT_PATH[] = "/dev/input";
static const char BOOT_VIDEO_SYS_PROP_NAME[] = "persist.sys.bootvideo";
static const char BOOT_VIDEO_VENDOR_PROP_NAME[] = "persist.vendor.media.bootvideo";
static const char BOOT_VIDEO_RUNNING_STATUS_PROP_NAME[] = "service.bootvideo.exit";
static const char BOOT_VIDEO_DATA_FILE[] = "/data/bootvideo";
static const char BOOT_VIDEO_VENDOR_FILE[] = "/vendor/etc/bootvideo";
static const char BOOT_VIDEO_VOL_SYSTEM_FILE[] = "/system/media/bootvideo.zip";
static const char BOOT_VIDEO_VOL_VENDOR_FILE[] = "/vendor/etc/bootvideo.zip";
static const char BOOT_VIDEO_OMX_DISPLAY_MODE_PROP_NAME[] = "media.omx.display_mode";
static const char BOOT_VIDEO_FRAME_COUNT[] = "/sys/module/amvideo/parameters/new_frame_count";

static const int HIDE_VOL_UI_WAIT_SLEEP_MS = 4000;
static const int BOOT_VIDEO_VOL_MAX = 100;
static const int BOOT_VIDEO_VOL_MUTE = 101;

static const int LAYER_VIDEO = 0x30000000;
static const int LAYER_VIDEO_VOL_UI_SHOW = 0x40000000;
static const int LAYER_VIDEO_VOL_UI_HIDE = 0x20000000;

static const int CONFIG_BOOTANIM = 0;
static const int CONFIG_BOOTANIM_BOOTVIDEO = 1;
static const int CONFIG_BOOTVIDEO_BOOTANIM = 2;
static const int CONFIG_BOOTVIDEO = 3;

static struct pollfd *ufds;
static char **device_names;
static int nfds;
struct label {
    const char *name;
    int value;
};

#define LABEL(constant) { #constant, constant }
#define LABEL_END { NULL, -1 }

#define UP      0
#define DOWN    1
#define REPEAT  2
#define MUTE    3

static struct label key_labels[] = {
    LABEL(KEY_VOLUMEUP),
    LABEL(KEY_VOLUMEDOWN),
    LABEL(KEY_MUTE),
    LABEL_END,
};

static struct label key_value_labels[] = {
    LABEL(UP),
    LABEL(DOWN),
    LABEL(REPEAT),
    LABEL_END,
};

int get_video_frame_count(const char *path) {
    int fd = -1;
    int ret = 0;
    char bcmd[16] = {0};

    fd = open(path, O_RDONLY);
    if (fd >= 0) {
        read(fd, bcmd, sizeof(bcmd));
        ret = strtol(bcmd, NULL, 16);
        close(fd);
        return ret > 0;
    } else {
        SLOGE("%s, open %s failed", __FUNCTION__, path);
    }

    return 0;
}

void property_set_int(const char* key, int defaultValue) {
    char property_val[PROPERTY_VALUE_MAX] = {0};

    snprintf(property_val, sizeof(property_val), "%d", defaultValue);
    property_set(key, property_val);
}

int property_get_int(const char* key, int defaultValue) {
    char buf[PROPERTY_VALUE_MAX] = {
            '\0',
    };

    if (property_get(key, buf, "") > 0) {
        return atoi(buf);
    }
    return defaultValue;
}

int getVol() {
    int vol = property_get_int(BOOT_VIDEO_SYS_PROP_NAME, -1);
    vol = vol % 1000;
    return vol;
}

void BootAnimation::bootVideoSetVolume(int status) {
    float vol = 0.5f;

    if (status == UP || status == DOWN) {
        if (mMute) {
           mVol = getVol();
           mMute = false;
        }
    }

    switch (status) {
        case UP:
            if (mVol < BOOT_VIDEO_VOL_MAX) {
                mVol++;
            }
            break;
        case DOWN:
            if (mVol > 0) {
                mVol--;
            }
            break;
        case MUTE:
            mMute = !mMute;
            if (mMute) {
                property_set_int(BOOT_VIDEO_SYS_PROP_NAME, (mVol + 1000));
                mVol = BOOT_VIDEO_VOL_MUTE;
            } else {
                mVol = getVol();
            }
            break;
    }

    if (mMediaPlayer != NULL) {
        vol = mMute ? 0.00f : (1.00f * mVol / BOOT_VIDEO_VOL_MAX);
        mMediaPlayer->setVolume(vol, vol);
    }

    if (!mMute) {
        property_set_int(BOOT_VIDEO_SYS_PROP_NAME, mVol);
    }
    SLOGD("%s, Volume %d, mVol=%d, vol=%f, mMute=%d", __FUNCTION__, status, mVol, vol, mMute);
}

bool BootAnimation::bootVideoVolumeUI(sp<BootVideoListener> listener) {
    String8 mBootVideoVolFileName;

    static const char* bootVideoVolFiles[] =
                {BOOT_VIDEO_VOL_SYSTEM_FILE, BOOT_VIDEO_VOL_VENDOR_FILE};
    for (const char* f : bootVideoVolFiles) {
        if (access(f, R_OK) == 0) {
            mBootVideoVolFileName = f;
            break;
        }
    }
    if (mBootVideoVolFileName.isEmpty()) {
        SLOGE("%s bootvideo file check failed", __FUNCTION__);
        return false;
    }
    SLOGD("%s, mBootVideoVolFileName=%s", __FUNCTION__, mBootVideoVolFileName.string());
    Animation* animation = loadAnimation(mBootVideoVolFileName);
    if (animation == NULL)
        return false;

    // Blend required to draw time on top of animation frames.
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glShadeModel(GL_FLAT);
    glDisable(GL_DITHER);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_BLEND);

    glBindTexture(GL_TEXTURE_2D, 0);
    glEnable(GL_TEXTURE_2D);
    glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

    const size_t pcount = animation->parts.size();
    nsecs_t frameDuration = s2ns(1) / animation->fps;
    const int animationX = (mWidth - animation->width) / 2;
    const int animationY = mHeight - animation->height;
    //If you want to display the UI the first time, set index to -1
    int index = mVol;
    int64_t hideVolUIStartTime = -1;

    SLOGD("BootAnimation::playAnimation pcount=%d, animationX=%d, animationY=%d", pcount, animationX, animationY);
    SLOGD("BootAnimation::playAnimation mWidth=%d,mHeight=%d,animation->width=%d, animation->height=%d", mWidth, mHeight, animation->width,  animation->height);

    const Animation::Part& part(animation->parts[0]);
    const size_t fcount = part.frames.size();
    int displayed[fcount];
    memset(displayed, 0, sizeof(displayed));
    SLOGD("%s, fcount=%d, part.count=%d", __FUNCTION__, fcount, part.count);
    glBindTexture(GL_TEXTURE_2D, 0);
    glClearColor(
        part.backgroundColor[0],
        part.backgroundColor[1],
        part.backgroundColor[2],
        0.0f);

    SurfaceComposerClient::Transaction t;
 
    while(!listener->isPlayCompleted) {
        if (index != mVol) {
            SLOGD("%s, pcount=%d, mVol=%d, name=%s", __FUNCTION__, pcount, mVol, part.frames[mVol].name.string());
            hideVolUIStartTime = elapsedRealtime();

            const Animation::Frame& frame(part.frames[mVol]);
            if (displayed[mVol] == 0) {
                glGenTextures(1, &frame.tid);
                glBindTexture(GL_TEXTURE_2D, frame.tid);
                glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

                int w, h;
                initTexture(frame.map, &w, &h);
                displayed[mVol] = 1;
            } else {
                glBindTexture(GL_TEXTURE_2D, frame.tid);
            }

            const int xc = animationX + frame.trimX;
            const int yc = animationY + frame.trimY;

            Region clearReg(Rect(mWidth, mHeight));
            clearReg.subtractSelf(Rect(xc, yc, xc+frame.trimWidth, yc+frame.trimHeight));
            if (!clearReg.isEmpty()) {
                Region::const_iterator head(clearReg.begin());
                Region::const_iterator tail(clearReg.end());
                glEnable(GL_SCISSOR_TEST);
                while (head != tail) {
                    const Rect& r2(*head++);
                    glScissor(r2.left, mHeight - r2.bottom, r2.width(), r2.height());
                    glClear(GL_COLOR_BUFFER_BIT);
                }
                glDisable(GL_SCISSOR_TEST);
            }
            // specify the y center as ceiling((mHeight - frame.trimHeight) / 2)
            // which is equivalent to mHeight - (yc + frame.trimHeight)
            glDrawTexiOES(xc, mHeight - (yc + frame.trimHeight),
                          0, frame.trimWidth, frame.trimHeight);

            handleViewport(frameDuration);

            eglSwapBuffers(mDisplay, mSurface);
            t.setLayer(mFlingerSurfaceControl, LAYER_VIDEO_VOL_UI_SHOW)
                .apply();
            index = mVol;
        }
        usleep(20000);
        if (hideVolUIStartTime != -1 && !mMute) {
            if ((elapsedRealtime() - hideVolUIStartTime) > HIDE_VOL_UI_WAIT_SLEEP_MS){
                SLOGD("%s, hide vol ui", __FUNCTION__);
                hideVolUIStartTime = -1;
                t.setLayer(mFlingerSurfaceControl, LAYER_VIDEO_VOL_UI_HIDE)
                    .apply();
            }
        }
    }

    t.setLayer(mFlingerSurfaceControl, LAYER_VIDEO_VOL_UI_HIDE)
        .apply();

    // Free textures created for looping parts now that the animation is done.
    for (const Animation::Part& part : animation->parts) {
        if (part.count != 1) {
            const size_t fcount = part.frames.size();
            for (size_t j = 0; j < fcount; j++) {
                const Animation::Frame& frame(part.frames[j]);
                glDeleteTextures(1, &frame.tid);
            }
        }
    }

    releaseAnimation(animation);

    return true;
}

void waitForMediaPlayerService() {
    int64_t waitStartTime = elapsedRealtime();
    sp<IServiceManager> sm = defaultServiceManager();
    const String16 name("media.player");
    const int SERVICE_WAIT_SLEEP_MS = 100;
    const int LOG_PER_RETRIES = 10;
    int retry = 0;
    while (sm->checkService(name) == nullptr) {
        retry++;
        if ((retry % LOG_PER_RETRIES) == 0) {
            SLOGW("Waiting for MediaPlayerService, waited for %" PRId64 " ms",
                  elapsedRealtime() - waitStartTime);
        }
        usleep(SERVICE_WAIT_SLEEP_MS * 1000);
    };
    int64_t totalWaited = elapsedRealtime() - waitStartTime;
    if (totalWaited > SERVICE_WAIT_SLEEP_MS) {
        SLOGI("Waiting for MediaPlayerService took %" PRId64 " ms", totalWaited);
    }
}

BootAnimation::BootVideoListener::BootVideoListener() : isPlayCompleted(false) {
}

BootAnimation::BootVideoListener::~BootVideoListener() {

}

void BootAnimation::BootVideoListener::notify(int msg, int ext1, int ext2, const Parcel *obj) {
    SLOGD("BootVideoListener msg=%d,ext1=%d, ext2=%d, obj=%p", msg, ext1, ext2, obj);
    switch (msg) {
        case MEDIA_PLAYBACK_COMPLETE:
        case MEDIA_ERROR:
            isPlayCompleted = true;
            break;
        default:
            break;
    }
}

bool BootAnimation::bootVideo() {
    const float MAX_FPS = 60.0f;
    const float CHECK_DELAY = ns2us(s2ns(1) / MAX_FPS);

    String8 mBootVideoFileName;

    static const char* bootVideoFiles[] =
                {BOOT_VIDEO_DATA_FILE, BOOT_VIDEO_VENDOR_FILE};
    for (const char* f : bootVideoFiles) {
        if (access(f, R_OK) == 0) {
            mBootVideoFileName = f;
            break;
        }
    }

    if (mBootVideoFileName.isEmpty()) {
        SLOGE("%s bootvideo file check failed", __FUNCTION__);
        return false;
    }

    SLOGD("%s, mBootVideoFileName=%s", __FUNCTION__, mBootVideoFileName.string());

    const sp<IBinder> dpy = SurfaceComposerClient::getInternalDisplayToken();
    if (dpy == nullptr) {
      SLOGE("SurfaceComposer::getInternalDisplayToken failed.\n");
      return false;
    }

    DisplayConfig displayConfig;
    status_t err = SurfaceComposerClient::getActiveDisplayConfig(dpy, &displayConfig);
    if (err != NO_ERROR) {
      SLOGE("SurfaceComposer::getActiveDisplayConfig failed: %#x\n", err);
      return false;
    }
    // create the native surface
    sp<SurfaceControl> control = session()->createSurface(String8("BootVideo"),
            displayConfig.resolution.getWidth(), displayConfig.resolution.getHeight(), 
            PIXEL_FORMAT_RGB_565);

    SurfaceComposerClient::Transaction t;
    t.setLayer(control, LAYER_VIDEO)
        .apply();

    sp<Surface> s = control->getSurface();
    sp<IGraphicBufferProducer> new_st = s->getIGraphicBufferProducer();

    waitForMediaPlayerService();
    int mBootVideoFd = open(mBootVideoFileName, O_RDONLY);
    if (mBootVideoFd < 0) {
        SLOGE("%s, open %s failed", __FUNCTION__, mBootVideoFileName.string());
        return false;
    }

    mMediaPlayer = new MediaPlayer();
    sp<BootVideoListener> listener = new BootVideoListener();
    mMediaPlayer->setListener(listener);
    mMediaPlayer->reset();
    mMediaPlayer->setDataSource(mBootVideoFd, 0, 0x7ffffffffffffffL);
    mMediaPlayer->setLooping(false);
    mMediaPlayer->setVideoSurfaceTexture(new_st);
    mMediaPlayer->setAudioStreamType(AUDIO_STREAM_SYSTEM);

    SLOGD("bootVideo start mVol=%d", mVol);
    if(mMediaPlayer->prepare() == 0) {
        float vol = mMute ? 0.00f : (1.00f * mVol / BOOT_VIDEO_VOL_MAX);
        SLOGD("bootVideo vol=%f", vol);
        mMediaPlayer->setVolume(vol, vol);
        mMediaPlayer->start();

        int mDisplayMode = property_get_int(BOOT_VIDEO_OMX_DISPLAY_MODE_PROP_NAME, 0);
        //property_set(BOOT_VIDEO_OMX_DISPLAY_MODE_PROP_NAME, "1");
        SLOGD("bootVideo mDisplayMode=%d", mDisplayMode);

        if (mDisplayMode == 1) {
            while (!get_video_frame_count(BOOT_VIDEO_FRAME_COUNT) && !listener->isPlayCompleted ) {
                usleep(CHECK_DELAY);
            }
        }

        if ((mConfig == CONFIG_BOOTVIDEO_BOOTANIM) || (mConfig == CONFIG_BOOTVIDEO)) {
            if (mDisplayMode == 1) {
                t.setLayer(mFlingerSurfaceControl, LAYER_VIDEO_VOL_UI_SHOW)
                    .apply();
            }
        } else {
            t.setLayer(mFlingerSurfaceControl, LAYER_VIDEO_VOL_UI_HIDE)
                .apply();
        }

        if (mDisplayMode == 1) {
            eglSwapBuffers(mDisplay, mSurface);
        }

        mInputReaderThread = new InputReaderThread(this);
        mInputReaderThread->run("BootAnimation::InputReaderThread", PRIORITY_NORMAL);
        bool r = bootVideoVolumeUI(listener);
        SLOGD("bootVideo bootVideoVolumeUI r=%d", r);
        if (!r) {
            if (mInputReaderThread != nullptr) {
                mInputReaderThread->close_device(DEVICE_INPUT_PATH);
                mInputReaderThread->requestExit();
                mInputReaderThread = nullptr;
            }
            while ( !listener->isPlayCompleted){
                usleep(CHECK_DELAY);
            }
        }

        while ((mConfig == CONFIG_BOOTANIM_BOOTVIDEO) || (mConfig == CONFIG_BOOTVIDEO)) {
            char value[PROPERTY_VALUE_MAX];
            property_get(EXIT_PROP_NAME, value, "0");
            int exitnow = atoi(value);
            if (exitnow) {
                break;
            }
            usleep(CHECK_DELAY);
        }
    }

    if (mInputReaderThread != nullptr) {
        mInputReaderThread->close_device(DEVICE_INPUT_PATH);
        mInputReaderThread->requestExit();
        mInputReaderThread = nullptr;
    }

    mMediaPlayer->reset();
    mMediaPlayer->stop();

    listener=NULL;
    mMediaPlayer=NULL;
    close(mBootVideoFd);

    if (mConfig == CONFIG_BOOTVIDEO_BOOTANIM) {
        t.setLayer(mFlingerSurfaceControl, LAYER_VIDEO_VOL_UI_SHOW)
            .apply();
        movie();
    }

    return false;
}

static char *get_label(const struct label *labels, int value) {
    while (labels->name && value != labels->value)
        labels++;

    return (char *)labels->name;
}

int key_action_done(const int last_code, const int last_value,
        const int code, const int value) {
    SLOGD("%s, last_code=%d, code=%d, last_value=%d, value=%d", __FUNCTION__, last_code, code, last_value, value);
    if (last_code == code) {
        if (last_value == DOWN && value == UP){
            return 1;
        }else if (last_value == REPEAT){
            return 1;
      }
    }

    return 0;
}

int BootAnimation::InputReaderThread::open_device(const char *device) {
    int fd = -1;
    char name[80] = {0,};
    char location[80] = {0,};
    char idstr[80] = {0,};
    struct pollfd *new_ufds = NULL;
    char **new_device_names;

    SLOGD("[%s]device: %s", __FUNCTION__, device);
    fd = open(device, O_RDWR);
    if (fd < 0) {
        SLOGD("[%s]open device failed", __FUNCTION__);
        return -1;
    }
    // "aml_keypad"
    name[sizeof(name) - 1] = '\0';
    location[sizeof(location) - 1] = '\0';
    idstr[sizeof(idstr) - 1] = '\0';
    if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), &name) < 1)
        name[0] = '\0';
    if (ioctl(fd, EVIOCGPHYS(sizeof(location) - 1), &location) < 1)
        location[0] = '\0';
    if (ioctl(fd, EVIOCGUNIQ(sizeof(idstr) - 1), &idstr) < 1)
        idstr[0] = '\0';

    new_ufds = (struct pollfd *)realloc(ufds, sizeof(ufds[0]) * (nfds + 1));
    if (new_ufds == NULL) {
        SLOGD("[%s]out of memory", __FUNCTION__);
        return -1;
    }
    ufds = new_ufds;
    new_device_names = (char **)realloc(device_names, sizeof(device_names[0]) * (nfds + 1));
    if (new_device_names == NULL) {
        SLOGD("[%s]out of memory", __FUNCTION__);
        return -1;
    }
    device_names = new_device_names;

    ufds[nfds].fd = fd;
    ufds[nfds].events = POLLIN;
    device_names[nfds] = strdup(device);
    nfds++;

    return 0;
}

int BootAnimation::InputReaderThread::close_device(const char *device) {
    int i = 0;
    SLOGD("%s device=%s", __FUNCTION__, device);
    for (i = 1; i < nfds; i++) {
        if (strcmp(device_names[i], device) == 0) {
            int count = nfds - i - 1;
            free(device_names[i]);
            memmove(device_names + i, device_names + i + 1, sizeof(device_names[0]) * count);
            memmove(ufds + i, ufds + i + 1, sizeof(ufds[0]) * count);
            nfds--;
            return 0;
        }
    }

    return -1;
}

int BootAnimation::InputReaderThread::scan_dir(const char *dirname) {
    char devname[PATH_MAX] = {0,};
    char *filename = NULL;
    DIR *dir = NULL;
    struct dirent *de = NULL;

    SLOGD("%s dirname=%s", __FUNCTION__, dirname);
    dir = opendir(dirname);
    if (dir == NULL)
        return -1;

    strcpy(devname, dirname);
    filename = devname + strlen(devname);
    *filename++ = '/';
    while ((de = readdir(dir)) != NULL) {
        if (de->d_name[0] == '.' &&
            (de->d_name[1] == '\0' ||
             (de->d_name[1] == '.' && de->d_name[2] == '\0')))
            continue;

        strcpy(filename, de->d_name);
        open_device(devname);
    }
    closedir(dir);

    return 0;
}

int BootAnimation::InputReaderThread::read_notify(const char *dirname, int nfd) {
    int res;
    char event_buf[512] = {0,};
    struct inotify_event *event = NULL;
    char devname[PATH_MAX] = {0,};
    char *filename = NULL;
    int event_size = 0;
    int event_pos = 0;

    SLOGD("%s dirname=%s, nfd: %d", __FUNCTION__, dirname, nfd);
    res = read(nfd, event_buf, sizeof(event_buf));
    if (res < (int)sizeof(*event)) {
        if (errno == EINTR)
            return 0;
        SLOGD("[%s]cound not get event", __FUNCTION__);
        return -1;
    }

    strcpy(devname, dirname);
    filename = devname + strlen(devname);
    *filename++ = '/';

    while (res >= (int)sizeof(*event)) {
        event = (struct inotify_event *)(event_buf + event_pos);
        if (event->len) {
            strcpy(filename, event->name);
            if (event->mask & IN_CREATE)
                open_device(devname);
            else
                close_device(devname);
        }
        event_size = sizeof(*event) + event->len;
        res -= event_size;
        event_pos += event_size;
    }

    return 0;
}

BootAnimation::InputReaderThread::InputReaderThread(BootAnimation* bootAnimation) : Thread(false),
    mInotifyFd(-1), mLastKeyCode(-1), mLastKeyValue(-1), mBootAnimation(bootAnimation) {}

BootAnimation::InputReaderThread::~InputReaderThread() {
    SLOGD("InputReaderThread %s", __FUNCTION__);
    // mInotifyFd may be -1 but that's ok since we're not at risk of attempting to close a valid FD.
    close(mInotifyFd);
}

bool BootAnimation::InputReaderThread::threadLoop() {
    SLOGD("InputReaderThread %s", __FUNCTION__);

    bool shouldLoop = doThreadLoop();
    if (!shouldLoop) {
        close(mInotifyFd);
        mInotifyFd = -1;
    }

    return true;
}

bool BootAnimation::InputReaderThread::doThreadLoop() {
    // Poll instead of doing a blocking read so the Thread can exit if requested.
    /*
    ssize_t pollResult = poll(ufds, nfds, -1);
    if (ufds[0].revents & POLLIN) {
        read_notify(DEVICE_INPUT_PATH, ufds[0].fd);
    }*/
    SLOGD("InputReaderThread %s, nfds=%d\n", __FUNCTION__, nfds);
    int i,res;
    char *code_label = NULL;
    char *value_label = NULL;
    struct input_event inputEvent;
    while (1) {
        poll(ufds, nfds, -1);
        if (ufds[0].revents & POLLIN) {
            read_notify(DEVICE_INPUT_PATH, ufds[0].fd);
        }
        for (i = 1; i < nfds; i++) {
            if (ufds[i].revents) {
                if (ufds[i].revents & POLLIN) {
                    res = read(ufds[i].fd, &inputEvent, sizeof(inputEvent));
                    if (res < (int)sizeof(inputEvent)) {
                        SLOGD("%s, cound not get event", __FUNCTION__);
                        res = -1;
                    }
                    //SLOGE("inputEvent.type:%d\n", inputEvent.type);
                    switch (inputEvent.type) {
                        case EV_KEY:
                            SLOGD("inputEvent.code=%d", inputEvent.code);
                            code_label = get_label(key_labels, inputEvent.code);
                            value_label = get_label(key_value_labels, inputEvent.value);
                            if ((inputEvent.code == KEY_VOLUMEUP || inputEvent.code == KEY_PAGEUP) &&
                                    key_action_done(mLastKeyCode, mLastKeyValue, inputEvent.code, inputEvent.value)) {
                                mBootAnimation->bootVideoSetVolume(UP);
                            } else if ((inputEvent.code == KEY_VOLUMEDOWN || inputEvent.code == KEY_PAGEDOWN) &&
                                    key_action_done(mLastKeyCode, mLastKeyValue, inputEvent.code, inputEvent.value)) {
                                mBootAnimation->bootVideoSetVolume(DOWN);
                            } else if (inputEvent.code == KEY_MUTE &&
                                    key_action_done(mLastKeyCode, mLastKeyValue, inputEvent.code, inputEvent.value)) {
                                    mBootAnimation->bootVideoSetVolume(MUTE);
                            }
                            mLastKeyCode = inputEvent.code;
                            mLastKeyValue = inputEvent.value;
                            break;
                        default:
                            break;
                    }
                }
            }
        }
    }
    SLOGD("InputReaderThread %s exit", __FUNCTION__);

    return true;
}

status_t BootAnimation::InputReaderThread::readyToRun() {
    SLOGD("InputReaderThread %s", __FUNCTION__);
    nfds = 1;
    ufds = (struct pollfd *)calloc(1, sizeof(ufds[0]));
    ufds[0].fd = inotify_init();
    ufds[0].events = POLLIN;
    mInotifyFd = inotify_init();
    if (mInotifyFd < 0) {
        SLOGE("Could not initialize inotify fd");
        return NO_INIT;
    }

    int res = inotify_add_watch(ufds[0].fd, DEVICE_INPUT_PATH, IN_DELETE | IN_CREATE);
    if (res < 0) {
        close(res);
        mInotifyFd = -1;
        SLOGE("Could not add watch for %s", DEVICE_INPUT_PATH);
        return NO_INIT;
    }

    scan_dir(DEVICE_INPUT_PATH);

   return NO_ERROR;
}
// ---------------------------------------------------------------------------------


BootAnimation::BootAnimation(sp<Callbacks> callbacks)
        : Thread(false), mClockEnabled(true), mTimeIsAccurate(false), mTimeFormat12Hour(false),
        mTimeCheckThread(nullptr), mCallbacks(callbacks), mLooper(new Looper(false)), mRotation(ui::ROTATION_0) {
    mSession = new SurfaceComposerClient();

    std::string powerCtl = android::base::GetProperty("sys.powerctl", "");
    if (powerCtl.empty()) {
        mShuttingDown = false;
    } else {
        mShuttingDown = true;
    }

    int rotate = android::base::GetIntProperty("persist.sys.builtinrotation", 0);
    if (rotate != 0)
        mRotation = (ui::Rotation) rotate;
    else
        ALOGD("BootAnimation get property error\n");

    ALOGD("%sAnimationStartTiming start time: %" PRId64 "ms", mShuttingDown ? "Shutdown" : "Boot",
            elapsedRealtime());
}

BootAnimation::~BootAnimation() {
    if (mAnimation != nullptr) {
        releaseAnimation(mAnimation);
        mAnimation = nullptr;
    }
    ALOGD("%sAnimationStopTiming start time: %" PRId64 "ms", mShuttingDown ? "Shutdown" : "Boot",
            elapsedRealtime());
}

void BootAnimation::onFirstRef() {
    status_t err = mSession->linkToComposerDeath(this);
    SLOGE_IF(err, "linkToComposerDeath failed (%s) ", strerror(-err));
    if (err == NO_ERROR) {
        // Load the animation content -- this can be slow (eg 200ms)
        // called before waitForSurfaceFlinger() in main() to avoid wait
        ALOGD("%sAnimationPreloadTiming start time: %" PRId64 "ms",
                mShuttingDown ? "Shutdown" : "Boot", elapsedRealtime());
        preloadAnimation();
        ALOGD("%sAnimationPreloadStopTiming start time: %" PRId64 "ms",
                mShuttingDown ? "Shutdown" : "Boot", elapsedRealtime());
    }
}

sp<SurfaceComposerClient> BootAnimation::session() const {
    return mSession;
}

void BootAnimation::binderDied(const wp<IBinder>&) {
    // woah, surfaceflinger died!
    SLOGD("SurfaceFlinger died, exiting...");

    // calling requestExit() is not enough here because the Surface code
    // might be blocked on a condition variable that will never be updated.
    kill( getpid(), SIGKILL );
    requestExit();
}

status_t BootAnimation::initTexture(Texture* texture, AssetManager& assets,
        const char* name) {
    Asset* asset = assets.open(name, Asset::ACCESS_BUFFER);
    if (asset == nullptr)
        return NO_INIT;
    SkBitmap bitmap;
    sk_sp<SkData> data = SkData::MakeWithoutCopy(asset->getBuffer(false),
            asset->getLength());
    sk_sp<SkImage> image = SkImage::MakeFromEncoded(data);
    image->asLegacyBitmap(&bitmap, SkImage::kRO_LegacyBitmapMode);
    asset->close();
    delete asset;

    const int w = bitmap.width();
    const int h = bitmap.height();
    const void* p = bitmap.getPixels();

    GLint crop[4] = { 0, h, w, -h };
    texture->w = w;
    texture->h = h;

    glGenTextures(1, &texture->name);
    glBindTexture(GL_TEXTURE_2D, texture->name);

    switch (bitmap.colorType()) {
        case kAlpha_8_SkColorType:
            glTexImage2D(GL_TEXTURE_2D, 0, GL_ALPHA, w, h, 0, GL_ALPHA,
                    GL_UNSIGNED_BYTE, p);
            break;
        case kARGB_4444_SkColorType:
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA,
                    GL_UNSIGNED_SHORT_4_4_4_4, p);
            break;
        case kN32_SkColorType:
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA,
                    GL_UNSIGNED_BYTE, p);
            break;
        case kRGB_565_SkColorType:
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, w, h, 0, GL_RGB,
                    GL_UNSIGNED_SHORT_5_6_5, p);
            break;
        default:
            break;
    }

    glTexParameteriv(GL_TEXTURE_2D, GL_TEXTURE_CROP_RECT_OES, crop);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);

    return NO_ERROR;
}

status_t BootAnimation::initTexture(FileMap* map, int* width, int* height) {
    SkBitmap bitmap;
    sk_sp<SkData> data = SkData::MakeWithoutCopy(map->getDataPtr(),
            map->getDataLength());
    sk_sp<SkImage> image = SkImage::MakeFromEncoded(data);
    image->asLegacyBitmap(&bitmap, SkImage::kRO_LegacyBitmapMode);

    // FileMap memory is never released until application exit.
    // Release it now as the texture is already loaded and the memory used for
    // the packed resource can be released.
    delete map;

    const int w = bitmap.width();
    const int h = bitmap.height();
    const void* p = bitmap.getPixels();

    GLint crop[4] = { 0, h, w, -h };
    int tw = 1 << (31 - __builtin_clz(w));
    int th = 1 << (31 - __builtin_clz(h));
    if (tw < w) tw <<= 1;
    if (th < h) th <<= 1;

    switch (bitmap.colorType()) {
        case kN32_SkColorType:
            if (!mUseNpotTextures && (tw != w || th != h)) {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, tw, th, 0, GL_RGBA,
                        GL_UNSIGNED_BYTE, nullptr);
                glTexSubImage2D(GL_TEXTURE_2D, 0,
                        0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, p);
            } else {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA,
                        GL_UNSIGNED_BYTE, p);
            }
            break;

        case kRGB_565_SkColorType:
            if (!mUseNpotTextures && (tw != w || th != h)) {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, tw, th, 0, GL_RGB,
                        GL_UNSIGNED_SHORT_5_6_5, nullptr);
                glTexSubImage2D(GL_TEXTURE_2D, 0,
                        0, 0, w, h, GL_RGB, GL_UNSIGNED_SHORT_5_6_5, p);
            } else {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, w, h, 0, GL_RGB,
                        GL_UNSIGNED_SHORT_5_6_5, p);
            }
            break;
        default:
            break;
    }

    glTexParameteriv(GL_TEXTURE_2D, GL_TEXTURE_CROP_RECT_OES, crop);

    *width = w;
    *height = h;

    return NO_ERROR;
}

class BootAnimation::DisplayEventCallback : public LooperCallback {
    BootAnimation* mBootAnimation;

public:
    DisplayEventCallback(BootAnimation* bootAnimation) {
        mBootAnimation = bootAnimation;
    }

    int handleEvent(int /* fd */, int events, void* /* data */) {
        if (events & (Looper::EVENT_ERROR | Looper::EVENT_HANGUP)) {
            ALOGE("Display event receiver pipe was closed or an error occurred. events=0x%x",
                    events);
            return 0; // remove the callback
        }

        if (!(events & Looper::EVENT_INPUT)) {
            ALOGW("Received spurious callback for unhandled poll event.  events=0x%x", events);
            return 1; // keep the callback
        }

        constexpr int kBufferSize = 100;
        DisplayEventReceiver::Event buffer[kBufferSize];
        ssize_t numEvents;
        do {
            numEvents = mBootAnimation->mDisplayEventReceiver->getEvents(buffer, kBufferSize);
            for (size_t i = 0; i < static_cast<size_t>(numEvents); i++) {
                const auto& event = buffer[i];
                if (event.header.type == DisplayEventReceiver::DISPLAY_EVENT_HOTPLUG) {
                    SLOGV("Hotplug received");

                    if (!event.hotplug.connected) {
                        // ignore hotplug disconnect
                        continue;
                    }
                    auto token = SurfaceComposerClient::getPhysicalDisplayToken(
                        event.header.displayId);

                    if (token != mBootAnimation->mDisplayToken) {
                        // ignore hotplug of a secondary display
                        continue;
                    }

                    DisplayConfig displayConfig;
                    const status_t error = SurfaceComposerClient::getActiveDisplayConfig(
                        mBootAnimation->mDisplayToken, &displayConfig);
                    if (error != NO_ERROR) {
                        SLOGE("Can't get active display configuration.");
                    }
                    mBootAnimation->resizeSurface(displayConfig.resolution.getWidth(),
                        displayConfig.resolution.getHeight());
                }
            }
        } while (numEvents > 0);

        return 1;  // keep the callback
    }
};

EGLConfig BootAnimation::getEglConfig(const EGLDisplay& display) {
    const EGLint attribs[] = {
        EGL_RED_SIZE,   8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE,  8,
        EGL_DEPTH_SIZE, 0,
        EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };
    EGLint numConfigs;
    EGLConfig config;
    eglChooseConfig(display, attribs, &config, 1, &numConfigs);
    return config;
}

ui::Size BootAnimation::limitSurfaceSize(int width, int height) const {
    ui::Size limited(width, height);
    bool wasLimited = false;
    const float aspectRatio = float(width) / float(height);
    if (mMaxWidth != 0 && width > mMaxWidth) {
        limited.height = mMaxWidth / aspectRatio;
        limited.width = mMaxWidth;
        wasLimited = true;
    }
    if (mMaxHeight != 0 && limited.height > mMaxHeight) {
        limited.height = mMaxHeight;
        limited.width = mMaxHeight * aspectRatio;
        wasLimited = true;
    }
    SLOGV_IF(wasLimited, "Surface size has been limited to [%dx%d] from [%dx%d]",
             limited.width, limited.height, width, height);
    return limited;
}

status_t BootAnimation::readyToRun() {
    mAssets.addDefaultAssets();

    mDisplayToken = SurfaceComposerClient::getInternalDisplayToken();
    if (mDisplayToken == nullptr)
        return NAME_NOT_FOUND;

    DisplayConfig displayConfig;
    const status_t error =
            SurfaceComposerClient::getActiveDisplayConfig(mDisplayToken, &displayConfig);
    if (error != NO_ERROR)
        return error;

    mMaxWidth = android::base::GetIntProperty("ro.surface_flinger.max_graphics_width", 0);
    mMaxHeight = android::base::GetIntProperty("ro.surface_flinger.max_graphics_height", 0);
    ui::Size resolution = displayConfig.resolution;
    //resolution = limitSurfaceSize(resolution.width, resolution.height);
    // create the native surface
    if (ui::ROTATION_90 == mRotation || ui::ROTATION_270 == mRotation) {
        int temp = resolution.height;
        resolution.height= resolution.width;
        resolution.width= temp;
    }
    Rect destRect(resolution.getWidth(), resolution.getHeight());
    SurfaceComposerClient::Transaction t;
    t.setDisplayProjection(mDisplayToken, mRotation, destRect, destRect);
    t.apply();

    sp<SurfaceControl> control = session()->createSurface(String8("BootAnimation"),
            resolution.getWidth(), resolution.getHeight(), PIXEL_FORMAT_RGBA_8888);

    //SurfaceComposerClient::Transaction t;

    // this guest property specifies multi-display IDs to show the boot animation
    // multiple ids can be set with comma (,) as separator, for example:
    // setprop persist.boot.animation.displays 19260422155234049,19261083906282754
    Vector<uint64_t> physicalDisplayIds;
    char displayValue[PROPERTY_VALUE_MAX] = "";
    property_get(DISPLAYS_PROP_NAME, displayValue, "");
    bool isValid = displayValue[0] != '\0';
    if (isValid) {
        char *p = displayValue;
        while (*p) {
            if (!isdigit(*p) && *p != ',') {
                isValid = false;
                break;
            }
            p ++;
        }
        if (!isValid)
            SLOGE("Invalid syntax for the value of system prop: %s", DISPLAYS_PROP_NAME);
    }
    if (isValid) {
        std::istringstream stream(displayValue);
        for (PhysicalDisplayId id; stream >> id; ) {
            physicalDisplayIds.add(id);
            if (stream.peek() == ',')
                stream.ignore();
        }

        // In the case of multi-display, boot animation shows on the specified displays
        // in addition to the primary display
        auto ids = SurfaceComposerClient::getPhysicalDisplayIds();
        constexpr uint32_t LAYER_STACK = 0;
        for (auto id : physicalDisplayIds) {
            if (std::find(ids.begin(), ids.end(), id) != ids.end()) {
                sp<IBinder> token = SurfaceComposerClient::getPhysicalDisplayToken(id);
                if (token != nullptr)
                    t.setDisplayLayerStack(token, LAYER_STACK);
            }
        }
        t.setLayerStack(control, LAYER_STACK);
    }

    t.setLayer(control, 0x40000000)
        .apply();

    sp<Surface> s = control->getSurface();

    // initialize opengl and egl
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(display, nullptr, nullptr);
    EGLConfig config = getEglConfig(display);
    EGLSurface surface = eglCreateWindowSurface(display, config, s.get(), nullptr);
    EGLContext context = eglCreateContext(display, config, nullptr, nullptr);
    EGLint w, h;
    eglQuerySurface(display, surface, EGL_WIDTH, &w);
    eglQuerySurface(display, surface, EGL_HEIGHT, &h);

    if (eglMakeCurrent(display, surface, surface, context) == EGL_FALSE)
        return NO_INIT;

    mDisplay = display;
    mContext = context;
    mSurface = surface;
    mWidth = w;
    mHeight = h;
    mFlingerSurfaceControl = control;
    mFlingerSurface = s;
    mTargetInset = -1;

    // Register a display event receiver
    mDisplayEventReceiver = std::make_unique<DisplayEventReceiver>();
    status_t status = mDisplayEventReceiver->initCheck();
    SLOGE_IF(status != NO_ERROR, "Initialization of DisplayEventReceiver failed with status: %d",
            status);
    mLooper->addFd(mDisplayEventReceiver->getFd(), 0, Looper::EVENT_INPUT,
            new DisplayEventCallback(this), nullptr);

    return NO_ERROR;
}

void BootAnimation::resizeSurface(int newWidth, int newHeight) {
    // We assume this function is called on the animation thread.
    if (newWidth == mWidth && newHeight == mHeight) {
        return;
    }
    SLOGV("Resizing the boot animation surface to %d %d", newWidth, newHeight);

    eglMakeCurrent(mDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroySurface(mDisplay, mSurface);

    const auto limitedSize = limitSurfaceSize(newWidth, newHeight);
    mWidth = limitedSize.width;
    mHeight = limitedSize.height;

    SurfaceComposerClient::Transaction t;
    t.setSize(mFlingerSurfaceControl, mWidth, mHeight);
    t.apply();

    EGLConfig config = getEglConfig(mDisplay);
    EGLSurface surface = eglCreateWindowSurface(mDisplay, config, mFlingerSurface.get(), nullptr);
    if (eglMakeCurrent(mDisplay, surface, surface, mContext) == EGL_FALSE) {
        SLOGE("Can't make the new surface current. Error %d", eglGetError());
        return;
    }
    glViewport(0, 0, mWidth, mHeight);
    glScissor(0, 0, mWidth, mHeight);

    mSurface = surface;
}

bool BootAnimation::preloadAnimation() {
    findBootAnimationFile();
    if (!mZipFileName.isEmpty()) {
        mAnimation = loadAnimation(mZipFileName);
        return (mAnimation != nullptr);
    }

    return false;
}

bool BootAnimation::findBootAnimationFileInternal(const std::vector<std::string> &files) {
    for (const std::string& f : files) {
        if (access(f.c_str(), R_OK) == 0) {
            mZipFileName = f.c_str();
            return true;
        }
    }
    return false;
}

void BootAnimation::findBootAnimationFile() {
    // If the device has encryption turned on or is in process
    // of being encrypted we show the encrypted boot animation.
    char decrypt[PROPERTY_VALUE_MAX];
    property_get("vold.decrypt", decrypt, "");

    bool encryptedAnimation = atoi(decrypt) != 0 ||
        !strcmp("trigger_restart_min_framework", decrypt);

    if (!mShuttingDown && encryptedAnimation) {
        static const std::vector<std::string> encryptedBootFiles = {
            PRODUCT_ENCRYPTED_BOOTANIMATION_FILE, SYSTEM_ENCRYPTED_BOOTANIMATION_FILE,
        };
        if (findBootAnimationFileInternal(encryptedBootFiles)) {
            return;
        }
    }

    const bool playDarkAnim = android::base::GetIntProperty("ro.boot.theme", 0) == 1;
    static const std::vector<std::string> bootFiles = {
        APEX_BOOTANIMATION_FILE, playDarkAnim ? PRODUCT_BOOTANIMATION_DARK_FILE : PRODUCT_BOOTANIMATION_FILE,
        OEM_BOOTANIMATION_FILE, SYSTEM_BOOTANIMATION_FILE
    };
    static const std::vector<std::string> shutdownFiles = {
        PRODUCT_SHUTDOWNANIMATION_FILE, OEM_SHUTDOWNANIMATION_FILE, SYSTEM_SHUTDOWNANIMATION_FILE, ""
    };
    static const std::vector<std::string> userspaceRebootFiles = {
        PRODUCT_USERSPACE_REBOOT_ANIMATION_FILE, OEM_USERSPACE_REBOOT_ANIMATION_FILE,
        SYSTEM_USERSPACE_REBOOT_ANIMATION_FILE,
    };

    if (android::base::GetBoolProperty("sys.init.userspace_reboot.in_progress", false)) {
        findBootAnimationFileInternal(userspaceRebootFiles);
    } else if (mShuttingDown) {
        findBootAnimationFileInternal(shutdownFiles);
    } else {
        findBootAnimationFileInternal(bootFiles);
    }
}

bool BootAnimation::threadLoop() {
    bool result = false;
    // We have no bootanimation file, so we use the stock android logo
    // animation.
    SLOGD("BootAnimation::threadLoop");
    property_set(BOOT_VIDEO_RUNNING_STATUS_PROP_NAME, "1");
    if (mZipFileName.isEmpty()) {
        result = android();
    } else {
        //vendor prop to sys prop
        int bootVideoProp = property_get_int(BOOT_VIDEO_VENDOR_PROP_NAME, -1);
        if (bootVideoProp == -1) {
            result = movie();
        } else {
            mConfig =bootVideoProp / 1000;
            int volProp = property_get_int(BOOT_VIDEO_SYS_PROP_NAME, -1);
            if (volProp == -1) {
                //first boot
                mMute = false;
                mVol = bootVideoProp % 1000;
                property_set_int(BOOT_VIDEO_SYS_PROP_NAME, mVol);
            } else {
                mMute =( (volProp / 1000) == 0) ? false : true;
                if (mMute) {
                    mVol = BOOT_VIDEO_VOL_MUTE;
                } else {
                    mVol = volProp % 1000;
                }
            }
            SLOGD("bootVideoProp=%d, mConfig=%d, mVol=%d, mMute=%d", bootVideoProp, mConfig, mVol, mMute);
            switch (mConfig) {
                case CONFIG_BOOTANIM_BOOTVIDEO:
                    result = movie();
                    result = bootVideo();
                    break;
                case CONFIG_BOOTVIDEO_BOOTANIM:
                    result = bootVideo();
                    result = movie();
                    break;
                case CONFIG_BOOTVIDEO:
                    result = bootVideo();
                    break;
                case CONFIG_BOOTANIM:
                default:
                    result = movie();
            }
        }

    }

    property_set(BOOT_VIDEO_RUNNING_STATUS_PROP_NAME, "0");
    eglMakeCurrent(mDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroyContext(mDisplay, mContext);
    eglDestroySurface(mDisplay, mSurface);
    mFlingerSurface.clear();
    mFlingerSurfaceControl.clear();
    eglTerminate(mDisplay);
    eglReleaseThread();
    IPCThreadState::self()->stopProcess();
    return result;
}

bool BootAnimation::android() {
    SLOGD("%sAnimationShownTiming start time: %" PRId64 "ms", mShuttingDown ? "Shutdown" : "Boot",
            elapsedRealtime());
    initTexture(&mAndroid[0], mAssets, "images/android-logo-mask.png");
    initTexture(&mAndroid[1], mAssets, "images/android-logo-shine.png");

    mCallbacks->init({});

    // clear screen
    glShadeModel(GL_FLAT);
    glDisable(GL_DITHER);
    glDisable(GL_SCISSOR_TEST);
    glClearColor(0,0,0,1);
    glClear(GL_COLOR_BUFFER_BIT);
    eglSwapBuffers(mDisplay, mSurface);

    glEnable(GL_TEXTURE_2D);
    glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);

    // Blend state
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);

    const nsecs_t startTime = systemTime();
    do {
        processDisplayEvents();
        const GLint xc = (mWidth  - mAndroid[0].w) / 2;
        const GLint yc = (mHeight - mAndroid[0].h) / 2;
        const Rect updateRect(xc, yc, xc + mAndroid[0].w, yc + mAndroid[0].h);
        glScissor(updateRect.left, mHeight - updateRect.bottom, updateRect.width(),
                updateRect.height());

        nsecs_t now = systemTime();
        double time = now - startTime;
        float t = 4.0f * float(time / us2ns(16667)) / mAndroid[1].w;
        GLint offset = (1 - (t - floorf(t))) * mAndroid[1].w;
        GLint x = xc - offset;

        glDisable(GL_SCISSOR_TEST);
        glClear(GL_COLOR_BUFFER_BIT);

        glEnable(GL_SCISSOR_TEST);
        glDisable(GL_BLEND);
        glBindTexture(GL_TEXTURE_2D, mAndroid[1].name);
        glDrawTexiOES(x,                 yc, 0, mAndroid[1].w, mAndroid[1].h);
        glDrawTexiOES(x + mAndroid[1].w, yc, 0, mAndroid[1].w, mAndroid[1].h);

        glEnable(GL_BLEND);
        glBindTexture(GL_TEXTURE_2D, mAndroid[0].name);
        glDrawTexiOES(xc, yc, 0, mAndroid[0].w, mAndroid[0].h);

        EGLBoolean res = eglSwapBuffers(mDisplay, mSurface);
        if (res == EGL_FALSE)
            break;

        // 12fps: don't animate too fast to preserve CPU
        const nsecs_t sleepTime = 83333 - ns2us(systemTime() - now);
        if (sleepTime > 0)
            usleep(sleepTime);

        checkExit();
    } while (!exitPending());

    glDeleteTextures(1, &mAndroid[0].name);
    glDeleteTextures(1, &mAndroid[1].name);
    return false;
}

void BootAnimation::checkExit() {
    // Allow surface flinger to gracefully request shutdown
    char value[PROPERTY_VALUE_MAX];
    property_get(EXIT_PROP_NAME, value, "0");
    int exitnow = atoi(value);
    if (exitnow) {
        requestExit();
        mCallbacks->shutdown();
    }
}

bool BootAnimation::validClock(const Animation::Part& part) {
    return part.clockPosX != TEXT_MISSING_VALUE && part.clockPosY != TEXT_MISSING_VALUE;
}

bool parseTextCoord(const char* str, int* dest) {
    if (strcmp("c", str) == 0) {
        *dest = TEXT_CENTER_VALUE;
        return true;
    }

    char* end;
    int val = (int) strtol(str, &end, 0);
    if (end == str || *end != '\0' || val == INT_MAX || val == INT_MIN) {
        return false;
    }
    *dest = val;
    return true;
}

// Parse two position coordinates. If only string is non-empty, treat it as the y value.
void parsePosition(const char* str1, const char* str2, int* x, int* y) {
    bool success = false;
    if (strlen(str1) == 0) {  // No values were specified
        // success = false
    } else if (strlen(str2) == 0) {  // we have only one value
        if (parseTextCoord(str1, y)) {
            *x = TEXT_CENTER_VALUE;
            success = true;
        }
    } else {
        if (parseTextCoord(str1, x) && parseTextCoord(str2, y)) {
            success = true;
        }
    }

    if (!success) {
        *x = TEXT_MISSING_VALUE;
        *y = TEXT_MISSING_VALUE;
    }
}

// Parse a color represented as an HTML-style 'RRGGBB' string: each pair of
// characters in str is a hex number in [0, 255], which are converted to
// floating point values in the range [0.0, 1.0] and placed in the
// corresponding elements of color.
//
// If the input string isn't valid, parseColor returns false and color is
// left unchanged.
static bool parseColor(const char str[7], float color[3]) {
    float tmpColor[3];
    for (int i = 0; i < 3; i++) {
        int val = 0;
        for (int j = 0; j < 2; j++) {
            val *= 16;
            char c = str[2*i + j];
            if      (c >= '0' && c <= '9') val += c - '0';
            else if (c >= 'A' && c <= 'F') val += (c - 'A') + 10;
            else if (c >= 'a' && c <= 'f') val += (c - 'a') + 10;
            else                           return false;
        }
        tmpColor[i] = static_cast<float>(val) / 255.0f;
    }
    memcpy(color, tmpColor, sizeof(tmpColor));
    return true;
}


static bool readFile(ZipFileRO* zip, const char* name, String8& outString) {
    ZipEntryRO entry = zip->findEntryByName(name);
    SLOGE_IF(!entry, "couldn't find %s", name);
    if (!entry) {
        return false;
    }

    FileMap* entryMap = zip->createEntryFileMap(entry);
    zip->releaseEntry(entry);
    SLOGE_IF(!entryMap, "entryMap is null");
    if (!entryMap) {
        return false;
    }

    outString.setTo((char const*)entryMap->getDataPtr(), entryMap->getDataLength());
    delete entryMap;
    return true;
}

// The font image should be a 96x2 array of character images.  The
// columns are the printable ASCII characters 0x20 - 0x7f.  The
// top row is regular text; the bottom row is bold.
status_t BootAnimation::initFont(Font* font, const char* fallback) {
    status_t status = NO_ERROR;

    if (font->map != nullptr) {
        glGenTextures(1, &font->texture.name);
        glBindTexture(GL_TEXTURE_2D, font->texture.name);

        status = initTexture(font->map, &font->texture.w, &font->texture.h);

        glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
    } else if (fallback != nullptr) {
        status = initTexture(&font->texture, mAssets, fallback);
    } else {
        return NO_INIT;
    }

    if (status == NO_ERROR) {
        font->char_width = font->texture.w / FONT_NUM_COLS;
        font->char_height = font->texture.h / FONT_NUM_ROWS / 2;  // There are bold and regular rows
    }

    return status;
}

void BootAnimation::drawText(const char* str, const Font& font, bool bold, int* x, int* y) {
    glEnable(GL_BLEND);  // Allow us to draw on top of the animation
    glBindTexture(GL_TEXTURE_2D, font.texture.name);

    const int len = strlen(str);
    const int strWidth = font.char_width * len;

    if (*x == TEXT_CENTER_VALUE) {
        *x = (mWidth - strWidth) / 2;
    } else if (*x < 0) {
        *x = mWidth + *x - strWidth;
    }
    if (*y == TEXT_CENTER_VALUE) {
        *y = (mHeight - font.char_height) / 2;
    } else if (*y < 0) {
        *y = mHeight + *y - font.char_height;
    }

    int cropRect[4] = { 0, 0, font.char_width, -font.char_height };

    for (int i = 0; i < len; i++) {
        char c = str[i];

        if (c < FONT_BEGIN_CHAR || c > FONT_END_CHAR) {
            c = '?';
        }

        // Crop the texture to only the pixels in the current glyph
        const int charPos = (c - FONT_BEGIN_CHAR);  // Position in the list of valid characters
        const int row = charPos / FONT_NUM_COLS;
        const int col = charPos % FONT_NUM_COLS;
        cropRect[0] = col * font.char_width;  // Left of column
        cropRect[1] = row * font.char_height * 2; // Top of row
        // Move down to bottom of regular (one char_heigh) or bold (two char_heigh) line
        cropRect[1] += bold ? 2 * font.char_height : font.char_height;
        glTexParameteriv(GL_TEXTURE_2D, GL_TEXTURE_CROP_RECT_OES, cropRect);

        glDrawTexiOES(*x, *y, 0, font.char_width, font.char_height);

        *x += font.char_width;
    }

    glDisable(GL_BLEND);  // Return to the animation's default behaviour
    glBindTexture(GL_TEXTURE_2D, 0);
}

// We render 12 or 24 hour time.
void BootAnimation::drawClock(const Font& font, const int xPos, const int yPos) {
    static constexpr char TIME_FORMAT_12[] = "%l:%M";
    static constexpr char TIME_FORMAT_24[] = "%H:%M";
    static constexpr int TIME_LENGTH = 6;

    time_t rawtime;
    time(&rawtime);
    struct tm* timeInfo = localtime(&rawtime);

    char timeBuff[TIME_LENGTH];
    const char* timeFormat = mTimeFormat12Hour ? TIME_FORMAT_12 : TIME_FORMAT_24;
    size_t length = strftime(timeBuff, TIME_LENGTH, timeFormat, timeInfo);

    if (length != TIME_LENGTH - 1) {
        SLOGE("Couldn't format time; abandoning boot animation clock");
        mClockEnabled = false;
        return;
    }

    char* out = timeBuff[0] == ' ' ? &timeBuff[1] : &timeBuff[0];
    int x = xPos;
    int y = yPos;
    drawText(out, font, false, &x, &y);
}

bool BootAnimation::parseAnimationDesc(Animation& animation)  {
    String8 desString;

    if (!readFile(animation.zip, "desc.txt", desString)) {
        return false;
    }
    char const* s = desString.string();

    // Parse the description file
    for (;;) {
        const char* endl = strstr(s, "\n");
        if (endl == nullptr) break;
        String8 line(s, endl - s);
        const char* l = line.string();
        int fps = 0;
        int width = 0;
        int height = 0;
        int count = 0;
        int pause = 0;
        char path[ANIM_ENTRY_NAME_MAX];
        char color[7] = "000000"; // default to black if unspecified
        char clockPos1[TEXT_POS_LEN_MAX + 1] = "";
        char clockPos2[TEXT_POS_LEN_MAX + 1] = "";

        char pathType;
        if (sscanf(l, "%d %d %d", &width, &height, &fps) == 3) {
            // SLOGD("> w=%d, h=%d, fps=%d", width, height, fps);
            animation.width = width;
            animation.height = height;
            animation.fps = fps;
        } else if (sscanf(l, " %c %d %d %" STRTO(ANIM_PATH_MAX) "s #%6s %16s %16s",
                          &pathType, &count, &pause, path, color, clockPos1, clockPos2) >= 4) {
            //SLOGD("> type=%c, count=%d, pause=%d, path=%s, color=%s, clockPos1=%s, clockPos2=%s",
            //    pathType, count, pause, path, color, clockPos1, clockPos2);
            Animation::Part part;
            part.playUntilComplete = pathType == 'c';
            part.count = count;
            part.pause = pause;
            part.path = path;
            part.audioData = nullptr;
            part.animation = nullptr;
            if (!parseColor(color, part.backgroundColor)) {
                SLOGE("> invalid color '#%s'", color);
                part.backgroundColor[0] = 0.0f;
                part.backgroundColor[1] = 0.0f;
                part.backgroundColor[2] = 0.0f;
            }
            parsePosition(clockPos1, clockPos2, &part.clockPosX, &part.clockPosY);
            animation.parts.add(part);
        }
        else if (strcmp(l, "$SYSTEM") == 0) {
            // SLOGD("> SYSTEM");
            Animation::Part part;
            part.playUntilComplete = false;
            part.count = 1;
            part.pause = 0;
            part.audioData = nullptr;
            part.animation = loadAnimation(String8(SYSTEM_BOOTANIMATION_FILE));
            if (part.animation != nullptr)
                animation.parts.add(part);
        }
        s = ++endl;
    }

    return true;
}

bool BootAnimation::preloadZip(Animation& animation) {
    // read all the data structures
    const size_t pcount = animation.parts.size();
    void *cookie = nullptr;
    ZipFileRO* zip = animation.zip;
    if (!zip->startIteration(&cookie)) {
        return false;
    }

    ZipEntryRO entry;
    char name[ANIM_ENTRY_NAME_MAX];
    while ((entry = zip->nextEntry(cookie)) != nullptr) {
        const int foundEntryName = zip->getEntryFileName(entry, name, ANIM_ENTRY_NAME_MAX);
        if (foundEntryName > ANIM_ENTRY_NAME_MAX || foundEntryName == -1) {
            SLOGE("Error fetching entry file name");
            continue;
        }

        const String8 entryName(name);
        const String8 path(entryName.getPathDir());
        const String8 leaf(entryName.getPathLeaf());
        if (leaf.size() > 0) {
            if (entryName == CLOCK_FONT_ZIP_NAME) {
                FileMap* map = zip->createEntryFileMap(entry);
                if (map) {
                    animation.clockFont.map = map;
                }
                continue;
            }

            for (size_t j = 0; j < pcount; j++) {
                if (path == animation.parts[j].path) {
                    uint16_t method;
                    // supports only stored png files
                    if (zip->getEntryInfo(entry, &method, nullptr, nullptr, nullptr, nullptr, nullptr)) {
                        if (method == ZipFileRO::kCompressStored) {
                            FileMap* map = zip->createEntryFileMap(entry);
                            if (map) {
                                Animation::Part& part(animation.parts.editItemAt(j));
                                if (leaf == "audio.wav") {
                                    // a part may have at most one audio file
                                    part.audioData = (uint8_t *)map->getDataPtr();
                                    part.audioLength = map->getDataLength();
                                } else if (leaf == "trim.txt") {
                                    part.trimData.setTo((char const*)map->getDataPtr(),
                                                        map->getDataLength());
                                } else {
                                    Animation::Frame frame;
                                    frame.name = leaf;
                                    frame.map = map;
                                    frame.trimWidth = animation.width;
                                    frame.trimHeight = animation.height;
                                    frame.trimX = 0;
                                    frame.trimY = 0;
                                    part.frames.add(frame);
                                }
                            }
                        } else {
                            SLOGE("bootanimation.zip is compressed; must be only stored");
                        }
                    }
                }
            }
        }
    }

    // If there is trimData present, override the positioning defaults.
    for (Animation::Part& part : animation.parts) {
        const char* trimDataStr = part.trimData.string();
        for (size_t frameIdx = 0; frameIdx < part.frames.size(); frameIdx++) {
            const char* endl = strstr(trimDataStr, "\n");
            // No more trimData for this part.
            if (endl == nullptr) {
                break;
            }
            String8 line(trimDataStr, endl - trimDataStr);
            const char* lineStr = line.string();
            trimDataStr = ++endl;
            int width = 0, height = 0, x = 0, y = 0;
            if (sscanf(lineStr, "%dx%d+%d+%d", &width, &height, &x, &y) == 4) {
                Animation::Frame& frame(part.frames.editItemAt(frameIdx));
                frame.trimWidth = width;
                frame.trimHeight = height;
                frame.trimX = x;
                frame.trimY = y;
            } else {
                SLOGE("Error parsing trim.txt, line: %s", lineStr);
                break;
            }
        }
    }

    zip->endIteration(cookie);

    return true;
}

bool BootAnimation::movie() {
    if (mAnimation == nullptr) {
        mAnimation = loadAnimation(mZipFileName);
    }

    if (mAnimation == nullptr)
        return false;
    // mCallbacks->init() may get called recursively,
    // this loop is needed to get the same results
    for (const Animation::Part& part : mAnimation->parts) {
        if (part.animation != nullptr) {
            mCallbacks->init(part.animation->parts);
        }
    }
    mCallbacks->init(mAnimation->parts);

    bool anyPartHasClock = false;
    for (size_t i=0; i < mAnimation->parts.size(); i++) {
        if(validClock(mAnimation->parts[i])) {
            anyPartHasClock = true;
            break;
        }
    }
    if (!anyPartHasClock) {
        mClockEnabled = false;
    }

    // Check if npot textures are supported
    mUseNpotTextures = false;
    String8 gl_extensions;
    const char* exts = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
    if (!exts) {
        glGetError();
    } else {
        gl_extensions.setTo(exts);
        if ((gl_extensions.find("GL_ARB_texture_non_power_of_two") != -1) ||
            (gl_extensions.find("GL_OES_texture_npot") != -1)) {
            mUseNpotTextures = true;
        }
    }

    // Blend required to draw time on top of animation frames.
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glShadeModel(GL_FLAT);
    glDisable(GL_DITHER);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_BLEND);

    glBindTexture(GL_TEXTURE_2D, 0);
    glEnable(GL_TEXTURE_2D);
    glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

    bool clockFontInitialized = false;
    if (mClockEnabled) {
        clockFontInitialized =
            (initFont(&mAnimation->clockFont, CLOCK_FONT_ASSET) == NO_ERROR);
        mClockEnabled = clockFontInitialized;
    }

    if (mClockEnabled && !updateIsTimeAccurate()) {
        mTimeCheckThread = new TimeCheckThread(this);
        mTimeCheckThread->run("BootAnimation::TimeCheckThread", PRIORITY_NORMAL);
    }

    playAnimation(*mAnimation);

    if (mTimeCheckThread != nullptr) {
        mTimeCheckThread->requestExit();
        mTimeCheckThread = nullptr;
    }

    if (clockFontInitialized) {
        glDeleteTextures(1, &mAnimation->clockFont.texture.name);
    }

    releaseAnimation(mAnimation);
    mAnimation = nullptr;

    return false;
}

bool BootAnimation::playAnimation(const Animation& animation) {
    const size_t pcount = animation.parts.size();
    nsecs_t frameDuration = s2ns(1) / animation.fps;

    SLOGD("%sAnimationShownTiming start time: %" PRId64 "ms", mShuttingDown ? "Shutdown" : "Boot",
            elapsedRealtime());
    for (size_t i=0 ; i<pcount ; i++) {
        const Animation::Part& part(animation.parts[i]);
        const size_t fcount = part.frames.size();
        glBindTexture(GL_TEXTURE_2D, 0);

        // Handle animation package
        if (part.animation != nullptr) {
            playAnimation(*part.animation);
            if (exitPending())
                break;
            continue; //to next part
        }

        for (int r=0 ; !part.count || r<part.count ; r++) {
            // Exit any non playuntil complete parts immediately
            if(exitPending() && !part.playUntilComplete)
                break;

            mCallbacks->playPart(i, part, r);

            glClearColor(
                    part.backgroundColor[0],
                    part.backgroundColor[1],
                    part.backgroundColor[2],
                    1.0f);

            for (size_t j=0 ; j<fcount && (!exitPending() || part.playUntilComplete) ; j++) {
                processDisplayEvents();

                const int animationX = (mWidth - animation.width) / 2;
                const int animationY = (mHeight - animation.height) / 2;

                const Animation::Frame& frame(part.frames[j]);
                nsecs_t lastFrame = systemTime();

                if (r > 0) {
                    glBindTexture(GL_TEXTURE_2D, frame.tid);
                } else {
                    if (part.count != 1) {
                        glGenTextures(1, &frame.tid);
                        glBindTexture(GL_TEXTURE_2D, frame.tid);
                        glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                        glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                    }
                    int w, h;
                    initTexture(frame.map, &w, &h);
                }

                const int xc = animationX + frame.trimX;
                const int yc = animationY + frame.trimY;
                Region clearReg(Rect(mWidth, mHeight));
                clearReg.subtractSelf(Rect(xc, yc, xc+frame.trimWidth, yc+frame.trimHeight));
                if (!clearReg.isEmpty()) {
                    Region::const_iterator head(clearReg.begin());
                    Region::const_iterator tail(clearReg.end());
                    glEnable(GL_SCISSOR_TEST);
                    while (head != tail) {
                        const Rect& r2(*head++);
                        glScissor(r2.left, mHeight - r2.bottom, r2.width(), r2.height());
                        glClear(GL_COLOR_BUFFER_BIT);
                    }
                    glDisable(GL_SCISSOR_TEST);
                }
                // specify the y center as ceiling((mHeight - frame.trimHeight) / 2)
                // which is equivalent to mHeight - (yc + frame.trimHeight)
                glDrawTexiOES(xc, mHeight - (yc + frame.trimHeight),
                              0, frame.trimWidth, frame.trimHeight);
                if (mClockEnabled && mTimeIsAccurate && validClock(part)) {
                    drawClock(animation.clockFont, part.clockPosX, part.clockPosY);
                }
                handleViewport(frameDuration);

                eglSwapBuffers(mDisplay, mSurface);

                nsecs_t now = systemTime();
                nsecs_t delay = frameDuration - (now - lastFrame);
                //SLOGD("%lld, %lld", ns2ms(now - lastFrame), ns2ms(delay));
                lastFrame = now;

                if (delay > 0) {
                    struct timespec spec;
                    spec.tv_sec  = (now + delay) / 1000000000;
                    spec.tv_nsec = (now + delay) % 1000000000;
                    int err;
                    do {
                        err = clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &spec, nullptr);
                    } while (err<0 && errno == EINTR);
                }

                checkExit();
            }

            usleep(part.pause * ns2us(frameDuration));

            // For infinite parts, we've now played them at least once, so perhaps exit
            if(exitPending() && !part.count && mCurrentInset >= mTargetInset)
                break;
        }

    }

    // Free textures created for looping parts now that the animation is done.
    for (const Animation::Part& part : animation.parts) {
        if (part.count != 1) {
            const size_t fcount = part.frames.size();
            for (size_t j = 0; j < fcount; j++) {
                const Animation::Frame& frame(part.frames[j]);
                glDeleteTextures(1, &frame.tid);
            }
        }
    }

    return true;
}

void BootAnimation::processDisplayEvents() {
    // This will poll mDisplayEventReceiver and if there are new events it'll call
    // displayEventCallback synchronously.
    mLooper->pollOnce(0);
}

void BootAnimation::handleViewport(nsecs_t timestep) {
    if (mShuttingDown || !mFlingerSurfaceControl || mTargetInset == 0) {
        return;
    }
    if (mTargetInset < 0) {
        // Poll the amount for the top display inset. This will return -1 until persistent properties
        // have been loaded.
        mTargetInset = android::base::GetIntProperty("persist.sys.displayinset.top",
                -1 /* default */, -1 /* min */, mHeight / 2 /* max */);
    }
    if (mTargetInset <= 0) {
        return;
    }

    if (mCurrentInset < mTargetInset) {
        // After the device boots, the inset will effectively be cropped away. We animate this here.
        float fraction = static_cast<float>(mCurrentInset) / mTargetInset;
        int interpolatedInset = (cosf((fraction + 1) * M_PI) / 2.0f + 0.5f) * mTargetInset;

        SurfaceComposerClient::Transaction()
                .setCrop(mFlingerSurfaceControl, Rect(0, interpolatedInset, mWidth, mHeight))
                .apply();
    } else {
        // At the end of the animation, we switch to the viewport that DisplayManager will apply
        // later. This changes the coordinate system, and means we must move the surface up by
        // the inset amount.
        Rect layerStackRect(0, 0, mWidth, mHeight - mTargetInset);
        Rect displayRect(0, mTargetInset, mWidth, mHeight);

        SurfaceComposerClient::Transaction t;
        t.setPosition(mFlingerSurfaceControl, 0, -mTargetInset)
                .setCrop(mFlingerSurfaceControl, Rect(0, mTargetInset, mWidth, mHeight));
        t.setDisplayProjection(mDisplayToken, ui::ROTATION_0, layerStackRect, displayRect);
        t.apply();

        mTargetInset = mCurrentInset = 0;
    }

    int delta = timestep * mTargetInset / ms2ns(200);
    mCurrentInset += delta;
}

void BootAnimation::releaseAnimation(Animation* animation) const {
    for (Vector<Animation::Part>::iterator it = animation->parts.begin(),
         e = animation->parts.end(); it != e; ++it) {
        if (it->animation)
            releaseAnimation(it->animation);
    }
    if (animation->zip)
        delete animation->zip;
    delete animation;
}

BootAnimation::Animation* BootAnimation::loadAnimation(const String8& fn) {
    if (mLoadedFiles.indexOf(fn) >= 0) {
        SLOGE("File \"%s\" is already loaded. Cyclic ref is not allowed",
            fn.string());
        return nullptr;
    }
    ZipFileRO *zip = ZipFileRO::open(fn);
    if (zip == nullptr) {
        SLOGE("Failed to open animation zip \"%s\": %s",
            fn.string(), strerror(errno));
        return nullptr;
    }

    Animation *animation =  new Animation;
    animation->fileName = fn;
    animation->zip = zip;
    animation->clockFont.map = nullptr;
    mLoadedFiles.add(animation->fileName);

    parseAnimationDesc(*animation);
    if (!preloadZip(*animation)) {
        releaseAnimation(animation);
        return nullptr;
    }

    mLoadedFiles.remove(fn);
    return animation;
}

bool BootAnimation::updateIsTimeAccurate() {
    static constexpr long long MAX_TIME_IN_PAST =   60000LL * 60LL * 24LL * 30LL;  // 30 days
    static constexpr long long MAX_TIME_IN_FUTURE = 60000LL * 90LL;  // 90 minutes

    if (mTimeIsAccurate) {
        return true;
    }
    if (mShuttingDown) return true;
    struct stat statResult;

    if(stat(TIME_FORMAT_12_HOUR_FLAG_FILE_PATH, &statResult) == 0) {
        mTimeFormat12Hour = true;
    }

    if(stat(ACCURATE_TIME_FLAG_FILE_PATH, &statResult) == 0) {
        mTimeIsAccurate = true;
        return true;
    }

    FILE* file = fopen(LAST_TIME_CHANGED_FILE_PATH, "r");
    if (file != nullptr) {
      long long lastChangedTime = 0;
      fscanf(file, "%lld", &lastChangedTime);
      fclose(file);
      if (lastChangedTime > 0) {
        struct timespec now;
        clock_gettime(CLOCK_REALTIME, &now);
        // Match the Java timestamp format
        long long rtcNow = (now.tv_sec * 1000LL) + (now.tv_nsec / 1000000LL);
        if (ACCURATE_TIME_EPOCH < rtcNow
            && lastChangedTime > (rtcNow - MAX_TIME_IN_PAST)
            && lastChangedTime < (rtcNow + MAX_TIME_IN_FUTURE)) {
            mTimeIsAccurate = true;
        }
      }
    }

    return mTimeIsAccurate;
}

BootAnimation::TimeCheckThread::TimeCheckThread(BootAnimation* bootAnimation) : Thread(false),
    mInotifyFd(-1), mSystemWd(-1), mTimeWd(-1), mBootAnimation(bootAnimation) {}

BootAnimation::TimeCheckThread::~TimeCheckThread() {
    // mInotifyFd may be -1 but that's ok since we're not at risk of attempting to close a valid FD.
    close(mInotifyFd);
}

bool BootAnimation::TimeCheckThread::threadLoop() {
    bool shouldLoop = doThreadLoop() && !mBootAnimation->mTimeIsAccurate
        && mBootAnimation->mClockEnabled;
    if (!shouldLoop) {
        close(mInotifyFd);
        mInotifyFd = -1;
    }
    return shouldLoop;
}

bool BootAnimation::TimeCheckThread::doThreadLoop() {
    static constexpr int BUFF_LEN (10 * (sizeof(struct inotify_event) + NAME_MAX + 1));

    // Poll instead of doing a blocking read so the Thread can exit if requested.
    struct pollfd pfd = { mInotifyFd, POLLIN, 0 };
    ssize_t pollResult = poll(&pfd, 1, 1000);

    if (pollResult == 0) {
        return true;
    } else if (pollResult < 0) {
        SLOGE("Could not poll inotify events");
        return false;
    }

    char buff[BUFF_LEN] __attribute__ ((aligned(__alignof__(struct inotify_event))));;
    ssize_t length = read(mInotifyFd, buff, BUFF_LEN);
    if (length == 0) {
        return true;
    } else if (length < 0) {
        SLOGE("Could not read inotify events");
        return false;
    }

    const struct inotify_event *event;
    for (char* ptr = buff; ptr < buff + length; ptr += sizeof(struct inotify_event) + event->len) {
        event = (const struct inotify_event *) ptr;
        if (event->wd == mSystemWd && strcmp(SYSTEM_TIME_DIR_NAME, event->name) == 0) {
            addTimeDirWatch();
        } else if (event->wd == mTimeWd && (strcmp(LAST_TIME_CHANGED_FILE_NAME, event->name) == 0
                || strcmp(ACCURATE_TIME_FLAG_FILE_NAME, event->name) == 0)) {
            return !mBootAnimation->updateIsTimeAccurate();
        }
    }

    return true;
}

void BootAnimation::TimeCheckThread::addTimeDirWatch() {
        mTimeWd = inotify_add_watch(mInotifyFd, SYSTEM_TIME_DIR_PATH,
                IN_CLOSE_WRITE | IN_MOVED_TO | IN_ATTRIB);
        if (mTimeWd > 0) {
            // No need to watch for the time directory to be created if it already exists
            inotify_rm_watch(mInotifyFd, mSystemWd);
            mSystemWd = -1;
        }
}

status_t BootAnimation::TimeCheckThread::readyToRun() {
    mInotifyFd = inotify_init();
    if (mInotifyFd < 0) {
        SLOGE("Could not initialize inotify fd");
        return NO_INIT;
    }

    mSystemWd = inotify_add_watch(mInotifyFd, SYSTEM_DATA_DIR_PATH, IN_CREATE | IN_ATTRIB);
    if (mSystemWd < 0) {
        close(mInotifyFd);
        mInotifyFd = -1;
        SLOGE("Could not add watch for %s: %s", SYSTEM_DATA_DIR_PATH, strerror(errno));
        return NO_INIT;
    }

    addTimeDirWatch();

    if (mBootAnimation->updateIsTimeAccurate()) {
        close(mInotifyFd);
        mInotifyFd = -1;
        return ALREADY_EXISTS;
    }

    return NO_ERROR;
}

// ---------------------------------------------------------------------------

} // namespace android
