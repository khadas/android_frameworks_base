package com.android.server.khadas;

import android.util.Log;
import android.app.IKhadasApiManager;
import android.content.Context;
import android.os.HandlerThread;
import android.os.ServiceManager;

import java.io.File;
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
import android.os.LocaleList;
import com.android.internal.app.LocalePicker;

public class KhadasApiServiceImpl extends IKhadasApiManager.Stub {
	private static final String TAG = "KhadasApiServiceImpl";

	private static final String PROP_LED_WHITE_TRIGGER = "persist.sys.white.led.trigger";
	private static final String PROP_LED_RED_TRIGGER = "persist.sys.red.led.mode";
	private static final String SYS_LED_WHITE_TRIGGER = "/sys/class/leds/sys_led/trigger";
	private static final String SYS_LED_RED_TRIGGER = "/sys/class/redled/mode";
	public static final int LED_WHITE = 0;
	public static final int LED_RED   = 1;
	private static final int INDEX_HEARTBEAT = 0;
	private static final int INDEX_ON = 1;
	private static final int INDEX_OFF = 2;
	private static final int INDEX_LED[] = {
			INDEX_HEARTBEAT,
			INDEX_ON,
			INDEX_OFF,
	};
	private static final String ModeList[] = {
			"heartbeat",
			"default-on",
			"off",
	};

	private static final String PROP_FAN_ENABLE = "persist.sys.fan.enable";
	private static final String PROP_FAN_MODE  = "persist.sys.fan.mode";
	private static final String PROP_FAN_LEVEL = "persist.sys.fan.level";
	private static final String PROP_FAN_INDEX = "persist.sys.fna.index";
	private static final String SYS_FAN_MODE = "/sys/class/hwmon/hwmon1/mode";
	private static final String SYS_FAN_LEVEL = "/sys/class/thermal/cooling_device0/cur_state";
	private static final String SYS_FAN_ENABLE = "/sys/class/hwmon/hwmon1/enable";
	private static final int INDEX_AUTO = 0;
	private static final int INDEX_LEVEL_1 = 1;
	private static final int INDEX_LEVEL_2 = 2;
	private static final int INDEX_LEVEL_3 = 3;
	private static final int INDEX_LEVEL_4 = 4;
	private static final int INDEX_LEVEL_5 = 5;
	private static final String WOL_STATE_SYS = "/sys/class/wol/enable";

	Context mcontext;
	public KhadasApiServiceImpl(Context context) {
		//Log.d(TAG,"IKhadasApiService is create");
		mcontext = context;
	}

	public void start() {
		Log.i(TAG, "Starting KhadasApiManager Service");

		HandlerThread handlerThread = new HandlerThread("KhadasApiServiceThread");
		handlerThread.start();
	}

	private void setNode(String name   , int value){
		Log.d(TAG,"node name: " + name + " = " + value);
		File file = new File(name);
		if((file == null) || !file.exists()){
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

	public void setSystemProperties_int(String name, int value) {
		SystemProperties.set(name, String.valueOf(value));
	}

	public int getSystemProperties_int(String name, int value) {
		return SystemProperties.getInt(name, value);
	}

	public void setSystemProperties(String name, String val) {
		SystemProperties.set(name, val);
	}

	public String getSystemProperties(String name, String val) {
		return SystemProperties.get(name, val);
	}

	public void setLedMode(int type, int mode){
		Log.d(TAG,"setLed type: " + type + "setLedMode: " + mode);
		try {
			BufferedWriter bufWriter = null;
			if (type == LED_WHITE) {
				bufWriter = new BufferedWriter(new FileWriter(SYS_LED_WHITE_TRIGGER));
				bufWriter.write(ModeList[mode]);
				SystemProperties.set(PROP_LED_WHITE_TRIGGER, String.valueOf(mode));
			} else {
				bufWriter = new BufferedWriter(new FileWriter(SYS_LED_RED_TRIGGER));
				bufWriter.write(String.valueOf(INDEX_LED[mode]));
				SystemProperties.set(PROP_LED_RED_TRIGGER, String.valueOf(mode));
			}
			bufWriter.close();
		} catch (IOException e) {
			e.printStackTrace();
			Log.e(TAG,"can't write the led node");
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


	public void setFanMode(int mode){
		Log.d(TAG,"setLedMode: " + mode);

		switch (mode) {
			case INDEX_AUTO:
				setNode(SYS_FAN_ENABLE,1);
				setSystemProperties_int(PROP_FAN_ENABLE,1);
				setNode(SYS_FAN_MODE,1);
				setSystemProperties_int(PROP_FAN_MODE,1);
				setSystemProperties_int(PROP_FAN_INDEX,mode);
				break;
			case INDEX_LEVEL_1:
			case INDEX_LEVEL_2:
			case INDEX_LEVEL_3:
			case INDEX_LEVEL_4:
			case INDEX_LEVEL_5:
				setNode(SYS_FAN_ENABLE,1);
				setSystemProperties_int(PROP_FAN_ENABLE,1);
				setNode(SYS_FAN_MODE,0);
				setSystemProperties_int(PROP_FAN_MODE,0);
				setNode(SYS_FAN_LEVEL,mode);
				setSystemProperties_int(PROP_FAN_LEVEL,mode);
				setSystemProperties_int(PROP_FAN_INDEX,mode);
				break;
			case 6:
				setNode(SYS_FAN_ENABLE,0);
				setSystemProperties_int(PROP_FAN_ENABLE,0);
				break;
			default:
				//setNode(SYS_FAN_ENABLE,0);
				//setSystemProperties_int(PROP_FAN_ENABLE,0);
				break;
		}
	}

	public int getFanMode()
	{
		if(0 == getSystemProperties_int(PROP_FAN_ENABLE,0))
			return 6;
		else
			return getSystemProperties_int(PROP_FAN_INDEX,0);
	}

	public void setWolMode(boolean mode) {
		try {
			RandomAccessFile rdf = null;
			rdf = new RandomAccessFile(WOL_STATE_SYS, "rw");
			rdf.writeBytes(mode ? "1" : "0");
			rdf.close();
		} catch (IOException re) {
			Log.e(TAG, "setWolMode");
		}
	}

	public boolean getWolMode() {
		boolean enabled = false;
		try {
			FileReader fread = new FileReader(WOL_STATE_SYS);
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

	public void switchLanguage(String language, String country){

		Locale locale = new Locale(language, country);
		LocaleList localeList = LocalePicker.getLocales();
		int index = -1;
		for (int i = 0; i < localeList.size(); i++) {
			Locale lc = localeList.get(i);
			if (locale.equals(lc)) {
				index = i;
				break;
			}
		}
		Locale[] localeArrayNew = new Locale[index < 0 ? localeList.size() + 1 : localeList.size()];
		if (index < 0) {
			localeArrayNew[0] = locale;
			for (int i = 0; i < localeList.size(); i++) {
				localeArrayNew[i + 1] = localeList.get(i);
			}
		} else {
			for (int i = 0; i < localeList.size(); i++) {
				localeArrayNew[i] = localeList.get(i);
			}
			localeArrayNew[index] = localeArrayNew[0];
			localeArrayNew[0] = locale;
		}
		LocaleList mLocalesToSetNext = new LocaleList(localeArrayNew);
		LocalePicker.updateLocales(mLocalesToSetNext);
	}
}
