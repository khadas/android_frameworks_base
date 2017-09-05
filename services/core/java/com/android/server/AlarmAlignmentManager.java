/*
 * Copyright (C) 2016-2017 Intel Mobile Communications GmbH
 *
 * Sec Class: Intel Confidential (IC)
 *
 * All rights reserved.
 *
 */
package com.android.server;

import android.app.AlarmManager;
import android.app.AlarmManager.AlarmClockInfo;
import android.app.AlarmManager.AppAlarmConfig;
import android.app.AlarmManager.ScreenOffAlarmStrategy;
import android.app.AppGlobals;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.util.FastXmlSerializer;
import com.android.server.AlarmManagerService.Alarm;
import com.android.server.AlarmManagerService.Batch;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import static android.app.AlarmManager.APP_ALARM_STRATEGY_PROP;
import static android.app.AlarmManager.APP_ALARM_FIXED_INTERVAL;
import static android.app.AlarmManager.RTC_WAKEUP;
import static android.app.AlarmManager.RTC;
import static android.app.AlarmManager.ScreenOffAlarmStrategy.SCREEN_OFF_ALARM_STRATEGY_NONE;
import static android.app.AlarmManager.ScreenOffAlarmStrategy.SCREEN_OFF_ALARM_STRATEGY_FIXED2;

final class AlarmAlignmentManager {
    private static final boolean DBG = AlarmManagerService.localLOGV;
    private static final String TAG = "AlarmAlignmentManager";

    private static final AlarmAlignmentManager sInstance = new AlarmAlignmentManager();

    private AlarmManagerService mAlarmService = null;
    private AppAlarmConfig mAppAlarmConfig = null;
    private AtomicFile mAppAlarmCfgOverwriteFile = null;

    private final ArrayList<Alarm> mSkippedNonWakeupAlarms = new ArrayList<Alarm>();

    private AlarmAlignmentManager(){
        // init it to avoid race conditions
        mAppAlarmConfig = new AppAlarmConfig();

        // init the App alarm config file on data partition
        File dataDir = Environment.getDataDirectory();
        File winConfOverwrite = new File(dataDir, "system/alarm_alignment_conf.xml");
        mAppAlarmCfgOverwriteFile = new AtomicFile(winConfOverwrite);
    }

    public static AlarmAlignmentManager getInstance(AlarmManagerService service) {
        if (service == null)
            throw new IllegalArgumentException("AlarmManagerService instance needed!");
        sInstance.mAlarmService = service;
        return sInstance;
    }

    /**
     * Align App alarm.
     *
     * Called from AlarmManagerService.setImpl()
     */
    AlarmExtraData alignAppAlarm(int type, long nowElapsed,
            long nominalTrigger, long minTrigger, AlarmAlignmentInfo info) {
        if (DBG) Slog.v(TAG, "alignAppAlarm++");

        AlarmExtraData extra = null;

        if (info != null) {
            if (mAppAlarmConfig.mAppAlarmSkipShort > 0
                    && info.triggerElapsed <= (nowElapsed + mAppAlarmConfig.mAppAlarmSkipShort)) {
                Slog.d(TAG, "skip short interval alarm less than or equals "
                        + mAppAlarmConfig.mAppAlarmSkipShort + ", now="
                        + nowElapsed + ", trigger=" + info.triggerElapsed);
            } else {
                extra = new AlarmExtraData();
                extra.orgRepeatInterval = info.interval;
                extra.orgWindowLength = info.windowLength;
                extra.triggerDelta = 0L;
                extra.survival = 0; // I'm born dead x_x#

                if (info.interval > 0) {
                    if (mAppAlarmConfig.mAppAlarmRepeatAlignment > 0
                            && info.interval < mAppAlarmConfig.mAppAlarmRepeatAlignment) {
                        Slog.d(TAG, "extend repeat interval from " + info.interval + " to "
                                + mAppAlarmConfig.mAppAlarmRepeatAlignment + " when screen off");
                        info.interval = mAppAlarmConfig.mAppAlarmRepeatAlignment;
                    }
                }

                if ((info.interval <= 0 || isStrict())
                        && (isWakeupType(type) || isAlignNonWakeup())) {
                    if (mAppAlarmConfig.mAlarmStrategy == SCREEN_OFF_ALARM_STRATEGY_FIXED2) {
                        Slog.d(TAG, "align with strategy " + mAppAlarmConfig.mAlarmStrategy
                                + ", triggerElapsed=" + info.triggerElapsed);
                        long roundedElapsed = roundToNextWakeup(info.triggerElapsed, nowElapsed, 0);
                        Slog.d(TAG, "roundedElapsed=" + roundedElapsed);

                        extra.triggerDelta = roundedElapsed - info.triggerElapsed;

                        if (extra.triggerDelta != 0) {
                            if (RTC_WAKEUP == type || RTC == type) {
                                info.triggerAtTime += extra.triggerDelta;
                                // in this case, triggerelapsed increased by
                                // (minTrigger - nominalTrigger)
                                // triggerAtTime also need to add this delta
                                if (nominalTrigger < minTrigger) {
                                    info.triggerAtTime += (minTrigger - nominalTrigger);
                                }
                            } else {
                                info.triggerAtTime = roundedElapsed;
                            }
                            info.triggerElapsed = roundedElapsed;
                        }

                        Slog.d(TAG, "force set as standalone, windowLength=" + info.windowLength);
                        info.windowLength = AlarmManager.WINDOW_EXACT;
                        info.flags |= AlarmManager.FLAG_STANDALONE;
                    }
                }
            }
        }

        if (DBG) Slog.v(TAG, "alignAppAlarm--");
        return extra;
    }

