package com.tstc.zstangshi.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tstc.zstangshi.R;

import java.util.Locale;

/**
 * 统一样式的选择列表（替代系统的 AlertDialog.setItems）。
 *
 * 为什么不用系统的：黑底白字的单选弹窗跟这套浅色卡片完全不搭，圆角、行高、选中态
 * 都控制不了。这里换成底部弹层里的一列圆角行，选中的那行是品牌色淡底 + 右侧对勾。
 */
public final class OptionSheet {

    public interface OnPick {
        void onPick(int index);
    }

    private OptionSheet() {}

    /** 超过这个数量就带搜索框，避免一次建两千多个 View（教务班级字典 2198 项）。 */
    private static final int SEARCH_FROM = 60;
    private static final int MAX_ROWS = 120;

    public static void show(Activity host, String title, String[] options, int selected, OnPick pick) {
        float d = host.getResources().getDisplayMetrics().density;
        int dp = Math.round(d);

        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp * 20, 0, dp * 20, dp * 16);

        TextView t = new TextView(host);
        t.setText(title);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(host.getColor(R.color.text_primary));
        t.setPadding(dp * 2, dp * 4, 0, dp * 10);
        box.addView(t);

        final boolean searchable = options.length > SEARCH_FROM;
        final EditText input = new EditText(host);
        if (searchable) {
            input.setHint("输入关键字筛选（共 " + options.length + " 项）");
            input.setTextSize(13);
            input.setSingleLine(true);
            input.setTextColor(host.getColor(R.color.text_primary));
            input.setHintTextColor(host.getColor(R.color.text_tertiary));
            input.setPadding(dp * 12, dp * 10, dp * 12, dp * 10);
            GradientDrawable ig = new GradientDrawable();
            ig.setColor(host.getColor(R.color.bg_sunken));
            ig.setCornerRadius(dp * 10);
            input.setBackground(ig);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ilp.bottomMargin = dp * 10;
            box.addView(input, ilp);
        }

        ScrollView sc = new ScrollView(host);
        LinearLayout list = new LinearLayout(host);
        list.setOrientation(LinearLayout.VERTICAL);
        sc.addView(list);

        final Sheet.Handle[] handle = new Sheet.Handle[1];
        final Runnable fill = () -> {
            list.removeAllViews();
            String q = searchable
                    ? input.getText().toString().trim().toLowerCase(Locale.ROOT) : "";
            int shown = 0;
            for (int i = 0; i < options.length && shown < MAX_ROWS; i++) {
                if (!q.isEmpty() && !options[i].toLowerCase(Locale.ROOT).contains(q)) continue;
                boolean on = i == selected;
                LinearLayout row = new LinearLayout(host);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp * 14, dp * 13, dp * 14, dp * 13);

                GradientDrawable g = new GradientDrawable();
                g.setColor(host.getColor(on ? R.color.brand_soft : R.color.bg_sunken));
                g.setCornerRadius(dp * 12);
                row.setBackground(g);

                TextView label = new TextView(host);
                label.setText(options[i]);
                label.setTextSize(14.5f);
                label.setTextColor(host.getColor(on ? R.color.brand_on_soft : R.color.text_primary));
                if (on) label.setTypeface(Typeface.DEFAULT_BOLD);
                row.addView(label, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                if (on) {
                    TextView tick = new TextView(host);
                    tick.setText("✓");
                    tick.setTextSize(14);
                    tick.setTypeface(Typeface.DEFAULT_BOLD);
                    tick.setTextColor(host.getColor(R.color.brand));
                    row.addView(tick);
                }

                final int idx = i;
                row.setOnClickListener(v -> {
                    if (handle[0] != null) handle[0].dismiss();
                    if (pick != null) pick.onPick(idx);
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = dp * 8;
                list.addView(row, lp);
                shown++;
            }
            if (shown == 0 || (searchable && q.isEmpty() && options.length > MAX_ROWS)) {
                TextView hint = new TextView(host);
                hint.setText(shown == 0
                        ? "没有匹配项，换个关键字试试"
                        : "仅列出前 " + MAX_ROWS + " 项，输入关键字可缩小范围");
                hint.setTextSize(12);
                hint.setGravity(Gravity.CENTER);
                hint.setTextColor(host.getColor(R.color.text_tertiary));
                hint.setPadding(0, dp * 10, 0, dp * 10);
                list.addView(hint);
            }
        };
        if (searchable) {
            input.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) { fill.run(); }
            });
        }
        fill.run();

        box.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        int rows = Math.min(options.length, 7);
        int h = dp * (74 + rows * 56 + (searchable ? 62 : 0));
        int cap = Math.round(host.getResources().getDisplayMetrics().heightPixels * 0.7f);
        handle[0] = Sheet.show(host, box, Math.min(h, cap));
    }

    /** 选项多的时候只要标题 + 一列，高度交给 Sheet 自己收。 */
    static int wrapHeight(Activity host, int rows) {
        float d = host.getResources().getDisplayMetrics().density;
        return Math.round((74 + rows * 56) * d);
    }

    /**
     * 二次确认也用同一套皮肤（系统的 AlertDialog 在这套浅色界面里太出戏）。
     *
     * @param danger true 时确定按钮是红底，删除类操作用
     */
    public static void confirm(Activity host, String title, String message,
                               String positive, boolean danger, Runnable onYes) {
        int dp = Math.round(host.getResources().getDisplayMetrics().density);

        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp * 22, 0, dp * 22, dp * 18);

        android.widget.TextView t = new android.widget.TextView(host);
        t.setText(title);
        t.setTextSize(16);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(host.getColor(R.color.text_primary));
        t.setPadding(dp * 2, dp * 4, 0, dp * 8);
        box.addView(t);

        android.widget.TextView m = new android.widget.TextView(host);
        m.setText(message);
        m.setTextSize(13);
        m.setLineSpacing(dp * 4, 1f);
        m.setTextColor(host.getColor(R.color.text_secondary));
        m.setPadding(dp * 2, 0, 0, dp * 20);
        box.addView(m);

        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);

        final Sheet.Handle[] handle = new Sheet.Handle[1];

        android.widget.TextView cancel = new android.widget.TextView(host);
        cancel.setText("取消");
        cancel.setTextSize(14);
        cancel.setGravity(android.view.Gravity.CENTER);
        cancel.setTextColor(host.getColor(R.color.text_secondary));
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(host.getColor(R.color.bg_sunken));
        cg.setCornerRadius(dp * 12);
        cancel.setBackground(cg);
        cancel.setOnClickListener(v -> {
            if (handle[0] != null) handle[0].dismiss();
        });
        row.addView(cancel, new LinearLayout.LayoutParams(0, dp * 46, 1f));

        android.widget.TextView ok = new android.widget.TextView(host);
        ok.setText(positive);
        ok.setTextSize(14);
        ok.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        ok.setGravity(android.view.Gravity.CENTER);
        ok.setTextColor(0xFFFFFFFF);
        GradientDrawable og = new GradientDrawable();
        og.setColor(danger ? 0xFFE0533F : host.getColor(R.color.brand));
        og.setCornerRadius(dp * 12);
        ok.setBackground(og);
        ok.setOnClickListener(v -> {
            if (handle[0] != null) handle[0].dismiss();
            if (onYes != null) onYes.run();
        });
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(0, dp * 46, 1f);
        okLp.leftMargin = dp * 10;
        row.addView(ok, okLp);

        box.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        handle[0] = Sheet.show(host, box, dp * 182);
    }
}
