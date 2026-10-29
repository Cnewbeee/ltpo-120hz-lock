package com.ltpo120hz;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.Locale;

/**
 * 判断"前台这个应用是不是游戏"。
 * <p>
 * 三个信号叠加，逐级放宽：
 * <ol>
 *   <li>用户手动标记的包名（最可靠，永远优先）</li>
 *   <li>{@link ApplicationInfo#category} == CATEGORY_GAME 或 FLAG_IS_GAME
 *       —— Play 上架的游戏都会带上，这是系统自己给的分类</li>
 *   <li>一小撮明确的游戏包名前缀兜底（国产渠道服常常不写 category）</li>
 * </ol>
 * 系统组件（桌面、SystemUI、输入法、安装器、WebView、GMS）归为 NEUTRAL，
 * 这样游戏内嵌的广告 / 登录网页不会让锁定反复开关。
 */
public final class GameDetector {

    public static final int KIND_UNKNOWN = 0;
    public static final int KIND_GAME = 1;
    public static final int KIND_NEUTRAL = 2;
    public static final int KIND_OTHER = 3;

    /** 只放足够独特、不会误伤音乐/社交类的前缀。 */
    private static final String[] GAME_PREFIXES = {
            "com.tencent.tmgp.",
            "com.tencent.game.",
            "com.miHoYo.",
            "com.mihoyo.",
            "com.hypergryph.",
            "com.papegames.",
            "com.yostar.",
            "com.mojang.",
            "com.roblox.",
            "com.supercell.",
            "com.epicgames.",
            "com.riotgames.",
            "com.gameloft.",
            "com.ea.gp.",
            "com.garena.game.",
            "com.dts.freefire",
            "com.mobile.legends",
            "com.activision.callofduty",
            "com.netease.mrzh",
            "com.netease.g78na",
            "com.netease.pm02",
            "com.bilibili.game",
    };

    /** 这些包出现时不要改状态：它们是游戏过程中必然会短暂路过的地方。 */
    private static final String[] NEUTRAL_PREFIXES = {
            "com.android.systemui",
            "android",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.google.android.webview",
            "com.android.webview",
            "com.android.chrome",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.phone",
            "com.android.incallui",
            "com.google.android.dialer",
            "com.android.settings",
    };

    private GameDetector() {
    }

    public static int classify(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return KIND_UNKNOWN;
        }
        if (pkg.equals(c.getPackageName())) {
            return KIND_NEUTRAL;
        }
        if (Prefs.gameList(c).contains(pkg)) {
            return KIND_GAME;
        }
        if (isNeutral(pkg)) {
            return KIND_NEUTRAL;
        }
        if (isGame(c, pkg)) {
            return KIND_GAME;
        }
        return KIND_OTHER;
    }

    private static boolean isNeutral(String pkg) {
        for (String p : NEUTRAL_PREFIXES) {
            if (pkg.equals(p) || pkg.startsWith(p + ".")) {
                return true;
            }
        }
        return false;
    }

    public static boolean isGame(Context c, String pkg) {
        if (pkg == null) {
            return false;
        }
        for (String p : GAME_PREFIXES) {
            if (pkg.startsWith(p)) {
                return true;
            }
        }
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
            if (ai.category == ApplicationInfo.CATEGORY_GAME) {
                return true;
            }
            if ((ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0) {
                return true;
            }
        } catch (Throwable ignore) {
            // 包不可见（Android 11+ 包可见性限制）或已卸载
        }
        return false;
    }

    public static String label(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return "未知";
        }
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence name = pm.getApplicationLabel(ai);
            if (name != null && name.length() > 0) {
                return name.toString();
            }
        } catch (Throwable ignore) {
        }
        return pkg;
    }

    public static String kindName(int kind) {
        switch (kind) {
            case KIND_GAME:
                return "游戏";
            case KIND_NEUTRAL:
                return "系统/中性";
            case KIND_OTHER:
                return "普通应用";
            default:
                return "未检测到";
        }
    }

    public static String describe(Context c, String pkg) {
        return String.format(Locale.US, "%s（%s）", label(c, pkg), kindName(classify(c, pkg)));
    }
}