    /**
     * Align repeat App alarm.
     *
     * Called from AlarmManagerService.triggerAlarmsLocked()
     */
    void alignRepeatAppAlarmLocked(Alarm alarm, long nextElapsed,
            long nowELAPSED, AlarmAlignmentInfo info) {
        if (DBG) Slog.v(TAG, "alignRepeatAppAlarmLocked++");

        if (info != null
                && mAppAlarmConfig.mAlarmStrategy == SCREEN_OFF_ALARM_STRATEGY_FIXED2) {

            if (mAppAlarmConfig.mAppAlarmSkipShort > 0
                    && info.triggerElapsed <= (nowELAPSED + mAppAlarmConfig.mAppAlarmSkipShort)) {
                Slog.d(TAG, "skip short interval repeat alarm less than or equals "
                        + mAppAlarmConfig.mAppAlarmSkipShort + ", " + alarm);
                Slog.d(TAG, "now:" + nowELAPSED + ",trigger:" + info.triggerElapsed);
            } else {
                Slog.d(TAG, "align repeat alarm:" + alarm);
                long roundedElapsed = roundToNextWakeup(nextElapsed, nowELAPSED, 0);
                long deltaElapsed = roundedElapsed - nextElapsed;
                if (deltaElapsed != 0) {
                    alarm.ensureExtra();
                    alarm.extra.triggerDelta = deltaElapsed;

                    info.triggerAtTime += deltaElapsed;
                    info.triggerElapsed += deltaElapsed;
                }

                Slog.d(TAG, "force set to standalone, windowLength=" + alarm.windowLength);
                alarm.ensureExtra(); // save windowLength

                info.windowLength = AlarmManager.WINDOW_EXACT;
                info.flags |= AlarmManager.FLAG_STANDALONE;
            }
        }

        if (DBG) Slog.v(TAG, "alignRepeatAppAlarmLocked--");
    }

    /**
     * Extend repeat interval when screen off.
     */
    void coolDownRepeatAlarmsLocked() {
        if (DBG) Slog.d(TAG, "coolDownRepeatAlarmsLocked() ++");

        if (mAppAlarmConfig.mAppAlarmRepeatAlignment > 0) {
            for (Batch b : mAlarmService.mAlarmBatches) {
                for (Alarm a : b.alarms) {
                    if (a.repeatInterval > 0
                            && a.operation != null
                            && isFilteredAppAlarm(a.operation.getCreatorPackage(),
                                a.operation.getIntent(), a.alarmClock, a.flags)) {
                        a.ensureExtra(); // original repeat interval saved
                        a.extra.survival = 1; // trigger on time for 1st time

                        if (a.wakeup
                                && a.repeatInterval < mAppAlarmConfig.mAppAlarmRepeatAlignment) {
                            a.repeatInterval = mAppAlarmConfig.mAppAlarmRepeatAlignment;

                            Slog.d(TAG, "setting repeat interval to "
                                    + mAppAlarmConfig.mAppAlarmRepeatAlignment
                                    + " for " + a.operation.getTargetPackage() +
                                    " original repeat interval:" + a.extra.orgRepeatInterval);
                        }
                    }
                }
            }
        }

        if (DBG) Slog.d(TAG, "coolDownRepeatAlarmsLocked() --");
    }

