package com.ltpo120hz;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * 可选的低延迟前台检测。开启后，游戏启动/退出的识别几乎是即时的，
 * 不用等 UsageStats 轮询。不开启也不影响主流程（会用轮询兜底）。
 * <p>
 * 只监听窗口状态变化，不读取任何界面内容。
 */
public final class GameSenseService extends AccessibilityService {

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        ForegroundTracker.init(this);
        Logbuf.add("a11y", "无障碍服务已连接");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence pkg = event.getPackageName();
        if (pkg != null) {
            ForegroundTracker.report(pkg.toString());
        }
    }

    @Override
    public void onInterrupt() {
        Logbuf.add("a11y", "无障碍服务被中断");
    }
}
