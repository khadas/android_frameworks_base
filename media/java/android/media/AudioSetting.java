package android.media;

import android.content.ContentResolver;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioSystem;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.ArrayList;



import android.util.Log;
import android.os.SystemProperties;
import android.provider.Settings;

/**
 * {@hide}
 */

public class AudioSetting {
    private static final String TAG = "AudioSetting";
    protected static final boolean DEBUG_SETTING = Log.isLoggable(TAG + ".SETTING", Log.DEBUG);
    //mode
    public static String AUDIO_SETTING_MODE_HDMI_AUTO_PREF = "Auto";
    public static String AUDIO_SETTING_MODE_DECODE_PREF = "Decode";
    public static String AUDIO_SETTING_MODE_MANUAL_PREF = "Manual";

    //format
    public static String AUDIO_SETTING_FORMAT_AC3 = "AC3";
    public static String AUDIO_SETTING_FORMAT_EAC3 = "EAC3";
    public static String AUDIO_SETTING_FORMAT_TRUEHD = "TrueHD";
    public static String AUDIO_SETTING_FORMAT_DTS = "DTS";
    public static String AUDIO_SETTING_FORMAT_DTSHD = "DTS-HD";

    public static String AUDIO_SETTING_DEVICE_HDMI_PREF = "HDMI";
    public static String AUDIO_SETTING_DEVICE_SPDIF_PREF = "SPDIF";

    public static String AUDIO_SUPPORT_BITSTREAM_FORMAT = "sup_bitstream_formats";
    private static String DEVICES_PREF = "Device:";
    private static String MODES_PREF = "Mode:";
    private static String FORMATS_PREF = "Format:";
    private static String SPERATOR = ";";
    private final String NONE = "NONE";

    private final String PROPERTY_NAME = "persist.system.audio.setting";

    public static final int AUDIO_SETTING_MODE_DECODE = 0;
    public static final int AUDIO_SETTING_MODE_BITSTREAM_AUTO = 1;
    public static final int AUDIO_SETTING_MODE_BITSTREAM_MANUAL = 2;

    private int mDevice = AudioSystem.DEVICE_NONE;
    private int mMode = AUDIO_SETTING_MODE_DECODE;

   // private AudioManager mAudioManager;
    private Context mContext;

    // store formats and formats string from audio hal
    private Map<Integer,String> BITSTREAM_FORMAT_MAP = new HashMap<Integer,String>();

    // store formats and formats string write to property
    private Map<Integer,String> BITSTREAM_SETTING_MAP = new HashMap<Integer,String>();

    private List<Integer> mSupport = new ArrayList<Integer>();

    private static final Object mLock = new Object();

    public AudioSetting(Context context) {
        mContext = context;
        initFormatsMap();
    }

    public boolean isEnable(int format) {
        boolean enable = mSupport.contains(format);
        if (DEBUG_SETTING) {
            Log.d(TAG,"isEnable: format = "+format+",enable = "+enable);
        }
        return enable;
    }

    public int getMode() {
        return mMode;
    }

    public int getDevice() {
        return mDevice;
    }

    private void initFormatsMap() {
        /*
         * Format and name map: the format name is reported by audio hal
         *                      the format is defined in AudioFormat.java
         */
        BITSTREAM_FORMAT_MAP.put(AudioFormat.ENCODING_AC3,"AUDIO_FORMAT_AC3");
        BITSTREAM_FORMAT_MAP.put(AudioFormat.ENCODING_E_AC3,"AUDIO_FORMAT_E_AC3");
        BITSTREAM_FORMAT_MAP.put(AudioFormat.ENCODING_DOLBY_TRUEHD,"AUDIO_FORMAT_DOLBY_TRUEHD");
        BITSTREAM_FORMAT_MAP.put(AudioFormat.ENCODING_E_AC3_JOC,"AUDIO_FORMAT_E_AC3_JOC");
        BITSTREAM_FORMAT_MAP.put(AudioFormat.ENCODING_AC4,"AUDIO_FORMAT_AC4");
        BITSTREAM_FORMAT_MAP.put(AudioFormat.ENCODING_DTS,"AUDIO_FORMAT_DTS");
        BITSTREAM_FORMAT_MAP.put(AudioFormat.ENCODING_DTS_HD,"AUDIO_FORMAT_DTS_HD");

        /*
         * Format and name map: the format name is will writed to property
         *                      the format is defined in AudioFormat.java
         */
        BITSTREAM_SETTING_MAP.put(AudioFormat.ENCODING_AC3,"AC3");
        BITSTREAM_SETTING_MAP.put(AudioFormat.ENCODING_E_AC3,"EAC3");
        BITSTREAM_SETTING_MAP.put(AudioFormat.ENCODING_DOLBY_TRUEHD,"TrueHD");
        BITSTREAM_SETTING_MAP.put(AudioFormat.ENCODING_E_AC3_JOC,"EAC3-JOC");
        BITSTREAM_SETTING_MAP.put(AudioFormat.ENCODING_AC4,"AC4");
        BITSTREAM_SETTING_MAP.put(AudioFormat.ENCODING_DTS,"DTS");
        BITSTREAM_SETTING_MAP.put(AudioFormat.ENCODING_DTS_HD,"DTS-HD");
    }

