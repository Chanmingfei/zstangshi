package com.tstc.zstangshi.ui;

import android.app.Activity;
import android.os.Build;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

/**
 * 预测性返回（predictive back）的薄封装，只用 framework API。
 *
 * Android 13（API 33）起，返回手势走 {@link OnBackInvokedDispatcher}；
 * Android 14（API 34）起多了 {@link OnBackAnimationCallback}，手势还没松手就会
 * 持续回调 onBackProgressed —— 这就是「跟手动画」的来源。
 *
 * 用法：不在需要接管返回时调用 {@link #uninstall}，交给系统做默认的退出动画。
 * 所有 API 33 以下一律短路返回 null，调用方按老路子走 onBackPressed。
 */
public final class BackGestures {

    /** 调用方只关心这四个时机。 */
    public interface Handler {
        /** 手势被系统认定为返回，开始跟手。 */
        void onStart();

        /** 手指当前位置对应的进度（0 = 刚开始，1 = 已到触发阈值）。 */
        void onProgress(float progress);

        /** 松手，返回已触发。 */
        void onCommit();

        /** 手势被取消（手指划回去了）。 */
        void onCancel();
    }

    private BackGestures() {
    }

    public static boolean supported() {
        return Build.VERSION.SDK_INT >= 33;
    }

    /**
     * 注册回调，返回值下一次 {@link #uninstall} 时原样传回来。
     * 返回 null 表示这台机器/这个版本不支持，调用方不用管。
     */
    public static Object install(Activity a, Handler h) {
        if (Build.VERSION.SDK_INT < 33 || a == null || h == null) return null;
        try {
            OnBackInvokedDispatcher d = a.getOnBackInvokedDispatcher();
            if (d == null) return null;
            // 34 起用带进度的回调，33 只有「已触发」这一下
            OnBackInvokedCallback cb = (Build.VERSION.SDK_INT >= 34)
                    ? new Animated(h) : new Plain(h);
            d.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
            return cb;
        } catch (Throwable t) {
            // 某些定制 ROM 的 dispatcher 会抛，退回 onBackPressed 兜底即可
            return null;
        }
    }

    public static void uninstall(Activity a, Object token) {
        if (token == null || Build.VERSION.SDK_INT < 33 || a == null) return;
        try {
            a.getOnBackInvokedDispatcher()
                    .unregisterOnBackInvokedCallback((OnBackInvokedCallback) token);
        } catch (Throwable ignored) {
        }
    }

    /** Android 13：只有「触发」这一个回调。 */
    private static final class Plain implements OnBackInvokedCallback {
        private final Handler h;

        Plain(Handler h) {
            this.h = h;
        }

        @Override
        public void onBackInvoked() {
            h.onCommit();
        }
    }

    /** Android 14+：手势过程中持续下发进度，动画可以跟着手指走。 */
    private static final class Animated implements OnBackAnimationCallback {
        private final Handler h;

        Animated(Handler h) {
            this.h = h;
        }

        @Override
        public void onBackStarted(BackEvent e) {
            h.onStart();
        }

        @Override
        public void onBackProgressed(BackEvent e) {
            float p = 0f;
            try {
                p = e.getProgress();
            } catch (Throwable ignored) {
            }
            h.onProgress(Math.max(0f, Math.min(1f, p)));
        }

        @Override
        public void onBackInvoked() {
            h.onCommit();
        }

        @Override
        public void onBackCancelled() {
            h.onCancel();
        }
    }
}
