package com.ltpo120hz;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 纯代码搭界面的小工具，避免引入 AppCompat / Material 依赖，
 * 这样编译产物小、构建快，也不会因为依赖版本问题构建失败。
 */
final class Ui {

    static final int BG = 0xFF0B0F14;
    static final int CARD = 0xFF141B23;
    static final int CARD_ALT = 0xFF1B242E;
    static final int ACCENT = 0xFF3DDC97;
    static final int TEXT = 0xFFE8EEF5;
    static final int TEXT_DIM = 0xFF8FA0B2;
    static final int STROKE = 0xFF2A3644;
    static final int WARN = 0xFFFFB020;
    static final int DANGER = 0xFFFF5C5C;

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable round(int fill, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(radiusPx);
        g.setColor(fill);
        return g;
    }

    static GradientDrawable round(int fill, float radiusPx, float strokePx, int strokeColor) {
        GradientDrawable g = round(fill, radiusPx);
        g.setStroke(Math.max(1, Math.round(strokePx)), strokeColor);
        return g;
    }

    static TextView text(Context c, CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(0f, 1.15f);
        return t;
    }

    static TextView bold(Context c, CharSequence s, float sp, int color) {
        TextView t = text(c, s, sp, color);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        l.setBackground(round(CARD, dp(c, 18), dp(c, 1), STROKE));
        return l;
    }

    static LinearLayout.LayoutParams lp(int widthDp, int heightDp, Context c) {
        return new LinearLayout.LayoutParams(
                widthDp < 0 ? widthDp : dp(c, widthDp),
                heightDp < 0 ? heightDp : dp(c, heightDp));
    }

    static LinearLayout.LayoutParams matchWrap(Context c, int topMarginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(c, topMarginDp);
        return p;
    }

    static View gap(Context c, int dpHeight) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, dpHeight)));
        return v;
    }

    static <T extends View> T margins(T v, Context c, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        v.setLayoutParams(p);
        return v;
    }

    /** 可点击的「按钮」，用 TextView 实现，样式完全可控。 */
    static TextView button(Context c, CharSequence label, boolean primary, View.OnClickListener l) {
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        int h = dp(c, 44);
        t.setMinHeight(h);
        t.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10));
        t.setTextColor(primary ? 0xFF06120C : TEXT);

        GradientDrawable bg = round(primary ? ACCENT : CARD_ALT, dp(c, 12),
                dp(c, 1), primary ? ACCENT : STROKE);
        RippleDrawable ripple = new RippleDrawable(
                ColorStateList.valueOf(primary ? 0x44000000 : 0x33FFFFFF), bg, null);
        t.setBackground(ripple);
        t.setClickable(true);
        t.setFocusable(true);
        t.setOnClickListener(l);
        return t;
    }

    /** 分段选择器（样式 / 位置）。返回那排按钮，便于外部改选中态。 */
    static LinearLayout segmented(Context c, String[] labels, int selected,
                                  OnSegmentClick cb) {
        LinearLayout row = row(c);
        row.setLayoutParams(matchWrap(c, 0));
        TextView[] buttons = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView t = new TextView(c);
            t.setText(labels[i]);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(c, 9), 0, dp(c, 9));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            p.setMargins(dp(c, 3), 0, dp(c, 3), 0);
            t.setLayoutParams(p);
            t.setClickable(true);
            t.setOnClickListener(v -> {
                cb.onClick(index);
                paintSegment(c, buttons, index);
            });
            buttons[i] = t;
            row.addView(t);
        }
        paintSegment(c, buttons, selected);
        return row;
    }

    interface OnSegmentClick {
        void onClick(int index);
    }

    private static void paintSegment(Context c, TextView[] btns, int selected) {
        for (int i = 0; i < btns.length; i++) {
            boolean on = i == selected;
            btns[i].setTextColor(on ? 0xFF06120C : TEXT_DIM);
            btns[i].setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            btns[i].setBackground(round(on ? ACCENT : CARD_ALT, dp(c, 10),
                    dp(c, 1), on ? ACCENT : STROKE));
        }
    }

    /** 一行「标题 —— 值」。 */
    static LinearLayout kv(Context c, String k, TextView value) {
        LinearLayout r = row(c);
        r.setLayoutParams(matchWrap(c, 6));
        TextView key = text(c, k, 13f, TEXT_DIM);
        r.addView(key, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(value);
        return r;
    }

    static TextView dim(Context c, CharSequence s, float sp) {
        return text(c, s, sp, TEXT_DIM);
    }
}
