/*
 * Copyright (C) 2026 The Infinity-X Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.systemui.qs.tiles;

import static com.android.internal.logging.MetricsLogger.VIEW_UNKNOWN;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BypassChargingController;
import com.android.systemui.util.settings.GlobalSettings;
import com.android.systemui.util.settings.SettingObserver;

import javax.inject.Inject;

public class BypassChargingTile extends QSTileImpl<BooleanState>
        implements BatteryController.BatteryStateChangeCallback {

    public static final String TILE_SPEC = "bypass_charging";

    private final BatteryController mBatteryController;
    private final BypassChargingController mBypassChargingController;
    private final SettingObserver mSetting;

    @Nullable
    private Icon mIcon;

    @Inject
    public BypassChargingTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            GlobalSettings globalSettings,
            UserTracker userTracker,
            BatteryController batteryController,
            BypassChargingController bypassChargingController) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);

        mBatteryController = batteryController;
        mBypassChargingController = bypassChargingController;
        mBatteryController.observe(getLifecycle(), this);
        mSetting = new SettingObserver(globalSettings, mHandler,
                BypassChargingController.SETTING_ACTIVE, userTracker.getUserId()) {
            @Override
            protected void handleValueChanged(int value, boolean observedChange) {
                handleRefreshState(value);
            }
        };
    }

    @Override
    public boolean isAvailable() {
        return mBypassChargingController.isSupported();
    }

    @Override
    public BooleanState newTileState() {
        BooleanState state = new BooleanState();
        state.handlesLongClick = false;
        return state;
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        mBypassChargingController.setManualBypassRequested(!mState.value);
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_bypass_charging_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        int value = arg instanceof Integer ? (Integer) arg : mSetting.getValue();
        state.value = value != 0;
        state.label = mContext.getString(R.string.quick_settings_bypass_charging_label);
        state.hasLongClickEffect = false;
        if (mIcon == null) {
            mIcon = maybeLoadResourceIcon(R.drawable.ic_qs_bypass_charging);
        }
        state.icon = mIcon;
        state.state = !mBatteryController.isPluggedIn() && !state.value
                ? Tile.STATE_UNAVAILABLE
                : state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
    }

    @Override
    public int getMetricsCategory() {
        return VIEW_UNKNOWN;
    }

    @Override
    public void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
        mSetting.setListening(listening);
    }

    @Override
    protected void handleDestroy() {
        mSetting.setListening(false);
        super.handleDestroy();
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        refreshState();
    }
}
