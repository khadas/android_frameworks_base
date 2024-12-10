package com.android.server;

import android.os.RemoteException;
import android.os.custom.ICustomService;
import android.content.Context;
import android.os.HandlerThread;
import android.os.ServiceManager;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import android.os.SystemProperties;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.Locale;
import java.util.List;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Arrays;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Calendar;
import java.net.URL;

import android.os.SystemProperties;
import android.provider.Settings;

import android.text.TextUtils;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemProperties;
import android.provider.Settings;
import android.content.ComponentName;
import android.os.LocaleList;
import android.os.SystemClock;
import com.android.internal.app.LocalePicker;
import android.util.Log;
import java.util.Timer;
import java.util.TimerTask;
import android.app.ActivityManager;
import android.os.Handler;
import android.os.Message;

import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.content.Intent;
import android.content.pm.ResolveInfo;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.IPackageDeleteObserver;
import android.os.Bundle;

import android.app.role.RoleManager;
import java.util.concurrent.CompletableFuture;
import android.os.RemoteCallback;
import android.content.Context;
import android.os.UserHandle;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import java.lang.*;
import java.util.Date;
import java.util.Locale;

public class CustomService extends ICustomService.Stub {
    private static final String TAG = "CustomService";

    private final Context mContext;

    private static final String PROP_LED_WHITE_TRIGGER = "persist.sys.white_led_control";
    private static final String PROP_LED_RED_TRIGGER = "persist.sys.red_led_control";

    private static final String SYS_LED_WHITE_BREATH = "/sys/class/leds/state_led/breath";
    private static final String SYS_LED_WHITE_BREATH_STATE = "/sys/class/leds/state_led/state_brightness";
    private static final String SYS_LED_RED_TRIGGER = "/sys/class/mcu/redled";

    public static final int LED_WHITE = 0;
    public static final int LED_RED   = 1;

    private static final int INDEX_OFF = 0;
    private static final int INDEX_ON = 1;
    private static final int INDEX_TIMER = 2;
    private static final int INDEX_HEARTBEAT = 3;

    private static final int INDEX_LED[] = {
        INDEX_OFF,
        INDEX_ON,
        INDEX_TIMER,
        INDEX_HEARTBEAT
    };

    private static final String ModeList[] = {
        "off",
        "default-on",
        "timer",
        "heartbeat"
    };

    private static final String PROP_FAN_ENABLE = "persist.sys.fan.enable";
    private static final String PROP_FAN_MODE  = "persist.sys.fan.mode";
    private static final String PROP_FAN_LEVEL = "persist.sys.fan.level";
    private static final String PROP_FAN_INDEX = "persist.sys.fna.index";
    private static final String PROP_FAN_CTL = "persist.sys.fan_control";

    private static final String SYS_FAN_MODE = "/sys/class/fan/mode";
    private static final String SYS_FAN_LEVEL = "/sys/class/fan/level";
    private static final String SYS_FAN_ENABLE = "/sys/class/fan/enable";

    private static final int INDEX_LEVEL_OFF = 0;
    private static final int INDEX_LEVEL_AUTO = 1;
    private static final int INDEX_LEVEL_1 = 2;
    private static final int INDEX_LEVEL_2 = 3;
    private static final int INDEX_LEVEL_3 = 4;
    private static final int INDEX_LEVEL_4 = 5;
    private static final int INDEX_LEVEL_5 = 6;

    private static final String WOL0_STATE_SYS = "/sys/class/wol/eth0_enable";
    private static final String WOL1_STATE_SYS = "/sys/class/wol/eth1_enable";

    private PowerManager mPowerManager;
    private static final long START_TIME = 1 * 60 * 1000;
    private static final long CHECK_TIME = 1 * 60 * 1000;
    private static boolean isInit = false;
    private Timer timer = null;
    private TimerTask task = null;

    private static final String CUSTOM_UPDATE_TIME_ACTION = "com.custom.action.UPDATE_TIME";
    private static final String CUSTOM_SILENT_INSTALL_ACTION = "com.custom.action.SILENT_INSTALL";
    private static final String CUSTOM_SILENT_UNINSTALL_ACTION = "com.custom.action.SILENT_UNINSTALL";
    private static final String CUSTOM_SILENT_INSTALL_RESULT_ACTION = "com.custom.action.SILENT_INSTALL_RESULT";
    private static final String CUSTOM_SILENT_UNINSTALL_RESULT_ACTION = "com.custom.action.SILENT_UNINSTALL_RESULT";
    private static final String CUSTOM_REBOOT_ACTION = "com.custom.action.REBOOT";
    private static final String CUSTOM_SHUTDOWN_ACTION = "com.custom.action.SHUTDOWN";
    private static final String CUSTOM_SLEEP_ACTION = "com.custom.action.SLEEP";
    private static final String CUSTOM_WAKEUP_ACTION = "com.custom.action.WAKEUP";
    private static final String CUSTOM_SHOW_STATUSBAR_ACTION = "com.custom.action.SHOW_STATUSBAR";
    private static final String CUSTOM_HIDE_STATUSBAR_ACTION = "com.custom.action.HIDE_STATUSBAR";
    private static final String CUSTOM_SHOW_NAVIBAR_ACTION = "com.custom.action.SHOW_NAVIBAR";
    private static final String CUSTOM_HIDE_NAVIBAR_ACTION = "com.custom.action.HIDE_NAVIBAR";
    private static final String CUSTOM_SET_SYSPROP_ACTION = "com.custom.action.SET_SYSPROP";
    private static final String CUSTOM_OTA_UPDATE_ACTION = "com.custom.action.OTA_UPDATE";
    private static final String CUSTOM_SET_DISPOS_ACTION = "com.custom.action.SET_DISPOS";
    private static final String ANDROID_FINISH_BOOTING_ACTION = "com.android.action.FINISH_BOOTING";  
    private static final String ANDROID_BOOT_COMPLETED_ACTION = "android.intent.action.BOOT_COMPLETED";

