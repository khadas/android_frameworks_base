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
import android.graphics.PixelFormat;
import android.view.KeyEvent;
import android.view.WindowManager;

public class EbookBaseDialog extends Dialog {
    private Dialog mParentDialog;

    public EbookBaseDialog(Context context, Dialog parentDialog) {
        super(context);
        getWindow().setType((WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG));
        setCanceledOnTouchOutside(true);

        mParentDialog = parentDialog;
    }

    @Override
    public void show() {
        super.show();
        WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
        //layoutParams.gravity = Gravity.CENTER;
        layoutParams.format = PixelFormat.TRANSLUCENT;
        layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
        layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
        getWindow().setAttributes(layoutParams);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        cancel();
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return true;
        }
        if (null != mParentDialog && mParentDialog.isShowing()) {
            mParentDialog.cancel();
            mParentDialog = null;
        }
        return super.onKeyDown(keyCode, event);
    }
}
