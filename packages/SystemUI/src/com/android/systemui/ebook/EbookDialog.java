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

package com.android.systemui.ebook;

import android.content.ContentValues;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.os.EbookManager;

import com.android.systemui.R;
import com.android.systemui.navigationbar.NavigationBar;

public class EbookDialog extends EbookBaseDialog implements View.OnClickListener, SeekBar.OnSeekBarChangeListener, CompoundButton.OnCheckedChangeListener {
    private static final String TAG = "EbookDialog";
    private Context mContext;
    private Button mRefreshButton;
    private CheckBox mRefreshCheckbox;
    private Button mColorCfgBtn;
    private EbookRefreshDialog mEbookRefreshDialog;
    private EbookColorCfgDialog mEbookColorCfgDialog;
    private EbookSettingsManager mEbookSettingsManager;

    public EbookDialog(Context context) {
        super(context, null);
        mContext = context;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.ebook_menu_dialog);
        NavigationBar.mIsShowEbookDialog = true;
        if (mEbookSettingsManager == null) {
            mEbookSettingsManager = new EbookSettingsManager(mContext);
        }
        mRefreshCheckbox = (CheckBox) findViewById(R.id.ebook_dialog_refresh_checkbox);
        mRefreshCheckbox.setChecked(EbookSettingsProvider.mIsRefreshSetting);
        mRefreshCheckbox.setOnCheckedChangeListener(this);
        mRefreshButton = (Button) findViewById(R.id.ebook_dialog_refresh_button);
        mRefreshButton.setOnClickListener(this);
        mRefreshButton.setEnabled(mRefreshCheckbox.isChecked());
        //color cfg
        mColorCfgBtn = (Button) findViewById(R.id.ebook_dialog_color_button);
        mColorCfgBtn.setOnClickListener(this);
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if(id == R.id.ebook_dialog_refresh_button) {
            if (EbookSettingsProvider.mIsRefreshSetting) {
                if (null != mEbookRefreshDialog && mEbookRefreshDialog.isShowing()) {
                    return;
                }
                mEbookRefreshDialog = new EbookRefreshDialog(mContext, this);
                mEbookRefreshDialog.show();
            }
        } else if (id == R.id.ebook_dialog_color_button) {
            if (null == mEbookColorCfgDialog) {
                mEbookColorCfgDialog = new EbookColorCfgDialog(mContext, this);
            }
            if (!mEbookColorCfgDialog.isShowing()) {
                mEbookColorCfgDialog.show();
            }
        }
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {

    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {

    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {

    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        int id = buttonView.getId();
        if(id == R.id.ebook_dialog_refresh_checkbox) {
            EbookSettingsProvider.mIsRefreshSetting = isChecked;
            ContentValues values = new ContentValues();
            values.put(EbookSettingsDataBaseHelper.IS_REFRESH_SETTING,
                    EbookSettingsProvider.mIsRefreshSetting ? 1 : 0);
            mContext.getContentResolver().update(EbookSettingsProvider.URI_EBOOK_SETTINGS,
                    values, EbookSettingsDataBaseHelper.PACKAGE_NAME + " = ?",
                    new String[]{EbookSettingsProvider.packageName});
            if (EbookSettingsProvider.mIsRefreshSetting) {
                mEbookSettingsManager.setRefreshMode(EbookSettingsProvider.mRefreshMode);
                mEbookSettingsManager.setFullModeCnt(EbookSettingsProvider.mRefreshFrequency);
            } else {
                mEbookSettingsManager.setRefreshMode(EbookSettingsManager.DEFAULT_REFRESH_MODE);
                mEbookSettingsManager.setFullModeCnt(EbookSettingsManager.DEFAULT_REFRESH_FREQUENCY);
            }
            mRefreshButton.setEnabled(isChecked);
        }
    }

    public void dismissAllDialog() {
        if (null != mEbookRefreshDialog && mEbookRefreshDialog.isShowing()) {
            mEbookRefreshDialog.cancel();
            mEbookRefreshDialog = null;
        }
        if (null != mEbookColorCfgDialog && mEbookColorCfgDialog.isShowing()) {
            mEbookColorCfgDialog.cancel();
            mEbookColorCfgDialog = null;
        }
        cancel();
    }
}