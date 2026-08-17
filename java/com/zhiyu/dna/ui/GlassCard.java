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
        cardPaint.setColor(0xFFFCFCFC); // 白卡片
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

        // 视频1风格: 纯白不透明卡片 + 柔和阴影 + 淡灰描边
        // 1) 阴影(向下偏移)
        canvas.save();
        canvas.translate(0, dp(5));
        Path shadow = new Path();
        shadow.addRoundRect(rect, radius, radius, Path.Direction.CW);
        shadowPaint.setColor(0x14000000);
        canvas.drawPath(shadow, shadowPaint);
        canvas.restore();

        // 2) 纯白填充 #FCFCFC
        cardPaint.setColor(0xFFFCFCFC);
        canvas.drawPath(path, cardPaint);

        // 3) 顶部镜面高光(JS 玻璃原理: 上缘亮线 + 淡渐变)
        canvas.save();
        canvas.clipPath(path);
        highlightPaint.setShader(new LinearGradient(0, rect.top, 0, rect.top + h * 0.4f,
                new int[]{0x2EFFFFFF, 0x0AFFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.3f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(rect, highlightPaint);
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStrokeWidth(dp(1.2f));
        edge.setColor(0x66FFFFFF);
        RectF topEdge = new RectF(rect.left + radius * 0.4f, rect.top + dp(2.5f),
                rect.right - radius * 0.4f, rect.top + dp(4));
        canvas.drawRoundRect(topEdge, dp(2), dp(2), edge);
        canvas.restore();

        // 4) 淡灰描边(视频1卡片边线)
        strokePaint.setColor(0x1AE0E6EC);
        strokePaint.setStrokeWidth(dp(1));
        canvas.drawPath(path, strokePaint);
    }
}
