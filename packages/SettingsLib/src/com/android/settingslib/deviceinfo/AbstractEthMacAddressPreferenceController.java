/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.settingslib.deviceinfo;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.EthernetManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.text.TextUtils;
import android.util.Log;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

import androidx.annotation.VisibleForTesting;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.android.settingslib.R;
import com.android.settingslib.core.lifecycle.Lifecycle;

import java.net.NetworkInterface;

/**
 * Preference controller for Ethernet MAC address
 */
public abstract class AbstractEthMacAddressPreferenceController
        extends AbstractConnectivityPreferenceController {

    @VisibleForTesting
    static final String KEY_ETH_MAC_ADDRESS = "ethernet_mac_address";

    private Preference mEthMacAddress;
    private final EthernetManager mEthManager;

    private static final String[] CONNECTIVITY_INTENTS = {
            ConnectivityManager.CONNECTIVITY_ACTION,
            "android.net.ethernet.ETHERNET_STATE_CHANGED"
    };

    @Override
    protected String[] getConnectivityIntents() {
        return CONNECTIVITY_INTENTS;
    }
	
    public AbstractEthMacAddressPreferenceController(Context context, Lifecycle lifecycle) {
        super(context, lifecycle);
        mEthManager = context.getSystemService(EthernetManager.class);
    }

    @Override
    public boolean isAvailable() {
        return mEthManager != null;
    }

    @Override
    public String getPreferenceKey() {
        return KEY_ETH_MAC_ADDRESS;
    }

    @Override
    public void displayPreference(PreferenceScreen screen) {
        super.displayPreference(screen);
        if (isAvailable()) {
            mEthMacAddress = screen.findPreference(KEY_ETH_MAC_ADDRESS);
            updateConnectivity();
        }
    }

    @SuppressLint("HardwareIds")
    @Override
    protected void updateConnectivity() {
        if (mEthMacAddress == null) {
            return;
        }

        String macAddress = getEthMacAddress();
        if (TextUtils.isEmpty(macAddress)) {
            mEthMacAddress.setSummary(R.string.status_unavailable);
        } else {
            mEthMacAddress.setSummary(macAddress);
        }
    }

    private String getEthMacAddress() {
        String macAddress = null;
        try {
            BufferedReader br = new BufferedReader(new FileReader("/sys/class/net/eth0/address"));
            macAddress = br.readLine().trim();
            br.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return macAddress;
	}
}