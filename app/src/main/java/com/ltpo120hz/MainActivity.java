package com.ltpo120hz;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 主界面：状态 + 样式 + 权限 + 系统级锁定 + 诊断。
 * 全部用代码搭，不依赖 AppCompat / Material。
 */
public final class MainActivity extends Activity {

    private static final int REQ_NOTIFICATION = 11;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView hzBig;
    private TextView statusLine;
    private TextView switchLine;
    private TextView primaryButton;
    private TextView overlayState;
    private TextView batteryState;
    private TextView sysState;
    private TextView logView;
    private TextView selfTestOut;
    private TextView selfTestButton;
    private TextView fgLine;
    private TextView gamePermState;
    private TextView sysWriteBtn;

    private int targetColorHz;

    private String lastLogDump = "";

    private boolean testing;
    private float testMin;
    private float testMax;
    private int testSwitches;
    private float lastTestHz;
    private long testStart;

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        RateMonitor.install(this);
        ForegroundTracker.init(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFillViewport(true);

        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 18);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars());
                top = i.top;
                bottom = i.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            root.setPadding(pad, pad + top, pad, pad + bottom);
            return insets;
        });

        setContentView(scroll);

        buildHeader(root);
        buildStatusCard(root);
        buildTargetCard(root);
        buildStyleCard(root);
        buildGameCard(root);
        buildSelfTestCard(root);
        buildPermissionCard(root);
        buildSysLockCard(root);
        buildLogCard(root);
        buildFootnote(root);

        askNotificationPermission();
        RateMonitor.check("activity-created");
        Logbuf.add("ui", "面板已打开");
    }

    @Override
    protected void onResume() {
        super.onResume();
        tickerOn = true;
        ui.post(tick);
    }

    private boolean tickerOn;
    private boolean lastPrimaryOn = true;
    private int tickCount;

    @Override
    protected void onPause() {
        tickerOn = false;
        ui.removeCallbacks(tick);
        super.onPause();
    }

    // ------------------------------------------------------------------ 界面构建

    private void buildHeader(LinearLayout root) {
        TextView title = Ui.bold(this, "120Hz 常驻", 27f, Ui.TEXT);
        title.setLetterSpacing(0.01f);
        root.addView(title, Ui.matchWrap(this, 2));

        TextView sub = Ui.dim(this,
                "LTPO 面板在换刷新率时亮度会跳一下。这个应用用一个几乎看不见的悬浮表面，"
                        + "让 SurfaceFlinger 一直留在高刷档，从根上避免那次切换。", 12.5f);
        root.addView(sub, Ui.matchWrap(this, 8));
        sub.setLineSpacing(0f, 1.35f);
    }

    private void buildStatusCard(LinearLayout root) {
        LinearLayout card = Ui.card(this);
        root.addView(card, Ui.matchWrap(this, 18));

        LinearLayout numberRow = Ui.row(this);
        hzBig = Ui.bold(this, "120.0", 46f, Ui.ACCENT);
        hzBig.setIncludeFontPadding(false);
        numberRow.addView(hzBig);
        TextView unit = Ui.dim(this, " Hz", 16f);
        unit.setPadding(0, 0, 0, Ui.dp(this, 8));
        LinearLayout.LayoutParams unitLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        unitLp.gravity = android.view.Gravity.BOTTOM;
        numberRow.addView(unit, unitLp);
        card.addView(numberRow);

        statusLine = Ui.dim(this, "读取中…", 12f);
        card.addView(statusLine, Ui.matchWrap(this, 4));

        switchLine = Ui.dim(this, "—", 12f);
        switchLine.setTextColor(Ui.WARN);
        card.addView(switchLine, Ui.matchWrap(this, 2));

        primaryButton = Ui.button(this, "开启锁定", true, v -> onPrimaryClick());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 14);
        card.addView(primaryButton, lp);
    }

    private void buildTargetCard(LinearLayout root) {
        LinearLayout card = Ui.card(this);
        root.addView(card, Ui.matchWrap(this, 14));

        card.addView(Ui.bold(this, "锁定挡位", 15f, Ui.TEXT));

        List<Float> rates = supportedRates();
        float target = Prefs.targetHz(this);
        int selected = nearestIndex(rates, target);

        String[] labels = new String[rates.size()];
        for (int i = 0; i < rates.size(); i++) {
            labels[i] = String.format(Locale.US, "%.0f", rates.get(i));
        }

        LinearLayout row = Ui.segmented(this, labels, selected, index -> {
            float hz = rates.get(index);
            Prefs.setTargetHz(MainActivity.this, hz);
            Logbuf.add("ui", String.format(Locale.US, "目标改为 %.0fHz", hz));
            restartIfRunning();
            refreshSoon();
        });
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = Ui.dp(this, 10);
        card.addView(row, rowLp);

        TextView unit = Ui.dim(this, "单位 Hz · 只列出这块屏幕真实支持的挡位", 11f);
        card.addView(unit, Ui.matchWrap(this, 8));

        TextView hint = Ui.dim(this,
                "锁 60Hz 同样不会跳亮度——亮度跳变来自「换挡」这个动作，而不是挡位本身。"
                        + "日常刷信息流锁 60 更省电；游戏或滑动要求顺滑时锁 120。"
                        + "改挡位会立即重建悬浮窗，不用手动重开。", 11.5f);
        hint.setLineSpacing(0f, 1.35f);
        card.addView(hint, Ui.matchWrap(this, 8));
    }

    /** 屏幕上报的刷新率列表；万一拿不到就给个兜底。 */
    private List<Float> supportedRates() {
        List<Float> rates = RateMonitor.supportedRates();
        if (rates == null || rates.isEmpty()) {
            rates = new ArrayList<>();
            rates.add(60f);
            rates.add(90f);
            rates.add(120f);
        }
        return rates;
    }

    private int nearestIndex(List<Float> rates, float hz) {
        int best = 0;
        float bestDelta = Float.MAX_VALUE;
        for (int i = 0; i < rates.size(); i++) {
            float delta = Math.abs(rates.get(i) - hz);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = i;
            }
        }
        return best;
    }

    private void buildStyleCard(LinearLayout root) {
        LinearLayout card = Ui.card(this);
        root.addView(card, Ui.matchWrap(this, 14));

        card.addView(Ui.bold(this, "悬浮窗样式", 15f, Ui.TEXT));

        int style = Prefs.style(this);
        LinearLayout styleRow = Ui.segmented(this,
                new String[]{"隐形 1px", "微点", "指示条"}, style, index -> {
                    Prefs.setStyle(MainActivity.this, index);
                    restartIfRunning();
                });
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = Ui.dp(this, 10);
        card.addView(styleRow, rowLp);

        TextView hint = Ui.dim(this,
                style == Prefs.STYLE_INVISIBLE
                        ? "1×1 像素、alpha=1/255，物理上无法被看到，也不挡触摸。"
                        : "会显示一个小图形，方便确认它真的在工作；同样不挡触摸。", 11.5f);
        card.addView(hint, Ui.matchWrap(this, 8));

        card.addView(Ui.bold(this, "停靠位置", 15f, Ui.TEXT),
                Ui.matchWrap(this, 16));
        LinearLayout cornerRow = Ui.segmented(this,
                new String[]{"左上", "右上", "左下", "右下"}, Prefs.corner(this), index -> {
                    Prefs.setCorner(MainActivity.this, index);
                    restartIfRunning();
                });
        LinearLayout.LayoutParams cornerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cornerLp.topMargin = Ui.dp(this, 10);
        card.addView(cornerRow, cornerLp);

        LinearLayout autoRow = Ui.row(this);
        autoRow.setPadding(0, Ui.dp(this, 14), 0, 0);
        TextView autoLabel = Ui.text(this, "开机自启", 14f, Ui.TEXT);
        autoRow.addView(autoLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = new Switch(this);
        sw.setChecked(Prefs.autostart(this));
        sw.setOnCheckedChangeListener((CompoundButton b, boolean checked) ->
                Prefs.setAutostart(MainActivity.this, checked));
        autoRow.addView(sw);
        card.addView(autoRow, Ui.matchWrap(this, 0));
    }

    private void buildGameCard(LinearLayout root) {
        LinearLayout card = Ui.card(this);
        root.addView(card, Ui.matchWrap(this, 14));

        card.addView(Ui.bold(this, "游戏时自动暂停", 15f, Ui.TEXT));

        LinearLayout row = Ui.row(this);
        row.addView(Ui.text(this, "检测到游戏就撤下悬浮窗", 13.5f, Ui.TEXT),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = new Switch(this);
        sw.setChecked(Prefs.gameAuto(this));
        sw.setOnCheckedChangeListener((CompoundButton b, boolean checked) -> {
            Prefs.setGameAuto(MainActivity.this, checked);
            refreshSoon();
        });
        row.addView(sw);
        card.addView(row, Ui.matchWrap(this, 8));

        TextView why = Ui.dim(this,
                "游戏里系统本身就会把帧率顶上去，亮度不会跳变；这时候没必要再常驻高刷，"
                        + "撤掉悬浮窗能省下不少电和发热。退出游戏大约 3 秒后自动恢复。", 11.5f);
        why.setLineSpacing(0f, 1.35f);
        card.addView(why, Ui.matchWrap(this, 8));

        LinearLayout r1 = Ui.row(this);
        r1.addView(Ui.text(this, "使用情况访问", 13.5f, Ui.TEXT),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        gamePermState = Ui.dim(this, "—", 12f);
        r1.addView(gamePermState);
        card.addView(r1, Ui.matchWrap(this, 14));
        card.addView(Ui.button(this, "授权使用情况访问", false, v -> openUsageAccess()),
                Ui.matchWrap(this, 8));

        TextView a11y = Ui.dim(this,
                "可选：开启无障碍服务后，识别从约 1 秒延迟变成瞬时，也更省电。"
                        + "它只读取「哪个应用到了前台」，不读屏幕内容。", 11.5f);
        a11y.setLineSpacing(0f, 1.35f);
        card.addView(a11y, Ui.matchWrap(this, 12));
        card.addView(Ui.button(this, "开启无障碍服务（可选）", false, v -> openAccessibility()),
                Ui.matchWrap(this, 8));

        fgLine = Ui.dim(this, "当前前台：—", 12f);
        card.addView(fgLine, Ui.matchWrap(this, 14));

        card.addView(Ui.button(this, "标记 / 取消标记当前前台应用", false,
                v -> toggleCurrentForeground()), Ui.matchWrap(this, 8));
    }

    private void buildSelfTestCard(LinearLayout root) {        LinearLayout card = Ui.card(this);
        root.addView(card, Ui.matchWrap(this, 14));

        card.addView(Ui.bold(this, "刷新率自检", 15f, Ui.TEXT));
        card.addView(Ui.dim(this,
                "连续采样 10 秒，报告最低 / 最高刷新率与切换次数。"
                        + "锁定时应当一直是 120Hz、切换 0 次；如果还在切，说明这个 ROM 不认第三方投票。", 11.5f),
                Ui.matchWrap(this, 8));

        selfTestButton = Ui.button(this, "自检 10 秒", false, v -> startSelfTest());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 12);
        card.addView(selfTestButton, lp);

        selfTestOut = Ui.dim(this, "尚未测试", 12f);
        card.addView(selfTestOut, Ui.matchWrap(this, 10));
    }

    private void buildPermissionCard(LinearLayout root) {
        LinearLayout card = Ui.card(this);
        root.addView(card, Ui.matchWrap(this, 14));

        card.addView(Ui.bold(this, "权限", 15f, Ui.TEXT));

        // 悬浮窗
        LinearLayout r1 = Ui.row(this);
        TextView l1 = Ui.text(this, "悬浮窗", 13.5f, Ui.TEXT);
        r1.addView(l1, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        overlayState = Ui.dim(this, "—", 12f);
        r1.addView(overlayState, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(r1, Ui.matchWrap(this, 12));
        card.addView(Ui.button(this, "授予悬浮窗权限", false, v -> requestOverlayPermission()),
                Ui.matchWrap(this, 8));

        // 电池优化
        LinearLayout r2 = Ui.row(this);
        TextView l2 = Ui.text(this, "忽略电池优化", 13.5f, Ui.TEXT);
        r2.addView(l2, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        batteryState = Ui.dim(this, "—", 12f);
        r2.addView(batteryState);
        card.addView(r2, Ui.matchWrap(this, 14));
        card.addView(Ui.button(this, "加入忽略名单", false, v -> requestBatteryExemption()),
                Ui.matchWrap(this, 8));

        // 通知
        card.addView(Ui.dim(this,
                "前台通知用于保活，去掉后进程容易被回收，刷新率会跟着回落。", 11.5f),
                Ui.matchWrap(this, 12));
    }

    private void buildSysLockCard(LinearLayout root) {
        LinearLayout card = Ui.card(this);
        root.addView(card, Ui.matchWrap(this, 14));

        card.addView(Ui.bold(this, "系统级锁定（推荐配合使用）", 15f, Ui.TEXT));
        TextView desc = Ui.dim(this,
                "直接写 min/peak_refresh_rate 让系统自己认为「用户要求 120Hz」。"
                        + "这条路径几乎不耗电，也不受各家的 per-app 刷新率白名单影响，"
                        + "但需要一次性 adb 授权。", 11.5f);
        card.addView(desc, Ui.matchWrap(this, 8));
        desc.setLineSpacing(0f, 1.35f);

        sysState = Ui.dim(this, "—", 11.5f);
        sysState.setTypeface(Typeface.MONOSPACE);
        card.addView(sysState, Ui.matchWrap(this, 10));

        LinearLayout btnRow = Ui.row(this);
        sysWriteBtn = Ui.button(this, writeLabel(), true, v -> writeSysLock());
        TextView clear = Ui.button(this, "清除", false, v -> clearSysLock());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        blp.rightMargin = Ui.dp(this, 6);
        btnRow.addView(sysWriteBtn, blp);
        LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        blp2.leftMargin = Ui.dp(this, 6);
        btnRow.addView(clear, blp2);
        card.addView(btnRow, Ui.matchWrap(this, 12));

        card.addView(Ui.button(this, "复制 adb 授权命令", false, v -> copyAdbCommand()),
                Ui.matchWrap(this, 10));
    }

    private void buildLogCard(LinearLayout root) {
        LinearLayout card = Ui.card(this);
        root.addView(card, Ui.matchWrap(this, 14));

        LinearLayout head = Ui.row(this);
        head.addView(Ui.bold(this, "诊断", 15f, Ui.TEXT),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView clear = Ui.text(this, "清空", 12.5f, Ui.ACCENT);
        clear.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        clear.setOnClickListener(v -> {
            Logbuf.clear();
            lastLogDump = "";
        });
        head.addView(clear);
        card.addView(head);

        TextView modes = Ui.dim(this, "支持的模式: " + RateMonitor.supportedSummary(), 11f);
        modes.setTypeface(Typeface.MONOSPACE);
        card.addView(modes, Ui.matchWrap(this, 8));

        ScrollView logScroll = new ScrollView(this);
        logScroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 190)));
        logView = Ui.dim(this, "", 10.5f);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logScroll.addView(logView);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 190));
        llp.topMargin = Ui.dp(this, 8);
        card.addView(logScroll, llp);
    }

    private void buildFootnote(LinearLayout root) {
        TextView foot = Ui.dim(this,
                "提示：如果你只是想要稳定的亮度，也可以先在系统设置里关掉「自适应/智能刷新率」，"
                        + "或在开发者选项里开启「显示刷新率」观察它到底什么时候在降频。", 11f);
        foot.setLineSpacing(0f, 1.4f);
        root.addView(foot, Ui.matchWrap(this, 16));
    }

    // ------------------------------------------------------------------ 轮询刷新

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!tickerOn) {
                return;
            }
            RateMonitor.check("ui-tick");

            float hz = RateMonitor.hz();
            if (hz > 0) {
                hzBig.setText(String.format(Locale.US, "%.1f", hz));
                boolean ok = hz >= Prefs.targetHz(MainActivity.this) - 2f;
                if (targetColorHz != (ok ? 1 : 2)) {
                    targetColorHz = ok ? 1 : 2;
                    hzBig.setTextColor(ok ? Ui.ACCENT : Ui.WARN);
                }
            }

            boolean on = LockService.isRunning();
            long startedAt = Prefs.startedAt(MainActivity.this);
            String uptime = "—";
            if (on && startedAt > 0) {
                long s = Math.max(0, (System.currentTimeMillis() - startedAt) / 1000L);
                uptime = String.format(Locale.US, "%02d:%02d:%02d",
                        s / 3600, (s % 3600) / 60, s % 60);
            }
            statusLine.setText(String.format(Locale.US,
                    "目标 %.0fHz · mode #%d · 已锁定 %s · %s",
                    Prefs.targetHz(MainActivity.this), RateMonitor.modeId(), uptime,
                    !on ? "未运行" : (LockService.isGameSuspended() ? "游戏接管·已暂停" : "运行中")));

            int changes = RateMonitor.changes();
            if (changes <= 0) {
                switchLine.setText("本次会话：暂无刷新率切换");
                switchLine.setTextColor(Ui.ACCENT);
            } else {
                switchLine.setText(String.format(Locale.US,
                        "本次会话检测到 %d 次刷新率切换", changes));
                switchLine.setTextColor(changes <= 2 ? Ui.WARN : Ui.DANGER);
            }

            primaryButton.setText(on ? "停止锁定" : "开启锁定");
            if (lastPrimaryOn != on) {
                lastPrimaryOn = on;
                primaryButton.setBackground(Ui.round(
                        on ? Ui.CARD_ALT : Ui.ACCENT, Ui.dp(MainActivity.this, 12),
                        Ui.dp(MainActivity.this, 1), on ? Ui.STROKE : Ui.ACCENT));
                primaryButton.setTextColor(on ? Ui.TEXT : 0xFF06120C);
            }

            boolean canOverlay = Settings.canDrawOverlays(MainActivity.this);
            overlayState.setText(canOverlay ? "已授权" : "未授权");
            overlayState.setTextColor(canOverlay ? Ui.ACCENT : Ui.DANGER);

            batteryState.setText(isIgnoringBattery() ? "已忽略" : "未设置");
            batteryState.setTextColor(isIgnoringBattery() ? Ui.ACCENT : Ui.WARN);

            if (tickCount % 4 == 0) {
                String sys = SysLock.hasPermission(MainActivity.this)
                        ? SysLock.read(MainActivity.this)
                        : "未授予 WRITE_SECURE_SETTINGS（用下面那条 adb 命令授权）";
                if (!sys.equals(sysState.getText().toString())) {
                    sysState.setText(sys);
                }

                String want = writeLabel();
                if (!want.equals(sysWriteBtn.getText().toString())) {
                    sysWriteBtn.setText(want);
                }

                boolean usage = ForegroundTracker.hasUsageAccess(MainActivity.this);
                boolean a11y = accessibilityEnabled();
                gamePermState.setText(usage
                        ? (a11y ? "已授权 · 无障碍已开" : "已授权")
                        : "未授权");
                gamePermState.setTextColor(usage ? Ui.ACCENT : Ui.WARN);
            }
            tickCount++;

            String fg = ForegroundTracker.current();
            if (fg == null) {
                fgLine.setText("当前前台：未识别（"
                        + (ForegroundTracker.hasUsageAccess(MainActivity.this)
                        ? "等待采样…" : "需要授予使用情况访问") + "）");
                fgLine.setTextColor(Ui.TEXT_DIM);
            } else {
                boolean isGame = GameDetector.classify(MainActivity.this, fg)
                        == GameDetector.KIND_GAME;
                fgLine.setText("当前前台：" + GameDetector.describe(MainActivity.this, fg)
                        + (LockService.isGameSuspended() ? " · 已暂停" : ""));
                fgLine.setTextColor(isGame ? Ui.WARN : Ui.TEXT_DIM);
            }

            String dump = Logbuf.dump();
            if (!dump.equals(lastLogDump)) {
                lastLogDump = dump;
                logView.setText(dump);
            }

            ui.postDelayed(this, 500L);
        }
    };

    // ------------------------------------------------------------------ 交互

    private void onPrimaryClick() {
        if (LockService.isRunning()) {
            LockService.stop(this);
            Logbuf.add("ui", "请求停止");
            ui.postDelayed(this::refreshSoon, 300);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("先给悬浮窗权限");
            requestOverlayPermission();
            return;
        }
        try {
            LockService.start(this);
            Logbuf.add("ui", "请求启动");
        } catch (Throwable t) {
            toast("启动失败: " + t.getMessage());
            Logbuf.add("ui", "启动失败: " + t);
        }
        ui.postDelayed(this::refreshSoon, 400);
    }

    private void refreshSoon() {
        if (tickerOn) {
            ui.removeCallbacks(tick);
            ui.post(tick);
        }
    }

    private void restartIfRunning() {
        if (!LockService.isRunning()) {
            return;
        }
        Intent i = new Intent(this, LockService.class)
                .setAction(LockService.ACTION_REFRESH);
        try {
            startForegroundService(i);
        } catch (Throwable t) {
            Logbuf.add("ui", "刷新失败: " + t);
        }
    }

    // ------------------------------------------------------------------ 游戏感知

    private void openUsageAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            toast("在列表里找到「120Hz 常驻」并允许");
        } catch (Throwable t) {
            toast("请到 设置 → 应用 → 特殊权限 → 使用情况访问 里手动开启");
        }
    }

    private void openAccessibility() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            toast("在「已下载的服务」里找到本应用并开启");
        } catch (Throwable t) {
            toast("请到 设置 → 无障碍 里手动开启");
        }
    }

    private boolean accessibilityEnabled() {
        try {
            String s = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null) {
                return false;
            }
            return s.toLowerCase(Locale.US).contains(getPackageName().toLowerCase(Locale.US));
        } catch (Throwable t) {
            return false;
        }
    }

    private void toggleCurrentForeground() {
        String pkg = ForegroundTracker.current();
        if (pkg == null) {
            toast("还没识别到前台应用，先授权使用情况访问");
            openUsageAccess();
            return;
        }
        if (pkg.equals(getPackageName())) {
            toast("当前就是本应用");
            return;
        }
        boolean added = Prefs.toggleGame(this, pkg);
        toast((added ? "已标记为游戏：" : "已取消标记：") + GameDetector.label(this, pkg));
        Logbuf.add("game", (added ? "手动加入名单 " : "手动移出名单 ") + pkg);
        refreshSoon();
    }

    private void requestOverlayPermission() {        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            toast("无法打开悬浮窗设置: " + t.getMessage());
        }
    }

    private boolean isIgnoringBattery() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private void requestBatteryExemption() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(
                        Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable t2) {
                toast("请到系统设置里手动忽略电池优化");
            }
        }
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            try {
                requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIFICATION);
            } catch (Throwable ignore) {
            }
        }
    }

    private String writeLabel() {
        return String.format(Locale.US, "写入 %.0fHz", Prefs.targetHz(this));
    }

    private void writeSysLock() {        String err = SysLock.apply(this, Prefs.targetHz(this));
        if (err == null) {
            toast("已写入，观察上面的 Hz 是否稳定在 120");
            RateMonitor.check("after-sys-write");
        } else {
            copyAdbCommand();
            toast("需要一次 adb 授权，命令已复制");
        }
    }

    private void clearSysLock() {
        String err = SysLock.clear(this);
        toast(err == null ? "已清除" : "未授权，无法清除");
        RateMonitor.check("after-sys-clear");
    }

    private void copyAdbCommand() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("adb", SysLock.GRANT_CMD));
            }
            toast("已复制到剪贴板");
        } catch (Throwable t) {
            toast(SysLock.GRANT_CMD);
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ 自检

    private void startSelfTest() {
        if (testing) {
            return;
        }
        testing = true;
        testMin = Float.MAX_VALUE;
        testMax = -1f;
        testSwitches = 0;
        lastTestHz = -1f;
        testStart = SystemClock.elapsedRealtime();
        selfTestButton.setText("测试中…");
        Logbuf.add("test", "开始 10 秒采样");
        ui.post(testTick);
    }

    private final Runnable testTick = new Runnable() {
        @Override
        public void run() {
            float hz = RateMonitor.hz();
            if (hz > 0) {
                testMin = Math.min(testMin, hz);
                testMax = Math.max(testMax, hz);
                if (lastTestHz > 0f && Math.abs(hz - lastTestHz) > 1f) {
                    testSwitches++;
                }
                lastTestHz = hz;
            }
            long elapsed = SystemClock.elapsedRealtime() - testStart;
            selfTestOut.setText(String.format(Locale.US,
                    "已采样 %.1f 秒 · 最低 %.1f Hz · 最高 %.1f Hz · 切换 %d 次",
                    elapsed / 1000f, testMin == Float.MAX_VALUE ? 0f : testMin,
                    Math.max(testMax, 0f), testSwitches));
            if (elapsed < 10_000L) {
                ui.postDelayed(this, 200L);
            } else {
                testing = false;
                selfTestButton.setText("再测一次");
                Logbuf.add("test", String.format(Locale.US,
                        "结果 min=%.1f max=%.1f switches=%d",
                        testMin, testMax, testSwitches));
            }
        }
    };
}
