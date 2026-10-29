package com.ltpo120hz;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * 前台应用追踪。
 * <p>
 * 两条数据源，谁先到用谁：
 * <ul>
 *   <li>UsageStatsManager 轮询（1 秒一次），需要用户在"使用情况访问权限"里授权；</li>
 *   <li>无障碍服务实时上报（可选，延迟最低）。</li>
 * </ul>
 * 两条都不通时 current() 返回 null，上层就保持现状不动——宁可不暂停，也不误暂停。
 */
public final class ForegroundTracker {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static Context appContext;
    private static volatile String current;
    private static volatile long currentAt;
    private static boolean polling;

    private ForegroundTracker() {
    }

    public static void init(Context c) {
        if (appContext == null) {
            appContext = c.getApplicationContext();
        }
    }

    /** 最近一次已知的前台包名，可能为 null。 */
    public static String current() {
        return current;
    }

    public static long currentAt() {
        return currentAt;
    }

    // ------------------------------------------------------------ 使用情况访问

    public static boolean hasUsageAccess(Context c) {
        try {
            AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) {
                return false;
            }
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), c.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void start(Context c) {
        init(c);
        if (polling) {
            return;
        }
        polling = true;
        MAIN.post(pollLoop);
        Logbuf.add("fg", "开始追踪前台应用");
    }

    public static void stop() {
        polling = false;
        MAIN.removeCallbacks(pollLoop);
    }

    private static final Runnable pollLoop = new Runnable() {
        @Override
        public void run() {
            if (!polling) {
                return;
            }
            pollOnce();
            MAIN.postDelayed(this, 1000L);
        }
    };

    private static void pollOnce() {
        Context c = appContext;
        if (c == null || !hasUsageAccess(c)) {
            return;
        }
        String pkg = latestResumed(c);
        if (pkg != null) {
            set(pkg);
        }
    }

    private static String latestResumed(Context c) {
        UsageStatsManager usm =
                (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        UsageEvents events;
        try {
            events = usm.queryEvents(now - 30_000L, now);
        } catch (Throwable t) {
            return null;
        }
        if (events == null) {
            return null;
        }
        // MOVE_TO_FOREGROUND 与 ACTIVITY_RESUMED 是同一个值（1），API 29 后改名
        final int resumedType = Build.VERSION.SDK_INT >= 29
                ? UsageEvents.Event.ACTIVITY_RESUMED
                : UsageEvents.Event.MOVE_TO_FOREGROUND;

        UsageEvents.Event e = new UsageEvents.Event();
        String last = null;
        long lastTs = 0L;
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() == resumedType && e.getTimeStamp() >= lastTs) {
                lastTs = e.getTimeStamp();
                last = e.getPackageName();
            }
        }
        return last;
    }

    // ------------------------------------------------------------ 无障碍上报

    /** 由 {@link GameSenseService} 调用，延迟比轮询低。 */
    public static void report(String pkg) {
        if (pkg != null && !pkg.isEmpty()) {
            set(pkg);
        }
    }

    private static synchronized void set(String pkg) {
        if (!pkg.equals(current)) {
            current = pkg;
            currentAt = System.currentTimeMillis();
            Logbuf.add("fg", "前台 → " + pkg);
        }
    }
}
