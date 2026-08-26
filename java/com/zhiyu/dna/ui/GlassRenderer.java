package com.zhiyu.dna.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * 液态玻璃渲染器 —— 移植自 AndroidLiquidGlass(Kyant0) 的 SDF 渲染原理:
 *  1. 折射: SDF 边缘采样偏移(类 AGSL shader 的 refractionHeight)
 *  2. 镜面高光: 基于 SDF normal 的光照方向计算
 *  3. 斜向光泽(bevel): 边缘倒角光效
 *  4. 柔和阴影 + 内描边
 *
 *  注意: 原库使用 Compose + RuntimeShader(AGSL), 本实现将相同算法移植到 Canvas 2D。
 */
public final class GlassRenderer {

    private GlassRenderer() {}

    public static void drawGlass(Canvas canvas, GlassScene scene, View self,
                                 RectF rect, float radius, Paint shadowPaint) {
        float w = self.getWidth(), h = self.getHeight();
        float cx = rect.centerX(), cy = rect.centerY();
        float hw = rect.width() / 2f, hh = rect.height() / 2f;

        // 阴影(向下偏移)
        Path shadow = new Path();
        shadow.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.drawPath(shadow, shadowPaint);

        Path path = new Path();
        path.addRoundRect(rect, radius, radius, Path.Direction.CW);

        // 1) 折射: 采样背景位图, 边缘偏移(模拟 SDF refraction)
        Bitmap cache = scene != null ? scene.getCache() : null;
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setFilterBitmap(true);
        if (cache != null) {
            int[] loc = new int[2];
            self.getLocationInWindow(loc);
            int[] sceneLoc = new int[2];
            scene.getLocationInWindow(sceneLoc);
            int dx = Math.max(0, loc[0] - sceneLoc[0]);
            int dy = Math.max(0, loc[1] - sceneLoc[1]);
            float scale = cache.getWidth() / (float) Math.max(1, scene.getWidth());
            float srcL = dx * scale, srcT = dy * scale;
            float srcR = Math.min(cache.getWidth(), srcL + w * scale);
            float srcB = Math.min(cache.getHeight(), srcT + h * scale);

            // SDF 折射: 中心放大 + 边缘偏移模拟玻璃厚度
            float refr = 1.08f;
            RectF dst = new RectF(cx - hw * refr, cy - hh * refr, cx + hw * refr, cy + hh * refr);
            canvas.save();
            canvas.clipPath(path);
            canvas.drawBitmap(cache, new android.graphics.Rect((int) srcL, (int) srcT,
                    (int) srcR, (int) srcB), dst, bgPaint);

            // 玻璃罩白(半透明基底)
            Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            cardPaint.setColor(0x88FFFFFF);
            canvas.drawRect(rect, cardPaint);

            // 2) 镜面高光: SDF normal 方向光照(模拟 AGSL shader 的 bevelIntensity)
            // 顶部高光渐变
            Paint hl = new Paint(Paint.ANTI_ALIAS_FLAG);
            hl.setShader(new LinearGradient(0, rect.top, 0, rect.top + h * 0.5f,
                    new int[]{0xCCFFFFFF, 0x44FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.3f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(rect.left, rect.top, rect.right, rect.top + h * 0.5f, hl);

            // 顶部亮线(镜面边缘)
            Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
            edge.setStrokeWidth(Math.max(1.6f, h * 0.03f));
            edge.setStrokeCap(Paint.Cap.ROUND);
            edge.setColor(0xE6FFFFFF);
            RectF topLine = new RectF(rect.left + h * 0.5f, rect.top + h * 0.045f,
                    rect.right - h * 0.5f, rect.top + h * 0.09f);
            canvas.drawRoundRect(topLine, h * 0.03f, h * 0.03f, edge);

            // 3) 斜向光泽(bevel/sheen)
            Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
            sheen.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                    new int[]{0x00FFFFFF, 0x30FFFFFF, 0x00FFFFFF},
                    new float[]{0.30f, 0.52f, 0.74f}, Shader.TileMode.CLAMP));
            canvas.drawRect(rect, sheen);

            // 边缘内阴影(玻璃厚度折射)
            Paint vignette = new Paint(Paint.ANTI_ALIAS_FLAG);
            vignette.setShader(new RadialGradient(cx, cy, Math.max(hw, hh) * 0.95f,
                    new int[]{0x00000000, 0x18000000},
                    new float[]{0.72f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(rect, vignette);
            canvas.restore();
        } else {
            Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            cardPaint.setColor(0xB0FFFFFF);
            canvas.drawPath(path, cardPaint);
            Paint hl = new Paint(Paint.ANTI_ALIAS_FLAG);
            hl.setShader(new LinearGradient(0, rect.top, 0, rect.top + h * 0.5f,
                    0x88FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            canvas.save();
            canvas.clipPath(path);
            canvas.drawRect(rect, hl);
            canvas.restore();
        }

        // 4) 内描边
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1.2f, h * 0.02f));
        stroke.setColor(0xE0FFFFFF);
        canvas.drawPath(path, stroke);
    }
}