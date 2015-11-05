package com.android.systemui.screenshot;

import android.content.Context;
import android.os.storage.DiskInfo;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.provider.Settings;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.io.File;
import android.os.storage.StorageVolume;

/**
 *
 */
public class ScreenshotUtils {
    /**
     *
     * @param context
     * @return
     */
    public static String getScreenshotSavePath(Context context) {
        StorageManager storageManager = context.getSystemService(StorageManager.class);
        final List<VolumeInfo> volumes = storageManager.getVolumes();
        Collections.sort(volumes, VolumeInfo.getDescriptionComparator());
        List<VolumeInfo> flashVolumes = new ArrayList<VolumeInfo>();
        List<VolumeInfo> sdVolumes = new ArrayList<VolumeInfo>();
        List<VolumeInfo> usbVolumes = new ArrayList<VolumeInfo>();
        for (VolumeInfo vol : volumes) {
            if(vol.getType() == VolumeInfo.TYPE_EMULATED) {
                flashVolumes.add(vol);
            } else if(vol.getType() == VolumeInfo.TYPE_PUBLIC) {
                DiskInfo disk = vol.getDisk();
                if(disk != null) {
                    if(disk.isSd()) {
                        sdVolumes.add(vol);
                    } else if(disk.isUsb()) {
                        usbVolumes.add(vol);
                    }
                }
            }
        }
        VolumeInfo saveVol = null;
        String screenshotLocation = Settings.System.getString(context.getContentResolver(),
                Settings.System.SCREENSHOT_LOCATION);
        if(Settings.System.SCREENSHOT_LOCATION_INTERNAL_SD.equals(screenshotLocation)) {
            if(!flashVolumes.isEmpty()) {
                saveVol = flashVolumes.get(0);
            }
        } else if(Settings.System.SCREENSHOT_LOCATION_EXTERNAL_SD.equals(screenshotLocation)) {
            if(!sdVolumes.isEmpty()) {
                saveVol = sdVolumes.get(0);
            }
        } else if(Settings.System.SCREENSHOT_LOCATION_USB.equals(screenshotLocation)) {
            if(!usbVolumes.isEmpty()) {
                saveVol = usbVolumes.get(0);
            }
        }
        String imageDir = null;
        if(saveVol != null) {
            StorageVolume sv = saveVol.buildStorageVolume(context, context.getUserId(), false);
            imageDir = sv.getPath();
        }
        Log.e("Screenshot", "screenshot_location " + screenshotLocation + " image_dir=" + imageDir);
        return imageDir;
    }
}