    /**
     * Recover the changed alarms when screen on.
     */
    void recoverChangedAlarmsLocked() {
        if (DBG) Slog.d(TAG, "recoverChangedAlarmsLocked() ++");

        List<Alarm> needRecoverAlarms = null;

        // step 1: find out the changed alarms
        for (Batch b : mAlarmService.mAlarmBatches) {
            for (Alarm a : b.alarms) {
                if (a.extra != null
                        && (a.repeatInterval != a.extra.orgRepeatInterval
                            || a.windowLength != a.extra.orgWindowLength
                            || a.extra.triggerDelta != 0)) {
                    if (needRecoverAlarms == null) {
                        needRecoverAlarms = new ArrayList<Alarm>();
                    }
                    needRecoverAlarms.add(a);
                }
            }
        }

        // step 2: set back the changed alarms
        long nowElapsed, nowRtc, triggerAtTime;
        boolean isRtc;
        int flags;

        if (needRecoverAlarms != null && needRecoverAlarms.size() > 0) {

            nowElapsed = SystemClock.elapsedRealtime();
            nowRtc = System.currentTimeMillis();
            long windowLength, repeatInterval;

            for (Alarm a : needRecoverAlarms) {
                Slog.d(TAG, "recovering alarm " + a);

                if (a.extra == null) {
                    Slog.w(TAG, "something wrong, null extra");
                    continue;
                }

                isRtc = (a.type == RTC || a.type == RTC_WAKEUP);
                if (a.extra.triggerDelta != 0) {
                    if (isRtc) {
                        triggerAtTime = nowRtc
                            + (a.whenElapsed - a.extra.triggerDelta - nowElapsed);
                    } else {
                        triggerAtTime = nowElapsed
                            + (a.whenElapsed - a.extra.triggerDelta - nowElapsed);
                    }
                } else {
                    if (isRtc) {
                        triggerAtTime = a.when;
                    } else {
                        triggerAtTime = a.whenElapsed;
                    }
                }

                if (a.extra.orgWindowLength != a.windowLength) {
                    windowLength = a.extra.orgWindowLength;
                } else {
                    windowLength = a.windowLength;
                }

                if (a.extra.orgRepeatInterval != a.repeatInterval) {
                    repeatInterval = a.extra.orgRepeatInterval;
                } else {
                    repeatInterval = a.repeatInterval;
                }

                flags = a.flags;
                if (a.extra.orgWindowLength != AlarmManager.WINDOW_EXACT) {
                    flags = a.flags & ~AlarmManager.FLAG_STANDALONE;
                }

                mAlarmService.setImpl(a.type, triggerAtTime, windowLength, repeatInterval,
                    a.operation, null, null, flags, a.workSource, a.alarmClock, a.uid, a.packageName);
            }
        }

        // step 3: handle the skipped non-wakeup alarms
        if (mSkippedNonWakeupAlarms != null && mSkippedNonWakeupAlarms.size() > 0) {
            if (DBG) {
                for (Alarm a : mSkippedNonWakeupAlarms) {
                    Slog.d(TAG, "found skipped alarm:" + a);
                }
            }

            // it must make a copy, otherwise setImpl() will remove alarm,
            // then there'll be ConcurrentModificationException
            ArrayList<Alarm> oldAlarms = (ArrayList<Alarm>) mSkippedNonWakeupAlarms.clone();
            mSkippedNonWakeupAlarms.clear(); // we don't need this any more

            nowElapsed = SystemClock.elapsedRealtime();
            nowRtc = System.currentTimeMillis();

            // step 3.1: add back repeat alarms, begin with next trigger time
            for (Alarm a : oldAlarms) {
                if (a.repeatInterval > 0) {
                    flags = a.flags;
                    if (a.extra != null) {
                        a.repeatInterval = a.extra.orgRepeatInterval;
                        a.windowLength = a.extra.orgWindowLength;
                        if (a.extra.orgWindowLength != AlarmManager.WINDOW_EXACT) {
                            flags = a.flags & ~AlarmManager.FLAG_STANDALONE;
                        }
                    }

                    if (a.repeatInterval > 0) {
                        a.count += (nowElapsed - a.whenElapsed) / a.repeatInterval;
                    }

                    isRtc = (a.type == RTC || a.type == RTC_WAKEUP);
                    if (isRtc) {
                        triggerAtTime = nowRtc + a.repeatInterval;
                    } else {
                        triggerAtTime = nowElapsed + a.repeatInterval;
                    }

                    mAlarmService.setImpl(a.type, triggerAtTime, a.windowLength, a.repeatInterval,
                            a.operation, null,  null, flags, a.workSource, a.alarmClock, a.uid, a.packageName);
                }
            }

            // step 3.2: deliver all of them since we're awake now
            Slog.d(TAG, "deliver skipped non-wakeup alarms");
            mAlarmService.deliverAlarmsLocked(oldAlarms, nowElapsed);

            oldAlarms.clear();
        }

        if (DBG) Slog.d(TAG, "recoverChangedAlarmsLocked() --");
    }

    /**
     * Load AppAlarmConfig from config files.
     */
    AppAlarmConfig loadAlarmWindowConfigs(boolean parseXml, boolean update) {
        if (DBG) Slog.d(TAG, "loadAlarmWindowConfigs() ++");
        AppAlarmConfig result;
        if (parseXml) {
            result = loadAlarmWindowConfigFiles();
            if (update) {
                updateAppAlarmConfig(result);
            }
        } else {
            result = mAppAlarmConfig;
        }
        if (DBG) Slog.d(TAG, "loadAlarmWindowConfigs() --");
        return result;
    }

