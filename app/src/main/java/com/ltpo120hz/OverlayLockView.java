package com.ltpo120hz;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.os.Build;
import android.view.Choreographer;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.Locale;

/**
 * 真正的「锁频表面」。
 * <p>
 * 两个动作同时做：
 * <ol>
 *   <li>给这个 Surface 显式投票（{@code Surface.setFrameRate}，API 30+）。SurfaceFlinger
 *       汇总所有可见图层的投票后选 display mode，1×1 的悬浮窗也是可见图层，投票有效。</li>
 *   <li>每一帧都真的画一次并提交 buffer。因为多数 ROM 会把「长时间没有新帧」的图层判定为静止，
 *       然后把整体刷新率降下来；持续出帧才能把面板钉在高刷模式。</li>
 * </ol>
 * 图像内容可以做到人眼不可见（1×1 像素、alpha=1），所以不需要大面积的动画。
 */
public final class OverlayLockView extends SurfaceView
        implements SurfaceHolder.Callback, Choreographer.FrameCallback {

    public interface StatsListener {
        void onStats(float renderFps);
    }

    // 指示条的内部排版常量（单位 dp），尺寸计算和绘制都用它们，避免两边写歪
    private static final float PILL_TEXT_SP = 11f;
    private static final float PILL_TEXT_X_DP = 11f;
    private static final float PILL_RIGHT_PAD_DP = 7f;
    private static final float PILL_HEIGHT_DP = 26f;
    private static final float PILL_MIN_WIDTH_DP = 62f;

    /**
     * 按「挡位 · 帧率」的实际文字宽度算出指示条该多宽，避免右边留一大截空白。
     * LockService 创建窗口时调它，drawPill 用同一组常量绘制。
     */
    public static int[] pillSize(Context context, float hz) {
        float d = context.getResources().getDisplayMetrics().density;
        Paint probe = new Paint(Paint.ANTI_ALIAS_FLAG);
        probe.setTypeface(android.graphics.Typeface.MONOSPACE);
        probe.setTextSize(PILL_TEXT_SP * d);
        String sample = String.format(java.util.Locale.US, "%.0fHz · %.0f", hz, hz);
        int w = Math.round((PILL_TEXT_X_DP + PILL_RIGHT_PAD_DP) * d + probe.measureText(sample));
        int minW = Math.round(PILL_MIN_WIDTH_DP * d);
        return new int[]{Math.max(w, minW), Math.round(PILL_HEIGHT_DP * d)};
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;

    private float targetHz = 120f;
    private int style = Prefs.STYLE_INVISIBLE;
    private boolean active;
    private Choreographer choreographer;
    private StatsListener statsListener;

    private long frameCount;
    private long windowStartNanos;
    private float renderFps;
    private boolean frameRateApplied;
    private boolean chained;
    private int lockFailures;

    public OverlayLockView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setWillNotDraw(true);
        setZOrderOnTop(true);
        getHolder().addCallback(this);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
    }

    public void configure(float hz, int newStyle) {
        targetHz = hz;
        style = newStyle;
        frameRateApplied = false;
        applyFrameRate("configure");
        invalidate();
    }

    public void setStatsListener(StatsListener l) {
        statsListener = l;
    }

    public float renderFps() {
        return renderFps;
    }

    public void start() {
        if (active) {
            return;
        }
        active = true;
        applyFrameRate("start");
        ensureChain();
        Logbuf.add("lock", String.format(Locale.US,
                "开始出帧 (目标 %.0fHz, 样式 %d)", targetHz, style));
    }

    public void stop() {
        if (!active) {
            return;
        }
        active = false;
        chained = false;
        choreographer().removeFrameCallback(this);
        Logbuf.add("lock", "停止出帧");
    }

    /** 守护线程在检测到刷新率掉下去时重新投票一次。 */
    public void reassert(String why) {
        frameRateApplied = false;
        applyFrameRate(why);
    }

    private Choreographer choreographer() {
        if (choreographer == null) {
            choreographer = Choreographer.getInstance();
        }
        return choreographer;
    }

    /** 用 chained 标记保证「帧链」只有一条：重复排帧会让每帧画两次。 */
    private void ensureChain() {
        if (!active || chained) {
            return;
        }
        chained = true;
        choreographer().postFrameCallback(this);
    }

    private void applyFrameRate(String why) {
        if (Build.VERSION.SDK_INT < 30) {
            return;
        }
        Surface s = getHolder().getSurface();
        if (s == null || !s.isValid()) {
            return;
        }
        try {
            s.setFrameRate(targetHz,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ALWAYS);
            if (!frameRateApplied) {
                Logbuf.add("lock", String.format(Locale.US,
                        "Surface.setFrameRate(%.0f, FIXED_SOURCE, ALWAYS) @%s", targetHz, why));
                frameRateApplied = true;
            }
        } catch (Throwable t) {
            Logbuf.add("lock", "setFrameRate 失败: " + t);
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!active) {
            chained = false;
            return;
        }
        drawOnce();
        frameCount++;
        if (windowStartNanos == 0L) {
            windowStartNanos = frameTimeNanos;
        } else {
            long elapsed = frameTimeNanos - windowStartNanos;
            if (elapsed >= 1_000_000_000L) {
                renderFps = frameCount * 1_000_000_000f / elapsed;
                frameCount = 0L;
                windowStartNanos = frameTimeNanos;
                StatsListener l = statsListener;
                if (l != null) {
                    l.onStats(renderFps);
                }
            }
        }
        if (active) {
            // chained 保持为 true，这里只续一条链
            choreographer().postFrameCallback(this);
        }
    }

    private void drawOnce() {
        SurfaceHolder holder = getHolder();
        Surface s = holder.getSurface();
        if (s == null || !s.isValid()) {
            return;
        }
        Canvas canvas;
        try {
            canvas = holder.lockCanvas();
        } catch (Throwable t) {
            onLockFailed("lockCanvas 异常: " + t);
            return;
        }
        if (canvas == null) {
            onLockFailed("lockCanvas 返回 null");
            return;
        }
        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            switch (style) {
                case Prefs.STYLE_DOT:
                    drawDot(canvas);
                    break;
                case Prefs.STYLE_PILL:
                    drawPill(canvas);
                    break;
                default:
                    drawInvisible(canvas);
                    break;
            }
        } finally {
            try {
                holder.unlockCanvasAndPost(canvas);
            } catch (Throwable ignore) {
                // 表面在释放过程中，忽略
            }
        }
    }

    /** 人眼不可见：整块画成 alpha=1/255 的纯黑，只为了让图层持续有内容。 */
    private void drawInvisible(Canvas canvas) {
        canvas.drawColor(0x01000000);
    }

    private void drawDot(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float r = Math.min(w, h) / 2f - 1.5f * density;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x33000000);
        canvas.drawCircle(cx, cy, r, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.2f * density);
        paint.setColor(0xCC3DDC97);
        canvas.drawCircle(cx, cy, r, paint);

        // 一个小指针，缓慢旋转，让「还在动」这件事肉眼可辨
        double angle = (System.nanoTime() % 4_000_000_000L) / 4_000_000_000d * Math.PI * 2;
        float ex = cx + (float) Math.cos(angle) * r * 0.6f;
        float ey = cy + (float) Math.sin(angle) * r * 0.6f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF3DDC97);
        canvas.drawCircle(ex, ey, 1.6f * density, paint);
    }

    private void drawPill(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        rect.set(0.5f * density, 0.5f * density, w - 0.5f * density, h - 0.5f * density);
        float radius = rect.height() / 2f;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xC0000000);
        canvas.drawRoundRect(rect, radius, radius, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f * density);
        paint.setColor(0x403DDC97);
        canvas.drawRoundRect(rect, radius, radius, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF3DDC97);
        canvas.drawCircle(2.0f * density + 4f * density, h / 2f, 2.4f * density, paint);

        paint.setColor(0xFFE8EEF5);
        paint.setTextSize(PILL_TEXT_SP * density);
        paint.setTypeface(android.graphics.Typeface.MONOSPACE);
        paint.setTextAlign(Paint.Align.LEFT);
        String label = String.format(Locale.US, "%.0fHz · %.0f", targetHz, renderFps);
        float textX = PILL_TEXT_X_DP * density;
        float textY = h / 2f - (paint.descent() + paint.ascent()) / 2f;
        canvas.drawText(label, textX, textY, paint);
    }

    private void onLockFailed(String msg) {
        lockFailures++;
        if (lockFailures == 1 || lockFailures % 300 == 0) {
            Logbuf.add("lock", msg + " (累计 " + lockFailures + ")");
        }
    }

    // ---------------------------------------------------------------- 回调

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Logbuf.add("lock", "surfaceCreated");
        frameRateApplied = false;
        applyFrameRate("surfaceCreated");
        if (active) {
            ensureChain();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        applyFrameRate("surfaceChanged");
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Logbuf.add("lock", "surfaceDestroyed");
    }
}
