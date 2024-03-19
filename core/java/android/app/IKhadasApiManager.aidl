package android.app;

interface IKhadasApiManager {
	void setLedMode(int type, int mode);
	int getLedMode(int type);
	void setFanMode(int mode);
	int getFanMode();
	void setWolMode(boolean mode);
	boolean getWolMode();
	void switchLanguage(String language, String country);
    void setSystemProperties_int(String name, int value);
    int getSystemProperties_int(String name, int value);
    void setSystemProperties(String name, String val);
    String getSystemProperties(String name, String val);
}