    public CustomService(Context context) {
        Log.d(TAG, "==========CustomService Start==========");

        mContext = context;
        mPowerManager = (PowerManager)mContext.getSystemService(Context.POWER_SERVICE);

        IntentFilter filter = new IntentFilter();
        filter.addAction(CUSTOM_UPDATE_TIME_ACTION);
        filter.addAction(CUSTOM_SILENT_INSTALL_ACTION);
        filter.addAction(CUSTOM_SILENT_UNINSTALL_ACTION);
        filter.addAction(CUSTOM_REBOOT_ACTION);
        filter.addAction(CUSTOM_SHUTDOWN_ACTION);
        filter.addAction(CUSTOM_SLEEP_ACTION);
        filter.addAction(CUSTOM_WAKEUP_ACTION);
        filter.addAction(CUSTOM_SHOW_STATUSBAR_ACTION);
        filter.addAction(CUSTOM_HIDE_STATUSBAR_ACTION);
        filter.addAction(CUSTOM_SHOW_NAVIBAR_ACTION);
        filter.addAction(CUSTOM_HIDE_NAVIBAR_ACTION);
        filter.addAction(CUSTOM_SET_SYSPROP_ACTION);
        filter.addAction(CUSTOM_SET_DISPOS_ACTION);
        filter.addAction(CUSTOM_OTA_UPDATE_ACTION);
        filter.addAction(ANDROID_FINISH_BOOTING_ACTION);
        filter.addAction(ANDROID_BOOT_COMPLETED_ACTION);
        mContext.registerReceiver(mCustomServiceBroadReceiver, filter);

        mSessionCallback = new InstallSessionCallback();
        mContext.getPackageManager().getPackageInstaller().registerSessionCallback(mSessionCallback);
    }

    private void startApp(Context context, String packageName) {
        if (!isApplicationAvilible(mContext, packageName)) {
            Log.e(TAG, "startApp Application is not avilible " + packageName);
            return;
        }

        Intent intent1 = new Intent();
        intent1.setPackage(packageName);
        intent1.setAction(Intent.ACTION_MAIN);
        intent1.addCategory(Intent.CATEGORY_LAUNCHER);
        intent1.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (intent1.resolveActivity(mContext.getPackageManager()) != null) {
            Log.e(TAG, "intent1 start");
            mContext.startActivity(intent1);
        } else {
           Log.e(TAG, "intent2 start");
           Intent intent2 = mContext.getPackageManager().getLaunchIntentForPackage(packageName);
           if (intent2 != null) {
               Log.e(TAG, "intent2 start success");
               mContext.startActivity(intent2);
            } else {
               Log.e(TAG, "Application start failed");
            }
        }
    }

