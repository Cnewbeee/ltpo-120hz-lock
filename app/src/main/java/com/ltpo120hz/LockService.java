package com.ltpo120hz;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;

import java.util.Locale;

/**
 * 常驻前台服务：持有那个 1×1（或微点 / 指示条）的悬浮窗。
 * <p>
 * 之所以要前台服务，是因为进程一旦被系统回收，悬浮窗消失 → 投票消失 →
 * 刷新率立刻回落到低档，用户又会看到亮度跳变。
 */
public final class LockService extends Service {

    public static final String ACTION_START = "com.ltpo120hz.action.START";
    public static final String ACTION_STOP = "com.ltpo120hz.action.STOP";
    public static final String ACTION_REFRESH = "com.ltpo120hz.action.REFRESH";

    public static final String CHANNEL_ID = "ltpo_lock";
    public static final int NOTIFICATION_ID = 0x120;

    private static volatile boolean running;

    private WindowManager windowManager;
    private OverlayLockView overlay;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private float targetHz = 120f;
    private int style = Prefs.STYLE_INVISIBLE;
    private float renderFps;
    private long startRealtime;

    /** 游戏接管：悬浮窗已被撤下，只在服务里占个位，等游戏退出再挂回去。 */
    private boolean gameSuspended;
    private int gameStreak;
    private int idleStreak;
    private long lastGameAt;
    private String gamePkg;

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            if (gameSuspended) {
                // 游戏接管期间不投票、也不去纠正刷新率
                updateNotification();
                handler.postDelayed(this, 2000L);
                return;
            }
            RateMonitor.check("watchdog");
            float hz = RateMonitor.hz();
            if (hz > 0f && hz < targetHz - 5f) {
                Logbuf.add("wd", String.format(Locale.US,
                        "刷新率掉到 %.1fHz，重新投票", hz));
                if (overlay != null) {
                    overlay.reassert("watchdog-dip");
                }
            }
            updateNotification();
            handler.postDelayed(this, 2000L);
        }
    };

    private final Runnable gameTick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            evaluateForeground();
            handler.postDelayed(this, 1000L);
        }
    };

    public static boolean isRunning() {
        return running;
    }

    public static void start(Context c) {
        Intent i = new Intent(c, LockService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    public static void stop(Context c) {
        try {
            c.stopService(new Intent(c, LockService.class));
        } catch (Throwable ignore) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        RateMonitor.install(this);
        ForegroundTracker.init(this);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        Logbuf.add("svc", "onStartCommand " + action);

        if (ACTION_STOP.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }

        float wanted = Prefs.targetHz(this);
        targetHz = RateMonitor.snap(wanted);
        if (Math.abs(targetHz - wanted) > 0.5f) {
            Logbuf.add("svc", String.format(Locale.US,
                    "目标 %.0fHz 面板不支持，吸附到 %.0fHz", wanted, targetHz));
        }
        style = Prefs.style(this);

        // 必须在 5 秒内进入前台
        startForeground(NOTIFICATION_ID, buildNotification());

        boolean refresh = ACTION_REFRESH.equals(action);

        if (!running) {
            // START 和 REFRESH 在"还没跑"的情况下走同一条完整启动路径
            if (!Settings.canDrawOverlays(this)) {
                Logbuf.add("svc", "没有悬浮窗权限，无法启动");
                stopSelf();
                return START_NOT_STICKY;
            }
            running = true;
            startRealtime = android.os.SystemClock.elapsedRealtime();
            Prefs.setStartedAt(this, System.currentTimeMillis());
            Prefs.get(this).edit().putBoolean(Prefs.K_RUNNING, true).apply();
            ForegroundTracker.start(this);
            addOverlay();
            handler.post(watchdog);
            handler.postDelayed(gameTick, 1500L);
            if (Prefs.adbLock(this)) {
                String err = SysLock.apply(this, targetHz);
                Logbuf.add("svc", "系统级锁定: " + (err == null ? "成功" : err));
            }
        } else if (refresh) {
            // 改了样式/位置：窗口尺寸变了，只能重建
            removeOverlay();
            if (!gameSuspended) {
                addOverlay();
            }
        } else if (overlay != null) {
            overlay.configure(targetHz, style);
        }
        return START_STICKY;
    }

    // ------------------------------------------------------------ 游戏感知

    /**
     * 每秒判断一次前台应用。
     * <p>
     * 逻辑刻意做慢了半拍 + 加了缓冲：
     * <ul>
     *   <li>连着 2 秒都是游戏才暂停，避免启动闪屏 / 加载页误判；</li>
     *   <li>连着 3 秒都不是游戏才恢复，避免游戏内切到广告页时来回抖动；</li>
     *   <li>游戏结束后 90 秒内的系统组件（WebView / GMS / 权限弹窗）仍算「游戏上下文」，
     *       否则游戏里的广告和登录页会把锁切来切去。</li>
     * </ul>
     */
    private void evaluateForeground() {
        if (!Prefs.gameAuto(this)) {
            if (gameSuspended) {
                resumeFromGame("自动暂停已关闭");
            }
            return;
        }

        String pkg = ForegroundTracker.current();
        int kind = GameDetector.classify(this, pkg);
        long now = System.currentTimeMillis();

        boolean gameish;
        if (kind == GameDetector.KIND_GAME) {
            lastGameAt = now;
            gameish = true;
        } else if (kind == GameDetector.KIND_UNKNOWN) {
            gameish = gameSuspended; // 什么都不知道的时候保持现状
        } else if (kind == GameDetector.KIND_NEUTRAL
                && now - lastGameAt < 90_000L) {
            gameish = true;
        } else {
            gameish = false;
        }

        if (gameish) {
            gameStreak++;
            idleStreak = 0;
        } else {
            idleStreak++;
            gameStreak = 0;
        }

        if (!gameSuspended && gameStreak >= 2) {
            suspendForGame(pkg);
        } else if (gameSuspended && idleStreak >= 3) {
            resumeFromGame(pkg);
        }
    }

    private void suspendForGame(String pkg) {
        if (gameSuspended) {
            return;
        }
        gameSuspended = true;
        suspendedFlag = true;
        gamePkg = pkg;
        gameStreak = 0;
        removeOverlay();
        Logbuf.add("game", "进入游戏 " + GameDetector.label(this, pkg) + "，已暂停出帧");
        updateNotification();
    }

    private void resumeFromGame(String pkg) {
        if (!gameSuspended) {
            return;
        }
        gameSuspended = false;
        suspendedFlag = false;
        idleStreak = 0;
        addOverlay();
        Logbuf.add("game", "离开游戏（" + GameDetector.label(this, pkg) + "），恢复出帧");
        updateNotification();
    }

    public static boolean isGameSuspended() {
        return suspendedFlag;
    }

    private static volatile boolean suspendedFlag;

    @Override
    public void onDestroy() {
        Logbuf.add("svc", "onDestroy");
        shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void shutdown() {
        running = false;
        gameSuspended = false;
        suspendedFlag = false;
        gameStreak = 0;
        idleStreak = 0;
        handler.removeCallbacks(watchdog);
        handler.removeCallbacks(gameTick);
        ForegroundTracker.stop();
        removeOverlay();
        Prefs.get(this).edit().putBoolean(Prefs.K_RUNNING, false).apply();
        Prefs.setStartedAt(this, 0L);
        stopForeground(true);
        stopSelf();
    }

    // ------------------------------------------------------------ 悬浮窗

    private int[] sizeFor(int s) {
        float d = getResources().getDisplayMetrics().density;
        if (s == Prefs.STYLE_DOT) {
            int v = Math.round(18 * d);
            return new int[]{v, v};
        }
        if (s == Prefs.STYLE_PILL) {
            // 宽度按「挡位 · 帧率」实测文字宽算，别留多余空白
            return OverlayLockView.pillSize(this, targetHz);
        }
        return new int[]{1, 1}; // 隐形：1 像素，物理上不可能被看到
    }

    /** 顶部安全距离：取「状态栏高度」和「挖孔安全边距」里更大的那个。 */
    private int statusBarHeight() {
        int h = 0;
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) {
                h = getResources().getDimensionPixelSize(id);
            }
        } catch (Throwable ignore) {
        }
        // 有刘海/挖孔的机器，挖孔往往比状态栏更高，以挖孔为准
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                android.view.Display d = RateMonitor.display();
                if (d != null) {
                    android.view.DisplayCutout cut = d.getCutout();
                    if (cut != null) {
                        h = Math.max(h, cut.getSafeInsetTop());
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        if (h <= 0) {
            h = Math.round(48 * getResources().getDisplayMetrics().density);
        }
        return h;
    }

    private int gravityFor(int corner) {        switch (corner) {
            case 0:
                return Gravity.TOP | Gravity.START;
            case 1:
                return Gravity.TOP | Gravity.END;
            case 3:
                return Gravity.BOTTOM | Gravity.END;
            default:
                return Gravity.BOTTOM | Gravity.START;
        }
    }

    private void addOverlay() {
        if (overlay != null || gameSuspended) {
            return;
        }
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) {
            Logbuf.add("svc", "WindowManager 不可用");
            return;
        }

        int[] size = sizeFor(style);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                size[0], size[1],
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);

        int corner = Prefs.corner(this);
        int gravity = gravityFor(corner);
        lp.gravity = gravity;
        float d = getResources().getDisplayMetrics().density;
        int margin = 0;
        lp.x = margin;
        lp.y = margin;
        // 可见样式停在上方时避开状态栏/挖孔：贴着状态栏下沿，不留间隔
        if (style != Prefs.STYLE_INVISIBLE && (gravity & Gravity.TOP) == Gravity.TOP) {
            lp.y = statusBarHeight();
        }

        // 老接口的两个提示，很多 ROM 只认这两个中的一个
        try {
            lp.preferredRefreshRate = targetHz;
        } catch (Throwable ignore) {
        }
        int modeId = RateMonitor.bestModeId(targetHz);
        if (modeId >= 0) {
            try {
                lp.preferredDisplayModeId = modeId;
            } catch (Throwable ignore) {
            }
        }

        overlay = new OverlayLockView(this);
        overlay.configure(targetHz, style);
        overlay.setStatsListener(fps -> renderFps = fps);

        try {
            windowManager.addView(overlay, lp);
        } catch (Throwable t) {
            Logbuf.add("svc", "addView 失败: " + t);
            overlay = null;
            running = false;
            return;
        }
        overlay.start();

        Logbuf.add("svc", String.format(Locale.US,
                "悬浮窗已添加: %dx%d px, 目标 %.0fHz, preferredDisplayModeId=%d",
                size[0], size[1], targetHz, modeId));
    }

    private void removeOverlay() {
        if (overlay != null) {
            try {
                overlay.stop();
            } catch (Throwable ignore) {
            }
            try {
                if (windowManager != null) {
                    windowManager.removeView(overlay);
                }
            } catch (Throwable ignore) {
            }
            overlay = null;
        }
    }

    // ------------------------------------------------------------ 通知

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, StopReceiver.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getBroadcast(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String text;
        if (gameSuspended) {
            text = "游戏中，已暂停出帧";
        } else {
            text = String.format(Locale.US, "显示 %.1f Hz · 出帧 %.0f fps",
                    RateMonitor.hz(), renderFps);
        }

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_hz)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(
                        R.drawable.ic_stat_hz, "停止", stopPi).build())
                .build();
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        try {
            nm.notify(NOTIFICATION_ID, buildNotification());
        } catch (Throwable ignore) {
        }
    }

    public long uptimeMs() {
        if (!running || startRealtime == 0L) {
            return 0L;
        }
        return android.os.SystemClock.elapsedRealtime() - startRealtime;
    }
}
