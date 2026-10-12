package com.hanh.gmsprotect;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AurogonRefreshReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !AurogonAllowlistService.ACTION_REFRESH_ALARM.equals(intent.getAction())) {
            return;
        }
        int stage = intent.getIntExtra(
                AurogonAllowlistService.EXTRA_STAGE,
                AurogonAllowlistService.STAGE_PERIODIC
        );
        AurogonAllowlistService.handleRefreshAlarm(context, stage);
    }
}
