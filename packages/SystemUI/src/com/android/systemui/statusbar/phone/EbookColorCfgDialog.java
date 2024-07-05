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
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import com.android.systemui.R;

public class EbookColorCfgDialog extends EbookBaseDialog implements
        View.OnClickListener, SeekBar.OnSeekBarChangeListener {
    private final String TAG = getClass().getSimpleName();
    private final int DEFAULT_COLOR_CFG_RKCFA_VALUE = 64;

    private Context mContext;
    private SeekBar sbColorDep;
    private SeekBar sbContrast;
    private SeekBar sbSaturation;
    private SeekBar sbBrightness;
    private TextView tvColorDep;
    private TextView tvContrast;
    private TextView tvSaturation;
    private TextView tvBrightness;

    private EbookSettingsManager mEbookSettingsManager;

    public EbookColorCfgDialog(Context context, Dialog parent) {
        super(context, parent);
        mContext = context;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.ebook_color_cfg_dialog);

        mEbookSettingsManager = new EbookSettingsManager(mContext);
        initView();
    }

    private void initView() {
        int colorDepValue = mEbookSettingsManager.getColorDep();
        sbColorDep = (SeekBar) findViewById(R.id.ebook_ccd_color_dep_seekbar);
        sbColorDep.setProgress(colorDepValue);
        sbColorDep.setOnSeekBarChangeListener(this);
        tvColorDep = (TextView) findViewById(R.id.ebook_ccd_color_dep_value);
        tvColorDep.setText(String.valueOf(colorDepValue));

        int contrastValue = mEbookSettingsManager.getContrast();
        sbContrast = (SeekBar) findViewById(R.id.ebook_ccd_contrast_seekbar);
        sbContrast.setProgress(contrastValue);
        sbContrast.setOnSeekBarChangeListener(this);
        tvContrast = (TextView) findViewById(R.id.ebook_ccd_contrast_value);
        tvContrast.setText(String.valueOf(contrastValue));

        int saturationValue = mEbookSettingsManager.getSaturation();
        sbSaturation = (SeekBar) findViewById(R.id.ebook_ccd_saturation_seekbar);
        sbSaturation.setProgress(saturationValue);
        sbSaturation.setOnSeekBarChangeListener(this);
        tvSaturation = (TextView) findViewById(R.id.ebook_ccd_saturation_value);
        tvSaturation.setText(String.valueOf(saturationValue));

        int brightnessValue = mEbookSettingsManager.getBrightness();
        sbBrightness = (SeekBar) findViewById(R.id.ebook_ccd_brightness_seekbar);
        sbBrightness.setProgress(brightnessValue);
        sbBrightness.setOnSeekBarChangeListener(this);
        tvBrightness = (TextView) findViewById(R.id.ebook_ccd_brightness_value);
        tvBrightness.setText(String.valueOf(brightnessValue));

        findViewById(R.id.ebook_ccd_reset_btn).setOnClickListener(this);
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.ebook_ccd_reset_btn) {
            Log.i(TAG, "click reset btn");
            sbColorDep.setProgress(DEFAULT_COLOR_CFG_RKCFA_VALUE);
            mEbookSettingsManager.setColorDep(DEFAULT_COLOR_CFG_RKCFA_VALUE);
            sbContrast.setProgress(DEFAULT_COLOR_CFG_RKCFA_VALUE);
            mEbookSettingsManager.setContrast(DEFAULT_COLOR_CFG_RKCFA_VALUE);
            sbSaturation.setProgress(DEFAULT_COLOR_CFG_RKCFA_VALUE);
            mEbookSettingsManager.setSaturation(DEFAULT_COLOR_CFG_RKCFA_VALUE);
            sbBrightness.setProgress(DEFAULT_COLOR_CFG_RKCFA_VALUE);
            mEbookSettingsManager.setBrightness(DEFAULT_COLOR_CFG_RKCFA_VALUE);
        }
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        int id = seekBar.getId();
        if (id == R.id.ebook_ccd_color_dep_seekbar) {
            tvColorDep.setText(String.valueOf(progress));
        } else if (id == R.id.ebook_ccd_contrast_seekbar) {
            tvContrast.setText(String.valueOf(progress));
        } else if (id == R.id.ebook_ccd_saturation_seekbar) {
            tvSaturation.setText(String.valueOf(progress));
        } else if (id == R.id.ebook_ccd_brightness_seekbar) {
            tvBrightness.setText(String.valueOf(progress));
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {

    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        int id = seekBar.getId();
        if (id == R.id.ebook_ccd_color_dep_seekbar) {
            mEbookSettingsManager.setColorDep(seekBar.getProgress());
        } else if (id == R.id.ebook_ccd_contrast_seekbar) {
            mEbookSettingsManager.setContrast(seekBar.getProgress());
        } else if (id == R.id.ebook_ccd_saturation_seekbar) {
            mEbookSettingsManager.setSaturation(seekBar.getProgress());
        } else if (id == R.id.ebook_ccd_brightness_seekbar) {
            mEbookSettingsManager.setBrightness(seekBar.getProgress());
        }
    }

}