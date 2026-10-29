package com.ltpo120hz;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.util.Locale;

/**
 * 系统级刷新率锁定（可选增强手段）。
 * <p>
 * 相比悬浮窗投票，这条路径是「让系统自己认为用户要求 120Hz」，因此几乎不耗电，
 * 也不受 per-app 刷新率白名单影响。缺点是需要一次性 adb 授权 WRITE_SECURE_SETTINGS。
 * <p>
 * 注意：不同 ROM 读的键不完全一样。AOSP / Pixel 系读 Settings.System 的
 * min_refresh_rate 与 peak_refresh_rate；部分国产 ROM 还读 Settings.Global 中的同名键，
 * 所以这里两处都写一次（写失败不影响主流程）。
 */
public final class SysLock {

    public static final String KEY_MIN = "min_refresh_rate";
    public static final String KEY_PEAK = "peak_refresh_rate";

    public static final String GRANT_CMD =
            "adb shell pm grant com.ltpo120hz android.permission.WRITE_SECURE_SETTINGS";

    private SysLock() {
    }

    public static boolean hasPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** @return null 表示成功，否则返回错误说明 */
    public static String apply(Context c, float hz) {
        if (!hasPermission(c)) {
            Logbuf.add("sys", "未授予 WRITE_SECURE_SETTINGS");
            return "未授权";
        }
        ContentResolver cr = c.getContentResolver();
        String v = String.format(Locale.US, "%.1f", hz);
        try {
            Settings.System.putString(cr, KEY_MIN, v);
            Settings.System.putString(cr, KEY_PEAK, v);
            Logbuf.add("sys", "写入 " + KEY_MIN + "=" + v + ", " + KEY_PEAK + "=" + v);
            return null;
        } catch (Throwable t) {
            Logbuf.add("sys", "写入失败: " + t);
            return String.valueOf(t.getMessage());
        }
    }

    /** 删除写入的键，恢复系统自适应刷新率。 */
    public static String clear(Context c) {
        if (!hasPermission(c)) {
            return "未授权";
        }
        ContentResolver cr = c.getContentResolver();
        try {
            Settings.System.putString(cr, KEY_MIN, null);
            Settings.System.putString(cr, KEY_PEAK, null);
            Settings.Global.putString(cr, KEY_MIN, null);
            Settings.Global.putString(cr, KEY_PEAK, null);
            Logbuf.add("sys", "已清除 " + KEY_MIN + " / " + KEY_PEAK);
            return null;
        } catch (Throwable t) {
            Logbuf.add("sys", "清除失败: " + t);
            return String.valueOf(t.getMessage());
        }
    }

    public static String read(Context c) {
        ContentResolver cr = c.getContentResolver();
        String sysMin = safeGet(() -> Settings.System.getString(cr, KEY_MIN));
        String sysPeak = safeGet(() -> Settings.System.getString(cr, KEY_PEAK));
        String gMin = safeGet(() -> Settings.Global.getString(cr, KEY_MIN));
        String gPeak = safeGet(() -> Settings.Global.getString(cr, KEY_PEAK));
        return "system: min=" + sysMin + " peak=" + sysPeak
                + "\nglobal: min=" + gMin + " peak=" + gPeak;
    }

    private interface Getter {
        String get();
    }

    private static String safeGet(Getter g) {
        try {
            String s = g.get();
            return s == null ? "—" : s;
        } catch (Throwable t) {
            return "?";
        }
    }
}
