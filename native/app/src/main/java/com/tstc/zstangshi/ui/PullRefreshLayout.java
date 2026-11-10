package com.tstc.zstangshi.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tstc.zstangshi.R;

/**
 * 轻量下拉刷新容器（项目零第三方依赖，不能用 androidx SwipeRefreshLayout）。
 *
 * 只在成绩页这类「进页读一次缓存、想看新数据再手动拉」的页面用：
 * 内容区自带 ScrollView，顶部是一条高度随手指变化的提示条；滚到顶部继续下拉
 * 超过阈值就触发回调，松手不足阈值自动收回。不会拦截正常的列表滚动。
 *
 * 提示条是「左侧图标 + 右侧文字」：下拉时画一个跟随手势翻转的箭头，
 * 达到阈值翻成向上；真正刷新时换成 indeterminate 旋转进度圈（品牌色），
 * 不再是干巴巴一行文字。
 */
public class PullRefreshLayout extends LinearLayout {

    private final LinearLayout header;
    private final TextView label;
    private final ArrowView arrow;
    private final ProgressBar spinner;
    private final ScrollView scroll;
    private final int trigger;
    private final int maxPull;
    private final int busyHeight;

    private float downY;
    private float downX;
    private boolean dragging;
    private boolean refreshing;
    private Runnable onRefresh;

    public PullRefreshLayout(Context c) {
        super(c);
        setOrientation(LinearLayout.VERTICAL);
        float d = c.getResources().getDisplayMetrics().density;
        trigger = Math.round(62 * d);
        maxPull = Math.round(108 * d);
        busyHeight = Math.round(50 * d);

        header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER);
        header.setClickable(true);

        int icon = Math.round(22 * d);
        FrameLayout iconHost = new FrameLayout(c);
        arrow = new ArrowView(c, c.getColor(R.color.brand));
        spinner = new ProgressBar(c, null, android.R.attr.progressBarStyleSmall);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(c.getColor(R.color.brand)));
        spinner.setVisibility(GONE);
        iconHost.addView(arrow, new FrameLayout.LayoutParams(icon, icon, Gravity.CENTER));
        iconHost.addView(spinner, new FrameLayout.LayoutParams(icon, icon, Gravity.CENTER));
        header.addView(iconHost, new LinearLayout.LayoutParams(icon, icon));

        label = new TextView(c);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setTextSize(12.5f);
        label.setTextColor(c.getColor(R.color.text_secondary));
        label.setIncludeFontPadding(false);
        label.setText("下拉刷新");
        label.setPadding(Math.round(9 * d), 0, 0, 0);
        header.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addView(header, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));

        scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        addView(scroll, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    /** 把页面内容放进这个 ScrollView（而不是自己再建一个）。 */
    public ScrollView scrollView() {
        return scroll;
    }

    public void setOnRefresh(Runnable r) {
        onRefresh = r;
    }

    public void setRefreshing(boolean on) {
        refreshing = on;
        dragging = false;
        if (on) {
            arrow.setVisibility(GONE);
            spinner.setVisibility(VISIBLE);
        } else {
            spinner.setVisibility(GONE);
            arrow.setVisibility(VISIBLE);
            arrow.setRotation(0f);
        }
        setHeader(on ? busyHeight : 0, on ? "正在从教务刷新…" : "下拉刷新");
    }

    private void setHeader(int h, String text) {
        LayoutParams lp = (LayoutParams) header.getLayoutParams();
        lp.height = Math.max(0, h);
        header.setLayoutParams(lp);
        header.setAlpha(refreshing ? 1f : Math.min(1f, h / (float) trigger));
        label.setText(text);
        if (!refreshing) arrow.setRotation(h >= trigger ? 180f : 0f);
    }

    private int headerHeight() {
        return ((LayoutParams) header.getLayoutParams()).height;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (refreshing) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = ev.getY();
                downX = ev.getX();
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                float dy = ev.getY() - downY;
                float dx = ev.getX() - downX;
                // 必须是「已经在顶部 + 明显向下 + 以竖向为主」才抢手势
                if (dy > 12 && dy > Math.abs(dx) * 1.4f && scroll.getScrollY() <= 0) {
                    dragging = true;
                    return true;
                }
                break;
            default:
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!dragging) return super.onTouchEvent(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                int h = (int) Math.min(maxPull, (ev.getY() - downY) * 0.55f);
                setHeader(Math.max(0, h), h >= trigger ? "松开立即刷新" : "下拉刷新");
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean fire = headerHeight() >= trigger;
                dragging = false;
                if (fire && onRefresh != null) {
                    setRefreshing(true);
                    post(onRefresh);
                } else {
                    setHeader(0, "下拉刷新");
                }
                return true;
            default:
                return true;
        }
    }

    /** Canvas 画的向下箭头：几何居中，旋转 180° 就是「松开刷新」的向上箭头。 */
    private static final class ArrowView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        ArrowView(Context c, int color) {
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
            float arm = Math.min(getWidth(), getHeight()) * 0.24f;
            path.reset();
            path.moveTo(cx - arm, cy - arm * 0.55f);
            path.lineTo(cx, cy + arm * 0.55f);
            path.lineTo(cx + arm, cy - arm * 0.55f);
            canvas.drawPath(path, paint);
        }
    }
}
