package com.tstc.zstangshi.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tstc.zstangshi.R;

/**
 * 应用内的确认弹窗。走的是和 {@link OptionSheet} 同一套底部弹层（{@link Sheet}）：
 * 圆角卡片从下面滑上来、顶部有那条小横杠，按钮是圆角行，不用系统的白底 AlertDialog。
 */
public final class ConfirmDialog {

    private ConfirmDialog() {
    }

    /** 两个按钮：取消 / 确认。onOk 在主线程回调。 */
    public static void show(Activity a, String title, String message,
                            String cancelText, String okText, Runnable onOk) {
        if (a == null || a.isFinishing()) return;
        final int dp = Math.round(a.getResources().getDisplayMetrics().density);

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp * 20, 0, dp * 20, dp * 12);

        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(a.getColor(R.color.text_primary));
        t.setPadding(dp * 2, dp * 4, 0, dp * 10);
        box.addView(t);

        if (message != null && !message.isEmpty()) {
            TextView m = new TextView(a);
            m.setText(message);
            m.setTextSize(13);
            m.setTextColor(a.getColor(R.color.text_secondary));
            m.setLineSpacing(dp * 3, 1f);
            m.setPadding(dp * 2, 0, dp * 2, dp * 14);
            box.addView(m);
        }

        final Sheet.Handle[] handle = new Sheet.Handle[1];
        // 主按钮在上，和弹层里「先看到要做什么、再看到算了」的顺序一致
        box.addView(row(a, okText, getColorSafe(a, R.color.brand), 0xFFFFFFFF, true, () -> {
            if (handle[0] != null) handle[0].dismiss();
            if (onOk != null) onOk.run();
        }));
        if (cancelText != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp * 8;
            box.addView(row(a, cancelText, getColorSafe(a, R.color.bg_sunken),
                    getColorSafe(a, R.color.text_secondary), false, () -> {
                        if (handle[0] != null) handle[0].dismiss();
                    }), lp);
        }

        int width = a.getResources().getDisplayMetrics().widthPixels;
        box.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        handle[0] = Sheet.show(a, box, box.getMeasuredHeight() + dp * 18);
    }

    /** 只有一个「知道了」的提示框。 */
    public static void alert(Activity a, String title, String message) {
        show(a, title, message, null, "知道了", null);
    }

    private static int getColorSafe(Activity a, int res) {
        return a.getColor(res);
    }

    private static TextView row(Activity a, String text, int bg, int fg,
                                boolean bold, Runnable onClick) {
        int dp = Math.round(a.getResources().getDisplayMetrics().density);
        TextView v = new TextView(a);
        v.setText(text);
        v.setTextSize(14.5f);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(fg);
        v.setPadding(dp * 14, dp * 13, dp * 14, dp * 13);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp * 12);
        v.setBackground(g);
        v.setClickable(true);
        v.setOnClickListener(x -> onClick.run());
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return v;
    }
}
