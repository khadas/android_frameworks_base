/*
 * Copyright (c) 2024 Rockchip Electronics Co., Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.server.wm;

import android.app.ActivityTaskManager;
import android.animation.ValueAnimator;
import android.app.IActivityTaskManager;
import android.graphics.Rect;
import android.text.TextUtils;
import android.util.Slog;
import android.util.Xml;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.view.WindowManagerPolicyConstants.PointerEventListener;
import android.view.VelocityTracker;

import com.android.server.wm.WindowManagerService;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;

public class FingersDisplayAnimationEventListener implements PointerEventListener {
    public static final String TAG = FingersDisplayAnimationEventListener.class.getSimpleName();

    private static boolean DEBUG = false;

    private static final String CONFIG_PATH = "/system/etc/config_three_fingers.xml";
    private static final String TAG_CONFIG = "config";
    private static final String TAG_LOG_ENABLE = "log-enable";
    private static final String TAG_ENABLE = "enable";
    private static final String TAG_FINGER_NUM = "finger-num";
    private static final String TAG_MIN_SWIPE_DISTANCE_RATIO = "min-swipe-distance-ratio";
    private static final String TAG_EFFECTIVE_DISTANCE_RATIO = "effective-distance-ratio";
    private static final String TAG_WHITE_APP_LISTS = "white-app-lists";
    private static final String TAG_BLACK_APP_LISTS = "black-app-lists";
    private static final String TAG_PACKAGE = "package";
    private static final String TAG_LOCATION = "location";
    private static final String TAG_DISPLAY = "display";
    private static final String TAG_UNIQUE_ID = "uniqueId";
    private static final String TAG_TOP = "top";
    private static final String TAG_LEFT = "left";
    private static final String TAG_RIGHT = "right";
    private static final String TAG_BOTTOM = "bottom";

    private static native SurfaceControl nativePrepareSurfaceControl(long srcSurface);

    private static native void nativeBindSurface(long mTransaction, long srcSurface,
            long dstSurface);

    private static native void nativeInitSurface(long mTransaction, SurfaceControl mSrcSurface,
            int width, int height);

    private static native void nativeChangeSurfaceControl(long mTransaction, long moveSurface,
            float[] distance, float[] scale, float[] skew);

    enum MODE {
        OFF, PARTIAL, FULL;
    }

    private MODE mFeatureMode = MODE.OFF;
    private ArrayList<String> mWhiteAppLists = new ArrayList<>();
    private ArrayList<String> mBlackAppLists = new ArrayList<>();
    private ThreeFlingersDisplayConfig mDisplayConfig;

    private int mNumFingers = 3; // default value is 3

    // default value is 0.1
    private float mMinSwipeDistanceRatio = 0.1f;
    // default value is 0.5
    private float mEffectiveDistanceRatio = 0.5f;

    private final RootWindowContainer mRootWindowContainer;
    private final DisplayContent mSrcDisplayContent;
    private DisplayContent mTargetDisplayContent;
    private final WindowManagerService mWmService;
    private final SurfaceControl.Transaction mTransaction;
    private IActivityTaskManager mAtm;
    private ActivityRecord mMovingActivityRecord;

    private SurfaceControl mSrcSurface = null;
    private SurfaceControl mAnimationSurface = null;

    private VelocityTracker mVelocityTracker;
    private float initialX, initialY, deltaX, deltaY;
    private boolean mIsXAxis = false;
    private boolean mIsYAxis = false;
    private float screenWidth, screenHeight;
    private float mBaseX, mBaseY;
    private ValueAnimator mValueAnimator;
    private boolean mMovingArOccludes = true;

    public FingersDisplayAnimationEventListener(WindowManagerService wms,
            DisplayContent srcDisplayContent, RootWindowContainer rwc) {
        this.mWmService = wms;
        this.mSrcDisplayContent = srcDisplayContent;
        this.mRootWindowContainer = rwc;
        this.mTransaction = wms.mTransactionFactory.get();
        mAtm = ActivityTaskManager.getService();
        screenWidth = mSrcDisplayContent.getDisplayInfo().logicalWidth;
        screenHeight = mSrcDisplayContent.getDisplayInfo().logicalHeight;
        if (DEBUG) {
            Slog.d(TAG, "screenWidth = " + screenWidth + ", screenHeight = " + screenHeight);
        }
        initConfig();
    }

    // 手势算法仅供参考，可自行完善及优化
    @Override
    public void onPointerEvent(MotionEvent motionEvent) {
        if (DEBUG) {
            Slog.d(TAG, "screenWidth = " + screenWidth + ", screenHeight = " + screenHeight);
            Slog.d(TAG, "motionEvent = " + motionEvent);
        }

        if (mValueAnimator != null) {
            if (DEBUG) {
                Slog.d(TAG, "Now is animating!");
            }
            return;
        }

        if (mFeatureMode == MODE.OFF) {
            if (DEBUG) {
                Slog.d(TAG, "This feature is disabled!");
            }
            return;
        }

        // 此处是流程最早的时候，mMovingActivityRecord在异常时不需要置空
        mMovingActivityRecord = mSrcDisplayContent.topRunningActivity();
        if (mMovingActivityRecord == null) {
            if (DEBUG) {
                Slog.d(TAG, "mMovingActivityRecord == null!");
            }
            return;
        }

        if (mSrcDisplayContent.getTopRootTask().isActivityTypeHome()) {
            if (DEBUG) {
                Slog.d(TAG, "This is home app! Don't Handled!");
            }
            return;
        }

        if (mFeatureMode == MODE.PARTIAL) {
            if (!mWhiteAppLists.contains(mMovingActivityRecord.packageName)) {
                if (DEBUG) {
                    Slog.d(TAG, "mWhiteAppLists do not have package("
                            + mMovingActivityRecord.packageName + "). Don't Handled!");
                }
                return;
            }
        }

        if (mBlackAppLists.contains(mMovingActivityRecord.packageName)) {
            if (DEBUG) {
                Slog.d(TAG, "mBlackAppLists has package(" + mMovingActivityRecord.packageName
                        + "). Don't Handled!");
            }
            return;
        }

        int pointerCount = motionEvent.getPointerCount();
        if (pointerCount != mNumFingers) {
            if (DEBUG) {
                Slog.d(TAG, "This is no three fingers swipe! Don't Handled!");
            }
            return;
        }

        switch (motionEvent.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (mVelocityTracker == null) {
                    mVelocityTracker = VelocityTracker.obtain();
                }
                mVelocityTracker.addMovement(motionEvent);
                initialX = motionEvent.getX();
                initialY = motionEvent.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                if (mVelocityTracker == null) {
                    if (DEBUG) {
                        Slog.d(TAG, "mVelocityTracker is null. Don't handled ACTION_MOVE event!");
                    }
                    return;
                }
                mVelocityTracker.addMovement(motionEvent);
                deltaX = motionEvent.getX() - initialX;
                deltaY = motionEvent.getY() - initialY;

                if (DEBUG) {
                    // 处理三指移动的事件
                    mVelocityTracker.computeCurrentVelocity(1000); // 计算1秒内的速度
                    // 获取X和Y方向的速度
                    float velocityX = mVelocityTracker.getXVelocity();
                    float velocityY = mVelocityTracker.getYVelocity();
                    Slog.d(TAG, "ACTION_MOVE velocityX = " + velocityX + ", velocityY = "
                            + velocityY + ", deltaX = " + deltaX + ", deltaY = " + deltaY);
                    Slog.d(TAG, "movionEvent = " + motionEvent);
                }

                // 移动距离大于MIN_SWIPE_DISTANCE才触发move事件
                if (Math.abs(deltaX) > (screenWidth * mMinSwipeDistanceRatio)
                        || Math.abs(deltaY) > (screenHeight * mMinSwipeDistanceRatio)) {
                    if (mIsXAxis || mIsYAxis) {
                        if (DEBUG) {
                            Slog.d(TAG, "mIsXAxis = " + mIsXAxis + ", mIsYAxis = " + mIsYAxis);
                        }
                    } else {
                        mIsXAxis = Math.abs(deltaX) > (screenWidth * mMinSwipeDistanceRatio);
                        mIsYAxis = Math.abs(deltaY) > (screenHeight * mMinSwipeDistanceRatio);
                    }
                    if (mIsXAxis) {
                        goMoving(deltaX, mIsXAxis);
                    } else if (mIsYAxis) {
                        goMoving(deltaY, !mIsYAxis);
                    } else {
                        if (DEBUG) {
                            Slog.w(TAG, "计算失败，下次再来！");
                        }
                    }
                }
                break;
            case MotionEvent.ACTION_HOVER_MOVE:
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_UP:
                if (DEBUG) {
                    Slog.d(TAG, "motionEvent = " + motionEvent);
                }
                if (mVelocityTracker == null) {
                    if (DEBUG) {
                        Slog.d(TAG, "mVelocityTracker is null. Don't handled UP event!");
                    }
                    return;
                }
                deltaX = motionEvent.getX() - initialX;
                deltaY = motionEvent.getY() - initialY;
                mVelocityTracker.addMovement(motionEvent);
                // 开启动画
                keepMoving(mIsXAxis ? deltaX : deltaY, mIsXAxis);
                break;

            default:
                if (DEBUG)
                    Slog.d(TAG, "Ignoring " + motionEvent);
                break;
        }
    }

    // 可自行实现寻找目标Display的逻辑
    private DisplayContent getTargetDisplayContent(String uniqueId) {
        DisplayContent targetDisplay = null;
        if (TextUtils.isEmpty(uniqueId)) {
            return targetDisplay;
        }
        for (int index = 0; index < mRootWindowContainer.getChildCount(); index++) {
            DisplayContent displayContent = mRootWindowContainer.getChildAt(index);
            if (displayContent.mCurrentUniqueDisplayId.equals(uniqueId)) {
                targetDisplay = displayContent;
                break;
            }
        }
        if (DEBUG)
            Slog.d(TAG, "targetDisplay = " + targetDisplay);
        return targetDisplay;
    }

    private void releaseAnimationSurface() {
        if (mAnimationSurface != null) {
            nativeBindSurface(mTransaction.mNativeObject, mAnimationSurface.mNativeObject, 0);
            mAnimationSurface.release();
            mAnimationSurface = null;
        }
    }

    private void resetSrcSurface() {
        // SrcSurface返回原位置
        if (mSrcSurface != null) {
            nativeChangeSurfaceControl(mTransaction.mNativeObject, mSrcSurface.mNativeObject,
                    new float[] {mBaseX, mBaseY}, new float[] {1, 1}, new float[] {0, 0});
            mSrcSurface = null;
            mBaseX = 0;
            mBaseY = 0;
        }
    }

    private void releaseVelocityTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }

    private void releaseMovingActivityRecord() {
        if (mMovingActivityRecord != null) {
            synchronized (mWmService.mGlobalLock) {
                if (mMovingArOccludes == mMovingActivityRecord.fillsParent()) {
                } else {
                    mMovingActivityRecord.setOccludesParent(mMovingArOccludes);
                }
            }
            mMovingArOccludes = true;
            mMovingActivityRecord = null;
        }
    }

    private void releaseAnimation() {
        if (mValueAnimator != null) {
            mValueAnimator.cancel();
            mValueAnimator = null;
        }
    }

    private void releaseSurfaceAndEvent() {
        if (DEBUG) {
            Slog.d(TAG, "releaseSurfaceAndEvent!");
        }
        enableInteractionAfterMoving(mTargetDisplayContent);
        releaseAnimationSurface();
        resetSrcSurface();
        mIsXAxis = false;
        mIsYAxis = false;
        releaseMovingActivityRecord();
        releaseAnimation();
        mTargetDisplayContent = null;
        releaseVelocityTracker();
    }

    private void prepareAnimationSurface(DisplayContent srcDisplay, DisplayContent targetDisplay,
            float distance, boolean isXAxis) {
        if (targetDisplay == null || srcDisplay == targetDisplay) {
            if (DEBUG) {
                Slog.d(TAG, "targetDisplay is null or equals srcDisplay, return!");
            }
            releaseSurfaceAndEvent();
            return;
        }
        if (mTargetDisplayContent != null && targetDisplay != mTargetDisplayContent) {
            releaseAnimationSurface();
        }
        if (mAnimationSurface != null)
            return;
        mTargetDisplayContent = targetDisplay;
        // 获取顶层Activity
        if (mMovingActivityRecord == null)
            return;
        SurfaceControl sc = mMovingActivityRecord.getSurfaceControl();
        final Rect containerBounds = mMovingActivityRecord.getWindowConfiguration().getBounds();
        int width = containerBounds.width();
        int height = containerBounds.height();
        mBaseX = (screenWidth - width) / 2;
        mBaseY = (screenHeight - height) / 2;
        if (DEBUG) {
            Slog.d(TAG, "mAnimationDisplay size: " + width + " x " + height + ", mBaseX = " + mBaseX
                    + ", mBaseY = " + mBaseY);
            Slog.d(TAG, "mMovingActivityRecord = " + mMovingActivityRecord.toString());
        }
        SurfaceControl mAnimation = nativePrepareSurfaceControl(sc.mNativeObject);
        nativeBindSurface(mTransaction.mNativeObject, mAnimation.mNativeObject,
                mTargetDisplayContent.getSurfaceControl().mNativeObject);
        nativeInitSurface(mTransaction.mNativeObject, mAnimation, width, height);
        mAnimationSurface = mAnimation;
        mSrcSurface = sc;
        synchronized (mWmService.mGlobalLock) {
            mMovingArOccludes = mMovingActivityRecord.fillsParent();
            if (mMovingArOccludes) {
                mMovingActivityRecord.setOccludesParent(false);
            }
        }
        disableInteractionDuringMoving(mTargetDisplayContent);

    }

    public void goMoving(float distance, boolean isXAxis) {
        DisplayContent targetDisplayContent = getTargetDisplayContent(
                getTargetDisplayContentUniqueId(mDisplayConfig, isXAxis, distance));
        prepareAnimationSurface(mSrcDisplayContent, targetDisplayContent, distance, isXAxis);
        if (mSrcSurface == null || mAnimationSurface == null) {
            if (DEBUG) {
                Slog.w(TAG, "mSrcSurface or mAnimationSurface is null! Don't Handled!");
            }
            return;
        }
        float totalDistance =
                isXAxis ? mAnimationSurface.getWidth() : mAnimationSurface.getHeight();
        totalDistance = isXAxis ? screenWidth : screenHeight;
        float xDistance = mBaseX + (isXAxis ? distance : 0);
        float yDistance = mBaseY + (isXAxis ? 0 : distance);
        float targetDistance = 0f;
        if (DEBUG) {
            Slog.d(TAG, "goMoving distance = " + distance + ", isXAxis = " + isXAxis + ", width = "
                    + mAnimationSurface.getWidth() + ", height = " + mAnimationSurface.getHeight()
                    + ", xDistance = " + xDistance + ", yDistance = " + yDistance);
        }
        if (Math.abs(distance) < totalDistance) {
            if (mSrcSurface != null && mAnimationSurface != null) {
                float sx = (float) targetDisplayContent.getDisplayInfo().logicalWidth / screenWidth;
                float sy =
                        (float) targetDisplayContent.getDisplayInfo().logicalHeight / screenHeight;
                nativeChangeSurfaceControl(mTransaction.mNativeObject, mSrcSurface.mNativeObject,
                        new float[] {xDistance, yDistance}, new float[] {1, 1}, new float[] {0, 0});
                targetDistance = distance > 0 ? -(totalDistance - distance)
                        : totalDistance - Math.abs(distance);
                targetDistance -= distance;
                if (DEBUG) {
                    Slog.d(TAG, "animation targetDistance = " + targetDistance);
                    Slog.i(TAG, "sx = " + sx + ", sy = " + sy);
                    Slog.d(TAG, "animation width = " + mAnimationSurface.getWidth() + ", height = "
                            + mAnimationSurface.getHeight());
                }
                nativeChangeSurfaceControl(mTransaction.mNativeObject,
                        mAnimationSurface.mNativeObject,
                        new float[] {isXAxis ? targetDistance : 0, isXAxis ? 0 : targetDistance},
                        new float[] {sx, sy}, new float[] {0, 0});
            }
        } else if (Math.abs(distance) >= totalDistance) {
            if (DEBUG) {
                Slog.d(TAG, "distance(" + distance + ") equals totalDistance(" + totalDistance
                        + ")! moveRootTaskToDisplay!");
            }
            moveRootTaskToDisplay(mTargetDisplayContent);
        }
    }

    private void keepMoving(float distance, boolean isXAxis) {
        if (mAnimationSurface == null) {
            if (DEBUG)
                Slog.d(TAG, "mAnimationSurface is null!");
            return;
        }
        // 判断是X轴方向还是Y轴方向，计算总距离
        float totalDistance =
                isXAxis ? screenWidth : screenHeight;
        // 处理三指移动的事件
        mVelocityTracker.computeCurrentVelocity(1000);
        // 计算时间
        float speed = Math
                .abs(isXAxis ? mVelocityTracker.getXVelocity() : mVelocityTracker.getYVelocity());
        // 判断是否返回原场景
        boolean isRevert = Math.abs(distance) < (totalDistance * mEffectiveDistanceRatio);
        // 根据mVelocityTracker计算得到的移动速度计算出应该动画运行时间
        int mAnimTimeout = (int) (isRevert ? (Math.abs(distance) / speed * 1000)
                : ((totalDistance - Math.abs(distance)) / speed * 1000));
        // 动画时间最长为1000
        mAnimTimeout = mAnimTimeout > 1000 ? 1000 : mAnimTimeout;
        // 计算跳转需要移动的距离
        final float targetDistance = distance > 0 ? totalDistance : -totalDistance;
        if (Math.abs(distance) < totalDistance) {
            if (DEBUG) {
                Slog.d(TAG, "keepMoving speed = " + speed + ", mAnimTimeout = " + mAnimTimeout
                        + ", isRevert = " + isRevert);
            }
            // 如果回原位置，则计算到0，如果跳转，则计算到targetDistance
            mValueAnimator = ValueAnimator.ofFloat(distance, isRevert ? 0 : targetDistance);
            mValueAnimator.setDuration(mAnimTimeout);
            mValueAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    float value = (float) animation.getAnimatedValue();
                    if (DEBUG) {
                        Slog.d(TAG, "onAnimationUpdate value = " + value + ", targetDistance = "
                                + targetDistance);
                    }
                    if (value == targetDistance && mTargetDisplayContent != null) {
                        moveRootTaskToDisplay(mTargetDisplayContent);
                    } else if (value == 0) {
                        releaseSurfaceAndEvent();
                    } else {
                        goMoving(value, isXAxis);
                    }
                }
            });
            mValueAnimator.start();
        }
    }

    private void moveRootTaskToDisplay(DisplayContent targeDisplayContent) {
        try {
            if (DEBUG) {
                Slog.d(TAG, "getTopRootTask = " + mMovingActivityRecord);
            }

            if (targeDisplayContent != null) {
                mAtm.moveRootTaskToDisplay(mMovingActivityRecord.getRootTaskId(),
                        targeDisplayContent.getDisplayId());
                final Rect containerBounds =
                        mMovingActivityRecord.getWindowConfiguration().getBounds();
                int width = containerBounds.width();
                int height = containerBounds.height();
                mBaseX = (targeDisplayContent.getDisplayInfo().logicalWidth - width) / 2;
                mBaseY = (targeDisplayContent.getDisplayInfo().logicalHeight - height) / 2;
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            releaseSurfaceAndEvent();
        }
    }

    private void disableInteractionDuringMoving(DisplayContent targetDisplayContent) {
        if (mMovingActivityRecord != null && mSrcDisplayContent != null
                && mMovingActivityRecord.getTask() != null) {
            final int mMovingArTaskId = mMovingActivityRecord.getTask().mTaskId;
            mSrcDisplayContent.forAllRootTasks(rootTask -> {
                if (rootTask.mTaskId != mMovingArTaskId) {
                    ActivityRecord disableAr = rootTask.topRunningActivity();
                    if (disableAr != null && disableAr.isState(ActivityRecord.State.STARTED,
                            ActivityRecord.State.RESUMED, ActivityRecord.State.PAUSED)) {
                        if (DEBUG) {
                            Slog.d(TAG,
                                    "disableInteractionDuringMoving rootTask = " + rootTask
                                            + ", disableAr = " + disableAr + ", state = "
                                            + disableAr.getState());
                        }
                        disableAr.pauseKeyDispatchingLocked();
                    }
                }
            });
        }
        if (targetDisplayContent != null) {
            targetDisplayContent.forAllRootTasks(rootTask -> {
                ActivityRecord disableAr = rootTask.topRunningActivity();
                if (disableAr != null && disableAr.isState(ActivityRecord.State.RESUMED)) {
                    if (DEBUG) {
                        Slog.d(TAG,
                                "disableInteractionDuringMoving rootTask = " + rootTask
                                        + ", disableAr = " + disableAr + ", state = "
                                        + disableAr.getState());
                    }
                    disableAr.pauseKeyDispatchingLocked();
                }
            });
        }
    }

    private void enableInteractionAfterMoving(DisplayContent targetDisplayContent) {
        if (mMovingActivityRecord != null && mSrcDisplayContent != null
                && mMovingActivityRecord.getTask() != null) {
            final int mMovingArTaskId = mMovingActivityRecord.getTask().mTaskId;
            mSrcDisplayContent.forAllRootTasks(rootTask -> {
                if (rootTask.mTaskId != mMovingArTaskId) {
                    ActivityRecord disableAr = rootTask.topRunningActivity();
                    if (disableAr != null && disableAr.isState(ActivityRecord.State.STARTED,
                            ActivityRecord.State.RESUMED, ActivityRecord.State.PAUSED)) {
                        if (DEBUG) {
                            Slog.d(TAG,
                                    "enableInteractionAfterMoving rootTask = " + rootTask
                                            + ", disableAr = " + disableAr + ", state = "
                                            + disableAr.getState());
                        }
                        disableAr.resumeKeyDispatchingLocked();
                    }
                }
            });
        }
        if (targetDisplayContent != null) {
            targetDisplayContent.forAllRootTasks(rootTask -> {
                ActivityRecord disableAr = rootTask.topRunningActivity();
                if (disableAr != null && disableAr.isState(ActivityRecord.State.RESUMED)) {
                    if (DEBUG) {
                        Slog.d(TAG,
                                "enableInteractionAfterMoving rootTask = " + rootTask
                                        + ", disableAr = " + disableAr + ", state = "
                                        + disableAr.getState());
                    }
                    disableAr.resumeKeyDispatchingLocked();
                }
            });
        }
    }

    private void initConfig() {
        try {
            String ns = null;
            File configFile = new File(CONFIG_PATH);
            if (!configFile.exists()) {
                if (DEBUG) {
                    Slog.w(TAG,
                            CONFIG_PATH + " not exist! Don't do anything! Feature is Disabled!");
                }
                return;
            }
            InputStream in = new FileInputStream(configFile);
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, "UTF-8");
            parser.nextTag();
            parser.require(XmlPullParser.START_TAG, null, TAG_CONFIG);
            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                switch (eventType) {
                    case XmlPullParser.START_DOCUMENT:
                        break;
                    case XmlPullParser.START_TAG:
                        String name = parser.getName();
                        switch (name) {
                            case TAG_LOG_ENABLE:
                                DEBUG = Boolean.valueOf(parser.nextText());
                                if (DEBUG) {
                                    Slog.d(TAG, "mLogEnable = " + DEBUG);
                                }
                                break;
                            case TAG_ENABLE:
                                mFeatureMode = MODE.valueOf(parser.nextText());
                                if (DEBUG) {
                                    Slog.d(TAG, "mEnable = " + mFeatureMode);
                                }
                                break;
                            case TAG_FINGER_NUM:
                                mNumFingers = Integer.valueOf(parser.nextText());
                                if (DEBUG) {
                                    Slog.d(TAG, "mNumFingers = " + mNumFingers);
                                }
                                break;
                            case TAG_MIN_SWIPE_DISTANCE_RATIO:
                                mMinSwipeDistanceRatio = Float.valueOf(parser.nextText());
                                if (DEBUG) {
                                    Slog.d(TAG, "mMinSwipeDistance = " + mMinSwipeDistanceRatio);
                                }
                                break;
                            case TAG_EFFECTIVE_DISTANCE_RATIO:
                                mEffectiveDistanceRatio = Float.valueOf(parser.nextText());
                                if (DEBUG) {
                                    Slog.d(TAG,
                                            "mEffectiveDistanceRatio = " + mEffectiveDistanceRatio);
                                }
                                break;
                            case TAG_WHITE_APP_LISTS:
                                initWhiteAppLists(parser);
                                break;
                            case TAG_BLACK_APP_LISTS:
                                initBlackAppLists(parser);
                                break;
                            case TAG_LOCATION:
                                initDisplayConfig(parser);
                                break;
                            default:
                                break;
                        }
                        break;
                    case XmlPullParser.END_TAG:
                        break;
                }
                eventType = parser.next();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void initWhiteAppLists(XmlPullParser parser) {
        try {
            mWhiteAppLists.clear();
            parser.require(XmlPullParser.START_TAG, null, TAG_WHITE_APP_LISTS);
            while (parser.next() != XmlPullParser.END_TAG) {
                if (parser.getEventType() != XmlPullParser.START_TAG) {
                    continue;
                }
                String name = parser.getName();
                if (name.equals(TAG_PACKAGE)) {
                    mWhiteAppLists.add(parser.nextText());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (DEBUG) {
            Slog.d(TAG, "mWhiteAppLists = " + mWhiteAppLists);
        }
    }

    private void initBlackAppLists(XmlPullParser parser) {
        try {
            mBlackAppLists.clear();
            parser.require(XmlPullParser.START_TAG, null, TAG_BLACK_APP_LISTS);
            while (parser.next() != XmlPullParser.END_TAG) {
                if (parser.getEventType() != XmlPullParser.START_TAG) {
                    continue;
                }
                String name = parser.getName();
                if (name.equals(TAG_PACKAGE)) {
                    mBlackAppLists.add(parser.nextText());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (DEBUG) {
            Slog.d(TAG, "mBlackAppLists = " + mBlackAppLists);
        }
    }

    private void initDisplayConfig(XmlPullParser parser) {
        try {
            parser.require(XmlPullParser.START_TAG, null, TAG_LOCATION);
            while (parser.next() != XmlPullParser.END_TAG) {
                if (parser.getEventType() != XmlPullParser.START_TAG) {
                    continue;
                }
                String name = parser.getName();
                if (name.equals(TAG_DISPLAY) && mDisplayConfig == null) {
                    mDisplayConfig = readDisplayConfig(parser);
                }
            }
            if (DEBUG) {
                Slog.d(TAG, "mDisplayConfig = " + mDisplayConfig);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private ThreeFlingersDisplayConfig readDisplayConfig(XmlPullParser parser) {
        ThreeFlingersDisplayConfig displayConfig = null;
        try {
            parser.require(XmlPullParser.START_TAG, null, TAG_DISPLAY);
            String uniqueId, top, right, left, bottom;
            uniqueId = top = right = left = bottom = "";
            while (parser.next() != XmlPullParser.END_TAG) {
                if (parser.getEventType() != XmlPullParser.START_TAG) {
                    continue;
                }
                String name = parser.getName();
                if (name.equals(TAG_UNIQUE_ID)) {
                    uniqueId = parser.nextText();
                } else if (name.equals(TAG_TOP)) {
                    top = parser.nextText();
                } else if (name.equals(TAG_BOTTOM)) {
                    bottom = parser.nextText();
                } else if (name.equals(TAG_LEFT)) {
                    left = parser.nextText();
                } else if (name.equals(TAG_RIGHT)) {
                    right = parser.nextText();
                }
            }
            if (uniqueId.equals(mSrcDisplayContent.mCurrentUniqueDisplayId)) {
                displayConfig = new ThreeFlingersDisplayConfig(uniqueId, top, bottom, left, right);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return displayConfig;
    }

    public String getTargetDisplayContentUniqueId(ThreeFlingersDisplayConfig config,
            boolean isXAxis, float distance) {
        String uniqueId = "";
        if (config != null) {
            if (isXAxis) {
                uniqueId = distance > 0 ? config.rightUniqueId : config.leftUniqueId;
            } else {
                uniqueId = distance > 0 ? config.bottomUniqueId : config.topUniqueId;
            }
        }
        return uniqueId;
    }

    class ThreeFlingersDisplayConfig {
        private String uniqueId;
        private String topUniqueId;
        private String bottomUniqueId;
        private String leftUniqueId;
        private String rightUniqueId;

        public ThreeFlingersDisplayConfig(String uniqueId, String topUniqueId,
                String bottomUniqueId, String leftUniqueId, String rightUniqueId) {
            this.uniqueId = uniqueId;
            this.topUniqueId = topUniqueId;
            this.bottomUniqueId = bottomUniqueId;
            this.leftUniqueId = leftUniqueId;
            this.rightUniqueId = rightUniqueId;
        }

        public String getUniqueId() {
            return uniqueId;
        }

        public String getTopUniqueId() {
            return topUniqueId;
        }

        public String getBottomUniqueId() {
            return bottomUniqueId;
        }

        public String getLeftUniqueId() {
            return leftUniqueId;
        }

        public String getRightUniqueId() {
            return rightUniqueId;
        }

        @Override
        public String toString() {
            return "ThreeFlingersDisplayConfig{" + "uniqueId='" + uniqueId + '\''
                    + ", topUniqueId='" + topUniqueId + '\'' + ", bottomUniqueId='" + bottomUniqueId
                    + '\'' + ", leftUniqueId='" + leftUniqueId + '\'' + ", rightUniqueId='"
                    + rightUniqueId + '\'' + '}';
        }
    }

}