    /**
     * Save AppAlarmConfig.
     */
    boolean persistAppAlarmConfig(AppAlarmConfig alarmConfig, boolean update, boolean persist) {
        if (DBG) Slog.d(TAG, "persistAppAlarmConfig() ++");

        boolean result = true;

        if (alarmConfig == null) {
            result = false;
            return result;
        }

        if (persist) {
            synchronized (mAppAlarmCfgOverwriteFile) {

                FileOutputStream fos = null;
                try {
                    fos = mAppAlarmCfgOverwriteFile.startWrite();
                } catch (IOException e) {
                    Slog.w(TAG, "Failed to persist App alarm config: " + e);
                    result = false;
                    return result;
                }

                try {
                    XmlSerializer out = new FastXmlSerializer();
                    out.setOutput(fos, "utf-8");
                    out.startDocument(null, true);
                    out.startTag(null, "alarm-align");

                    String strategy = null;
                    switch (alarmConfig.mAlarmStrategy) {
                        case SCREEN_OFF_ALARM_STRATEGY_FIXED2:
                            strategy = "fixed2";
                            break;
                        case SCREEN_OFF_ALARM_STRATEGY_NONE:
                        default:
                            strategy = "none";
                            break;
                    }
                    out.attribute(null, "strategy", strategy);
                    out.attribute(null, "strict", alarmConfig.mIsStrict?"true":"false");
                    out.attribute(null, "skip_non_wakeup",
                            alarmConfig.mSkipNonWakeup?"true":"false");
                    out.attribute(null, "align_non_wakeup",
                            alarmConfig.mAlignNonWakeup?"true":"false");
                    out.attribute(null, "align_system",
                            alarmConfig.mAlignSystemAlarm?"true":"false");
                    out.attribute(null, "fixed_alignment",
                            String.valueOf(alarmConfig.mAppAlarmFixedAlignment / 1000));
                    out.attribute(null,
                            "skip_short_len", String.valueOf(alarmConfig.mAppAlarmSkipShort / 1000));
                    out.attribute(null, "repeat_alignment",
                            String.valueOf(alarmConfig.mAppAlarmRepeatAlignment / 1000));

                    out.startTag(null, "white-list");
                    out.attribute(null, "ignore_top_app",
                            alarmConfig.mAppAlarmIgnoreTop ? "true" : "false");

                    if (alarmConfig.mAppAlarmWhiteList != null) {
                        for (Map.Entry<String,String> entry
                                : alarmConfig.mAppAlarmWhiteList.entrySet()) {
                            out.startTag(null, "app");
                            out.attribute(null, "package", entry.getKey());
                            out.attribute(null, "action", entry.getValue());
                            out.endTag(null, "app");
                        }
                    }

                    out.endTag(null, "white-list");

                    out.startTag(null, "black-list");
                    if (alarmConfig.mAppAlarmBlackList != null) {
                        for (Map.Entry<String,String> entry
                                : alarmConfig.mAppAlarmBlackList.entrySet()) {
                            out.startTag(null, "app");
                            out.attribute(null, "package", entry.getKey());
                            out.attribute(null, "action", entry.getValue());
                            out.endTag(null, "app");
                        }
                    }
                    out.endTag(null, "black-list");

                    out.endTag(null, "alarm-align");
                    out.endDocument();
                    mAppAlarmCfgOverwriteFile.finishWrite(fos);

                } catch (IOException e) {
                    Slog.w(TAG, "Failed to persist App alarm config, restoring backup.", e);
                    mAppAlarmCfgOverwriteFile.failWrite(fos);
                    result = false;
                }
            }
        }

        if (result && update) {
            updateAppAlarmConfig(alarmConfig);
        }

        if (DBG) Slog.d(TAG, "persistAppAlarmConfig() --");
        return result;
    }

    boolean isCharging() {
        boolean result = true;

        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent intent = mAlarmService.getContext().registerReceiver(null, filter);
        if (intent != null) {
            final int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                    BatteryManager.BATTERY_STATUS_UNKNOWN);
            result = (status == BatteryManager.BATTERY_STATUS_CHARGING);
        }

