package android.os.custom;

interface ICustomService {

    void setLedMode(int type, int mode);
	int getLedMode(int type);
	void setFanMode(int mode);
	int getFanMode();
	void setWolMode(boolean mode);
	boolean getWolMode();
    void setStartupApp(String pkgName);
    void setKeepAliveApp(String pkgName);
    boolean gpioCrtl(int gpio, String direction, int level);
    int gpioRead(int gpio);
    boolean gpioRequest(int gpio);
    boolean gpioSetDirection(int gpio, String direction);
    boolean gpioSetValue(int gpio, int value);
    int gpioGetValue(int gpio);
    boolean gpioFree(int gpio);
    int i2cReadByteData(int bus, int addr, int reg);
    int i2cWriteByteData(int bus, int addr, int reg, int value);
	void switchLanguage(String language, String country);
    boolean setTime(int year, int month, int day, int hour, int minute, int second);
    void setSystemPropertiesInt(String name, int value);
    int getSystemPropertiesInt(String name, int value);
    void setSystemProperties(String name, String val);
    String getSystemProperties(String name, String val);
    int setDisplayPosition(int pos);
    int screenRecord(String pathName, int seconds);
    String execSuCmd(String cmd);
    boolean silentCmdInstall(String apkPath);
    boolean silentCmdUnInstall(String package_name);
    void silentInstall(String apkPath, boolean isLaunch);
    void silentUnInstall(String package_name);
    void reboot();
    void shutdown();
    void sleep();
    void wakeup();
    void installPackage(String zipPath);
    void updateSystem(String zipPath);
    void setStatusBarVisibility(boolean visibility);
    void setNavigationbarVisibility(boolean visibility);
}