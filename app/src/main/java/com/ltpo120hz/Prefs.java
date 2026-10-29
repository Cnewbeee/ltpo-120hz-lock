package com.ltpo120hz;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 极简配置存储。
 */
public final class Prefs {

    private static final String FILE = "ltpo_prefs";

    public static final String K_RUNNING   = "running";
    public static final String K_STYLE     = "style";      // 0=隐形 1=微点 2=指示条
    public static final String K_TARGET    = "target_hz";
    public static final String K_AUTOSTART = "autostart";
    public static final String K_CORNER    = "corner";     // 0=左上 1=右上 2=左下 3=右下
    public static final String K_ADB_LOCK  = "adb_lock";
    public static final String K_STARTED_AT = "started_at";
    public static final String K_GAME_AUTO = "game_auto";
    public static final String K_GAME_LIST = "game_list";

    public static final int STYLE_INVISIBLE = 0;
    public static final int STYLE_DOT       = 1;
    public static final int STYLE_PILL      = 2;

    private Prefs() {
    }

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static int style(Context c) {
        int v = get(c).getInt(K_STYLE, STYLE_INVISIBLE);
        return (v < 0 || v > 2) ? STYLE_INVISIBLE : v;
    }

    public static void setStyle(Context c, int v) {
        get(c).edit().putInt(K_STYLE, v).apply();
    }

    public static float targetHz(Context c) {
        float f = get(c).getFloat(K_TARGET, 120f);
        if (f < 24f || f > 240f) {
            f = 120f;
        }
        return f;
    }

    public static void setTargetHz(Context c, float hz) {
        get(c).edit().putFloat(K_TARGET, hz).apply();
    }

    public static int corner(Context c) {
        int v = get(c).getInt(K_CORNER, 2);
        return (v < 0 || v > 3) ? 2 : v;
    }

    public static void setCorner(Context c, int v) {
        get(c).edit().putInt(K_CORNER, v).apply();
    }

    public static boolean autostart(Context c) {
        return get(c).getBoolean(K_AUTOSTART, false);
    }

    public static void setAutostart(Context c, boolean v) {
        get(c).edit().putBoolean(K_AUTOSTART, v).apply();
    }

    public static boolean adbLock(Context c) {
        return get(c).getBoolean(K_ADB_LOCK, false);
    }

    public static void setAdbLock(Context c, boolean v) {
        get(c).edit().putBoolean(K_ADB_LOCK, v).apply();
    }

    public static long startedAt(Context c) {
        return get(c).getLong(K_STARTED_AT, 0L);
    }

    public static void setStartedAt(Context c, long v) {
        get(c).edit().putLong(K_STARTED_AT, v).apply();
    }

    /** 检测到游戏时是否自动暂停出帧，默认开。 */
    public static boolean gameAuto(Context c) {
        return get(c).getBoolean(K_GAME_AUTO, true);
    }

    public static void setGameAuto(Context c, boolean v) {
        get(c).edit().putBoolean(K_GAME_AUTO, v).apply();
    }

    /** 手动标记为「游戏」的包名。 */
    public static java.util.Set<String> gameList(Context c) {
        try {
            java.util.Set<String> s = get(c).getStringSet(K_GAME_LIST, null);
            return s == null ? new java.util.HashSet<>() : new java.util.HashSet<>(s);
        } catch (Throwable t) {
            return new java.util.HashSet<>();
        }
    }

    public static void setGameList(Context c, java.util.Set<String> s) {
        get(c).edit().putStringSet(K_GAME_LIST, new java.util.HashSet<>(s)).apply();
    }

    /** @return true 表示这次是「加入」，false 表示「移出」 */
    public static boolean toggleGame(Context c, String pkg) {
        java.util.Set<String> s = gameList(c);
        boolean added;
        if (s.contains(pkg)) {
            s.remove(pkg);
            added = false;
        } else {
            s.add(pkg);
            added = true;
        }
        setGameList(c, s);
        return added;
    }
}
