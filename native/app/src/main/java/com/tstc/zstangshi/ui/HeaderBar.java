package com.tstc.zstangshi.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tstc.zstangshi.R;

/**
 * 二级页统一顶栏。
 *
 * 之前三个页面各自用 TextView 打「‹」：字号 26/28sp 的字体自带 ascent/descent 留白，
 * 箭头在 42~48dp 的方格里视觉上偏低偏左，标题又是另一套行高，于是「返回按钮和文字对不齐」。
 * 这里把箭头改成 Canvas 画的路径（几何居中，不受字体度量影响），并让箭头、「返回」、
 * 标题、右侧按钮共用同一行高 + includeFontPadding(false)，三个页面完全一致。
 */
public final class HeaderBar {

    private HeaderBar() {}

    /** 生成一行顶栏；右侧按钮用 {@link #action} 生成后 addView 追加即可。 */
    public static LinearLayout create(Activity host, String title, View.OnClickListener onBack) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(host, 8), dp(host, 6), dp(host, 12), dp(host, 6));
        row.setMinimumHeight(dp(host, 52));

        LinearLayout back = new LinearLayout(host);
        back.setOrientation(LinearLayout.HORIZONTAL);
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setPadding(dp(host, 6), 0, dp(host, 10), 0);
        back.setOnClickListener(onBack);
        back.addView(new Chevron(host, host.getColor(R.color.brand)),
                new LinearLayout.LayoutParams(dp(host, 15), dp(host, 22)));

        TextView label = new TextView(host);
        label.setText("返回");
        label.setTextSize(13);
        label.setIncludeFontPadding(false);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setTextColor(host.getColor(R.color.brand));
        label.setPadding(dp(host, 5), 0, 0, 0);
        back.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(host, 40)));
        row.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(host, 40)));

        TextView t = new TextView(host);
        t.setText(title);
        t.setTextSize(17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setIncludeFontPadding(false);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setTextColor(host.getColor(R.color.text_primary));
        t.setPadding(dp(host, 4), 0, 0, 0);
        row.addView(t, new LinearLayout.LayoutParams(0, dp(host, 40), 1f));
        return row;
    }

    /** 顶栏右侧的文字按钮（刷新等），和标题共用行高。 */
    public static TextView action(Activity host, String text, View.OnClickListener onClick) {
        TextView tv = new TextView(host);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setIncludeFontPadding(false);
        tv.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        tv.setTextColor(host.getColor(R.color.brand));
        tv.setPadding(dp(host, 12), 0, dp(host, 4), 0);
        tv.setMinWidth(dp(host, 44));
        tv.setOnClickListener(onClick);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(host, 40)));
        return tv;
    }

    /** 正文里的「‹ 重新筛选」之类次级返回链接，同样用绘制的箭头保证对齐。 */
    public static LinearLayout backLink(Activity host, String text, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(host, 2), 0, dp(host, 12));
        row.setOnClickListener(onClick);
        row.addView(new Chevron(host, host.getColor(R.color.brand)),
                new LinearLayout.LayoutParams(dp(host, 9), dp(host, 14)));

        TextView tv = new TextView(host);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setIncludeFontPadding(false);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setTextColor(host.getColor(R.color.brand));
        tv.setPadding(dp(host, 6), 0, 0, 0);
        row.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(host, 22)));
        return row;
    }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** 纯几何的「‹」，不受字体 ascent/descent 影响，永远在 View 正中。 */
    private static final class Chevron extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Chevron(Context c, int color) {
            super(c);
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                    2f, c.getResources().getDisplayMetrics()));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float arm = Math.min(getWidth(), getHeight()) * 0.30f;
            path.reset();
            path.moveTo(cx + arm * 0.62f, cy - arm);
            path.lineTo(cx - arm * 0.62f, cy);
            path.lineTo(cx + arm * 0.62f, cy + arm);
            canvas.drawPath(path, paint);
        }
    }
}
