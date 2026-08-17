package com.zhiyu.dna.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

import java.util.Random;

/**
 * 液态玻璃背景: 浅色渐变 + 缓慢漂移的柔光色块。
 * 提供 drawTo() 供 GlassScene 捕获为低分辨率位图(玻璃折射采样用)。
 */
public class AnimatedBackground extends View {

    // 视频1精确配色: 浅灰蓝垂直渐变(无彩色光斑)
    private static final int[] BG_COLORS = {0xFFD1D7DE, 0xFFE1E7ED, 0xFFECF2F7};
    private static final int[][] BLOB_COLORS = {};   // 不画光斑

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint[] blobPaints = new Paint[BLOB_COLORS.length];
    private final float[] blobX = new float[BLOB_COLORS.length];
    private final float[] blobY = new float[BLOB_COLORS.length];
    private final float[] blobR = new float[BLOB_COLORS.length];
    private final float[] speedX = new float[BLOB_COLORS.length];
    private final float[] speedY = new float[BLOB_COLORS.length];
    private final Random rnd = new Random();
    private long lastT = 0;
    private Runnable frameCallback;

    public AnimatedBackground(Context context) {
        super(context);
        for (int i = 0; i < BLOB_COLORS.length; i++) {
            blobPaints[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
            blobX[i] = rnd.nextFloat() * 1080f;
            blobY[i] = 200 + rnd.nextFloat() * 1800f;
            blobR[i] = 220 + rnd.nextFloat() * 320f;
            speedX[i] = (rnd.nextFloat() - 0.5f) * 14f;
            speedY[i] = (rnd.nextFloat() - 0.5f) * 10f;
        }
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        bgPaint.setShader(new LinearGradient(0, 0, w, h, BG_COLORS, null, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = System.currentTimeMillis();
        if (lastT == 0) lastT = now;
        float dt = Math.min(64f, now - lastT) / 1000f;
        lastT = now;
        tick(dt);
        drawTo(canvas);
        if (frameCallback != null) frameCallback.run();
        postInvalidateOnAnimation();
    }

    public void setFrameCallback(Runnable r) {
        this.frameCallback = r;
    }

    /** 推进时间(不绘制) */
    public void tick(float dt) {
        for (int i = 0; i < BLOB_COLORS.length; i++) {
            blobX[i] += speedX[i] * dt;
            blobY[i] += speedY[i] * dt;
            if (blobX[i] < -blobR[i]) blobX[i] = getWidth() + blobR[i];
            if (blobX[i] > getWidth() + blobR[i]) blobX[i] = -blobR[i];
            if (blobY[i] < -blobR[i]) blobY[i] = getHeight() + blobR[i];
            if (blobY[i] > getHeight() + blobR[i]) blobY[i] = -blobR[i];
        }
    }

    /** 绘制当前状态到任意画布(供 GlassScene 捕获) */
    public void drawTo(Canvas canvas) {
        canvas.drawRect(0, 0, getWidth(), getHeight(), bgPaint);
        for (int i = 0; i < BLOB_COLORS.length; i++) {
            blobPaints[i].setShader(new RadialGradient(0, 0, blobR[i],
                    BLOB_COLORS[i][0], BLOB_COLORS[i][1], Shader.TileMode.CLAMP));
            canvas.save();
            canvas.translate(blobX[i], blobY[i]);
            canvas.drawCircle(0, 0, blobR[i], blobPaints[i]);
            canvas.restore();
        }
    }
}