        return result;
    }

    ScreenOffAlarmStrategy getStrategy() {
        return mAppAlarmConfig.mAlarmStrategy;
    }

    boolean isSkipNonWakeup() {
        return mAppAlarmConfig.mSkipNonWakeup;
    }

    boolean isAlignNonWakeup() {
        return mAppAlarmConfig.mAlignNonWakeup;
    }

    boolean isStrict() {
        return mAppAlarmConfig.mIsStrict;
    }

    void addSkippedNonWakeupAlarmLocked(Alarm a) {
        mSkippedNonWakeupAlarms.add(a);
    }

    boolean lookForSkippedNonWakeupAlarmLocked(String packageName) {
        boolean result = false;

        if (mSkippedNonWakeupAlarms != null && mSkippedNonWakeupAlarms.size() > 0) {
            String pkg = null;
            for (Alarm a : mSkippedNonWakeupAlarms) {
                if (a != null && a.operation != null) {
                    pkg = a.operation.getCreatorPackage();
                    if (pkg != null && pkg.equals(packageName)) {
                        result = true;
                        break;
                    }
                }
            }
        }

        return result;
    }

    void removeSkippedNonWakeupAlarmLocked(PendingIntent operation) {
        if (mSkippedNonWakeupAlarms != null && mSkippedNonWakeupAlarms.size() > 0) {
            Alarm a = null;
            for (int i = mSkippedNonWakeupAlarms.size() - 1; i >= 0; i--) {
                a = mSkippedNonWakeupAlarms.get(i);
                if (a != null && a.operation != null && a.operation.equals(operation)) {
                    mSkippedNonWakeupAlarms.remove(i);
                }
            }
        }
    }

    void removeSkippedNonWakeupAlarmLocked(String packageName) {
        if (mSkippedNonWakeupAlarms != null && mSkippedNonWakeupAlarms.size() > 0) {
            String pkg = null;
            Alarm a = null;
            for (int i = mSkippedNonWakeupAlarms.size() - 1; i >= 0; i--) {
                a = mSkippedNonWakeupAlarms.get(i);
                if (a != null && a.operation != null) {
                    pkg = a.operation.getCreatorPackage();
                    if (pkg != null && pkg.equals(packageName)) {
                        mSkippedNonWakeupAlarms.remove(i);
                    }
                }
            }
        }
    }

    void removeSkippedNonWakeupAlarmLocked(int userHandle) {
        if (mSkippedNonWakeupAlarms != null && mSkippedNonWakeupAlarms.size() > 0) {
            Alarm a = null;
            for (int i = mSkippedNonWakeupAlarms.size() - 1; i >= 0; i--) {
                a = mSkippedNonWakeupAlarms.get(i);
                if (a != null && a.operation != null
                        && UserHandle.getUserId(a.operation.getCreatorUid()) == userHandle) {
                    mSkippedNonWakeupAlarms.remove(i);
                }
            }
        }
    }

    void decreaseSurvivalLocked(Alarm alarm) {
        if (alarm != null && alarm.extra != null && alarm.extra.survival > 0) {
            alarm.extra.survival--;
            if (DBG) Slog.d(TAG, "decreaseSurvivalLocked() - " + alarm);
        }
    }

    /**
     * Check if the current alarm is filtered.
     */
    boolean isFilteredAppAlarmLocked(Alarm alarm) {
        // if the alarm has extra, it must be filtered when set
        return alarm != null && alarm.operation != null
            && alarm.alarmClock == null
            && ((alarm.flags&AlarmManager.FLAG_IDLE_UNTIL) == 0)
            && alarm.extra != null
            && alarm.extra.survival <= 0; // no more play, insert coins
    }

    /**
     * Check the package and intent if it's filtered.
     */
    boolean isFilteredAppAlarm(String pkg, Intent intent, AlarmClockInfo alarmClock,
            int flags) {
        if ((flags&AlarmManager.FLAG_IDLE_UNTIL) != 0) {
            return false;
        }

        // Don't align an alarm with alarm clock info. Hopefully this will not cause
        // inconsistency to the real alarm clock, we suppose the alarm clock is only set when
        // screen on by user.
        if (alarmClock != null) {
            return false;
        }

        if (pkg == null) { // suppose this is system alarm
            if (mAppAlarmConfig.mAlignSystemAlarm) {
                return true;
            } else {
                return false;
            }
        }

        // check blacklist first
        if (mAppAlarmConfig.mAppAlarmBlackList.containsKey(pkg)) {
            String actionPattern = mAppAlarmConfig.mAppAlarmBlackList.get(pkg);
            if (DBG) Slog.d(TAG, "isFilteredAppAlarm() - hit black list, pkg=" + pkg
                    + ", actionPattern=" + actionPattern);
            if (actionPattern == null || actionPattern.length() == 0) {
                return true;
            }
            // need to match action
            String action = intent.getAction();
            if (action != null && Pattern.matches(actionPattern, action)) {
                if (DBG) Slog.d(TAG, "isFilteredAppAlarm() - blacklist action match:" + action);
                return true;
            }
        }

        // ignore white list App
        if (mAppAlarmConfig.mAppAlarmWhiteList.containsKey(pkg)) {
            String actionPattern = mAppAlarmConfig.mAppAlarmWhiteList.get(pkg);
            if (DBG) Slog.d(TAG, "isFilteredAppAlarm() - hit white list, pkg=" + pkg
                    + ", actionPattern=" + actionPattern);
            if (actionPattern == null || actionPattern.length() == 0) {
                return false;
            }
            // need to match action
            String action = intent.getAction();
            if (action != null && Pattern.matches(actionPattern, action)) {
                if (DBG) Slog.d(TAG, "isFilteredAppAlarm() - white list action match:" + action);
                return false;
            }
        }

        // ignore top App
        // TODO find a better way to get the top package from ActivityManager before
        // implement this.
        /*if (mAppAlarmConfig.mAppAlarmIgnoreTop
                && pkg.equals(AlarmManager.sCurTopPackage)) {
            if (DBG) Slog.d(TAG, "isFilteredAppAlarm() - ignore top App:" + pkg);
            return false;
        }*/

        if (!mAppAlarmConfig.mAlignSystemAlarm) {
            IPackageManager pm = AppGlobals.getPackageManager();
            ApplicationInfo info = null;
            try {
                info = pm.getApplicationInfo(pkg, 0, 0);
            } catch (RemoteException e) {
                Slog.e(TAG, "failed to get application info", e);
            }

            if (info != null)
                return (info.flags & ApplicationInfo.FLAG_SYSTEM) == 0;

            // something wrong, we're going to check with package name
            if (pkg.equals("android")
                    || pkg.startsWith("com.android.")
                    || pkg.startsWith("com.intel.")
                    || pkg.startsWith("com.google.")) {
                return false;
            }
        }

        return true;
    }

    /**
     * Round trigger time to next wakeup time.
     *
     * @param triggerelapsed original trigger time
     * @param nowElapsed now
     * @param roundtype how to round, 0 floor, 1 ceiling, others round
     *
     * @return rounded time
     */
    long roundToNextWakeup(long triggerElapsed, long nowElapsed, int roundType) {
        if (DBG) Slog.d(TAG, "roundToNextWakeup() ++");

        Slog.d(TAG, "roundToNextWakeup() - triggerElapsed=" + triggerElapsed
                + ", nowElapsed=" + nowElapsed);

        long roundedElapsed = triggerElapsed;

        if (mAppAlarmConfig.mAlarmStrategy != SCREEN_OFF_ALARM_STRATEGY_NONE) {

            long next = triggerElapsed;

            if (mAppAlarmConfig.mAlarmStrategy == SCREEN_OFF_ALARM_STRATEGY_FIXED2
                    && mAppAlarmConfig.mAppAlarmFixedAlignment > 0) {
                next = (nowElapsed / mAppAlarmConfig.mAppAlarmFixedAlignment + 1)
                    * mAppAlarmConfig.mAppAlarmFixedAlignment;
            }

            Slog.d(TAG, "next=" + next);

            if (triggerElapsed <= next) {
                roundedElapsed = next;
            } else {
                while (next < triggerElapsed) {
                    if (mAppAlarmConfig.mAlarmStrategy == SCREEN_OFF_ALARM_STRATEGY_FIXED2) {
                        next += mAppAlarmConfig.mAppAlarmFixedAlignment;
                    }
                }

                if (next == triggerElapsed) { // Great, no need to round.
                    roundedElapsed = next;
                } else {
                    if (roundType == 0) {
                        if (mAppAlarmConfig.mAlarmStrategy == SCREEN_OFF_ALARM_STRATEGY_FIXED2) {
                            roundedElapsed = next - mAppAlarmConfig.mAppAlarmFixedAlignment;
                        }
                    } else if (roundType == 1) {
                        roundedElapsed = next;
                    } else {
                        if (mAppAlarmConfig.mAlarmStrategy == SCREEN_OFF_ALARM_STRATEGY_FIXED2) {
                            if ((next - triggerElapsed)
                                    <= mAppAlarmConfig.mAppAlarmFixedAlignment / 2) {
                                roundedElapsed = next;
                            } else {
                                roundedElapsed = next - mAppAlarmConfig.mAppAlarmFixedAlignment;
                            }
                        }
                    }
                }
            }
        }

        Slog.d(TAG, "roundedElapsed=" + roundedElapsed);

        if (DBG) Slog.d(TAG, "roundToNextWakeup() --");
        return roundedElapsed;
    }

    private AppAlarmConfig loadAlarmWindowConfigFiles() {
        if (DBG) Slog.d(TAG, "loadAlarmWindowConfigFiles() ++");

        AppAlarmConfig alarmConfig = new AppAlarmConfig();

        FileInputStream fis = null;

        File rootDir = Environment.getRootDirectory();
        File winConfFile = new File(rootDir, "etc/alarm_alignment_conf.xml");
        if (winConfFile.exists()) {
            Slog.d(TAG, "loading " + winConfFile.getAbsolutePath());
            try {
                fis = new FileInputStream(winConfFile);
                parseAlarmWindowConfig(fis, alarmConfig, false);
            } catch (FileNotFoundException e) {
                Slog.e(TAG, "system alarm_alignment_conf.xml not found!", e);
            } finally {
                if (fis != null) {
                    try {
                        fis.close();
                    } catch (IOException ioe) {
                        Slog.w(TAG, "Error closing system alarm_alignment_conf.xml", ioe);
                    }
                }
                fis = null;
            }
        }

        synchronized (mAppAlarmCfgOverwriteFile) {
            File winConfOverwrite = mAppAlarmCfgOverwriteFile.getBaseFile();
            if (winConfOverwrite.exists()) {
                Slog.d(TAG, "loading " + winConfOverwrite.getAbsolutePath());
                try {
                    fis = mAppAlarmCfgOverwriteFile.openRead();
                    parseAlarmWindowConfig(fis, alarmConfig, true);
                } catch (FileNotFoundException e) {
                    Slog.e(TAG, "override alarm_alignment_conf.xml not found!", e);
                } finally {
                    if (fis != null) {
                        try {
                            fis.close();
                        } catch (IOException ioe) {
                            Slog.w(TAG, "Error closing override alarm_alignment_conf.xml", ioe);
                        }
                    }
                    fis = null;
                }
            }
        }

        if (alarmConfig.mIsStrict) {
            if (alarmConfig.mAlarmStrategy == SCREEN_OFF_ALARM_STRATEGY_FIXED2) {
                alarmConfig.mAppAlarmRepeatAlignment = alarmConfig.mAppAlarmFixedAlignment;
                Slog.d(TAG, "strict mode and fixed strategy, force repeat interval to "
                        + alarmConfig.mAppAlarmRepeatAlignment);
            }
        }

        if (DBG) Slog.d(TAG, "loadAlarmWindowConfigFiles() --");

        return alarmConfig;
    }

    private void parseAlarmWindowConfig(FileInputStream alarmConfFile,
            AppAlarmConfig alarmConfig, boolean isOverwrite) {
        if (DBG) Slog.d(TAG, "parseAlarmWindowConfig() ++");

        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(alarmConfFile, null);

            boolean inWhiteList = false, inBlackList = false;
            int eventType;
            String tagName, repeatAlignment, alarmStrategy, fixedAlignment, appPackage;
            String actionPattern, ignoreTopApp, isStrict, skipNonWakeup, alignNonWakeup;
            String alignSystem, skipShortLen;

            do {
                eventType = parser.next();
                if (eventType == XmlPullParser.START_TAG) {
                    tagName = parser.getName();

                    if ("alarm-align".equals(tagName)) {
                        alarmStrategy = parser.getAttributeValue(null, "strategy");
                        if ("fixed2".equals(alarmStrategy)) {
                            alarmConfig.mAlarmStrategy = SCREEN_OFF_ALARM_STRATEGY_FIXED2;
                        } else if ("none".equals(alarmStrategy)) {
                            alarmConfig.mAlarmStrategy = SCREEN_OFF_ALARM_STRATEGY_NONE;
                        } else {
                            Slog.w(TAG, "invalid strategy name:" + alarmStrategy);
                            if (!isOverwrite) {
                                alarmConfig.mAlarmStrategy = SCREEN_OFF_ALARM_STRATEGY_NONE;
                            }
                        }
                        Slog.d(TAG, "mAlarmStrategy=" + alarmConfig.mAlarmStrategy);

                        fixedAlignment = parser.getAttributeValue(null, "fixed_alignment");
                        if (fixedAlignment != null) {
                            try {
                                alarmConfig.mAppAlarmFixedAlignment
                                    = Long.parseLong(fixedAlignment) * 1000L;
                            } catch (NumberFormatException nfe) {
                                Slog.e(TAG, "invalid fixed alignment", nfe);
                                if (!isOverwrite)
                                    alarmConfig.mAppAlarmFixedAlignment = 0L;
                            }
                        }
                        Slog.d(TAG, "mAppAlarmFixedAlignment="
                                + alarmConfig.mAppAlarmFixedAlignment);

                        isStrict = parser.getAttributeValue(null, "strict");
                        if (isStrict != null) {
                            if ("true".equalsIgnoreCase(isStrict)) {
                                alarmConfig.mIsStrict = true;
                            } else {
                                alarmConfig.mIsStrict = false;
                            }
                        }
                        Slog.d(TAG, "mIsStrict = " + alarmConfig.mIsStrict);

                        skipNonWakeup = parser.getAttributeValue(null, "skip_non_wakeup");
                        if (skipNonWakeup != null) {
                            if ("true".equalsIgnoreCase(skipNonWakeup)) {
                                alarmConfig.mSkipNonWakeup = true;
                            } else {
                                alarmConfig.mSkipNonWakeup = false;
                            }
                        }
                        Slog.d(TAG, "mSkipNonWakeup = " + alarmConfig.mSkipNonWakeup);

                        alignNonWakeup = parser.getAttributeValue(null, "align_non_wakeup");
                        if (alignNonWakeup != null) {
                            if ("true".equalsIgnoreCase(alignNonWakeup)) {
                                alarmConfig.mAlignNonWakeup = true;
                            } else {
                                alarmConfig.mAlignNonWakeup = false;
                            }
                        }
                        Slog.d(TAG, "mAlignNonWakeup = " + alarmConfig.mAlignNonWakeup);

                        alignSystem = parser.getAttributeValue(null, "align_system");
                        if (alignSystem != null) {
                            if ("true".equalsIgnoreCase(alignSystem)) {
                                alarmConfig.mAlignSystemAlarm = true;
                            } else {
                                alarmConfig.mAlignSystemAlarm = false;
                            }
                        }
                        Slog.d(TAG, "mAlignSystemAlarm = " + alarmConfig.mAlignSystemAlarm);

                        skipShortLen = parser.getAttributeValue(null, "skip_short_len");
                        if (skipShortLen != null) {
                            try {
                                alarmConfig.mAppAlarmSkipShort =
                                    Long.parseLong(skipShortLen) * 1000L;
                            } catch (NumberFormatException nfe) {
                                Slog.e(TAG, "invalid skip short length", nfe);
                                if (!isOverwrite)
                                    alarmConfig.mAppAlarmSkipShort = 0L;
                            }
                        }
                        Slog.d(TAG, "mAppAlarmSkipShort=" + alarmConfig.mAppAlarmSkipShort);

                        repeatAlignment = parser.getAttributeValue(null, "repeat_alignment");
                        if (repeatAlignment != null) {
                            try {
                                alarmConfig.mAppAlarmRepeatAlignment
                                    = Long.parseLong(repeatAlignment) * 1000L;
                            } catch (NumberFormatException nfe) {
                                Slog.e(TAG, "invalid repeat alignment", nfe);
                                if (!isOverwrite)
                                    alarmConfig.mAppAlarmRepeatAlignment = 0L;
                            }
                        }
                        Slog.d(TAG, "mAppAlarmRepeatAlignment="
                                + alarmConfig.mAppAlarmRepeatAlignment);

                    } else if ("white-list".equals(tagName)) {
                        inWhiteList = true;
                        ignoreTopApp = parser.getAttributeValue(null, "ignore_top_app");
                        if (ignoreTopApp != null) {
                            if ("true".equalsIgnoreCase(ignoreTopApp)) {
                                alarmConfig.mAppAlarmIgnoreTop = true;
                            } else {
                                alarmConfig.mAppAlarmIgnoreTop = false;
                            }
                        }
                        Slog.d(TAG, "mAppAlarmIgnoreTop = " + alarmConfig.mAppAlarmIgnoreTop);

                    } else if ("black-list".equals(tagName)) {
                        inBlackList = true;

                    } else if ("app".equals(tagName)) {
                        appPackage = parser.getAttributeValue(null, "package");
                        if (appPackage != null && appPackage.length() > 0) {
                            actionPattern = parser.getAttributeValue(null, "action");
                            if (inWhiteList) {
                                Slog.d(TAG, "add white list:" + appPackage + ", " + actionPattern);
                                alarmConfig.mAppAlarmWhiteList.put(appPackage, actionPattern);
                            } else if (inBlackList) {
                                Slog.d(TAG, "add black list:" + appPackage + ", " + actionPattern);
                                alarmConfig.mAppAlarmBlackList.put(appPackage, actionPattern);
                            } else {
                                Slog.w(TAG, "invalid app tag context!");
                            }
                        }
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    tagName = parser.getName();
                    if ("white-list".equals(tagName)) {
                        inWhiteList = false;
                    } else if ("black-list".equals(tagName)) {
                        inBlackList = false;
                    }
                }
            } while(eventType != XmlPullParser.END_DOCUMENT);
        } catch (XmlPullParserException e) {
            Slog.e(TAG, "Error parsing alarm_alignment_conf.xml", e);
        } catch (IOException e) {
            Slog.e(TAG, "Error reading alarm_alignment_conf.xml", e);
        }

        if (DBG) Slog.d(TAG, "parseAlarmWindowConfig() --");
    }

    private void updateAppAlarmConfig(AppAlarmConfig alarmConfig) {
        synchronized (mAlarmService) {
            Slog.w(TAG, "updating App alarm config:");
            Slog.w(TAG, "old config:" + mAppAlarmConfig);
            Slog.w(TAG, "new config:" + alarmConfig);
            mAppAlarmConfig = alarmConfig;
            updateAppAlarmProperties(mAppAlarmConfig);
        }
    }

    private void updateAppAlarmProperties(AppAlarmConfig alarmConfig) {
        String alarmStrategy;
        switch (alarmConfig.mAlarmStrategy) {
            case SCREEN_OFF_ALARM_STRATEGY_FIXED2:
                alarmStrategy = "fixed2";
                break;
            case SCREEN_OFF_ALARM_STRATEGY_NONE:
            default:
                alarmStrategy = "none";
                break;
        }
        SystemProperties.set(APP_ALARM_STRATEGY_PROP, alarmStrategy);
        if (alarmConfig.mAlarmStrategy == SCREEN_OFF_ALARM_STRATEGY_FIXED2) {
            SystemProperties.set(APP_ALARM_FIXED_INTERVAL,
                    String.valueOf(alarmConfig.mAppAlarmFixedAlignment));
        } else {
            SystemProperties.set(APP_ALARM_FIXED_INTERVAL, "");
        }
    }

    private boolean isWakeupType(int type) {
        return type == AlarmManager.ELAPSED_REALTIME_WAKEUP
            || type == AlarmManager.RTC_WAKEUP;
    }

    void dumpLocked(PrintWriter pw, long nowELAPSED, long nowRTC, SimpleDateFormat sdf) {
        if (mAppAlarmConfig != null) {
            AppAlarmConfig cfg = mAppAlarmConfig;
            pw.println();
            pw.println("  App Alarm Alignment Config:");
            pw.print("    strategy:"); pw.println(cfg.mAlarmStrategy);
            pw.print("    repeat alignment:"); pw.println(cfg.mAppAlarmRepeatAlignment);
            pw.print("    fixed alignment:"); pw.println(cfg.mAppAlarmFixedAlignment);
            pw.print("    skip short interval:"); pw.println(cfg.mAppAlarmSkipShort);
            pw.print("    strict:"); pw.println(cfg.mIsStrict);
            pw.print("    ignore top:"); pw.println(cfg.mAppAlarmIgnoreTop);
            pw.print("    skip nonwakeup:"); pw.println(cfg.mSkipNonWakeup);
            pw.print("    align nonwakeup:"); pw.println(cfg.mAlignNonWakeup);
            pw.print("    align system alarm:"); pw.println(cfg.mAlignSystemAlarm);

            pw.println("    whitelist:");
            if(cfg.mAppAlarmWhiteList != null && cfg.mAppAlarmWhiteList.size() > 0) {
                for (Map.Entry<String, String> e : cfg.mAppAlarmWhiteList.entrySet()) {
                    pw.print("      pkg:"); pw.print(e.getKey());
                    pw.print(", act:"); pw.print(e.getValue());
                    pw.println();
                }
            }
            pw.println("    blacklist:");
            if (cfg.mAppAlarmBlackList != null && cfg.mAppAlarmBlackList.size() > 0) {
                for (Map.Entry<String, String> e : cfg.mAppAlarmBlackList.entrySet()) {
                    pw.print("      pkg:"); pw.print(e.getKey());
                    pw.print(", act:"); pw.print(e.getValue());
                    pw.println();
                }
            }
            pw.println();
        }

        if (mSkippedNonWakeupAlarms != null && mSkippedNonWakeupAlarms.size() > 0) {
            pw.println();
            pw.println("  Skipped None Wakeup Alarms:");
            pw.println();
        }
    }

    static final class AlarmExtraData {
        public long orgRepeatInterval;
        public long orgWindowLength;
        public long triggerDelta;
        public int survival;

        public void copyFrom(AlarmExtraData e) {
            orgRepeatInterval = e.orgRepeatInterval;
            orgWindowLength = e.orgWindowLength;
            triggerDelta = e.triggerDelta;
            survival = e.survival;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("AlarmExtraData{");
            sb.append(" repeat ");
            sb.append(orgRepeatInterval);
            sb.append(" win ");
            sb.append(orgWindowLength);
            sb.append(" delta ");
            sb.append(triggerDelta);
            sb.append(" survival ");
            sb.append(survival);
            sb.append('}');
            return sb.toString();
        }

        public void dump(PrintWriter pw, String prefix) {
            pw.print(prefix); pw.println("Extras:");
            pw.print(prefix); pw.print("  orgRepeatInterval="); pw.println(orgRepeatInterval);
            pw.print(prefix); pw.print("  orgWindowLength="); pw.println(orgWindowLength);
            pw.print(prefix); pw.print("  triggerDelta="); pw.println(triggerDelta);
            pw.print(prefix); pw.print("  survival="); pw.println(survival);
        }
    }

    static final class AlarmAlignmentInfo {
        public long triggerAtTime;
        public long triggerElapsed;
        public long interval;
        public long windowLength;
        public int flags;

        public AlarmAlignmentInfo(long triggerAtTime, long triggerElapsed,
                long interval, long windowLength, int flags) {
            this.triggerAtTime = triggerAtTime;
            this.triggerElapsed = triggerElapsed;
            this.interval = interval;
            this.windowLength = windowLength;
            this.flags = flags;
        }
    }
}
