#define LOG_TAG "RkNativeDisplayManager"

#include "android_os_Parcel.h"
#include "android_util_Binder.h"
#include "android/graphics/Bitmap.h"
#include "android/graphics/GraphicsJNI.h"
#include "core_jni_helpers.h"

#include <JNIHelp.h>
#include <ScopedUtfChars.h>
#include <jni.h>
#include <memory>
#include <stdio.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <xf86drm.h>
#include <xf86drmMode.h>

#include <cutils/log.h>
#include <cutils/properties.h>
#include <drm_fourcc.h>
#include <string>
#include <map>
#include <vector>
#include <iostream>
#include <inttypes.h>
#include <sstream>

#include <linux/netlink.h>
#include <sys/socket.h>
#include "rkdisplay/drmresources.h"
#include "rkdisplay/drmmode.h"
#include "rkdisplay/drmconnector.h"

namespace android{

static struct {
    jclass clazz;
    jmethodID ctor;
    jfieldID width;
    jfieldID height;
    jfieldID refreshRate;
    jfieldID clock;
    jfieldID flags;
    jfieldID interlaceFlag;
    jfieldID yuvFlag;
    jfieldID connectorId;
    jfieldID mode_type;
    jfieldID idx;
    jfieldID hsync_start;
    jfieldID hsync_end;
    jfieldID htotal;
    jfieldID hskew;
    jfieldID vsync_start;
    jfieldID vsync_end;
    jfieldID vtotal;
    jfieldID vscan;
} gRkPhysicalDisplayInfoClassInfo;

static struct{
    jclass clazz;
    jmethodID ctor;
    jfieldID color_capa;
    jfieldID depth_capa;
}gRkColorModeSupportInfo;

int mFd=0;
DrmResources *drm_ = NULL;
DrmConnector* primary=NULL;
DrmConnector* extend=NULL;


///////////////////////////////////////////////////////////////////////////////////////////////
static bool builtInHdmi(int type){
    return type == DRM_MODE_CONNECTOR_HDMIA || type == DRM_MODE_CONNECTOR_HDMIB;
}

static void updateConnectors(){
    if (drm_->connectors().size() == 2) {
        bool foundHdmi=false;
        int cnt=0,crtcId1=0,crtcId2=0;
        for (auto &conn : drm_->connectors()) {
            if (cnt == 0 && drm_->GetCrtcFromConnector(conn.get())) {
                ALOGD("encoderId1: %d", conn->encoder()->id());
                crtcId1 = drm_->GetCrtcFromConnector(conn.get())->id();
            } else if (drm_->GetCrtcFromConnector(conn.get())){
                ALOGD("encoderId2: %d", conn->encoder()->id());
                crtcId2 = drm_->GetCrtcFromConnector(conn.get())->id();
            }
            
            if (builtInHdmi(conn->get_type()))
                foundHdmi=true;
            cnt++;
        }
        ALOGD("crtc: %d %d foundHdmi %d", crtcId1, crtcId2, foundHdmi);
        char property[PROPERTY_VALUE_MAX];
        property_get("sys.hwc.device.primary", property, "null");
        if (crtcId1 == crtcId2 && foundHdmi && strstr(property, "HDMI-A") == NULL) {
            for (auto &conn : drm_->connectors()) {
                if (builtInHdmi(conn->get_type()) && conn->state() == DRM_MODE_CONNECTED) {
                    extend = conn.get();
                    conn->set_display(1);
                } else if(!builtInHdmi(conn->get_type()) && conn->state() == DRM_MODE_CONNECTED) {
                    primary = conn.get();
                    conn->set_display(0);
                }
            }
        } else {
            primary = drm_->GetConnectorFromType(HWC_DISPLAY_PRIMARY);
            extend = drm_->GetConnectorFromType(HWC_DISPLAY_EXTERNAL);
        }
        
    } else {
        primary = drm_->GetConnectorFromType(HWC_DISPLAY_PRIMARY);
        extend = drm_->GetConnectorFromType(HWC_DISPLAY_EXTERNAL);
    }
}

static void hotPlugUpdate(){
    DrmConnector *mextend = NULL;
    DrmConnector *mprimary = NULL;

    for (auto &conn : drm_->connectors()) {
        drmModeConnection old_state = conn->state();

        conn->UpdateModes();

        drmModeConnection cur_state = conn->state();
        ALOGD("old_state %d cur_state %d conn->get_type() %d", old_state, cur_state, conn->get_type());

        if (cur_state == old_state)
            continue;
        ALOGI("%s event  for connector %u\n",
            cur_state == DRM_MODE_CONNECTED ? "Plug" : "Unplug", conn->id());
    
        if (cur_state == DRM_MODE_CONNECTED) {
         if (conn->possible_displays() & HWC_DISPLAY_EXTERNAL_BIT)
           mextend = conn.get();
         else if (conn->possible_displays() & HWC_DISPLAY_PRIMARY_BIT)
           mprimary = conn.get();
        }
    }

    /*
    * status changed?
    */
    drm_->DisplayChanged();

    DrmConnector *old_primary = drm_->GetConnectorFromType(HWC_DISPLAY_PRIMARY);
    mprimary = mprimary ? mprimary : old_primary;
    if (!mprimary || mprimary->state() != DRM_MODE_CONNECTED) {
    mprimary = NULL;
    for (auto &conn : drm_->connectors()) {
     if (!(conn->possible_displays() & HWC_DISPLAY_PRIMARY_BIT))
       continue;
     if (conn->state() == DRM_MODE_CONNECTED) {
       mprimary = conn.get();
       break;
     }
    }
    }

    if (!mprimary) {
        ALOGE("%s %d Failed to find primary display\n", __FUNCTION__, __LINE__);
        return;
    }
    if (mprimary != old_primary) {
        drm_->SetPrimaryDisplay(mprimary);
    }

    DrmConnector *old_extend = drm_->GetConnectorFromType(HWC_DISPLAY_EXTERNAL);
    mextend = mextend ? mextend : old_extend;
    if (!mextend || mextend->state() != DRM_MODE_CONNECTED) {
        mextend = NULL;
        for (auto &conn : drm_->connectors()) {
            if (!(conn->possible_displays() & HWC_DISPLAY_EXTERNAL_BIT))
                continue;
            if (conn->id() == mprimary->id())
                continue;
            if (conn->state() == DRM_MODE_CONNECTED) {
                mextend = conn.get();
                break;
            }
        }
    }
    drm_->SetExtendDisplay(mextend);

    updateConnectors();
}



static void nativeInit(JNIEnv* env, jobject obj) {
    if (drm_ == NULL) {
        drm_ = new DrmResources();
        drm_->Init();
        ALOGD("nativeInit: ");
        hotPlugUpdate();
        if (primary == NULL) {
            for (auto &conn : drm_->connectors()) {
                if ((conn->possible_displays() & HWC_DISPLAY_PRIMARY_BIT)) {
                    drm_->SetPrimaryDisplay(conn.get());
                    primary = conn.get();
                }
                if ((conn->possible_displays() & HWC_DISPLAY_EXTERNAL_BIT) && conn->state() == DRM_MODE_CONNECTED) {
                    drm_->SetExtendDisplay(conn.get());
                    extend = conn.get();
                }
            }
        }
        ALOGD("primary: %p extend: %p", primary, extend);
    }
}

#define BUFFER_LENGTH    256
#define RESOLUTION_AUTO 1<<0
#define COLOR_AUTO (1<<1)
#define HDCP1X_EN (1<<2)
#define RESOLUTION_WHITE_EN (1<<3)

struct Resolution
{
    unsigned int hdisplay;
    unsigned int vdisplay;
    unsigned int dclk;
    unsigned int hsync_start;
    unsigned int hsync_end;
    unsigned int htotal;
    unsigned int vsync_start;
    unsigned int vsync_end;
    unsigned int vtotal;
    unsigned int flags;
};

enum output_format {
    output_rgb=0,
    output_ycbcr444=1,
    output_ycbcr422=2,
    output_ycbcr420=3,
    output_ycbcr_high_subsampling=4,  // (YCbCr444 > YCbCr422 > YCbCr420 > RGB)
    output_ycbcr_low_subsampling=5	, // (RGB > YCbCr420 > YCbCr422 > YCbCr444)
    invalid_output=6,
};

enum  output_depth{
    Automatic=0,
    depth_24bit=8,
    depth_30bit=10,
};

struct overscan {
    unsigned short maxvalue;
    unsigned short leftscale;
    unsigned short rightscale;
    unsigned short topscale;
    unsigned short bottomscale;
};

struct disp_info{
    struct Resolution resolution;
    struct overscan scan;
    enum output_format  format;
    enum output_depth depthc;
    unsigned int feature;
    unsigned int reserve[10];
};


struct file_base_paramer
{
    struct disp_info main;
    struct disp_info aux;
};

static char const *const device_template[] =
{
    "/dev/block/platform/1021c000.rksdmmc/by-name/baseparamer",
    "/dev/block/platform/30020000.rksdmmc/by-name/baseparamer",
    "/dev/block/platform/ff0f0000.rksdmmc/by-name/baseparamer",
    "/dev/block/platform/ff520000.rksdmmc/by-name/baseparamer",
    "/dev/block/rknand_baseparamer",
    NULL
};

const char* GetBaseparameterFile(void)
{
    int i = 0;

    while (device_template[i]) {
        if (!access(device_template[i], R_OK | W_OK))
            return device_template[i];
        i++;
    }
    return NULL;
}

static void nativeSaveConfig(JNIEnv* env, jobject obj) {
    char buf[BUFFER_LENGTH];
    int foundMainIdx=-1,foundAuxIdx=-1;
    struct file_base_paramer base_paramer;

    if (primary != NULL) {
        std::vector<DrmMode> mModes = primary->modes();
        char resolution[PROPERTY_VALUE_MAX];
        unsigned int w=0,h=0,hsync_start=0,hsync_end=0,htotal=0;
        unsigned int vsync_start=0,vsync_end=0,vtotal=0,flags=0;
        float vfresh=0;

        property_get("persist.sys.resolution.main", resolution, "0x0@0.00-0-0-0-0-0-0-0");
        if (strncmp(resolution, "Auto", 4) != 0 && strncmp(resolution, "0x0p0-0", 7) !=0)
            sscanf(resolution,"%dx%d@%f-%d-%d-%d-%d-%d-%d-%x", &w, &h, &vfresh, &hsync_start,&hsync_end,&htotal,&vsync_start,&vsync_end,
            &vtotal, &flags);
        for (size_t c = 0; c < mModes.size(); ++c){
            const DrmMode& info = mModes[c];
            char formatRefresh[16];
            sprintf(formatRefresh, "%.2f", info.v_refresh());

            if (info.h_display() == w &&
                info.v_display() == h &&
                info.h_sync_start() == hsync_start &&
                info.h_sync_end() == hsync_end &&
                info.h_total() == htotal &&
                info.v_sync_start() == vsync_start &&
                info.v_sync_end() == vsync_end &&
                info.v_total()==vtotal &&
                atof(formatRefresh)==vfresh) {
                ALOGD("***********************found main idx %d ****************", (int)c);
                foundMainIdx = c;
                sprintf(buf, "display=%d,iface=%d,enable=%d,mode=%s\n",
                primary->display(), primary->get_type(), primary->state(), resolution);
                break;
            }
        }
    }

    if (extend != NULL) {
        std::vector<DrmMode> mModes = extend->modes();
        char resolution[PROPERTY_VALUE_MAX];
        unsigned int w=0,h=0,hsync_start=0,hsync_end=0,htotal=0;
        unsigned int vsync_start=0,vsync_end=0,vtotal=0,flags;
        float vfresh=0;

        property_get("persist.sys.resolution.aux", resolution, "0x0@0.00-0-0-0-0-0-0-0");
        if (strncmp(resolution, "Auto", 4) != 0 && strncmp(resolution, "0x0p0-0", 7) !=0)
            sscanf(resolution,"%dx%d@%f-%d-%d-%d-%d-%d-%d-%x", &w, &h, &vfresh, &hsync_start,&hsync_end,&htotal,&vsync_start,&vsync_end,
            &vtotal, &flags);
        for (size_t c = 0; c < mModes.size(); ++c){
            const DrmMode& info = mModes[c];
            char formatRefresh[16];
            sprintf(formatRefresh, "%.2f", info.v_refresh());
            if (info.h_display() == w &&
                info.v_display() == h &&
                info.h_sync_start() == hsync_start &&
                info.h_sync_end() == hsync_end &&
                info.h_total() == htotal &&
                info.v_sync_start() == vsync_start &&
                info.v_sync_end() == vsync_end &&
                info.v_total()==vtotal &&
                atof(formatRefresh)==vfresh) {
                ALOGD("***********************found main idx %d ****************", (int)c);
                foundAuxIdx = c;
                break;
            }
        }
    }

    int file;
    const char *baseparameterfile = GetBaseparameterFile();
    if (!baseparameterfile) {
        ALOGW("base paramter file can not be find");
        sync();
        return;
    }
    file = open(baseparameterfile, O_RDWR);
    if (file < 0) {
        ALOGW("base paramter file can not be opened");
        sync();
        return;
    }
    // caculate file's size and read it
    unsigned int length = lseek(file, 0L, SEEK_END);
    lseek(file, 0L, SEEK_SET);
    if(length < sizeof(base_paramer)) {
        ALOGE("BASEPARAME data's length is error\n");
        sync();
        close(file);
        return;
    }

    read(file, (void*)&base_paramer, sizeof(file_base_paramer));

    for (auto &conn : drm_->connectors()) {
        if (conn->state() == DRM_MODE_CONNECTED && (conn->possible_displays() & HWC_DISPLAY_PRIMARY_BIT)) {
            char resolution[PROPERTY_VALUE_MAX];
            int w=0,h=0,hsync_start=0,hsync_end=0,htotal=0;
            int vsync_start=0,vsync_end=0,vtotal=0,flags=0;
            int left=0,top=0,right=0,bottom=0;
            float vfresh=0;

            property_get("persist.sys.resolution.main", resolution, "0x0@0.00-0-0-0-0-0-0-0");
            if (strncmp(resolution, "Auto", 4) != 0 && strncmp(resolution, "0x0p0-0", 7) !=0) {
                std::vector<DrmMode> mModes = primary->modes();
                sscanf(resolution,"%dx%d@%f-%d-%d-%d-%d-%d-%d-%x", &w, &h, &vfresh, &hsync_start,&hsync_end,&htotal,&vsync_start,&vsync_end,
                &vtotal, &flags);

                base_paramer.main.resolution.hdisplay = w;
                base_paramer.main.resolution.vdisplay = h;
                base_paramer.main.resolution.hsync_start = hsync_start;
                base_paramer.main.resolution.hsync_end = hsync_end;
                if (foundMainIdx != -1)
                    base_paramer.main.resolution.dclk = mModes[foundMainIdx].clock();
                else
                    base_paramer.main.resolution.dclk = htotal*vtotal*vfresh;
                base_paramer.main.resolution.htotal = htotal;
                base_paramer.main.resolution.vsync_start = vsync_start;
                base_paramer.main.resolution.vsync_end = vsync_end;
                base_paramer.main.resolution.vtotal = vtotal;
                base_paramer.main.resolution.flags = flags;
            } else {
                base_paramer.main.feature|= RESOLUTION_AUTO;
            }

            memset(resolution,0,sizeof(resolution));
            property_get("persist.sys.overscan.main", resolution, "overscan 100,100,100,100");
            sscanf(resolution, "overscan %d,%d,%d,%d",
                    &left,
                    &top,
                    &right,
                    &bottom);
            base_paramer.main.scan.leftscale = (unsigned short)left;
            base_paramer.main.scan.topscale = (unsigned short)top;
            base_paramer.main.scan.rightscale = (unsigned short)right;
            base_paramer.main.scan.bottomscale = (unsigned short)bottom;

            memset(resolution,0,sizeof(resolution));
            property_get("persist.sys.color.main", resolution, "Auto");
            if (strncmp(resolution, "Auto", 4) != 0){
                char color[16];
                char depth[16];

                sscanf(resolution, "%s-%s", color, depth);
                if (strncmp(color, "RGB", 3) == 0)
                    base_paramer.main.format = output_rgb;
                else if (strncmp(color, "YCBCR444", 8) == 0)
                    base_paramer.main.format = output_ycbcr444;
                else if (strncmp(color, "YCBCR422", 8) == 0)
                    base_paramer.main.format = output_ycbcr422;
                else if (strncmp(color, "YCBCR420", 8) == 0)
                    base_paramer.main.format = output_ycbcr420;
                else
                    base_paramer.main.feature |= COLOR_AUTO;

                if (strncmp(depth, "8bit", 4) == 0)
                    base_paramer.main.depthc = depth_24bit;
                else if (strncmp(depth, "10bit", 5) == 0)
                    base_paramer.main.depthc = depth_30bit;
                else
                    base_paramer.main.depthc = Automatic;
            } else {
                base_paramer.main.feature |= COLOR_AUTO;
            }

            memset(resolution,0,sizeof(resolution));
            property_get("persist.sys.hdcp1x.main", resolution, "0");
            if (atoi(resolution) > 0)
                base_paramer.main.feature |= HDCP1X_EN;

            memset(resolution,0,sizeof(resolution));
            property_get("persist.sys.resolution_white.main", resolution, "0");
            if (atoi(resolution) > 0)
                base_paramer.main.feature |= RESOLUTION_WHITE_EN;
        } else if(conn->state() == DRM_MODE_CONNECTED && (conn->possible_displays() & HWC_DISPLAY_EXTERNAL_BIT)) {
            char resolution[PROPERTY_VALUE_MAX];
            int w=0,h=0,hsync_start=0,hsync_end=0,htotal=0;
            int vsync_start=0,vsync_end=0,vtotal=0,flags=0;
            float vfresh=0;
            int left=0,top=0,right=0,bottom=0;

            property_get("persist.sys.resolution.aux", resolution, "0x0p0-0");
            if (strncmp(resolution, "Auto", 4) != 0 && strncmp(resolution, "0x0p0-0", 7) !=0) {
                std::vector<DrmMode> mModes = extend->modes();
                sscanf(resolution,"%dx%d@%f-%d-%d-%d-%d-%d-%d-%x", &w, &h, &vfresh, &hsync_start,&hsync_end,&htotal,&vsync_start,&vsync_end,
                &vtotal, &flags);
                base_paramer.aux.resolution.hdisplay = w;
                base_paramer.aux.resolution.vdisplay = h;
                if (foundMainIdx != -1)
                    base_paramer.aux.resolution.dclk = mModes[foundMainIdx].clock();
                else
                    base_paramer.aux.resolution.dclk = htotal*vtotal*vfresh;
                base_paramer.aux.resolution.hsync_start = hsync_start;
                base_paramer.aux.resolution.hsync_end = hsync_end;
                base_paramer.aux.resolution.htotal = htotal;
                base_paramer.aux.resolution.vsync_start = vsync_start;
                base_paramer.aux.resolution.vsync_end = vsync_end;
                base_paramer.aux.resolution.vtotal = vtotal;
                base_paramer.aux.resolution.flags = flags;
            } else {
                base_paramer.aux.feature |= RESOLUTION_AUTO;
            }

            memset(resolution,0,sizeof(resolution));
            property_get("persist.sys.overscan.aux", resolution, "overscan 100,100,100,100");
            sscanf(resolution, "overscan %d,%d,%d,%d",
                    &left,
                    &top,
                    &right,
                    &bottom);
            base_paramer.aux.scan.leftscale = (unsigned short)left;
            base_paramer.aux.scan.topscale = (unsigned short)top;
            base_paramer.aux.scan.rightscale = (unsigned short)right;
            base_paramer.aux.scan.bottomscale = (unsigned short)bottom;

            memset(resolution,0,sizeof(resolution));
            property_get("persist.sys.color.aux", resolution, "Auto");
            if (strncmp(resolution, "Auto", 4) != 0){
                char color[16];
                char depth[16];

                sscanf(resolution, "%s-%s", color, depth);
                if (strncmp(color, "RGB", 3) == 0)
                    base_paramer.aux.format = output_rgb;
                else if (strncmp(color, "YCBCR444", 8) == 0)
                    base_paramer.aux.format = output_ycbcr444;
                else if (strncmp(color, "YCBCR422", 8) == 0)
                    base_paramer.aux.format = output_ycbcr422;
                else if (strncmp(color, "YCBCR420", 8) == 0)
                    base_paramer.aux.format = output_ycbcr420;
                else
                    base_paramer.aux.feature |= COLOR_AUTO;

                if (strncmp(depth, "8bit", 4) == 0)
                    base_paramer.aux.depthc = depth_24bit;
                else if (strncmp(depth, "10bit", 5) == 0)
                    base_paramer.aux.depthc = depth_30bit;
                else
                    base_paramer.aux.depthc = Automatic;
            } else {
                base_paramer.aux.feature |= COLOR_AUTO;
            }

            memset(resolution,0,sizeof(resolution));
            property_get("persist.sys.hdcp1x.aux", resolution, "0");
            if (atoi(resolution) > 0)
                base_paramer.aux.feature |= HDCP1X_EN;

            memset(resolution,0,sizeof(resolution));
            property_get("persist.sys.resolution_white.aux", resolution, "0");
            if (atoi(resolution) > 0)
                base_paramer.aux.feature |= RESOLUTION_WHITE_EN;

        }
    }
    lseek(file, 0L, SEEK_SET);
    write(file, (char*)(&base_paramer), sizeof(base_paramer));
    close(file);
    sync();

    ALOGD("[%s] hdmi:%d,%d,%d,%d,%d,%d foundMainIdx %d\n", __FUNCTION__,
            base_paramer.main.resolution.hdisplay,
            base_paramer.main.resolution.vdisplay,
            base_paramer.main.resolution.hsync_start,
            base_paramer.main.resolution.hsync_end,
            base_paramer.main.resolution.htotal,
            base_paramer.main.resolution.flags,
            foundMainIdx);

    ALOGD("[%s] tve:%d,%d,%d,%d,%d,%d foundAuxIdx %d\n", __FUNCTION__,
            base_paramer.aux.resolution.hdisplay,
            base_paramer.aux.resolution.vdisplay,
            base_paramer.aux.resolution.hsync_start,
            base_paramer.aux.resolution.hsync_end,
            base_paramer.aux.resolution.htotal,
            base_paramer.aux.resolution.flags,
            foundAuxIdx);

}

static void nativeUpdateConnectors(JNIEnv* env, jobject obj){
    hotPlugUpdate();
}

static void nativeSetMode(JNIEnv* env, jobject obj, jint dpy,jint iface_type, jstring mode)
{
    int display = dpy;
    int type = iface_type;
    const char* mMode = env->GetStringUTFChars(mode, NULL);

    ALOGD("nativeSetMode %s display %d iface_type %d", mMode, display, type);
    if (display == HWC_DISPLAY_PRIMARY){
        property_set("persist.sys.resolution.main", mMode);
    } else if (display == HWC_DISPLAY_EXTERNAL) {
        property_set("persist.sys.resolution.aux", mMode);
    }

}

static jstring nativeGetCurMode(JNIEnv* env, jobject obj, jint dpy, jint iface_type)
{
    int display=dpy;
    int type = iface_type;
    char resolution[PROPERTY_VALUE_MAX];

    ALOGD("nativeGetCurMode: dpy %d iface_type %d", display, type);
    if (display == HWC_DISPLAY_PRIMARY){
        property_get("persist.sys.resolution.main", resolution, "0x0p0-0");
    } else if (display == HWC_DISPLAY_EXTERNAL) {
        property_get("persist.sys.resolution.aux", resolution, "0x0p0-0");
    }

    return env->NewString((const jchar*)resolution, strlen(resolution));
}

static jint nativeGetNumConnectors(JNIEnv* env, jobject obj)
{
    int numConnectors=0;

    numConnectors = drm_->connectors().size();
    return static_cast<jint>(numConnectors);
}


static jint nativeGetConnectionState(JNIEnv* env, jobject obj, jint dpy)
{
    drmModeConnection cur_state=DRM_MODE_UNKNOWNCONNECTION;

    if (dpy == HWC_DISPLAY_PRIMARY && primary)
        cur_state = primary->state();
    else if (dpy == HWC_DISPLAY_EXTERNAL && extend)
        cur_state = extend->state();

    ALOGD("nativeGetConnectionState cur_state %d ", cur_state);
    return static_cast<jint>(cur_state);
}

static jint nativeGetBuiltIn(JNIEnv* env, jobject obj, jint dpy)
{
    int built_in=0;

    if (dpy == HWC_DISPLAY_PRIMARY && primary)
        built_in = primary->get_type();
    else if (dpy == HWC_DISPLAY_EXTERNAL && extend)
        built_in = extend->get_type();
    else
        built_in = 0;

    return static_cast<jint>(built_in);
}

static jobject nativeGetCorlorModeConfigs(JNIEnv* env, jclass clazz,
        jint dpy){
    int display = dpy;
    DrmConnector* mCurConnector;
    uint64_t color_capacity=0;
    uint64_t depth_capacity=0;
    jobject infoObj = env->NewObject(gRkColorModeSupportInfo.clazz,
                gRkColorModeSupportInfo.ctor);

    if (display == HWC_DISPLAY_PRIMARY) {
        mCurConnector = primary;
    }else if (display == HWC_DISPLAY_EXTERNAL){
        mCurConnector = extend;
    } else {
        return NULL;
    }

    if (mCurConnector != NULL) {
        if (mCurConnector->hdmi_output_mode_capacity_property().id())
            mCurConnector->hdmi_output_mode_capacity_property().value( &color_capacity);

        if (mCurConnector->hdmi_output_depth_capacity_property().id())
            mCurConnector->hdmi_output_depth_capacity_property().value(&depth_capacity);

        env->SetIntField(infoObj, gRkColorModeSupportInfo.color_capa, (int)color_capacity);
        env->SetIntField(infoObj, gRkColorModeSupportInfo.depth_capa, (int)depth_capacity);
        ALOGD("nativeGetCorlorModeConfigs: corlor=%d depth=%d ",(int)color_capacity,(int)depth_capacity);
    }

    return infoObj;
}

static jobjectArray nativeGetDisplayConfigs(JNIEnv* env, jclass clazz,
        jint dpy) {
    int display = dpy;

    std::vector<DrmMode> mModes;
    DrmConnector* mCurConnector;
    int idx=0;

    if (display == HWC_DISPLAY_PRIMARY) {
        mCurConnector = primary;
        if (primary != NULL)
            mModes = primary->modes();
        ALOGD("primary built_in %d", mCurConnector->built_in());
    }else if (display == HWC_DISPLAY_EXTERNAL){
        if (extend != NULL) {
            mCurConnector = extend;
            mModes = extend->modes();
            ALOGD("extend : %d", extend->built_in());
        } else
            return NULL;
    } else {
        return NULL;
    }

    if (mModes.size() == 0)
        return NULL;

    jobjectArray configArray = env->NewObjectArray(mModes.size(),
            gRkPhysicalDisplayInfoClassInfo.clazz, NULL);

    for (size_t c = 0; c < mModes.size(); ++c) {
        const DrmMode& info = mModes[c];
        float vfresh;
        vfresh = info.clock()/ (float)(info.v_total()* info.h_total()) * 1000.0f;
        jobject infoObj = env->NewObject(gRkPhysicalDisplayInfoClassInfo.clazz,
                gRkPhysicalDisplayInfoClassInfo.ctor);
        env->SetIntField(infoObj, gRkPhysicalDisplayInfoClassInfo.width, info.h_display());
        env->SetIntField(infoObj, gRkPhysicalDisplayInfoClassInfo.height, info.v_display());
        env->SetFloatField(infoObj, gRkPhysicalDisplayInfoClassInfo.refreshRate, vfresh);//1000 * 1000 * 1000 /
        env->SetIntField(infoObj, gRkPhysicalDisplayInfoClassInfo.clock, info.clock());
        env->SetIntField(infoObj, gRkPhysicalDisplayInfoClassInfo.flags, info.flags());
        env->SetBooleanField(infoObj, gRkPhysicalDisplayInfoClassInfo.interlaceFlag, info.flags()&(1<<4));
        env->SetBooleanField(infoObj, gRkPhysicalDisplayInfoClassInfo.yuvFlag, (info.flags()&(1<<24) || info.flags()&(1<<23)));
        env->SetIntField(infoObj, gRkPhysicalDisplayInfoClassInfo.connectorId, mCurConnector->id());//mode_type
        env->SetIntField(infoObj, gRkPhysicalDisplayInfoClassInfo.mode_type, info.type());
        env->SetIntField(infoObj, gRkPhysicalDisplayInfoClassInfo.idx, idx);
        env->SetIntField(infoObj, gRkPhysicalDisplayInfoClassInfo.hsync_start, info.h_sync_start());
        env->SetIntField(infoObj, gRkPhysicalDisplayInfoClassInfo.hsync_end, info.h_sync_end());
        env->SetIntField(infoObj, gRkPhysicalDisplayInfoClassInfo.htotal, info.h_total());
        env->SetIntField(infoObj, gRkPhysicalDisplayInfoClassInfo.hskew, info.h_skew());
        env->SetIntField(infoObj, gRkPhysicalDisplayInfoClassInfo.vsync_start, info.v_sync_start());
        env->SetIntField(infoObj, gRkPhysicalDisplayInfoClassInfo.vsync_end, info.v_sync_end());
        env->SetIntField(infoObj, gRkPhysicalDisplayInfoClassInfo.vtotal, info.v_total());
        env->SetIntField(infoObj, gRkPhysicalDisplayInfoClassInfo.vscan, info.v_scan());
        idx++;
        ALOGV("display%d. mode[%d]  %dx%d info.fps %f clock %d   hsync_start %d hsync_enc %d htotal %d hskew %d", 
            display,(int)c, info.h_display(), info.v_display(), info.v_refresh(), info.clock(),  info.h_sync_start(),info.h_sync_end(),
            info.h_total(), info.h_skew());
        ALOGV("vsync_start %d vsync_end %d vtotal %d vscan %d flags 0x%x", info.v_sync_start(), info.v_sync_end(),
            info.v_total(), info.v_scan(), info.flags());

        env->SetObjectArrayElement(configArray, static_cast<jsize>(c), infoObj);
        env->DeleteLocalRef(infoObj);
    }

    return configArray;
}


// ----------------------------------------------------------------------------
//com.android.server.rkdisplay
static const JNINativeMethod sRkDrmModeMethods[] = {
    {"nativeInit", "()V",
            (void*) nativeInit},
    {"nativeUpdateConnectors", "()V",
            (void*) nativeUpdateConnectors},
    {"nativeSaveConfig", "()V",
            (void*) nativeSaveConfig},
    {"nativeGetDisplayConfigs", "(I)[Lcom/android/server/rkdisplay/RkDisplayModes$RkPhysicalDisplayInfo;",
            (void*)nativeGetDisplayConfigs},
    {"nativeGetNumConnectors", "()I",
            (void*)nativeGetNumConnectors},
    {"nativeSetMode", "(IILjava/lang/String;)V",
            (void*)nativeSetMode},
    {"nativeGetCurMode", "(II)Ljava/lang/String;",
            (void*)nativeGetCurMode},
    {"nativeGetBuiltIn", "(I)I",
            (void*)nativeGetBuiltIn},
    {"nativeGetConnectionState", "(I)I",
            (void*)nativeGetConnectionState},
    {"nativeGetCorlorModeConfigs", "(I)Lcom/android/server/rkdisplay/RkDisplayModes$RkColorCapacityInfo;",
            (void*)nativeGetCorlorModeConfigs},

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

int register_com_android_server_rkdisplay_RkDisplayModes(JNIEnv* env)
{
    int res = jniRegisterNativeMethods(env, "com/android/server/rkdisplay/RkDisplayModes",
            sRkDrmModeMethods, NELEM(sRkDrmModeMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods register_com_android_server_rkdisplay_RkDisplayModes");
    (void)res; // Don't complain about unused variable in the LOG_NDEBUG case

    jclass clazz;
    FIND_CLASS(clazz, "com/android/server/rkdisplay/RkDisplayModes");

    FIND_CLASS(gRkPhysicalDisplayInfoClassInfo.clazz, "com/android/server/rkdisplay/RkDisplayModes$RkPhysicalDisplayInfo");
    gRkPhysicalDisplayInfoClassInfo.clazz = jclass(env->NewGlobalRef(gRkPhysicalDisplayInfoClassInfo.clazz));
    GET_METHOD_ID(gRkPhysicalDisplayInfoClassInfo.ctor,
            gRkPhysicalDisplayInfoClassInfo.clazz, "<init>", "()V");
    GET_FIELD_ID(gRkPhysicalDisplayInfoClassInfo.width, gRkPhysicalDisplayInfoClassInfo.clazz, "width", "I");
    GET_FIELD_ID(gRkPhysicalDisplayInfoClassInfo.height, gRkPhysicalDisplayInfoClassInfo.clazz, "height", "I");
    GET_FIELD_ID(gRkPhysicalDisplayInfoClassInfo.refreshRate, gRkPhysicalDisplayInfoClassInfo.clazz, "refreshRate", "F");
    GET_FIELD_ID(gRkPhysicalDisplayInfoClassInfo.clock, gRkPhysicalDisplayInfoClassInfo.clazz, "clock", "I");
    GET_FIELD_ID(gRkPhysicalDisplayInfoClassInfo.flags, gRkPhysicalDisplayInfoClassInfo.clazz, "flags", "I");
    GET_FIELD_ID(gRkPhysicalDisplayInfoClassInfo.interlaceFlag, gRkPhysicalDisplayInfoClassInfo.clazz, "interlaceFlag", "Z");
    GET_FIELD_ID(gRkPhysicalDisplayInfoClassInfo.yuvFlag, gRkPhysicalDisplayInfoClassInfo.clazz, "yuvFlag", "Z");
    GET_FIELD_ID(gRkPhysicalDisplayInfoClassInfo.connectorId, gRkPhysicalDisplayInfoClassInfo.clazz, "connectorId", "I");
    GET_FIELD_ID(gRkPhysicalDisplayInfoClassInfo.mode_type, gRkPhysicalDisplayInfoClassInfo.clazz, "mode_type", "I");
    GET_FIELD_ID(gRkPhysicalDisplayInfoClassInfo.idx, gRkPhysicalDisplayInfoClassInfo.clazz, "idx", "I");

    GET_FIELD_ID(gRkPhysicalDisplayInfoClassInfo.hsync_start, gRkPhysicalDisplayInfoClassInfo.clazz, "hsync_start", "I");
    GET_FIELD_ID(gRkPhysicalDisplayInfoClassInfo.hsync_end, gRkPhysicalDisplayInfoClassInfo.clazz, "hsync_end", "I");
    GET_FIELD_ID(gRkPhysicalDisplayInfoClassInfo.htotal, gRkPhysicalDisplayInfoClassInfo.clazz, "htotal", "I");
    GET_FIELD_ID(gRkPhysicalDisplayInfoClassInfo.hskew, gRkPhysicalDisplayInfoClassInfo.clazz, "hskew", "I");
    GET_FIELD_ID(gRkPhysicalDisplayInfoClassInfo.vsync_start, gRkPhysicalDisplayInfoClassInfo.clazz, "vsync_start", "I");
    GET_FIELD_ID(gRkPhysicalDisplayInfoClassInfo.vsync_end, gRkPhysicalDisplayInfoClassInfo.clazz, "vsync_end", "I");
    GET_FIELD_ID(gRkPhysicalDisplayInfoClassInfo.vtotal, gRkPhysicalDisplayInfoClassInfo.clazz, "vtotal", "I");
    GET_FIELD_ID(gRkPhysicalDisplayInfoClassInfo.vscan, gRkPhysicalDisplayInfoClassInfo.clazz, "vscan", "I");

     FIND_CLASS(gRkColorModeSupportInfo.clazz, "com/android/server/rkdisplay/RkDisplayModes$RkColorCapacityInfo");
     gRkColorModeSupportInfo.clazz = jclass(env->NewGlobalRef(gRkColorModeSupportInfo.clazz));
     GET_METHOD_ID(gRkColorModeSupportInfo.ctor,
             gRkColorModeSupportInfo.clazz, "<init>", "()V");
     GET_FIELD_ID(gRkColorModeSupportInfo.color_capa, gRkColorModeSupportInfo.clazz, "color_capa", "I");
     GET_FIELD_ID(gRkColorModeSupportInfo.depth_capa, gRkColorModeSupportInfo.clazz, "depth_capa", "I");
    return 0;
}
};

