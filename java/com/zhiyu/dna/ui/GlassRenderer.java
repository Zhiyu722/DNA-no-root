package com.zhiyu.dna.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * 液态玻璃渲染器 —— 供 GlassCard / GlassSegmented 共用:
 *  1. 折射: 采样 GlassScene 捕获的背景位图(放大 = 玻璃厚度感)
 *  2. 顶部高光渐变
 *  3. 上缘镜面高光弧
 *  4. 柔和阴影 + 内描边
 */
public final class GlassRenderer {

    private GlassRenderer() {}

    public static void drawGlass(Canvas canvas, GlassScene scene, View self,
                                 RectF rect, float radius, Paint shadowPaint) {
        float w = self.getWidth(), h = self.getHeight();

        // 阴影(向下偏移)
        Path shadow = new Path();
        shadow.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.drawPath(shadow, shadowPaint);

        Path path = new Path();
        path.addRoundRect(rect, radius, radius, Path.Direction.CW);

        // 折射: 采样背景
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
            float cx = rect.centerX(), cy = rect.centerY();
            float hw = rect.width() / 2f, hh = rect.height() / 2f;
            float refr = 1.05f;
            RectF dst = new RectF(cx - hw * refr, cy - hh * refr, cx + hw * refr, cy + hh * refr);
            canvas.save();
            canvas.clipPath(path);
            canvas.drawBitmap(cache, new android.graphics.Rect((int) srcL, (int) srcT,
                    (int) srcR, (int) srcB), dst, bgPaint);
            canvas.restore();
        }

        // 玻璃罩白 + 高光
        Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cardPaint.setStyle(Paint.Style.FILL);
        cardPaint.setColor(0x6EFFFFFF);
        canvas.drawPath(path, cardPaint);

        Paint hl = new Paint(Paint.ANTI_ALIAS_FLAG);
        hl.setShader(new LinearGradient(0, rect.top, 0, rect.top + h * 0.55f,
                new int[]{0x7AFFFFFF, 0x26FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        canvas.save();
        canvas.clipPath(path);
        canvas.drawRect(rect, hl);
        // 上缘镜面高光弧
        Paint spec = new Paint(Paint.ANTI_ALIAS_FLAG);
        spec.setStyle(Paint.Style.STROKE);
        spec.setStrokeWidth(Math.max(2f, h * 0.045f));
        spec.setStrokeCap(Paint.Cap.ROUND);
        spec.setShader(new LinearGradient(0, rect.top, 0, rect.top + h * 0.3f,
                0xD9FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        RectF arc = new RectF(rect.left + h * 0.2f, rect.top - h * 0.25f,
                rect.right - h * 0.2f, rect.top + h * 0.32f);
        canvas.drawRoundRect(arc, radius, radius, spec);
        canvas.restore();

        // 内描边
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1f, h * 0.016f));
        stroke.setColor(0xCFFFFFFF);
        canvas.drawPath(path, stroke);
    }
}
