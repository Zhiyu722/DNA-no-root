package com.zhiyu.dna.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 液态玻璃顶栏 —— 发光胶囊指示器(技能点式), 弹簧+1:1 跟手双模式滑动。
 *  - 玻璃面板: 折射背景 + 渐变描边 + 上缘高光
 *  - 指示胶囊: 蓝青渐变 + 外发光 + 顶部光泽, 像一颗液态发光能量球
 */
public class GlassSegmented extends LinearLayout {

    public interface OnSelectedListener {
        void onSelected(int index);
    }

    private final String[] items;
    private int selected = 0;
    private float pillPos = 0f;
    private float pillVel = 0f;
    private float targetPos = -1f;
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillGlossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private OnSelectedListener listener;
    private ValueAnimator springAnim;
    private long lastStep;
    private GlassScene scene;

    public GlassSegmented(Context context, String[] items) {
        super(context);
        this.items = items;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);
        setPadding((int) dp(5), (int) dp(5), (int) dp(5), (int) dp(5));
        setWillNotDraw(false);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        shadowPaint.setColor(0x24000000);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1.3f));
        springAnim = ValueAnimator.ofFloat(0f, 1f);
        springAnim.setDuration(900);
        springAnim.setRepeatCount(ValueAnimator.INFINITE);
        springAnim.addUpdateListener(a -> springStep());
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            TextView tv = new TextView(context);
            tv.setText(items[i]);
            tv.setTextSize(14.5f);
            tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            tv.setGravity(Gravity.CENTER);
            tv.setTextColor(ColorStateList.valueOf(0xFF1C242C));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) dp(38), 1);
            tv.setLayoutParams(lp);
            tv.setOnClickListener(v -> setSelected(idx, true));
            addView(tv);
        }
        updateTextColors();
    }

    public void setOnSelectedListener(OnSelectedListener l) {
        this.listener = l;
    }

    public void attachScene(GlassScene s) {
        this.scene = s;
    }

    public int getSelectedIndex() {
        return selected;
    }

    /** 每段宽度 px(1:1 像素跟手用) */
    public float getSegmentWidth() {
        return (getWidth() - dp(10)) / Math.max(1, items.length);
    }

    /** 段数 */
    public float getPageCount() {
        return items.length;
    }

    /** 当前胶囊位置(段单位) */
    public float getCurrentPos() {
        return pillPos;
    }

    /**
     * 连续位置(0..n-1)。
     * @param instant true = 手指拖动, 胶囊 1:1 跟手; false = 松手/切换, 弹簧回弹
     */
    public void setPosition(float pos, boolean instant) {
        if (instant) {
            if (springAnim.isRunning()) springAnim.cancel();
            pillPos = pos;
            pillVel = 0;
            targetPos = pos;
            int nearest = Math.round(pos);
            if (nearest != selected) {
                selected = nearest;
                updateTextColors();
            }
            invalidate();
        } else {
            // 关键修复: 只更新目标, 动画器已在运行就不重启 ——
            // 否则分页器弹簧每帧回调都会 cancel+start, dt 恒为 0, 胶囊被冻住(两秒延迟的根因)
            targetPos = pos;
            if (!springAnim.isRunning()) {
                lastStep = System.nanoTime();
                springAnim.start();
            }
            int nearest = Math.round(pos);
            if (nearest != selected) {
                selected = nearest;
                updateTextColors();
            }
        }
    }

    private void updateTextColors() {
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v instanceof TextView) {
                // 选中文字: 纯白(大半径阴影会吞掉字形, 不要用); 未选中: 深灰
                ((TextView) v).setTextColor(i == selected ? Color.WHITE : 0xFF4A5568);
                ((TextView) v).setShadowLayer(0, 0, 0, 0);
                ((TextView) v).setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            }
        }
    }

    private void setSelected(int index, boolean notify) {
        if (index < 0 || index >= items.length) return;
        boolean changed = selected != index;
        selected = index;
        updateTextColors();
        targetPos = index;
        if (!springAnim.isRunning()) {
            lastStep = System.nanoTime();
            springAnim.start();
        }
        if (changed && notify && listener != null) listener.onSelected(index);
    }

    private void springStep() {
        long now = System.nanoTime();
        float dt = Math.min(0.033f, (now - lastStep) / 1e9f);
        lastStep = now;
        double omegaN = Math.sqrt(340.0);
        double target = targetPos >= 0 ? targetPos : selected;
        double[] r = springUnderdamped(pillPos, pillVel, target, dt, omegaN, 0.58);
        pillPos = (float) r[0];
        pillVel = (float) r[1];
        if (Math.abs(pillPos - target) < 0.0035f && Math.abs(pillVel) < 0.02f) {
            pillPos = (float) target;
            pillVel = 0;
            springAnim.cancel();
        }
        invalidate();
    }

    private static double[] springUnderdamped(double current, double velocity, double target,
                                              double dt, double omegaN, double dampingRatio) {
        double x0 = current - target;
        double v0 = velocity;
        double omegaD = omegaN * Math.sqrt(Math.max(0, 1 - dampingRatio * dampingRatio));
        double decay = Math.exp(-dampingRatio * omegaN * dt);
        double cosWd = Math.cos(omegaD * dt);
        double sinWd = Math.sin(omegaD * dt);
        double offset = x0 * decay * cosWd + (v0 + dampingRatio * omegaN * x0) / omegaD * decay * sinWd;
        double b0 = (v0 + dampingRatio * omegaN * x0) / omegaD;
        double newVel = -dampingRatio * omegaN * offset + decay * (-x0 * omegaD * sinWd + b0 * omegaD * cosWd);
        return new double[]{target + offset, newVel};
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (items.length == 0) return;
        float w = getWidth();
        float h = getHeight();
        float radius = h / 2f;

        // ===== 玻璃面板 =====
        RectF panel = new RectF(dp(2), dp(2), w - dp(2), h - dp(2));
        GlassRenderer.drawGlass(canvas, scene, this, panel, radius, shadowPaint);

        // 渐变描边(青→蓝→紫, 液态光晕感)
        Path border = new Path();
        border.addRoundRect(panel, radius, radius, Path.Direction.CW);
        borderPaint.setShader(new LinearGradient(0, 0, w, h,
                new int[]{0x55FFFFFF, 0x9999FFFF, 0x66FFB3FF, 0x55FFFFFF},
                new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawPath(border, borderPaint);

        // ===== 发光指示胶囊 =====
        float seg = (w - dp(10)) / items.length;
        float x = dp(5) + pillPos * seg;
        RectF rect = new RectF(x + dp(3), dp(5), x + seg - dp(3), h - dp(5));

        // 外发光
        glowPaint.setStrokeWidth(dp(9));
        glowPaint.setShader(new LinearGradient(rect.left, 0, rect.right, 0,
                new int[]{0x4064C6FF, 0x55169AFF, 0x4064C6FF}, null, Shader.TileMode.CLAMP));
        Path glowPath = new Path();
        glowPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.drawPath(glowPath, glowPaint);

        // 胶囊主体
        pillPaint.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                new int[]{0xFF7AD7FF, 0xFF169AFF, 0xFF1273E8},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, radius, radius, pillPaint);

        // 顶部光泽
        pillGlossPaint.setShader(new LinearGradient(0, rect.top, 0, rect.top + h * 0.6f,
                0x77FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.save();
        Path clip = new Path();
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);
        canvas.drawRect(rect.left, rect.top, rect.right, rect.top + h * 0.6f, pillGlossPaint);
        // 顶部细亮线
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStrokeWidth(dp(1.6f));
        edge.setColor(0xBFFFFFFF);
        canvas.drawRoundRect(new RectF(rect.left + dp(6), rect.top + dp(2.4f),
                rect.right - dp(6), rect.top + dp(4)), dp(2), dp(2), edge);
        canvas.restore();

        // ===== 底部小光点(技能点式点缀) =====
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        float cy = h - dp(4.5f);
        for (int i = 0; i < items.length; i++) {
            float cx = dp(5) + (i + 0.5f) * seg;
            float dist = Math.abs(i - pillPos);
            int alpha = (int) (40 + 200 * Math.max(0, 1 - dist));
            dot.setColor(Color.argb(alpha, 0x40, 0xD0, 0xFF));
            canvas.drawCircle(cx, cy, dp(1.8f), dot);
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
    }
}
