package com.ltpo120hz;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 通知栏「停止」按钮的落点。
 * 用广播而不是直接 startService，是为了绕开 Android 8+ 的后台启动服务限制。
 */
public final class StopReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        LockService.stop(context);
        Logbuf.add("svc", "由通知栏停止");
    }
}
