package com.zhiyu.dna.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.widget.FrameLayout;

/**
 * 液态玻璃卡片 —— 移植 Liquidglass.js 的渲染配方:
 *  1. 折射: 把捕获的背景位图按卡片位置画进圆角内, 轻微放大(玻璃厚度感)
 *  2. 顶部高光渐变 + 上缘镜面高光弧(等效 specular highlight)
 *  3. 底部柔和阴影 + 1px 内描边(等效 outline)
 *  4. 连续圆角(squircle 近似)
 */
public class GlassCard extends FrameLayout {

    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint specularPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF bgRect = new RectF();
    private final Path path = new Path();
    private float radius = dp(28);
    private float refraction = 1.045f;   // 背景放大比例(折射厚度)

    private GlassScene scene;

    public GlassCard(Context context) {
        super(context);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setWillNotDraw(false);
        bgPaint.setFilterBitmap(true);
        bgPaint.setDither(true);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(1.1f));
        strokePaint.setColor(0xD9FFFFFF);
        cardPaint.setStyle(Paint.Style.FILL);
        cardPaint.setColor(0x8CFFFFFF); // 半透明白(当无背景位图时的兜底)
        shadowPaint.setStyle(Paint.Style.FILL);
        shadowPaint.setColor(0x1A000000);
        highlightPaint.setStyle(Paint.Style.FILL);
        specularPaint.setStyle(Paint.Style.STROKE);
        specularPaint.setStrokeWidth(dp(3));
        specularPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    /** 绑定场景容器, 用于折射采样 */
    public void attachScene(GlassScene s) {
        this.scene = s;
    }

    public GlassCard setRadius(float radiusDp) {
        this.radius = dp(radiusDp);
        invalidate();
        return this;
    }

    public GlassCard setRefraction(float scale) {
        this.refraction = scale;
        invalidate();
        return this;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // 顶部高光
        highlightPaint.setShader(new LinearGradient(0, 0, 0, h * 0.5f,
                new int[]{0x78FFFFFF, 0x28FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        // 上缘镜面高光: 白→透明
        specularPaint.setShader(new LinearGradient(0, 0, 0, h * 0.28f,
                0xE6FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        rect.set(dp(6), dp(8), w - dp(6), h - dp(2));
        path.reset();
        path.addRoundRect(rect, radius, radius, Path.Direction.CW);

        // 1) 折射: 采样背景位图
        Bitmap cache = scene != null ? scene.getCache() : null;
        if (cache != null) {
            int[] loc = new int[2];
            getLocationInWindow(loc);
            int[] sceneLoc = new int[2];
            scene.getLocationInWindow(sceneLoc);
            int dx = loc[0] - sceneLoc[0];
            int dy = loc[1] - sceneLoc[1];
            if (dx < 0) dx = 0;
            if (dy < 0) dy = 0;
            float scale = cache.getWidth() / (float) Math.max(1, scene.getWidth());
            float srcL = dx * scale, srcT = dy * scale;
            float srcR = srcL + w * scale, srcB = srcT + h * scale;
            if (srcR > cache.getWidth()) srcR = cache.getWidth();
            if (srcB > cache.getHeight()) srcB = cache.getHeight();

            // 玻璃内放大(折射厚度感) + 轻微偏移制造内阴影
            float cx = rect.centerX(), cy = rect.centerY();
            float halfW = rect.width() / 2f, halfH = rect.height() / 2f;
            bgRect.set(cx - halfW * refraction, cy - halfH * refraction,
                    cx + halfW * refraction, cy + halfH * refraction);

            canvas.save();
            canvas.clipPath(path);
            canvas.drawBitmap(cache, new android.graphics.Rect((int) srcL, (int) srcT,
                            (int) srcR, (int) srcB), bgRect, bgPaint);
            // 玻璃罩白
            canvas.drawRect(rect, cardPaint);
            // 顶部高光
            canvas.drawRect(rect.left, rect.top, rect.right, rect.top + h * 0.5f, highlightPaint);
            // 底部轻微变暗(折射边缘)
            canvas.restore();
        } else {
            // 无背景时: 纯玻璃质感兜底
            cardPaint.setColor(0xAFFFFFFF);
            canvas.drawPath(path, cardPaint);
            canvas.save();
            canvas.clipPath(path);
            canvas.drawRect(rect.left, rect.top, rect.right, rect.top + h * 0.5f, highlightPaint);
            canvas.restore();
        }

        // 2) 阴影(向下偏移)
        canvas.save();
        canvas.translate(0, dp(7));
        Path shadow = new Path();
        shadow.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.drawPath(shadow, shadowPaint);
        canvas.restore();

        // 3) 上缘镜面高光弧
        canvas.save();
        canvas.clipPath(path);
        RectF arcRect = new RectF(rect.left + dp(2), rect.top - dp(6), rect.right - dp(2), rect.top + h * 0.28f);
        canvas.drawRoundRect(arcRect, radius, radius, specularPaint);
        canvas.restore();

        // 4) 内描边
        strokePaint.setColor(0xD9FFFFFF);
        canvas.drawPath(path, strokePaint);
    }
}
