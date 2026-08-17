package com.zhiyu.dna.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 玻璃分段选择器(顶部 tab / 打包格式): 半透明白底 + 蓝色滑动选中胶囊。
 */
public class GlassSegmented extends LinearLayout {

    public interface OnSelectedListener {
        void onSelected(int index);
    }

    private final String[] items;
    private int selected = 0;
    private float pillPos = 0f;   // 胶囊位置(段索引, 弹簧驱动)
    private float pillVel = 0f;
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillGlossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private OnSelectedListener listener;
    private android.animation.ValueAnimator springAnim;
    private GlassScene scene;

    public GlassSegmented(Context context, String[] items) {
        super(context);
        this.items = items;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);
        setPadding((int) dp(4), (int) dp(4), (int) dp(4), (int) dp(4));
        setBackground(roundedBg(0x78FFFFFF, 0x99FFFFFF, dp(22)));
        setElevation(dp(2));
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            TextView tv = new TextView(context);
            tv.setText(items[i]);
            tv.setTextSize(14);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, 0, 0, 0);
            tv.setTextColor(ColorStateList.valueOf(0xFF1C242C));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) dp(36), 1);
            tv.setLayoutParams(lp);
            tv.setOnClickListener(v -> setSelected(idx, true));
            addView(tv);
        }
        setWillNotDraw(false);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        shadowPaint.setColor(0x1A000000);
        springAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        springAnim.setDuration(1000);
        springAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        springAnim.addUpdateListener(a -> springStep());
    }

    private void springStep() {
        long now = System.nanoTime();
        float dt = Math.min(0.033f, (now - lastStep) / 1e9f);
        lastStep = now;
        double omegaN = Math.sqrt(300.0);
        double target = targetPos >= 0 ? targetPos : selected;
        double[] r = springUnderdamped(pillPos, pillVel, target, dt, omegaN, 0.6);
        pillPos = (float) r[0];
        pillVel = (float) r[1];
        if (Math.abs(pillPos - target) < 0.004f && Math.abs(pillVel) < 0.02f) {
            pillPos = (float) target;
            pillVel = 0;
            springAnim.cancel();
        }
        invalidate();
    }

    private long lastStep;

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

    public void setOnSelectedListener(OnSelectedListener l) {
        this.listener = l;
    }

    /** 绑定场景容器, 让 tab 栏呈现真实玻璃折射效果 */
    public void attachScene(GlassScene s) {
        this.scene = s;
    }

    public int getSelectedIndex() {
        return selected;
    }

    /**
     * 连续位置目标(0..n-1 之间的浮点, 来自分页器滑动)。
     * @param instant true = 手指拖动中, 胶囊 1:1 跟手(无弹簧滞后);
     *                false = 松手/程序切换, 胶囊弹簧回位(液态过冲)。
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
            if (springAnim.isRunning()) springAnim.cancel();
            targetPos = pos;
            lastStep = System.nanoTime();
            springAnim.start();
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
                ((TextView) v).setTextColor(i == selected ? Color.WHITE : 0xFF1C242C);
            }
        }
    }

    private float targetPos = -1f;

    public void setSelected(int index, boolean notify) {
        if (index < 0 || index >= items.length) return;
        boolean changed = selected != index;
        selected = index;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v instanceof TextView) {
                ((TextView) v).setTextColor(i == selected ? Color.WHITE : 0xFF1C242C);
            }
        }
        targetPos = index;
        if (springAnim.isRunning()) springAnim.cancel();
        lastStep = System.nanoTime();
        springAnim.start();
        if (changed && notify && listener != null) listener.onSelected(index);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private android.graphics.drawable.Drawable roundedBg(int fill, int stroke, float radius) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(radius);
        gd.setStroke((int) dp(1), stroke);
        return gd;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (items.length == 0) return;
        float w = getWidth() - dp(8);
        float seg = w / items.length;
        float y = dp(4);
        float h = getHeight() - dp(8);

        // 玻璃背景(折射 + 高光 + 描边)
        RectF panel = new RectF(dp(4), dp(4), getWidth() - dp(4), getHeight() - dp(4));
        GlassRenderer.drawGlass(canvas, scene, this, panel, dp(22), shadowPaint);

        // 滑动胶囊(液态玻璃质感: 蓝色渐变 + 顶部光泽)
        float x = dp(4) + pillPos * seg;
        RectF rect = new RectF(x + dp(2), y, x + seg - dp(2), y + h);
        android.graphics.Shader shader = new android.graphics.LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                new int[]{0xFF64C6FF, 0xFF169AFF, 0xFF1273E8},
                new float[]{0f, 0.55f, 1f}, android.graphics.Shader.TileMode.CLAMP);
        pillPaint.setShader(shader);
        canvas.drawRoundRect(rect, h / 2f, h / 2f, pillPaint);
        // 胶囊顶部光泽
        pillGlossPaint.setShader(new android.graphics.LinearGradient(
                0, rect.top, 0, rect.top + h * 0.55f,
                0x66FFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP));
        canvas.save();
        android.graphics.Path clip = new android.graphics.Path();
        clip.addRoundRect(rect, h / 2f, h / 2f, android.graphics.Path.Direction.CW);
        canvas.clipPath(clip);
        canvas.drawRect(rect.left, rect.top, rect.right, rect.top + h * 0.55f, pillGlossPaint);
        canvas.restore();
    }

    @Override
    protected void dispatchDraw(android.graphics.Canvas canvas) {
        // 文字绘制在玻璃与胶囊之上
        super.dispatchDraw(canvas);
    }
}
