package com.ltpo120hz;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * 进程内的环形日志，界面上的「诊断」面板直接读它。
 * 用来观察刷新率到底有没有被切换、切换了几次、什么时候切。
 */
public final class Logbuf {

    private static final int CAP = 200;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>(CAP);
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static String lastLine = "";

    private Logbuf() {
    }

    public static synchronized void add(String tag, String msg) {
        String line = FMT.format(new Date()) + " [" + tag + "] " + msg;
        LINES.addLast(line);
        lastLine = line;
        while (LINES.size() > CAP) {
            LINES.removeFirst();
        }
        // 同时写一份到 logcat，方便用 adb logcat -s Ltpo120Hz 远程排查
        try {
            android.util.Log.i("Ltpo120Hz", "[" + tag + "] " + msg);
        } catch (Throwable ignore) {
        }
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder(LINES.size() * 48);
        for (String s : LINES) {
            sb.append(s).append('\n');
        }
        return sb.toString();
    }

    public static synchronized String last() {
        return lastLine;
    }

    public static synchronized void clear() {
        LINES.clear();
        lastLine = "";
    }
}
