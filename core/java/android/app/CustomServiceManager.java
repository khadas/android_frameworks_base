package android.app;


import android.content.Context;
import android.os.custom.ICustomService;
import android.annotation.SystemService;
import android.os.RemoteException;
import android.util.Log;

@SystemService(Context.CUSTOM_SERVICE)
public class CustomServiceManager {

    private static final String TAG = "CustomServiceManager";
    ICustomService mService;

    public CustomServiceManager(Context context, ICustomService service) {
        mService = service;
    }

    public void setLedMode(int type, int mode) {
        try {
            mService.setLedMode(type, mode);
        } catch (RemoteException e) {

        }
    }

    public int getLedMode(int type) {
        try {
            return mService.getLedMode(type);
        } catch (RemoteException e) {

        }
        return 0;
    }

    public void setFanMode(int mode) {
        try {
            mService.setFanMode(mode);
        } catch (RemoteException e) {

        }
    }

    public int getFanMode() {
        try {
            return mService.getFanMode();
        } catch (RemoteException e) {

        }
        return 0;
    }

    public void setWolMode(boolean mode) {
        try {
            mService.setWolMode(mode);
        } catch (RemoteException e) {

        }
    }

    public boolean getWolMode() {
        try {
            return mService.getWolMode();
        } catch (RemoteException e) {

        }
        return false;
    }

    public boolean gpioCrtl(int gpio, String direction, int level) {
        try {
            return mService.gpioCrtl(gpio, direction, level);
        } catch (RemoteException e) {
        }
        return false;
    }

    public int gpioRead(int gpio) {
        try {
            return mService.gpioRead(gpio);
        } catch (RemoteException e) {

        }
        return -1;
    }

    public boolean gpioRequest(int gpio) {
        try {
            return mService.gpioRequest(gpio);
        } catch (RemoteException e) {

        }
        return false;
    }

    public boolean gpioSetDirection(int gpio, String direction) {
        try {
            return mService.gpioSetDirection(gpio, direction);
        } catch (RemoteException e) {

        }
        return false;
    }

    public boolean gpioSetValue(int gpio, int value) {
        try {
            return mService.gpioSetValue(gpio, value);
        } catch (RemoteException e) {

        }
        return false;
    }

    public int gpioGetValue(int gpio) {
        try {
            return mService.gpioGetValue(gpio);
        } catch (RemoteException e) {

        }
        return -1;
    }

    public boolean gpioFree(int gpio) {
        try {
            return mService.gpioFree(gpio);
        } catch (RemoteException e) {

        }
        return false;
    }

    public int i2cReadByteData(int bus, int addr, int reg) {
        try {
            return mService.i2cReadByteData(bus, addr, reg);
        } catch (RemoteException e) {

        }
        return -1;
    }

    public int i2cWriteByteData(int bus, int addr, int reg, int value) {
        try {
            return mService.i2cWriteByteData(bus, addr, reg, value);
        } catch (RemoteException e) {

        }
        return -1;
    }

    public int screenRecord(String pathName, int seconds) {
        try {
            return mService.screenRecord(pathName, seconds);
        } catch (RemoteException e) {

        }
        return 0;
    }

    public void setStartupApp(String pkgName) {
        try {
            mService.setStartupApp(pkgName);
        } catch (RemoteException e) {

        }
    }

    public void setKeepAliveApp(String pkgName) {
        try {
            mService.setKeepAliveApp(pkgName);
        } catch (RemoteException e) {

        }
    }

    public void switchLanguage(String language, String country) {
        try {
            mService.switchLanguage(language, country);
        } catch (RemoteException e) {

        }
    }

    public void setSystemPropertiesInt(String name, int value) {
        try {
            mService.setSystemPropertiesInt(name, value);
        } catch (RemoteException e) {

        }
    }

    public int getSystemPropertiesInt(String name, int value) {
        try {
            return mService.getSystemPropertiesInt(name, value);
        } catch (RemoteException e) {
            return -1;
        }
    }

    public void setSystemProperties(String name, String val) {
        try {
            mService.setSystemProperties(name, val);
        } catch (RemoteException e) {

        }
    }

    public String getSystemProperties(String name, String val) {
        try {
            return mService.getSystemProperties(name, val);
        } catch (RemoteException e) {
            return "getValidResolution error";
        }
    }

    public String execSuCmd(String cmd) {
        try {
            return mService.execSuCmd(cmd);
        } catch (RemoteException e) {
            return "execSuCmd error";
        }
    }

    public int setDisplayPosition(int pos) {
        try {
            return mService.setDisplayPosition(pos);
        } catch (RemoteException e) {
            return -1;
        }
    }

    public boolean setTime(int year, int month, int day, int hour, int minute, int second) {
        try {
            return mService.setTime(year, month, day, hour, minute, second);
        } catch (RemoteException e) {
        }

        return false;
    }

    public void silentInstall(String apkPath, boolean isLaunch) {
        try {
            mService.silentInstall(apkPath, isLaunch);
        } catch (RemoteException e) {
        }
    }

    public void silentUnInstall(String pkgName) {
        try {
            mService.silentUnInstall(pkgName);
        } catch (RemoteException e) {
        }
    }

    public boolean silentCmdInstall(String apkPath) {
        try {
            return mService.silentCmdInstall(apkPath);
        } catch (RemoteException e) {
        }
        return false;
    }

    public boolean silentCmdUnInstall(String pkgName) {
        try {
            return mService.silentCmdUnInstall(pkgName);
        } catch (RemoteException e) {
        }
        return false;
    }

    public void reboot() {
        try {
            mService.reboot();
        } catch (RemoteException e) {
        }
    }

    public void shutdown() {
        try {
            mService.shutdown();
        } catch (RemoteException e) {
        }
    }

    public void sleep() {
        try {
            mService.sleep();
        } catch (RemoteException e) {
        }
    }

    public void wakeup() {
        try {
            mService.wakeup();
        } catch (RemoteException e) {
        }
    }

    public void installPackage(String zipPath) {
        try {
            mService.installPackage(zipPath);
        } catch (RemoteException e) {
        }
    }

    public void updateSystem(String zipPath) {
        try {
            mService.updateSystem(zipPath);
        } catch (RemoteException e) {
        }
    }

    public void setStatusBarVisibility(boolean visibility) {
        try {
            mService.setStatusBarVisibility(visibility);
        } catch (RemoteException e) {
        }
    }

    public void setNavigationbarVisibility(boolean visibility) {
        try {
            mService.setNavigationbarVisibility(visibility);
        } catch (RemoteException e) {
        }
    }

}
