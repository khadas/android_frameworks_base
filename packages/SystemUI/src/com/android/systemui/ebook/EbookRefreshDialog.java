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

import android.app.Dialog;
import android.content.ContentValues;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import com.android.systemui.R;

import java.util.ArrayList;
import java.util.List;

public class EbookRefreshDialog extends EbookBaseDialog implements
        SeekBar.OnSeekBarChangeListener, RadioGroup.OnCheckedChangeListener {
    private final String TAG = getClass().getSimpleName();

    private TextView tvFrequency;

    private Context mContext;
    private EbookSettingsManager mEbookSettingsManager;
    private List<EbookRefreshMode> mModeList;

    public EbookRefreshDialog(Context context, Dialog parent) {
        super(context, parent);
        mContext = context;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.ebook_refresh_dialog);

        mEbookSettingsManager = new EbookSettingsManager(mContext);
        mModeList = new ArrayList<>();
        mModeList.add(new EbookRefreshMode(R.string.ebook_refresh_mode_normal, 7));
        mModeList.add(new EbookRefreshMode(R.string.ebook_refresh_mode_regal, 9));
        mModeList.add(new EbookRefreshMode(R.string.ebook_refresh_mode_fast, 14));
        mModeList.add(new EbookRefreshMode(R.string.ebook_refresh_mode_high_speed, 12));
        mModeList.add(new EbookRefreshMode(R.string.ebook_refresh_mode_top_speed, 13));
        initView();
    }

    private void initView() {
        RadioGroup layoutMode = findViewById(R.id.ebook_refresh_dialog_rg);
        int checkId = -1;
        for (int i = 0; i < mModeList.size(); i++) {
            EbookRefreshMode mode = mModeList.get(i);
            RadioButton itemView = new RadioButton(mContext);
            itemView.setId(ViewGroup.generateViewId());
            itemView.setBackgroundResource(R.drawable.ebook_refresh_mode_rg);
            itemView.setTag(mode.getValue());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i == 0) {
                lp.setMargins(0, 0, 0, 0);
            } else {
                lp.setMargins(20, 0, 0, 0);
            }
            itemView.setLayoutParams(lp);
            itemView.setPadding(10, 0, 10, 0);
            itemView.setButtonDrawable(null);
            itemView.setText(mode.getTitleResId());
            if (mode.getValue() == EbookSettingsProvider.mRefreshMode) {
                checkId = itemView.getId();
            }
            layoutMode.addView(itemView);
        }
        layoutMode.check(checkId);
        layoutMode.setOnCheckedChangeListener(this);

        int fullModeCnt = mEbookSettingsManager.getFullModeCnt();
        SeekBar sbFrequency = (SeekBar) findViewById(R.id.ebook_rd_frequency_seekbar);
        sbFrequency.setProgress(fullModeCnt);
        sbFrequency.setOnSeekBarChangeListener(this);
        tvFrequency = (TextView) findViewById(R.id.ebook_rd_frequency_value);
        tvFrequency.setText(String.valueOf(fullModeCnt));
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        int id = seekBar.getId();
        if (id == R.id.ebook_rd_frequency_seekbar) {
            tvFrequency.setText(String.valueOf(progress));
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {

    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        int id = seekBar.getId();
        if (id == R.id.ebook_rd_frequency_seekbar) {
            int refreshFrequency = seekBar.getProgress();
            EbookSettingsProvider.mRefreshFrequency = refreshFrequency;
            mEbookSettingsManager.setFullModeCnt(refreshFrequency);
            //update db
            String packageName = EbookSettingsProvider.packageName;
            ContentValues values = new ContentValues();
            values.put("refresh_frequency", refreshFrequency);
            int updatedRows = mContext.getContentResolver().update(EbookSettingsProvider.URI_EBOOK_SETTINGS,
                    values, "package_name = ?", new String[]{packageName});
            Log.i(TAG, packageName + " update db, frequency " + refreshFrequency + "==="
                    + EbookSettingsProvider.mRefreshFrequency + ", row=" + updatedRows);
        }
    }

    @Override
    public void onCheckedChanged(RadioGroup group, int checkedId) {
        View itemView = findViewById(checkedId);
        int refreshMode = (int) itemView.getTag();
        EbookSettingsProvider.mRefreshMode = refreshMode;
        mEbookSettingsManager.setRefreshMode(refreshMode);
        mEbookSettingsManager.refreshAll();
        //update db
        String packageName = EbookSettingsProvider.packageName;
        ContentValues values = new ContentValues();
        values.put("refresh_mode", refreshMode);
        int updatedRows = mContext.getContentResolver().update(EbookSettingsProvider.URI_EBOOK_SETTINGS,
                values, "package_name = ?", new String[]{packageName});
        Log.i(TAG, packageName + " update db, mode " + refreshMode + ", row=" + updatedRows);
    }
}