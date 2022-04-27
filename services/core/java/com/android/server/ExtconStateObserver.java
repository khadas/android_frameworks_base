/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.server;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.FileUtils;
import android.util.Slog;
import android.util.ArrayMap;
import android.os.SystemProperties;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * A specialized ExtconUEventObserver that on receiving a {@link UEvent} calls {@link
 * #updateState(ExtconInfo, String, S)} with the value of{@link #parseState(ExtconInfo, String)}.
 *
 * @param <S> the type of state to parse and update
 * @hide
 */
public abstract class ExtconStateObserver<S> extends ExtconUEventObserver {
    private static final String TAG = "ExtconStateObserver";
    private static final boolean LOG = false;
    private final Map<String, String> mExtconHDMIStates = new ArrayMap<String, String>();
    private final Map<String, String> mExtconDPStates = new ArrayMap<String, String>();

    /**
     * Parses the current state from the state file for {@code extconInfo}.
     *
     * @param extconInfo the extconInfo to parse state for
     * @see #parseState(ExtconInfo, String)
     * @see ExtconInfo#getStatePath()
     */
    @Nullable
    public S parseStateFromFile(ExtconInfo extconInfo) throws IOException {
        String statePath = extconInfo.getStatePath();
        if (isTablet()) {
            String namePath = extconInfo.getNamePath();
            String state = FileUtils.readTextFile(new File(statePath), 0, null).trim();
            String name = FileUtils.readTextFile(new File(namePath), 0, null).trim();
            String status = "";
            if (state.contains("HDMI=") && name.contains(".hdmi")) {
                status = mExtconHDMIStates.get(name);
                if (status == null) {
                    status = state.replaceAll("HDMI", ("HDMI_"+mExtconHDMIStates.size()).toString());
                    mExtconHDMIStates.put(name, ("HDMI_"+mExtconHDMIStates.size()).toString());
                }
                return parseState(extconInfo, status);
            } else if (state.contains("DP=") && name.contains(".dp")) {
                status = mExtconDPStates.get(name);
                if (status == null) {
                    status = state.replaceAll("DP", ("DP_"+mExtconDPStates.size()).toString());
                    mExtconDPStates.put(name, ("DP_"+mExtconDPStates.size()).toString());
                }
                return parseState(extconInfo, status);
            } else {
                return parseState(
                        extconInfo,
                        FileUtils.readTextFile(new File(statePath), 0, null).trim());
            }
        } else {
            return parseState(
                extconInfo,
                FileUtils.readTextFile(new File(statePath), 0, null).trim());
        }
    }

    public boolean isTablet() {
        String product = SystemProperties.get("ro.target.product","");
        if(product.equals("tablet")){
            return true;
        }
        return false;
    }

    @Override
    public void onUEvent(ExtconInfo extconInfo, UEvent event) {
        if (LOG) Slog.d(TAG, extconInfo.getName() + " UEVENT: " + event);
        String name = event.get("NAME");
        S state = null;
        if (isTablet()) {
            String status = event.get("STATE");
            String newState = "";
            if (status.contains("HDMI=") && name.contains(".hdmi")) {
                newState = status.replaceAll("HDMI", mExtconHDMIStates.get(name));
                state = parseState(extconInfo, newState);
            } else if (status.contains("DP=") && name.contains(".dp")) {
                newState = status.replaceAll("DP", mExtconDPStates.get(name));
                state = parseState(extconInfo, newState);
            } else {
                state = parseState(extconInfo, event.get("STATE"));
            }
        } else {
            state = parseState(extconInfo, event.get("STATE"));
        }
        if (state != null) {
            updateState(extconInfo, name, state);
        }
    }


    /**
     * Subclasses of ExtconStateObserver should override this method update state for {@code
     * exconInfo} from an {@code UEvent}.
     *
     * @param extconInfo the external connection
     * @param eventName the {@code NAME} of the {@code UEvent}
     * @param state the{@code STATE} as parsed by {@link #parseState(ExtconInfo, String)}.
     */
    public abstract void updateState(ExtconInfo extconInfo, String eventName, @NonNull S state);

    /**
     * Subclasses of ExtconStateObserver should override this method to parse the {@code STATE} from
     * an UEvent.
     *
     * @param extconInfo that matches the {@code DEVPATH} of {@code event}
     * @param state the {@code STATE} from a {@code UEvent}.
     * @return the parsed state. Return null if the state can not be parsed.
     */
    @Nullable
    public abstract S parseState(ExtconInfo extconInfo, String state);
}
