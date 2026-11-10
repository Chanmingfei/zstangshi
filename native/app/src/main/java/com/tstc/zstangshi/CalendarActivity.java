package com.tstc.zstangshi;

import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tstc.zstangshi.data.Store;
import com.tstc.zstangshi.pages.CalendarPage;

/**
 * 校历（从首页宫格进）。
 *
 * 底栏只有四个位置，校历这种「一周看一次」的东西放进去太占地方，
 * 所以它是个独立页面，返回就回到首页。
 */
public class CalendarActivity extends BaseActivity {

    /** 整页的圆角卡片进出场（见 ui/PageSlide）。 */
    private com.tstc.zstangshi.ui.PageSlide slide;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(pageColor());

        // 顶栏：与功能页 / WebView 页共用 HeaderBar，箭头与标题严格同一行高
        LinearLayout bar = com.tstc.zstangshi.ui.HeaderBar
                .create(this, "校历", v -> closePage());

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        CalendarPage page = new CalendarPage(this, new Store(this));
        root.addView(page, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        applyInsets(root);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);

        // 整页圆角卡片进场：与系统预测性返回动画同一个圆角半径
        slide = new com.tstc.zstangshi.ui.PageSlide(this, pageColor());
        slide.enter();
        slide.attachBack();
    }

    private void closePage() {
        if (slide != null) {
            slide.finish();
        } else {
            finish();
        }
    }

    /**
     * 13 起返回手势由系统接管（系统自己会把页面缩成圆角卡片退场），别叠动画；
     * 老系统才用我们自己的滑动出场。
     */
    @Override
    public void onBackPressed() {
        if (slide != null && !com.tstc.zstangshi.ui.BackGestures.supported()) {
            slide.finish();
            return;
        }
        super.onBackPressed();
    }

    private void applyInsets(View root) {
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(0, bars.top, 0, bars.bottom);
                return insets;
            });
        } else {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            root.setPadding(0, id > 0 ? getResources().getDimensionPixelSize(id) : 0, 0, 0);
        }
    }
}
