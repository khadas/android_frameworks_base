package com.android.systemui.utils;

import android.content.Context;

/**
 * 尺寸转换工具
 * Created by GaoFei on 2016/1/3.
 */
public class SizeUtils {
    private SizeUtils(){
    }

    /**
     * 将dp转化成像素
     *
     * @param dpValue
     * @return
     */
    public static int dp2px(Context context, float dpValue){
       return (int)(DeviceInfoUtils.getDenstity(context) * dpValue);
    }

    /**
     * 将sp转化成像素
     *
     * @param spValue
     * @return
     */
    public float sp2px(Context context, float spValue){
        return DeviceInfoUtils.getScaleDenstity(context) * spValue;
    }

    /**
     * 将像素转化成dp
     * @return
     */
    public float px2dp(Context context, int pxValue){
        return pxValue / DeviceInfoUtils.getDenstity(context);
    }

}