    public void readSetting() {
        String setting = getAudioSetting();
        parseSetting(setting);
        readFormats();
        if(mMode == AUDIO_SETTING_MODE_BITSTREAM_AUTO) {
            getSupportBitstream();
        }
    }

    public void readFormats() {
        mSupport.clear();
        if(mMode == AUDIO_SETTING_MODE_BITSTREAM_AUTO) {
            if(mDevice == AudioSystem.DEVICE_OUT_HDMI) {
                getSupportBitstream();
            } else {
                Log.e(TAG,"readFormat: only hdmi support auto mode");
            }
        } else if (mMode == AUDIO_SETTING_MODE_BITSTREAM_MANUAL){
            String format = null;
            if(mDevice == AudioSystem.DEVICE_OUT_HDMI) {
                format = getHdmiFormatsSetting();
            } else if(mDevice == AudioSystem.DEVICE_OUT_SPDIF){
                format = getSpdifFormatsSetting();
            }

            if(format != null) {
                parserSupportFormat(format);
            }
        }
    }

    /*
     * Get support formats which can bitstream.
     * This is only can call when output device = HDMI and mode = Auto
     */
    public void getSupportBitstream() {
        /*
         * There is no interface to query which format can bitstream.
         * So we use getParameters of audio system to query it using parameter
         * AUDIO_SUPPORT_BITSTREAM_FORMAT. The implements of this is implete in
         * audio hal, the return string is like this:
         * sup_bitstream_formats=AUDIO_FORMAT_AC3|AUDIO_FORMAT_E_AC3|AUDIO_FORMAT_DTS|AUDIO_FORMAT_DTS_HD
         */
        String formats = AudioSystem.getParameters(AUDIO_SUPPORT_BITSTREAM_FORMAT);
        if(!formats.startsWith(AUDIO_SUPPORT_BITSTREAM_FORMAT)) {
            Log.d(TAG,"getSupportBitstream: not starWith AUDIO_SUPPORT_BITSTREAM_FORMAT");
            return ;
        }

        mSupport.clear();
        Iterator<Map.Entry<Integer,String>> iterator = BITSTREAM_FORMAT_MAP.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer,String> entry = iterator.next();
            int format = entry.getKey();
            String name = entry.getValue();
            if (DEBUG_SETTING) {
                Log.d(TAG,"getSupportBitstream: name = "+name+",format = "+format);
            }
            if(formats.contains(name)) {
                mSupport.add(format);
            }
        }
    }

    /*
     * disable/enable formats which can bitstream.
     * fomat: format in AudioFormat, for example: AudioFormat.ENCODING_AC3
     * enable:  true: can bistream; false: disable bistream
     */
    public void setFormats(int format, boolean enable) {
        if(!BITSTREAM_FORMAT_MAP.containsKey(format)) {
            Log.d(TAG,"setFormats: format = "+format+" is not a valid format");
            return ;
        }

        if (DEBUG_SETTING) {
            Log.d(TAG,"setFormats: format = "+AudioFormat.toDisplayName(format)+",enable = "+enable);
        }
        synchronized(mSupport) {
            if(enable) {
                if(!isEnable(format)) {
                    mSupport.add(format);
                }
            } else {
                if(isEnable(format)) {
                    mSupport.remove(new Integer(format));
                }
            }
        }

        String formats = translateFormatsString();
        if(mDevice == AudioSystem.DEVICE_OUT_HDMI) {
            setHdmiFormatsSetting(formats);
        } else if(mDevice == AudioSystem.DEVICE_OUT_SPDIF) {
            setSpdifFormatsSetting(formats);
        }
    }

    /*
     * get formats string which are writed to property.
     */
    private String translateFormatsString() {
        boolean first = true;
        StringBuilder  builder = new StringBuilder(FORMATS_PREF);
        Iterator<Map.Entry<Integer,String>> iterator = BITSTREAM_SETTING_MAP.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer,String> entry = iterator.next();
            int format = entry.getKey();
            String name = entry.getValue();
            if(isEnable(format)) {
                if(first) {
                    first = false;
                    builder.append(name);
                } else {
                    builder.append(" "+name);
                }
            }
        }

        if (DEBUG_SETTING) {
            Log.d(TAG,"translateFormatsString: value = "+builder.toString());
        }
        return builder.toString();
    }

    /*
     * app call this function to set device and mode
     */
    public void setDeviceAndMode(int device,int mode) {
        if((device != AudioSystem.DEVICE_OUT_HDMI) &&
            (device != AudioSystem.DEVICE_OUT_SPDIF) &&
            (device != AudioSystem.DEVICE_NONE)) {
            Log.d(TAG,"setDeviceAndMode: device = "+device+" is not valid device");
            return ;
        }

        if((mode != AUDIO_SETTING_MODE_DECODE) &&
            (mode != AUDIO_SETTING_MODE_BITSTREAM_AUTO) &&
            (mode != AUDIO_SETTING_MODE_BITSTREAM_MANUAL)) {
            Log.d(TAG,"setDeviceAndMode: mode = "+mode+" is not valid device");
            return ;
        }

        if((mode == AUDIO_SETTING_MODE_BITSTREAM_AUTO) &&
            (device != AudioSystem.DEVICE_OUT_HDMI)) {
            Log.d(TAG,"setDeviceAndMode: mode = "+mode+" is not valid device");
            return ;
        }

        if (DEBUG_SETTING) {
            Log.d(TAG,"setDeviceAndMode: device = "+getDeviceName(device)+",mode = "+getModeName(mode));
        }
        mDevice = device;
        mMode = mode;

        String setting = translateAudioSettingString();
        setAudioSetting(setting);

        readSetting();
    }

    /*
     * return strings which will be writed to database
     * The String will like this:
     *                Device:HDMI;Mode:Auto
     *                Device:SPDIF;Mode:Manaul
     */
    private String translateAudioSettingString() {
        StringBuilder  builder = new StringBuilder(DEVICES_PREF);
        if(mDevice == AudioSystem.DEVICE_OUT_HDMI) {
            builder.append(AUDIO_SETTING_DEVICE_HDMI_PREF);
        } else if(mDevice == AudioSystem.DEVICE_OUT_SPDIF) {
            builder.append(AUDIO_SETTING_DEVICE_SPDIF_PREF);
        } else {
            builder.append(NONE);
        }
        builder.append(SPERATOR);

        builder.append(MODES_PREF);
        if(mMode == AUDIO_SETTING_MODE_BITSTREAM_AUTO) {
            if(mDevice != AudioSystem.DEVICE_OUT_HDMI) {
                Log.d(TAG,"update erro: only hdmi support auto mode");
            }
            builder.append(AUDIO_SETTING_MODE_HDMI_AUTO_PREF);
        } else if(mMode == AUDIO_SETTING_MODE_BITSTREAM_MANUAL) {
            builder.append(AUDIO_SETTING_MODE_MANUAL_PREF);
        } else {
            builder.append(AUDIO_SETTING_MODE_DECODE_PREF);
        }

        if (DEBUG_SETTING) {
            Log.d(TAG,"translateAudioSettingString: value = "+builder.toString());
        }
        return builder.toString();
    }

    /*
     * parse mode and device of AudioSetting
     * The String will like this:
     *                Device:HDMI;Mode:Auto
     *                Device:SPDIF;Mode:Manaul
     */
    private void parseSetting(String value) {
        if(value == null) {
            return ;
        }

        if (DEBUG_SETTING) {
            Log.d(TAG,"parseSetting: value = "+value);
        }
        String[] temp = value.split(";");
        for(int i = 0; i < temp.length; i++) {
            String name = temp[i];
            if(name.startsWith(DEVICES_PREF)) {
                parseDevice(name);
            } else if(name.startsWith(MODES_PREF)) {
                parseMode(name);
            }
        }
    }

    private void parserSupportFormat(String value) {
        if(value.startsWith(FORMATS_PREF)) {
            if (DEBUG_SETTING) {
                Log.d(TAG,"parserSupportFormat: formats = "+value);
            }
            mSupport.clear();
            parseFormat(value);
        }
    }

    private void parseDevice(String device) {
        if(device.startsWith(DEVICES_PREF)) {
            if(device.contains(AUDIO_SETTING_DEVICE_HDMI_PREF)) {
                mDevice = AudioSystem.DEVICE_OUT_HDMI;
            } else if(device.contains(AUDIO_SETTING_DEVICE_SPDIF_PREF)) {
                mDevice = AudioSystem.DEVICE_OUT_SPDIF;
            } else {
                mDevice = AudioSystem.DEVICE_NONE;
            }
        }
        if (DEBUG_SETTING) {
            Log.d(TAG,"parseDevice: device = "+device+", mDevice = "+mDevice);
        }
    }

    private void parseMode(String mode) {
        if(mode.startsWith(MODES_PREF)) {
            if(mode.contains(AUDIO_SETTING_MODE_HDMI_AUTO_PREF)) {
                mMode = AUDIO_SETTING_MODE_BITSTREAM_AUTO;
            } else if(mode.contains(AUDIO_SETTING_MODE_MANUAL_PREF)) {
                mMode = AUDIO_SETTING_MODE_BITSTREAM_MANUAL;
            } else {
                mMode = AUDIO_SETTING_MODE_DECODE;
            }

            if (DEBUG_SETTING) {
                Log.d(TAG,"parseMode: mode = "+mode+", mMode = "+mMode);
            }
        }
    }

    private void parseFormat(String formats) {
        // Format:AC3 EAC3 DTS DTS-HD TrueHD
        if(formats.startsWith(FORMATS_PREF)) {
            String temp = formats.substring(FORMATS_PREF.length());
            if(temp != null) {
                String[] formatArray = temp.split(" ");
                for(int i = 0; i < formatArray.length; i++) {
                    Iterator<Map.Entry<Integer,String>> iterator = BITSTREAM_SETTING_MAP.entrySet().iterator();
                    while (iterator.hasNext()) {
                        Map.Entry<Integer,String> entry = iterator.next();
                        int format = entry.getKey();
                        String name = entry.getValue();
                        if(formatArray[i].equals(name)) {
                            mSupport.add(format);
                        }
                    }
                }
            }
        }
    }

    public void hdmiAutoUpdate() {
        if((mDevice == AudioSystem.DEVICE_OUT_HDMI) &&
            (mMode == AUDIO_SETTING_MODE_BITSTREAM_AUTO)) {
            getSupportBitstream();
            update();
        }
    }

    public void update() {
        StringBuilder  builder = new StringBuilder();
        String deviceForamt = translateAudioSettingString();
        String formats = translateFormatsString();

        builder.append(deviceForamt);
        builder.append(SPERATOR);
        builder.append(formats);

        SystemProperties.set(PROPERTY_NAME,builder.toString());
        try{
            Runtime.getRuntime().exec("sync");
        } catch (Exception e) {
        }
    }

    private void setHdmiFormatsSetting(String newVal) {
        synchronized (mLock) {
            Settings.Global.putString(mContext.getContentResolver(), Settings.Global.RK_BITSTREAM_HDMI_SUP_FORMATS, newVal);
        }
    }

    private String getHdmiFormatsSetting() {
        synchronized (mLock) {
            return Settings.Global.getString(mContext.getContentResolver(), Settings.Global.RK_BITSTREAM_HDMI_SUP_FORMATS);
        }
    }

    private void setSpdifFormatsSetting(String newVal) {
        // Format:AC3 DTS
        synchronized (mLock) {
            Settings.Global.putString(mContext.getContentResolver(), Settings.Global.RK_BITSTREAM_SPDIF_SUP_FORMATS, newVal);
        }
    }

    private String getSpdifFormatsSetting() {
        synchronized (mLock) {
            return Settings.Global.getString(mContext.getContentResolver(), Settings.Global.RK_BITSTREAM_SPDIF_SUP_FORMATS);
        }
    }

    private void setAudioSetting(String newVal) {
        synchronized (mLock) {
            Settings.Global.putString(mContext.getContentResolver(), Settings.Global.RK_AUDIO_SETTINGS, newVal);
        }
    }

    private String getAudioSetting() {
        /*
         * Device:HDMI;Mode:BS Auto
         */
        synchronized (mLock) {
            return Settings.Global.getString(mContext.getContentResolver(), Settings.Global.RK_AUDIO_SETTINGS);
        }
    }

    private String getDeviceName(int device) {
        switch(device) {
            case AudioSystem.DEVICE_OUT_HDMI:
                return "HDMI";
            case  AudioSystem.DEVICE_OUT_SPDIF:
                return "SPDIF";
            case  AudioSystem.DEVICE_NONE:
                return "NONE";
            default:
                return "UnKnow";
        }
    }

    private String getModeName(int mode) {
        switch(mode) {
            case AUDIO_SETTING_MODE_DECODE:
                return "Decode";
            case  AUDIO_SETTING_MODE_BITSTREAM_AUTO:
                return "Auto";
            case  AUDIO_SETTING_MODE_BITSTREAM_MANUAL:
                return "Manual";
            default:
                return "UnKnow";
        }
    }
}


