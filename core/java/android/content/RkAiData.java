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

package android.content;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * @hide
 */
public class RkAiData implements Parcelable {
    private int mType;
    private Bundle mInfo;

    /** @hide */
    public RkAiData(int type, /*@NonNull*/ Bundle info) {
        mType = type;
        mInfo = info;
    }

    public int getType () {
        return mType;
    }

    @Nullable
    public Bundle getInfo() {
        return mInfo;
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder(128);

        b.append("RkAiData { ");
        b.append("type=" + mType);
        b.append(", info=" + mInfo);
        b.append(" }");

        return b.toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeBundle(mInfo);
    }

    private RkAiData(Parcel in) {
        mType = in.readInt();
        mInfo = in.readBundle();
    }

    @NonNull
    public static final Parcelable.Creator<RkAiData> CREATOR =
        new Parcelable.Creator<RkAiData>() {

            @Override
            public RkAiData createFromParcel(Parcel source) {
                return new RkAiData(source);
            }

            @Override
            public RkAiData[] newArray(int size) {
                return new RkAiData[size];
            }
        };
}
