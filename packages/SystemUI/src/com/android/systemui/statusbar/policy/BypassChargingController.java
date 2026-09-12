/*
 * Copyright (C) 2026 The Infinity-X Project
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

package com.android.systemui.statusbar.policy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import com.android.systemui.dagger.SysUISingleton;

import lineageos.health.HealthInterface;

import javax.inject.Inject;

/** Coordinates manual and in-game charging bypass requests on DerpFest. */
@SysUISingleton
public class BypassChargingController {
    private static final String TAG = "BypassCharging";

    public static final String SETTING_ACTIVE = "bypass_charge_active";
    public static final String SETTING_MANUAL_REQUEST = "bypass_charge_manual_requested";
    public static final String SETTING_GAME_ENABLED = "bypass_charge_enabled";

    private static final String SETTING_SAVED_ENABLED = "bypass_charge_saved_enabled";
    private static final String SETTING_SAVED_MODE = "bypass_charge_saved_mode";
    private static final String SETTING_SAVED_LIMIT = "bypass_charge_saved_limit";

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private boolean mStarted;
    private boolean mGameActive;
    private boolean mGameRequested;
    private boolean mManualRequested;
    private boolean mBypassActive;
    private boolean mIsPluggedIn;

    private final ContentObserver mSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            mManualRequested = Settings.Global.getInt(mContext.getContentResolver(),
                    SETTING_MANUAL_REQUEST, 0) == 1;
            mGameRequested = mGameActive && isGameBypassEnabled();
            updateBypassState();
        }
    };

    private final BroadcastReceiver mPowerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                mIsPluggedIn = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            } else if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
                mIsPluggedIn = true;
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                mIsPluggedIn = false;
                mManualRequested = false;
                Settings.Global.putInt(mContext.getContentResolver(),
                        SETTING_MANUAL_REQUEST, 0);
            }
            updateBypassState();
        }
    };

    @Inject
    public BypassChargingController(Context context) {
        mContext = context;
        if (isSupported()) {
            start();
        }
    }

    public void start() {
        if (mStarted || !isSupported()) {
            return;
        }
        mStarted = true;

        mBypassActive = Settings.Global.getInt(mContext.getContentResolver(),
                SETTING_ACTIVE, 0) == 1;
        mManualRequested = Settings.Global.getInt(mContext.getContentResolver(),
                SETTING_MANUAL_REQUEST, 0) == 1;

        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(SETTING_MANUAL_REQUEST), false, mSettingsObserver,
                UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(SETTING_GAME_ENABLED), false, mSettingsObserver,
                UserHandle.USER_ALL);

        IntentFilter powerFilter = new IntentFilter();
        powerFilter.addAction(Intent.ACTION_POWER_CONNECTED);
        powerFilter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        powerFilter.addAction(Intent.ACTION_BATTERY_CHANGED);
        Intent initial = mContext.registerReceiver(mPowerReceiver, powerFilter, Context.RECEIVER_NOT_EXPORTED);
        if (initial != null) {
            mIsPluggedIn = initial.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        } else {
            BatteryManager bm = mContext.getSystemService(BatteryManager.class);
            if (bm != null) {
                mIsPluggedIn = bm.isCharging();
            }
        }

        updateBypassState();
    }

    public boolean isSupported() {
        return SystemProperties.getBoolean("persist.sys.battery_bypass_supported", false);
    }

    public void setGameActive(boolean active) {
        if (!isSupported()) {
            return;
        }
        start();
        mGameActive = active;
        mGameRequested = active && isGameBypassEnabled();
        updateBypassState();
    }

    public void setManualBypassRequested(boolean requested) {
        if (!isSupported()) {
            return;
        }
        start();
        mManualRequested = requested;
        Settings.Global.putInt(mContext.getContentResolver(),
                SETTING_MANUAL_REQUEST, requested ? 1 : 0);
        updateBypassState();
    }

    public boolean isBypassChargingActive() {
        return mBypassActive;
    }

    private boolean isGameBypassEnabled() {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                SETTING_GAME_ENABLED, 0, UserHandle.USER_CURRENT) == 1;
    }

    private synchronized void updateBypassState() {
        if (!mStarted) {
            return;
        }

        boolean shouldEnable = isPowerConnected() && (mManualRequested || mGameRequested);
        if (shouldEnable == mBypassActive) {
            return;
        }

        if (shouldEnable) {
            enableBypass();
        } else {
            restoreChargingControl();
        }
    }

    private boolean isPowerConnected() {
        if (mIsPluggedIn) {
            return true;
        }
        BatteryManager bm = mContext.getSystemService(BatteryManager.class);
        return bm != null && bm.isCharging();
    }

    private void enableBypass() {
        if (mBypassActive) {
            return;
        }

        BatteryManager batteryManager = mContext.getSystemService(BatteryManager.class);
        int level = batteryManager != null
                ? batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
        if (level < 0) {
            return;
        }

        HealthInterface health = null;
        boolean savedEnabled = false;
        int savedMode = HealthInterface.MODE_LIMIT;
        int savedLimit = 80;
        boolean savedStateRead = false;
        try {
            health = HealthInterface.getInstance(mContext);
            boolean wasBypassActive = Settings.Global.getInt(mContext.getContentResolver(),
                    SETTING_ACTIVE, 0) == 1;
            if (!wasBypassActive) {
                savedEnabled = health.getEnabled();
                savedMode = health.getMode();
                int currentLimit = health.getLimit();
                if (currentLimit >= 70 && currentLimit <= 100) {
                    savedLimit = currentLimit;
                } else {
                    savedLimit = Settings.Global.getInt(mContext.getContentResolver(),
                            SETTING_SAVED_LIMIT, 80);
                    if (savedLimit < 70 || savedLimit > 100) {
                        savedLimit = 80;
                    }
                }
                savedStateRead = true;

                Settings.Global.putInt(mContext.getContentResolver(),
                        SETTING_SAVED_ENABLED, savedEnabled ? 1 : 0);
                Settings.Global.putInt(mContext.getContentResolver(),
                        SETTING_SAVED_MODE, savedMode);
                Settings.Global.putInt(mContext.getContentResolver(),
                        SETTING_SAVED_LIMIT, savedLimit);
            }

            boolean success = health.setEnabled(false);
            success &= health.setMode(HealthInterface.MODE_LIMIT);
            success &= health.setLimit(level);
            success &= health.setEnabled(true);
            if (!success) {
                Settings.Global.putInt(mContext.getContentResolver(), SETTING_ACTIVE, 0);
                if (savedStateRead) {
                    restoreChargingControl(health, savedEnabled, savedMode, savedLimit);
                }
                Log.w(TAG, "Failed to enable charging bypass");
                return;
            }

            Settings.Global.putInt(mContext.getContentResolver(), SETTING_ACTIVE, 1);
            mBypassActive = true;
            Log.i(TAG, "Charging bypass enabled at " + level + "%");
        } catch (Exception e) {
            Settings.Global.putInt(mContext.getContentResolver(), SETTING_ACTIVE, 0);
            if (health != null && savedStateRead) {
                try {
                    restoreChargingControl(health, savedEnabled, savedMode, savedLimit);
                } catch (Exception restoreError) {
                    Log.w(TAG, "Failed to roll back charging control", restoreError);
                }
            }
            Log.w(TAG, "Failed to enable charging bypass", e);
        }
    }

    private void restoreChargingControl() {
        if (!mBypassActive) {
            return;
        }

        try {
            HealthInterface health = HealthInterface.getInstance(mContext);
            boolean enabled = Settings.Global.getInt(mContext.getContentResolver(),
                    SETTING_SAVED_ENABLED, 0) == 1;
            int mode = Settings.Global.getInt(mContext.getContentResolver(),
                    SETTING_SAVED_MODE, HealthInterface.MODE_LIMIT);
            int limit = Settings.Global.getInt(mContext.getContentResolver(),
                    SETTING_SAVED_LIMIT, 80);
            if (limit < 70 || limit > 100) {
                limit = 80;
            }
            restoreChargingControl(health, enabled, mode, limit);
            Settings.Global.putInt(mContext.getContentResolver(), SETTING_ACTIVE, 0);
            mBypassActive = false;
            Log.i(TAG, "Charging bypass disabled and charging control restored");
        } catch (Exception e) {
            Settings.Global.putInt(mContext.getContentResolver(), SETTING_ACTIVE, 1);
            Log.w(TAG, "Failed to restore charging control", e);
        }
    }

    private void restoreChargingControl(HealthInterface health, boolean enabled,
            int mode, int limit) {
        health.setEnabled(false);
        health.setMode(mode);
        health.setLimit(limit);
        health.setEnabled(enabled);
    }
}