    private void startApp2(Context context, String packageName, String activityInfo) {
        if (!isApplicationAvilible(mContext, packageName)) {
            Log.e(TAG, "startApp Application is not avilible " + packageName);
            return;
        }
        Log.e(TAG, "startApp2 packageName " + packageName + " activityInfo " + activityInfo);
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ComponentName cn = new ComponentName(packageName, activityInfo);
            intent.setComponent(cn);
            context.startActivity(intent);
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    private BroadcastReceiver mCustomServiceBroadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            String action = intent.getAction();
            Log.e(TAG, "mCustomServiceBroadReceiver " + action);

            if (action.equals(ANDROID_FINISH_BOOTING_ACTION)) {
                String startUpApp = Settings.Global.getString(mContext.getContentResolver(), Settings.Global.STARTUP_APP);
                Log.e(TAG, "FINISH BOOTING ACTION startUpApp " + startUpApp);
                startApp(mContext, startUpApp);
            }

            if(action.equals(ANDROID_BOOT_COMPLETED_ACTION)) {
                String topPkg = getTopPackageName(context);
                String startUpApp = Settings.Global.getString(mContext.getContentResolver(), Settings.Global.STARTUP_APP);
                Log.e(TAG, "BOOT COMPLETED ACTION startUpApp " + startUpApp + " topPkg " + topPkg);
                if(!TextUtils.isEmpty(topPkg) && !TextUtils.isEmpty(startUpApp) && !(topPkg.equals(startUpApp))) {
                     startApp(mContext, startUpApp);
                 }
                 initKeepAliveApp();
            }

            if(action.equals(CUSTOM_UPDATE_TIME_ACTION)) {
                String timeStr = intent.getStringExtra("time");
                Log.d(TAG, "UPDATE_TIME time " + timeStr);
                if (!TextUtils.isEmpty(timeStr)) {
                    SimpleDateFormat sd = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    Date dataTime = null;
                    try {
                        dataTime = sd.parse(timeStr);
                        long when = dataTime.getTime();
                        if(when / 1000 < Integer.MAX_VALUE) {
                            SystemClock.setCurrentTimeMillis(when);
                        }
                    } catch (Exception e) {
                    }
                }
            } else if (action.equals(CUSTOM_SILENT_INSTALL_ACTION)) {
                String apkPath = intent.getStringExtra("apkPath");
                boolean isLaunch = intent.getBooleanExtra("isLaunch", false);
                silentInstallApp(apkPath, isLaunch);
            } else if (action.equals(CUSTOM_SILENT_UNINSTALL_ACTION)) {
                String pkgName = intent.getStringExtra("pkgName");
                silentUninstallApp(pkgName);
            } else if (action.equals(CUSTOM_REBOOT_ACTION)) {
                mPowerManager.reboot(null);
            } else if (action.equals(CUSTOM_SHUTDOWN_ACTION)) {
                mPowerManager.shutdown(false, null, false);
            } else if (action.equals(CUSTOM_SLEEP_ACTION)) {
                mPowerManager.goToSleep(SystemClock.uptimeMillis(), PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON, 0);
            } else if (action.equals(CUSTOM_WAKEUP_ACTION)) {
                mPowerManager.wakeUp(SystemClock.uptimeMillis(), "android.policy:POWER");
            } else if (action.equals(CUSTOM_SHOW_STATUSBAR_ACTION)) {
                setStatusBarVisibility(true);
            }  else if (action.equals(CUSTOM_HIDE_STATUSBAR_ACTION)) {
                setStatusBarVisibility(false);
            } else if (action.equals(CUSTOM_SHOW_NAVIBAR_ACTION)) {
                setNavigationbarVisibility(true);
            } else if (action.equals(CUSTOM_HIDE_NAVIBAR_ACTION)) {
                setNavigationbarVisibility(false);
            } else if (action.equals(CUSTOM_OTA_UPDATE_ACTION)) {
                String path = intent.getStringExtra("path");
                installPackage(path);
            } else if(action.equals(CUSTOM_SET_SYSPROP_ACTION)) {
                String name = intent.getStringExtra("name");
                String value = intent.getStringExtra("value");
                setSystemProperties(name, value);
            } else if(action.equals(CUSTOM_SET_DISPOS_ACTION)) {
                int pos = intent.getIntExtra("pos", 0);
                Log.e(TAG, "pos " + pos);
                int index = 0;
                switch (pos) {
                case 0:
                    index = 0;
                    break;
                case 1:
                case 90:
                    index = 1;
                    break;
                case 2:
                case 180:
                    index = 2;
                    break;
                case 3:
                case 270:
                    index = 3;
                    break;
                default:
                    index = 0;
                }
                Log.d(TAG, "setDisplayPosition " + pos + " index " + index);
                SystemProperties.set("set.user_rotation", "true");
                Settings.System.putInt(mContext.getContentResolver(), "user_rotation", index);
                SystemProperties.set("persist.sys.user_rotation", "" + index);
                SystemProperties.set("set.user_rotation", "false");
            }
        }
    };

    private String getTopPackageName(Context context) {
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager != null) {
            List<ActivityManager.RunningTaskInfo> runningTasks = activityManager.getRunningTasks(1);
            if (runningTasks != null && !runningTasks.isEmpty()) {
                ActivityManager.RunningTaskInfo taskInfo = runningTasks.get(0);
                ComponentName componentName = taskInfo.topActivity;
                if (componentName != null) {
                    return componentName.getPackageName();
                }
            }
        }
        return null;
    }

    private void doKeepAliveAppCheck() {
        String toppackageName = getTopPackageName(mContext);
        String alwaysAliveApps = Settings.Global.getString(mContext.getContentResolver(), Settings.Global.KEEP_ALIVE_APP);
        String startUpApp = Settings.Global.getString(mContext.getContentResolver(), Settings.Global.STARTUP_APP);
        Log.d(TAG, "doKeepAliveAppCheck toppackageName " + toppackageName + " startUpApp " + startUpApp + " alwaysAliveApps " + alwaysAliveApps);

        if (!TextUtils.isEmpty(alwaysAliveApps)){
            java.util.List<String> pkgList =  new ArrayList<String>(Arrays.asList(alwaysAliveApps.split(";")));
            for(int i = 0; i < pkgList.size(); i++) {
                String startAppPkgName = pkgList.get(i);
                if(isApplicationAvilible(mContext, startAppPkgName) && toppackageName.equals(startAppPkgName)) {
                    Log.d(TAG, "alwaysAliveApps " + alwaysAliveApps + " launcher app " + startAppPkgName);
                    return;
                }
            }
            for(int i = 0; i < pkgList.size(); i++) {
                String startAppPkgName = pkgList.get(i);
                Log.d(TAG, "startAppPkgName " + startAppPkgName);
                if((mContext != null) && isApplicationAvilible(mContext, startAppPkgName) && (!toppackageName.equals(startAppPkgName))) {
                    Log.d(TAG, "startApp " + startAppPkgName);
                    startApp(mContext, startAppPkgName);
                    return;
                }
            }
      }
    }

    private Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            try {
                doKeepAliveAppCheck();
            } catch(Exception e) {
                e.printStackTrace();
            }
        }
    };

    public boolean initKeepAliveApp() {
        if(!isInit) {
            if(timer != null) {
                timer.cancel();
                timer = null;
            }

            task = new TimerTask() {
                @Override
                public void run() {
                    Message message = new Message();
                    message.what = 1;
                    handler.sendMessage(message);
                }
            };
            timer = new Timer();
            timer.schedule(task, START_TIME, CHECK_TIME);
            isInit = true;
        }
        return true;
    }

    public void deInitKeepAliveApp() {
        if(task != null) {
            task.cancel();
            task = null;
        }
        isInit = false;
    }

    public void start() {
        Log.d(TAG, "Starting CustomService Service");
    }

    public String execSuCmd(String cmd) {
        try {
            Process mProcess = Runtime.getRuntime().exec("cmdclient " + cmd);
            BufferedReader mInputReader = new BufferedReader(new InputStreamReader(mProcess.getInputStream()));
            BufferedReader mErrorReader = new BufferedReader(new InputStreamReader(mProcess.getErrorStream()));
            String msg = "";
            String line;
            int i = 0;
            while ((line = mInputReader.readLine()) != null) {
                if(0 != i)
                    msg += '\n';
                msg += line;
                i = 1;
            }
            mInputReader.close();

            i = 0;
            while ((line = mErrorReader.readLine()) != null) {
                if(0 != i)
                    msg += '\n';
                msg += line;
                i = 1;
            }
            mErrorReader.close();
            mProcess.destroy();
            Log.d(TAG, msg);
            return msg;
        } catch (IOException e) {
            e.printStackTrace();
            return "execSuCmd Error";
        }
    }

    private void setNode(String name, int value) {
        Log.d(TAG, "node name: " + name + " = " + value);
        File file = new File(name);
        if ((file == null) || !file.exists()) {
            Log.e(TAG, "" + name + " no exist");
        }
        try {
            FileOutputStream fout = new FileOutputStream(file);
            PrintWriter pWriter = new PrintWriter(fout);
            pWriter.println(value);
            pWriter.flush();
            pWriter.close();
            fout.close();
        } catch (IOException e) {
            Log.e(TAG, "node name: " + name + "ERR: " + e);
        }
    }

    public void setSystemPropertiesInt(String name, int value) {
        SystemProperties.set(name, String.valueOf(value));
    }

    public int getSystemPropertiesInt(String name, int value) {
        return SystemProperties.getInt(name, value);
    }

    public void setSystemProperties(String name, String val) {
        SystemProperties.set(name, val);
    }

    public String getSystemProperties(String name, String val) {
        return SystemProperties.get(name, val);
    }

    public void setLedMode(int type, int mode) {
        Log.d(TAG, "setLed type: " + type + "setLedMode: " + mode);
        try {
            BufferedWriter bufWriter = null;
            if (type == LED_WHITE) {
            	  if(mode == 0) {
                    bufWriter = new BufferedWriter(new FileWriter(SYS_LED_WHITE_BREATH));
                    bufWriter.write("0 0");
            	  } else if(mode == 1) {
                    bufWriter = new BufferedWriter(new FileWriter(SYS_LED_WHITE_BREATH_STATE));
                    bufWriter.write("0 255");
            	  } else if(mode == 2) {
                    bufWriter = new BufferedWriter(new FileWriter(SYS_LED_WHITE_BREATH_STATE));
                    bufWriter.write("0 0");
            	  }
                SystemProperties.set(PROP_LED_WHITE_TRIGGER, String.valueOf(mode));
            } else {
                bufWriter = new BufferedWriter(new FileWriter(SYS_LED_RED_TRIGGER));
                bufWriter.write(String.valueOf(mode));
                SystemProperties.set(PROP_LED_RED_TRIGGER, String.valueOf(mode));
            }
            bufWriter.close();
        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG, "can't write the led node");
        }
    }

    public int getLedMode(int type) {
        int mode;
        if (type == LED_WHITE)
            mode = SystemProperties.getInt(PROP_LED_WHITE_TRIGGER, 2);
        else
            mode = SystemProperties.getInt(PROP_LED_RED_TRIGGER, 2);
        return mode;
    }

    public void setFanMode(int mode) {
        Log.d(TAG, "setLedMode: " + mode);

        switch (mode) {
        case INDEX_LEVEL_OFF:
            setNode(SYS_FAN_ENABLE, 0);
            setSystemPropertiesInt(PROP_FAN_ENABLE, 0);
            break;
        case INDEX_LEVEL_AUTO:
            setNode(SYS_FAN_ENABLE, 1);
            setSystemPropertiesInt(PROP_FAN_ENABLE, 1);
            setNode(SYS_FAN_MODE, 1);
            setSystemPropertiesInt(PROP_FAN_MODE, 1);
            setSystemPropertiesInt(PROP_FAN_INDEX, mode);
            break;
        case INDEX_LEVEL_1:
        case INDEX_LEVEL_2:
        case INDEX_LEVEL_3:
        case INDEX_LEVEL_4:
        case INDEX_LEVEL_5:
            setNode(SYS_FAN_ENABLE, 1);
            setSystemPropertiesInt(PROP_FAN_ENABLE, 1);
            setNode(SYS_FAN_MODE, 0);
            setSystemPropertiesInt(PROP_FAN_MODE, 0);
            setNode(SYS_FAN_LEVEL, (mode - 1));
            setSystemPropertiesInt(PROP_FAN_LEVEL, (mode - 1));
            setSystemPropertiesInt(PROP_FAN_INDEX, mode);
            break;
        default:
            //setNode(SYS_FAN_ENABLE,0);
            //setSystemProperties_int(PROP_FAN_ENABLE,0);
            break;
        }
        SystemProperties.set(PROP_FAN_CTL, "" + mode);
    }

    public int getFanMode() {
//        if(0 == getSystemPropertiesInt(PROP_FAN_ENABLE, 0))
//            return INDEX_LEVEL_OFF;
//        return getSystemPropertiesInt(PROP_FAN_INDEX, 0);
          return getSystemPropertiesInt(PROP_FAN_CTL, 1);
    }

    public void setWolMode(boolean mode) {
        try {
            RandomAccessFile rdf = null;
            rdf = new RandomAccessFile(WOL0_STATE_SYS, "rw");
            rdf.writeBytes(mode ? "1" : "0");
            rdf = new RandomAccessFile(WOL1_STATE_SYS, "rw");
            rdf.writeBytes(mode ? "1" : "0");
            rdf.close();
        } catch (IOException re) {
            Log.e(TAG, "setWolMode");
        }
    }

    public boolean getWolMode() {
        boolean enabled = false;
        try {
            FileReader fread = new FileReader(WOL0_STATE_SYS);
            BufferedReader buffer = new BufferedReader(fread);
            String str = null;
            while ((str = buffer.readLine()) != null) {
                if (str.equals("1")) {
                    enabled = true;
                    break;
                } else {
                    enabled = false;
                }
            }
            buffer.close();
            fread.close();
        } catch (IOException e) {
            Log.e(TAG, "getWolMode");
        }
        return enabled;
    }

    private int gpioParse(String gpioStr) {
        if(gpioStr == null) {
            Log.e(TAG, "Input gpio error!");
            return -1;
        }

        if (!gpioStr.contains("GPIO")) {
            Log.e(TAG, "Input is not gpio name");
            return -1;
        }

        String msg_AO = execSuCmd("CustomApi msg_AO");
        if (!msg_AO.contains(gpioStr)) {
            String msg = execSuCmd("CustomApi msg_00");
            if (!msg.contains(gpioStr)) {
                Log.e(TAG, "Input cannot find GPIO name");
                return -1;
            } else {
                String[] ss = msg.split("\n");
                for (int i = 0; i < ss.length; i++) {
                    if (ss[i].contains(gpioStr)) {
                        return 410 + i - 1;
                    }
                }
            }
        } else {
            String[] ss = msg_AO.split("\n");
            for (int i = 0; i < ss.length; i++) {
                if (ss[i].contains(gpioStr)) {
                    return 496 + i - 1;
                }
            }
        }
        Log.e(TAG, "gpioParse Error");
        return -1;
    }

    public boolean gpioCrtl(int gpio, String direction, int level) {
        String exportPath;
        String directionPath;
        String direction_value_Path;
        String valuePath;
        String msg;

        if(gpio < 0)
            return false;

        exportPath = "echo " + gpio + " > /sys/class/gpio/export";
        directionPath = "echo " + direction + " > " + "/sys/class/gpio/gpio" + gpio + "/direction";
        valuePath = "echo " + level + " > /sys/class/gpio/gpio" + gpio + "/value";
        direction_value_Path = "cat /sys/class/gpio/gpio" + gpio + "/direction";

        execSuCmd(exportPath);
        msg = execSuCmd(direction_value_Path);
        if (msg.equals(direction)) {
            Log.d(TAG, "NO DO direction");
        } else {
            execSuCmd(directionPath);
            Log.d(TAG, "DO direction");
        }

        msg = execSuCmd(valuePath);
        if (msg.contains("No such file")) {
            Log.e(TAG, "gpioCrtl " + msg);
            return false;
        }

        return true;
    }

    public int gpioRead(int gpio) {
        String exportPath;
        String valuePath;
        String msg;

        if(gpio < 0)
            return -1;

        valuePath = "cat  /sys/class/gpio/gpio" + gpio + "/value";
        exportPath = "echo " + gpio + " > /sys/class/gpio/export";

        execSuCmd(exportPath);
        msg = execSuCmd(valuePath);

        if (msg.contains("No such file")) {
            Log.e(TAG, "No such file: " + msg);
            return -1;
        }

        if (msg.equals("0")) {
            return 0;
        } else if (msg.equals("1")) {
            return 1;
        }

        Log.e(TAG, "gpioRead " + msg);
        return -1;
    }

    public boolean gpioRequest(int gpio) {
        String gpioPath =   "/sys/class/gpio/gpio" + gpio;
        File gpioFile = new File(gpioPath);
        if (gpioFile.exists()) {
            Log.e(TAG, "gpioRequest gpioPath " + gpioPath + " is exist");
            return false;
        }

        String exportPath = "echo " + gpio + " > /sys/class/gpio/export";
        execSuCmd(exportPath);
        return true;
    }

    public boolean gpioSetDirection(int gpio, String direction) {
        String gpioDirPath =   "/sys/class/gpio/gpio" + gpio + "/direction";
        File file = new File(gpioDirPath);
        if (!file.exists()) {
            Log.e(TAG, "gpioSetDirection gpioSetDirection " + gpioDirPath + " is not exist");
            return false;
        }

        String directionPath = "echo " + direction + " > " + "/sys/class/gpio/gpio" + gpio + "/direction";
        String directionValuePath = "cat /sys/class/gpio/gpio" + gpio + "/direction";
        String msg = execSuCmd(directionValuePath);
        if (msg.equals(direction)) {
            Log.d(TAG, "gpioSetDirection NO DO direction");
        } else {
            execSuCmd(directionPath);
            Log.d(TAG, "gpioSetDirection DO direction");
        }
        return true;
    }

    public boolean gpioSetValue(int gpio, int value) {
        String gpioValuePath =   "/sys/class/gpio/gpio" + gpio + "/value";
        File file = new File(gpioValuePath);
        if (!file.exists()) {
            Log.e(TAG, "gpioSetDirection gpioSetValue " + gpioValuePath + " is not exist");
            return false;
        }

        String valuePath = "echo " + value + " > /sys/class/gpio/gpio" + gpio + "/value";
        String msg = execSuCmd(valuePath);
        if (msg.contains("No such file")) {
            Log.e(TAG, "gpioSetValue " + msg);
            return false;
        }
        return true;
    }

    public int gpioGetValue(int gpio) {
        String gpioValuePath =   "/sys/class/gpio/gpio" + gpio + "/value";
        File file = new File(gpioValuePath);
        if (!file.exists()) {
            Log.e(TAG, "gpioGetValue gpioSetValue " + gpioValuePath + " is not exist");
            return -1;
        }

        String valuePath = "cat  /sys/class/gpio/gpio" + gpio + "/value";
        String msg = execSuCmd(valuePath);
        if (msg.contains("No such file")) {
            Log.e(TAG, "gpioGetValue No such file: " + msg);
            return -1;
        }
        if (msg.equals("0")) {
            return 0;
        } else if (msg.equals("1")) {
            return 1;
        }
        return -1;
    }

    public boolean gpioFree(int gpio) {
        String gpioPath =   "/sys/class/gpio/gpio" + gpio;
        File gpioFile = new File(gpioPath);
        if (!gpioFile.exists()) {
            Log.e(TAG, "gpioFree gpioPath " + gpioPath + " is not exist");
            return false;
        }

        String unexportPath = "echo " + gpio + " > /sys/class/gpio/unexport";
        execSuCmd(unexportPath);
        return true;
    }

    public int i2cReadByteData(int bus, int addr, int reg) {
        String msg = execSuCmd("CustomApi i2cRead " + bus + " " + addr + " " + reg);
        if (msg.contains("failed") || msg.contains("Error") || msg.contains("No such device or address")) {
            Log.e(TAG, "i2cReadByteData " + msg);
            return -1;
        }
        Log.e(TAG, "i2cReadByteData " + msg);
        return Integer.valueOf(msg.substring(2, msg.length()), 16);
    }

    public int i2cWriteByteData(int bus, int addr, int reg, int value) {
        String msg = execSuCmd("CustomApi i2cWrite " + bus + " " +  addr + " " +  reg + " " +  value + " b");
        if (msg.contains("failed") || msg.contains("Error") || msg.contains("No such device or address")) {
            Log.e(TAG, "i2cWriteByteData " + msg);
            return -1;
        }
        return 0;
    }

    public int setDisplayPosition(int pos) {
        Intent disPlayPosIntent = new Intent(CUSTOM_SET_DISPOS_ACTION);
        disPlayPosIntent.putExtra("pos", pos);
        mContext.sendBroadcast(disPlayPosIntent);
        return pos;
    }

    public int screenRecord(String pathName, int seconds) {
        String msg = execSuCmd("CustomApi screenrecord " + seconds + " " + pathName);
        if (msg.contains("failed") || msg.contains("Error")) {
            Log.e(TAG, "screenrecord " + msg);
            return -1;
        }
        return 0;
    }

    private void getPkgNameActivity() {
        PackageManager packageManager = mContext.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolveInfos = packageManager.queryIntentActivities(intent, 0);
        Collections.sort(resolveInfos, new ResolveInfo.DisplayNameComparator(packageManager));
        for (ResolveInfo reInfo : resolveInfos) {
            String activityName = reInfo.activityInfo.name;
            String pkgName = reInfo.activityInfo.packageName;
            Log.e(TAG, "activityName---" + activityName + " pkgName---" + pkgName);
        }
    }

    public void setStartupApp(String pkgName) {
        getPkgNameActivity();
    }

    public void setKeepAliveApp(String pkgName) {

    }

    public void switchLanguage(String language, String country) {
        //        Locale locale = new Locale(language, country);
        //        LocaleList localeList = LocalePicker.getLocales();
        //        int index = -1;
        //        for (int i = 0; i < localeList.size(); i++) {
        //            Locale lc = localeList.get(i);
        //            if (locale.equals(lc)) {
        //                index = i;
        //                break;
        //            }
        //        }
        //        Locale[] localeArrayNew = new Locale[index < 0 ? localeList.size() + 1 : localeList.size()];
        //        if (index < 0) {
        //            localeArrayNew[0] = locale;
        //            for (int i = 0; i < localeList.size(); i++) {
        //                localeArrayNew[i + 1] = localeList.get(i);
        //            }
        //        } else {
        //            for (int i = 0; i < localeList.size(); i++) {
        //                localeArrayNew[i] = localeList.get(i);
        //            }
        //            localeArrayNew[index] = localeArrayNew[0];
        //            localeArrayNew[0] = locale;
        //        }
        //        LocaleList mLocalesToSetNext = new LocaleList(localeArrayNew);
        //        LocalePicker.updateLocales(mLocalesToSetNext);
    }


    public boolean setTime(int year, int month, int day, int hour, int minute, int second) {
        String timeStr = "" + year + "-" + month + "-" + day + " " + hour + ":" + minute + ":" + second;
        Intent timeIntent = new Intent(CUSTOM_UPDATE_TIME_ACTION);
        timeIntent.putExtra("time", timeStr);
        mContext.sendBroadcast(timeIntent);
        return true;
    }

    private int mSessionId = -1;
    private PackageInstaller.SessionCallback mSessionCallback;
    private InstallAppInfo installAppInfo;

    public void silentInstallApp(String apkFilePath, boolean isLaunch) {
        File apkFile = new File(apkFilePath);
        Log.e(TAG, "silentInstallApp apkFilePath " + apkFilePath);
        if (!apkFile.exists()) {
            Log.e(TAG, "apkFile is not exists" + apkFilePath);
            return;
        }

        PackageInfo packageInfo = mContext.getPackageManager().getPackageArchiveInfo(apkFilePath, PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES);
        if (packageInfo != null) {
            String packageName = packageInfo.packageName;
            int versionCode = packageInfo.versionCode;
            String versionName = packageInfo.versionName;
            Log.e(TAG, "packageName " + packageName + " versionCode " + versionCode + " versionName " + versionName);
        }

        PackageInstaller packageInstaller = mContext.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sessionParams = new PackageInstaller.SessionParams(PackageInstaller .SessionParams.MODE_FULL_INSTALL);
        sessionParams.setSize(apkFile.length());

        try {
            mSessionId = packageInstaller.createSession(sessionParams);
        } catch (IOException e) {
            e.printStackTrace();
        }

        Log.e(TAG, "silentInstallApp mSessionId " + mSessionId);
        if (mSessionId != -1) {
            boolean copySuccess = onTransfesApkFile(apkFilePath);
            if (copySuccess) {
                installAppInfo = new InstallAppInfo (mSessionId, apkFilePath, packageInfo, isLaunch);
                execInstallAPP(mSessionId);
            }
        }

    }

    private boolean onTransfesApkFile(String apkFilePath) {
        InputStream in = null;
        OutputStream out = null;
        PackageInstaller.Session session = null;
        boolean success = false;
        Log.e(TAG, "onTransfesApkFile apkFilePath " + apkFilePath);
        try {
            File apkFile = new File(apkFilePath);
            session = mContext.getPackageManager().getPackageInstaller().openSession(mSessionId);
            out = session.openWrite("base.apk", 0, apkFile.length());
            in = new FileInputStream(apkFile);
            int total = 0, c;
            byte[] buffer = new byte[1024 * 1024];
            while ((c = in.read(buffer)) != -1) {
                total += c;
                out.write(buffer, 0, c);
            }
            session.fsync(out);
            success = true;
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (null != session) {
                session.close();
            }
            try {
                if (null != out) {
                    out.close();
                }
                if (null != in) {
                    in.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return success;
    }

    private void execInstallAPP(int sessionId) {
        PackageInstaller.Session session = null;
        try {
            session = mContext.getPackageManager().getPackageInstaller().openSession(sessionId);
            Intent intent = new Intent();
            PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 1, intent, PendingIntent.FLAG_IMMUTABLE);
            session.commit(pendingIntent.getIntentSender());
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (null != session) {
                session.close();
            }
        }
    }

    private class InstallSessionCallback extends PackageInstaller.SessionCallback {
        @Override
        public void onCreated(int sessionId) {
        }

        @Override
        public void onBadgingChanged(int sessionId) {
        }

        @Override
        public void onActiveChanged(int sessionId, boolean active) {
        }

        @Override
        public void onProgressChanged(int sessionId, float progress) {
            if (sessionId == mSessionId) {
                int progres = (int) (Integer.MAX_VALUE * progress);
            }
        }

        @Override
        public void onFinished(int sessionId, boolean success) {
            // empty, finish is handled by InstallResultReceiver
            if (mSessionId == sessionId) {
                if(success) {
                    Log.e(TAG, "InstallSessionCallback success");
                } else {
                    Log.e(TAG, "InstallSessionCallback success faild");
                }
                Intent installResultIntent = new Intent(CUSTOM_SILENT_INSTALL_RESULT_ACTION);
                installResultIntent.putExtra("pkgName", installAppInfo.getInfo().packageName);
                installResultIntent.putExtra("success", success);
                mContext.sendBroadcast(installResultIntent);

                if(installAppInfo != null && installAppInfo.isLaunch()) {
                    Intent mLaunchIntent = mContext.getPackageManager().getLaunchIntentForPackage(installAppInfo.getInfo().packageName);
                    if (mLaunchIntent != null) {
                        List<ResolveInfo> list = mContext.getPackageManager().queryIntentActivities(mLaunchIntent, 0);
                        if (list != null && list.size() > 0) {
                            mContext.startActivity(mLaunchIntent);
                        }
                    }
                }

            }
        }
    }

    private boolean isApplicationAvilible(Context context, String appPackageName) {
        if (TextUtils.isEmpty(appPackageName)) {
            return false;
        }
        Intent filterIntent = new Intent(Intent.ACTION_MAIN, null);
        filterIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = context.getPackageManager().queryIntentActivities(filterIntent, 0);
        for (ResolveInfo resolveInfo : apps){
            String pkgName = resolveInfo.activityInfo.applicationInfo.packageName;
            if (appPackageName.equals(appPackageName)) {
                return true;
            }
        }
        return false;
    }

    private void silentUninstallApp(String packageName) {
        if (!isApplicationAvilible(mContext, packageName)) {
            Log.e(TAG, "silentUninstallApp Application is not avilible " + packageName);
            return;
        }
        Log.e(TAG, "silentUninstall " + packageName);
        //        Intent broadcastIntent = new Intent();
        //        PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 1, broadcastIntent, PendingIntent.FLAG_IMMUTABLE);
        //        PackageInstaller packageInstaller = mContext.getPackageManager().getPackageInstaller();
        //        packageInstaller.uninstall(packageName, pendingIntent.getIntentSender());
        //        Intent uninstallResultIntent = new Intent(CUSTOM_SILENT_UNINSTALL_RESULT_ACTION);
        //        mContext.sendBroadcast(uninstallResultIntent);

        PackageDeleteObserver observer = new PackageDeleteObserver();
        mContext.getPackageManager().deletePackage(packageName, observer, PackageManager.DELETE_ALL_USERS);
    }

    public boolean silentCmdInstall(String apkPath) {
        String msg = execSuCmd("CustomApi silentInstall " + apkPath);
        if (msg.contains("Success")) {
            return true;
        }
        return false;
    }

    public boolean silentCmdUnInstall(String package_name) {
        String msg = execSuCmd("CustomApi silentUnInstall " + package_name);
        if (msg.contains("Success")) {
            return true;
        }
        return false;
    }

    public void silentInstall(String apkPath, boolean isLaunch) {
        Log.d(TAG, "silentInstall " + apkPath);
        Intent silentInstallIntent = new Intent(CUSTOM_SILENT_INSTALL_ACTION);
        silentInstallIntent.putExtra("apkPath", apkPath);
        silentInstallIntent.putExtra("isLaunch", isLaunch);
        mContext.sendBroadcast(silentInstallIntent);
    }

    public void silentUnInstall(String pkgName) {
        Log.d(TAG, "silentUnInstall " + pkgName );
        Intent silentUninstallIntent = new Intent(CUSTOM_SILENT_UNINSTALL_ACTION);
        silentUninstallIntent.putExtra("pkgName", pkgName);
        mContext.sendBroadcast(silentUninstallIntent);
    }

    public void reboot() {
        Intent rebootIntent = new Intent(CUSTOM_REBOOT_ACTION);
        mContext.sendBroadcast(rebootIntent);
    }

    public void shutdown() {
        Intent shutdownIntent = new Intent(CUSTOM_SHUTDOWN_ACTION);
        mContext.sendBroadcast(shutdownIntent);
    }

    public void sleep() {
        Intent sleepIntent = new Intent(CUSTOM_SLEEP_ACTION);
        mContext.sendBroadcast(sleepIntent);
    }

    public void wakeup() {
        Intent wakeupIntent = new Intent(CUSTOM_WAKEUP_ACTION);
        mContext.sendBroadcast(wakeupIntent);
    }

    public void installPackage(String zipPath) {
        Log.d(TAG, "installPackage " + zipPath);
        Intent otaIntent = new Intent("com.khadas.ota.UPDATE");
        otaIntent.putExtra("path", zipPath);
        otaIntent.setPackage("com.droidlogic.updater");
        mContext.sendBroadcast(otaIntent);
    }

    public void updateSystem(String zipPath) {
        installPackage(zipPath);
    }

    public void setStatusBarVisibility(boolean v) {
        if(v) {
            mContext.sendBroadcast(new Intent("com.android.show_upper_bar"));
        } else {
            mContext.sendBroadcast(new Intent("com.android.hide_upper_bar"));
        }
    }
    public void setNavigationbarVisibility(boolean v) {
        if(v) {
            mContext.sendBroadcast(new Intent("com.android.show_bottom_bar"));
        } else {
            mContext.sendBroadcast(new Intent("com.android.hide_bottom_bar"));
        }
    }

    private class PackageDeleteObserver extends IPackageDeleteObserver.Stub {
        public void packageDeleted(String packageName, int returnCode) {
            Log.e(TAG, "packageDeleted " + packageName + " returnCode " + returnCode);

            if(returnCode == PackageManager.DELETE_SUCCEEDED) {

            } else {

            }
            Intent uninstallResultIntent = new Intent(CUSTOM_SILENT_UNINSTALL_RESULT_ACTION);
            uninstallResultIntent.putExtra("pkgName", packageName);
            uninstallResultIntent.putExtra("code", returnCode);
            mContext.sendBroadcast(uninstallResultIntent);
        }
    }

    //   private class PackageInstallObserver extends IPackageInstallObserver.Stub {
    //        public void packageInstalled(String packageName, int returnCode) {
    //            Log.e(TAG, "packageInstalled " + packageName + " returnCode " + returnCode);
    //        }
    //    }


    private class InstallAppInfo {

        private int sessionId;
        private String filePath;
        private PackageInfo info;
        private boolean isLaunch;

        public InstallAppInfo(int sessionId, String filePath, PackageInfo info, boolean isLaunch) {
            this.sessionId = sessionId;
            this.filePath = filePath;
            this.info = info;
            this.isLaunch = isLaunch;
        }

        public int getSessionId() {
            return sessionId;
        }

        public String getFilePath() {
            return filePath;
        }

        public PackageInfo getInfo() {
            return info;
        }

        public boolean isLaunch() {
            return isLaunch;
        }
    }

}
