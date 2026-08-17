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
 * 液态玻璃渲染器 —— 移植 Liquidglass.js 的渲染配方(增强版):
 *  1. 折射: 采样背景位图, 放大模拟玻璃厚度; 边缘加内阴影(折射弯曲感)
 *  2. 顶部镜面高光: 明亮白色渐变带 + 顶部亮线
 *  3. 斜向光泽(光线扫过玻璃的 sheen)
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

        // 折射: 采样背景(放大 = 玻璃厚度感)
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
            float refr = 1.09f;   // 折射放大
            RectF dst = new RectF(cx - hw * refr, cy - hh * refr, cx + hw * refr, cy + hh * refr);
            canvas.save();
            canvas.clipPath(path);
            canvas.drawBitmap(cache, new android.graphics.Rect((int) srcL, (int) srcT,
                    (int) srcR, (int) srcB), dst, bgPaint);

            // 玻璃罩白(更明显的白色玻璃)
            Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            cardPaint.setColor(0x99FFFFFF);
            canvas.drawRect(rect, cardPaint);

            // 顶部镜面高光: 明亮白渐变带(顶部 35%)
            Paint hl = new Paint(Paint.ANTI_ALIAS_FLAG);
            hl.setShader(new LinearGradient(0, rect.top, 0, rect.top + h * 0.55f,
                    new int[]{0xCCFFFFFF, 0x66FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(rect.left, rect.top, rect.right, rect.top + h * 0.55f, hl);

            // 顶部亮线(镜面边缘)
            Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
            edge.setStrokeWidth(Math.max(1.6f, h * 0.03f));
            edge.setStrokeCap(Paint.Cap.ROUND);
            edge.setColor(0xE6FFFFFF);
            RectF topLine = new RectF(rect.left + h * 0.5f, rect.top + h * 0.045f,
                    rect.right - h * 0.5f, rect.top + h * 0.09f);
            canvas.drawRoundRect(topLine, h * 0.03f, h * 0.03f, edge);

            // 斜向光泽(sheen): 左上到右下的白色斜带
            Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
            sheen.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                    new int[]{0x00FFFFFF, 0x28FFFFFF, 0x00FFFFFF},
                    new float[]{0.30f, 0.52f, 0.74f}, Shader.TileMode.CLAMP));
            canvas.drawRect(rect, sheen);

            // 边缘内阴影(折射弯曲/厚度感)
            Paint vignette = new Paint(Paint.ANTI_ALIAS_FLAG);
            vignette.setShader(new RadialGradient(cx, cy, Math.max(hw, hh) * 0.95f,
                    new int[]{0x00000000, 0x14000000},
                    new float[]{0.72f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(rect, vignette);
            canvas.restore();
        } else {
            // 无背景时: 纯玻璃质感兜底
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

        // 内描边
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1.5f, h * 0.025f));
        stroke.setColor(0xEEFFFFFF);
        canvas.drawPath(path, stroke);
    }
}
