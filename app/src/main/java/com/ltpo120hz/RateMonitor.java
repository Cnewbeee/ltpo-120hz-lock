package com.ltpo120hz;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;

import java.util.Locale;

/**
 * 读取当前显示刷新率 / display mode，并在切换发生时记一条日志。
 * <p>
 * 这里用 DisplayManager.DisplayListener 抓「模式变了」这个事件——它是判断
 * LTPO 是否真的在降频最直接的证据，也是验证悬浮窗锁频是否生效的唯一标准。
 */
public final class RateMonitor {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static DisplayManager displayManager;
    private static DisplayManager.DisplayListener listener;

    private static float lastHz = -1f;
    private static int lastModeId = -1;
    private static int changes;

    private RateMonitor() {
    }

    public static synchronized void install(Context context) {
        if (displayManager != null) {
            check("install");
            return;
        }
        Context app = context.getApplicationContext();
        displayManager = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager == null) {
            Logbuf.add("disp", "DisplayManager 不可用");
            return;
        }
        listener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
            }

            @Override
            public void onDisplayRemoved(int displayId) {
            }

            @Override
            public void onDisplayChanged(int displayId) {
                if (displayId == Display.DEFAULT_DISPLAY) {
                    check("display-changed");
                }
            }
        };
        displayManager.registerDisplayListener(listener, MAIN);
        check("install");
    }

    public static Display display() {
        DisplayManager dm = displayManager;
        if (dm == null) {
            return null;
        }
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        if (d != null) {
            return d;
        }
        Display[] all = dm.getDisplays();
        return all.length > 0 ? all[0] : null;
    }

    public static float hz() {
        Display d = display();
        return d == null ? -1f : d.getRefreshRate();
    }

    public static int modeId() {
        Display d = display();
        if (d == null) {
            return -1;
        }
        return d.getMode().getModeId();
    }

    /** 比较当前值与上次记录值，有变化就写日志。 */
    public static synchronized void check(String why) {
        Display d = display();
        if (d == null) {
            return;
        }
        float hz = d.getRefreshRate();
        int id = d.getMode().getModeId();
        if (lastHz < 0f || Math.abs(hz - lastHz) > 0.05f || id != lastModeId) {
            Logbuf.add("Hz", String.format(Locale.US, "%s → %.1f Hz (mode %d)", why, hz, id));
            if (lastHz >= 0f && Math.abs(hz - lastHz) > 0.05f) {
                changes++;
            }
            lastHz = hz;
            lastModeId = id;
        }
    }

    /** 本次进程内观察到的刷新率变化次数，界面上用它判断锁定是否真的生效。 */
    public static synchronized int changes() {
        return changes;
    }

    public static synchronized void resetChanges() {
        changes = 0;
    }

    /** 例如 "60Hz, 90Hz, 120Hz"，用于自检。 */
    public static String supportedSummary() {
        Display d = display();
        if (d == null) {
            return "未知";
        }
        Display.Mode[] modes = d.getSupportedModes();
        StringBuilder sb = new StringBuilder();
        for (Display.Mode m : modes) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(String.format(Locale.US, "%.0fHz(#%d)", m.getRefreshRate(), m.getModeId()));
        }
        return sb.length() == 0 ? "未知" : sb.toString();
    }

    public static boolean supports(float hz) {
        Display d = display();
        if (d == null) {
            return false;
        }
        for (Display.Mode m : d.getSupportedModes()) {
            if (Math.abs(m.getRefreshRate() - hz) < 1.0f) {
                return true;
            }
        }
        return false;
    }

    /** 找到最接近 hz 的 display mode id；找不到返回 -1。 */
    public static int bestModeId(float hz) {
        Display d = display();
        if (d == null) {
            return -1;
        }
        int best = -1;
        float bestDelta = Float.MAX_VALUE;
        for (Display.Mode m : d.getSupportedModes()) {
            float delta = Math.abs(m.getRefreshRate() - hz);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = m.getModeId();
            }
        }
        return best;
    }
}
