/*
 * Copyright 2024 Rockchip Electronics S.LSI Co. LTD
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import android.app.Dialog;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.os.EbookManager;

import com.android.systemui.R;

public class EbookRefreshDialog extends EbookBaseDialog implements View.OnClickListener, SeekBar.OnSeekBarChangeListener{
    private static final String TAG = "EbookRefreshDialog";
    private Context mContext;
    private Button mCommonButton, mAutoButton, mA2Button, mA2DitherButton, mDuButton, mDu4Button;
    private SeekBar mRefreshFrequencySeekbar;
    private TextView mRefreshFrequencyText;
    private EbookSettingsManager mEbookSettingsManager;
    private static final int SET_REFRESH_FREQUENCY_TEXT = 0;
    private static final int SET_REFRESH_FREQUENCY_SEEKBAR = 1;
    private static final int SET_REFRESH_MODE_BUTTON = 2;

    public Handler EbookRefreshDialogHandler=new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case SET_REFRESH_FREQUENCY_TEXT:
                    mRefreshFrequencyText.setText(""+EbookSettingsProvider.refreshFrequency);
                    break;
                case SET_REFRESH_FREQUENCY_SEEKBAR:
                    mRefreshFrequencySeekbar.setProgress(EbookSettingsProvider.refreshFrequency);
                    break;
                case SET_REFRESH_MODE_BUTTON:
                    switch (String.valueOf(EbookSettingsProvider.refreshMode)) {
                        case EbookManager.EbookMode.EPD_PART_GC16:
                            mCommonButton.setBackgroundColor(Color.LTGRAY);
                            mAutoButton.setBackgroundColor(Color.WHITE);
                            mA2Button.setBackgroundColor(Color.WHITE);
                            mA2DitherButton.setBackgroundColor(Color.WHITE);
                            mDuButton.setBackgroundColor(Color.WHITE);
                            mDu4Button.setBackgroundColor(Color.WHITE);
                            break;
                        case EbookManager.EbookMode.EPD_AUTO:
                            mCommonButton.setBackgroundColor(Color.WHITE);
                            mAutoButton.setBackgroundColor(Color.LTGRAY);
                            mA2Button.setBackgroundColor(Color.WHITE);
                            mA2DitherButton.setBackgroundColor(Color.WHITE);
                            mDuButton.setBackgroundColor(Color.WHITE);
                            mDu4Button.setBackgroundColor(Color.WHITE);
                            break;
                        case EbookManager.EbookMode.EPD_A2:
                            mCommonButton.setBackgroundColor(Color.WHITE);
                            mAutoButton.setBackgroundColor(Color.WHITE);
                            mA2Button.setBackgroundColor(Color.LTGRAY);
                            mA2DitherButton.setBackgroundColor(Color.WHITE);
                            mDuButton.setBackgroundColor(Color.WHITE);
                            mDu4Button.setBackgroundColor(Color.WHITE);
                            break;
                        case EbookManager.EbookMode.EPD_A2_DITHER:
                            mCommonButton.setBackgroundColor(Color.WHITE);
                            mAutoButton.setBackgroundColor(Color.WHITE);
                            mA2Button.setBackgroundColor(Color.WHITE);
                            mA2DitherButton.setBackgroundColor(Color.LTGRAY);
                            mDuButton.setBackgroundColor(Color.WHITE);
                            mDu4Button.setBackgroundColor(Color.WHITE);
                            break;
                        case EbookManager.EbookMode.EPD_DU:
                            mCommonButton.setBackgroundColor(Color.WHITE);
                            mAutoButton.setBackgroundColor(Color.WHITE);
                            mA2Button.setBackgroundColor(Color.WHITE);
                            mA2DitherButton.setBackgroundColor(Color.WHITE);
                            mDuButton.setBackgroundColor(Color.LTGRAY);
                            mDu4Button.setBackgroundColor(Color.WHITE);
                            break;
                        case EbookManager.EbookMode.EPD_DU4:
                            mCommonButton.setBackgroundColor(Color.WHITE);
                            mAutoButton.setBackgroundColor(Color.WHITE);
                            mA2Button.setBackgroundColor(Color.WHITE);
                            mA2DitherButton.setBackgroundColor(Color.WHITE);
                            mDuButton.setBackgroundColor(Color.WHITE);
                            mDu4Button.setBackgroundColor(Color.LTGRAY);
                            break;
                    }
                    break;
            }
        }
    };

    public EbookRefreshDialog(Context context, Dialog parent) {
        super(context, parent);
        mContext = context;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.ebook_refresh_dialog);
        mCommonButton = (Button) findViewById(R.id.ebook_refresh_dialog_mode_common_button);
        mAutoButton = (Button) findViewById(R.id.ebook_refresh_dialog_mode_auto_button);
        mA2Button = (Button) findViewById(R.id.ebook_refresh_dialog_mode_a2_button);
        mA2DitherButton = (Button) findViewById(R.id.ebook_refresh_dialog_mode_a2_dither_button);
        mDuButton = (Button) findViewById(R.id.ebook_refresh_dialog_mode_du_button);
        mDu4Button = (Button) findViewById(R.id.ebook_refresh_dialog_mode_du_4_button);
        mRefreshFrequencySeekbar = (SeekBar) findViewById(R.id.ebook_refresh_dialog_frequency_seekbar);
        mRefreshFrequencyText = (TextView) findViewById(R.id.ebook_refresh_dialog_frequency_text);
        if(mEbookSettingsManager == null) {
            mEbookSettingsManager = new EbookSettingsManager(mContext);
        }
        Message setRefreshFrequencyTextMessage = new Message();
        setRefreshFrequencyTextMessage.what = SET_REFRESH_FREQUENCY_TEXT;
        EbookRefreshDialogHandler.sendMessage(setRefreshFrequencyTextMessage);
        Message RefreshFrequencySeekbarMessage = new Message();
        RefreshFrequencySeekbarMessage.what = SET_REFRESH_FREQUENCY_SEEKBAR;
        EbookRefreshDialogHandler.sendMessage(RefreshFrequencySeekbarMessage);
        Message setRefreshModeButtonMessage = new Message();
        setRefreshModeButtonMessage.what = SET_REFRESH_MODE_BUTTON;
        EbookRefreshDialogHandler.sendMessage(setRefreshModeButtonMessage);
        mRefreshFrequencySeekbar.setOnSeekBarChangeListener(this);
        mCommonButton.setOnClickListener(this);
        mAutoButton.setOnClickListener(this);
        mA2Button.setOnClickListener(this);
        mA2DitherButton.setOnClickListener(this);
        mDuButton.setOnClickListener(this);
        mDu4Button.setOnClickListener(this);
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        Log.d(TAG, "id: " + id);
        if(id == R.id.ebook_refresh_dialog_mode_common_button) {
            String curMode = mEbookSettingsManager.getEbookMode();
            if(!EbookManager.EbookMode.EPD_PART_GC16.equals(curMode)){
                EbookSettingsProvider.refreshMode = Integer.valueOf(EbookManager.EbookMode.EPD_PART_GC16);
                setRefreshUIandMode();
            } else {
                Log.d(TAG, "curMode: " + curMode);
            }
        } else if(id == R.id.ebook_refresh_dialog_mode_auto_button) {
            String curMode = mEbookSettingsManager.getEbookMode();
            if(!EbookManager.EbookMode.EPD_AUTO.equals(curMode)){
                EbookSettingsProvider.refreshMode = Integer.valueOf(EbookManager.EbookMode.EPD_AUTO);
                setRefreshUIandMode();
            } else {
                Log.d(TAG, "curMode: " + curMode);
            }
        } else if(id == R.id.ebook_refresh_dialog_mode_a2_button) {
            String curMode = mEbookSettingsManager.getEbookMode();
            if(!EbookManager.EbookMode.EPD_A2.equals(curMode)){
                EbookSettingsProvider.refreshMode = Integer.valueOf(EbookManager.EbookMode.EPD_A2);
                setRefreshUIandMode();
            } else {
                Log.d(TAG, "curMode: " + curMode);
            }
        } else if(id == R.id.ebook_refresh_dialog_mode_a2_dither_button) {
            String curMode = mEbookSettingsManager.getEbookMode();
            if(!EbookManager.EbookMode.EPD_A2_DITHER.equals(curMode)){
                EbookSettingsProvider.refreshMode = Integer.valueOf(EbookManager.EbookMode.EPD_A2_DITHER);
                setRefreshUIandMode();
            } else {
                Log.d(TAG, "curMode: " + curMode);
            }
        } else if(id == R.id.ebook_refresh_dialog_mode_du_button) {
            String curMode = mEbookSettingsManager.getEbookMode();
            if(!EbookManager.EbookMode.EPD_DU.equals(curMode)){
                EbookSettingsProvider.refreshMode = Integer.valueOf(EbookManager.EbookMode.EPD_DU);
                setRefreshUIandMode();
            } else {
                Log.d(TAG, "curMode: " + curMode);
            }
        } else if(id == R.id.ebook_refresh_dialog_mode_du_4_button) {
            String curMode = mEbookSettingsManager.getEbookMode();
            if(!EbookManager.EbookMode.EPD_DU4.equals(curMode)){
                EbookSettingsProvider.refreshMode = Integer.valueOf(EbookManager.EbookMode.EPD_DU4);
                setRefreshUIandMode();
            } else {
                Log.d(TAG, "curMode: " + curMode);
            }
        }
        Log.d(TAG, "packageName: " + EbookSettingsProvider.packageName);
        ContentValues values = new ContentValues();
        values.put("refresh_mode", EbookSettingsProvider.refreshMode);
        int updatedRows = mContext.getContentResolver().update(EbookSettingsProvider.URI_EBOOK_SETTINGS,
                values, "package_name = ?", new String[]{EbookSettingsProvider.packageName});
        Log.d(TAG, "updatedRows: " + updatedRows);
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        int id = seekBar.getId();
        if(id == R.id.ebook_refresh_dialog_frequency_seekbar) {
            EbookSettingsProvider.refreshFrequency = seekBar.getProgress();
            Log.d(TAG, "EbookSettingsProvider.refreshFrequency: " + EbookSettingsProvider.refreshFrequency);
            Message setRefreshFrequencyTextMessage = new Message();
            setRefreshFrequencyTextMessage.what = SET_REFRESH_FREQUENCY_TEXT;
            EbookRefreshDialogHandler.sendMessage(setRefreshFrequencyTextMessage);
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {

    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        int id = seekBar.getId();
        if(id == R.id.ebook_refresh_dialog_frequency_seekbar) {
            Log.d(TAG, "ebook_refresh_dialog_frequency_seekbar is onClick ");
            //设置全刷频率
            mEbookSettingsManager.setFullModeCnt(EbookSettingsProvider.refreshFrequency);
            //把全刷频率更新到数据库
            Log.d(TAG, "packageName: " + EbookSettingsProvider.packageName);
            ContentValues values = new ContentValues();
            values.put("refresh_frequency", EbookSettingsProvider.refreshFrequency);
            int updatedRows = mContext.getContentResolver().update(EbookSettingsProvider.URI_EBOOK_SETTINGS,
                    values, "package_name = ?", new String[]{EbookSettingsProvider.packageName});
            Log.d(TAG, "updatedRows: " + updatedRows);
        }
    }

    private void setRefreshUIandMode() {
        Message setRefreshModeButtonMessage = new Message();
        setRefreshModeButtonMessage.what = SET_REFRESH_MODE_BUTTON;
        EbookRefreshDialogHandler.sendMessage(setRefreshModeButtonMessage);
        mEbookSettingsManager.setEbookMode(String.valueOf(EbookSettingsProvider.refreshMode));
        mEbookSettingsManager.refreshAll();
    }
}
