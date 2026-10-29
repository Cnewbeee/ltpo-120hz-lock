package com.ltpo120hz;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/**
 * 开机自启（默认关闭，可在界面里打开）。
 */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        if (!Prefs.autostart(context)) {
            return;
        }
        if (!Settings.canDrawOverlays(context)) {
            Logbuf.add("boot", "开机自启跳过：没有悬浮窗权限");
            return;
        }
        try {
            LockService.start(context);
            Logbuf.add("boot", "开机自启");
        } catch (Throwable t) {
            Logbuf.add("boot", "开机自启失败: " + t);
        }
    }
}
