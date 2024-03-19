package android.app;

import android.app.IKhadasApiManager;
import android.content.Context;
import android.os.RemoteException;
import android.os.Build;

public class KhadasApiManager {
    private static String TAG = "KhadasApiManager";

    IKhadasApiManager mKhadasApiManager;
    private Context context;
    public KhadasApiManager(Context ctx,IKhadasApiManager khadasApiManager) {
        mKhadasApiManager = khadasApiManager;
        context = ctx;
    }

    public void setLedMode(int type, int mode){
        try {
            mKhadasApiManager.setLedMode(type, mode);
        } catch (RemoteException e) {

        }
    }

    public int getLedMode(int type){
        try {
            return mKhadasApiManager.getLedMode(type);
        } catch (RemoteException e) {

        }
        return 0;
    }

    public void setFanMode(int mode){
        try {
            mKhadasApiManager.setFanMode(mode);
        } catch (RemoteException e) {

        }
    }

    public int getFanMode(){
        try {
            return mKhadasApiManager.getFanMode();
        } catch (RemoteException e) {

        }
        return 0;
    }

    public void setWolMode(boolean mode){
        try {
            mKhadasApiManager.setWolMode(mode);
        } catch (RemoteException e) {

        }
    }

    public boolean getWolMode(){
        try {
            return mKhadasApiManager.getWolMode();
        } catch (RemoteException e) {

        }
        return false;
    }

    public void switchLanguage(String language, String country){
        try {
            mKhadasApiManager.switchLanguage(language,country);
        } catch (RemoteException e) {

        }
    }

    public void setSystemProperties_int(String name, int value) {
        try {
            mKhadasApiManager.setSystemProperties_int(name, value);
        } catch (RemoteException e) {

        }
    }

    public int getSystemProperties_int(String name, int value) {
        try {
            return mKhadasApiManager.getSystemProperties_int(name, value);
        } catch (RemoteException e) {
            return -1;
        }
    }

    public void setSystemProperties(String name, String val) {
        try {
            mKhadasApiManager.setSystemProperties(name, val);
        } catch (RemoteException e) {

        }
    }

    public String getSystemProperties(String name, String val) {
        try {
            return mKhadasApiManager.getSystemProperties(name, val);
        } catch (RemoteException e) {
            return "getValidResolution error";
        }
    }
}
