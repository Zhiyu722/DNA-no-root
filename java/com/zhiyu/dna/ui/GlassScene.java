package com.zhiyu.dna.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.widget.FrameLayout;

/**
 * 玻璃场景容器: 持有动画背景, 定时(约 12fps)把背景渲染到低分辨率位图,
 * 供 GlassCard 做"折射采样"(把背景画进玻璃内部, 等效毛玻璃)。
 */
public class GlassScene extends FrameLayout {

    private final AnimatedBackground bg;
    private volatile Bitmap bgCache;
    private long lastCapture;
    private final Object lock = new Object();
    private final Runnable captureTask = this::capture;

    public GlassScene(Context context) {
        super(context);
        setWillNotDraw(true);
        bg = new AnimatedBackground(context);
        addView(bg, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    public AnimatedBackground getBackgroundView() {
        return bg;
    }

    /** 请求一帧背景捕获(由动画驱动调用) —— 降频防大屏卡死 */
    public void requestCapture() {
        postDelayed(captureTask, 250); // ~4fps, 大屏(平板)也不会卡主线程
    }

    private void capture() {
        long now = System.currentTimeMillis();
        if (now - lastCapture < 60) return;
        lastCapture = now;
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        int cw = Math.max(1, w / 4), ch = Math.max(1, h / 4);
        synchronized (lock) {
            if (bgCache == null || bgCache.getWidth() != cw || bgCache.getHeight() != ch) {
                if (bgCache != null) bgCache.recycle();
                bgCache = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
            }
            Canvas c = new Canvas(bgCache);
            c.scale(cw / (float) w, ch / (float) h);
            bg.drawTo(c);
        }
    }

    public Bitmap getCache() {
        return bgCache;
    }

    public void recycle() {
        synchronized (lock) {
            if (bgCache != null) {
                bgCache.recycle();
                bgCache = null;
            }
        }
    }
}
