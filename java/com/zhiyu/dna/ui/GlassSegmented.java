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
import android.view.MotionEvent;
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

    /** 拖动回调: 拖动中每帧上报小数位置(段单位), 用于联动分页器 */
    public interface OnDragListener {
        void onDrag(float pos);
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
    private OnDragListener dragListener;
    private ValueAnimator springAnim;
    private long lastStep;
    private long lastLog2;
    private GlassScene scene;
    private boolean fingerDragging;
    private float downX;
    private float dragStartPill;

    public GlassSegmented(Context context, String[] items) {
        super(context);
        this.items = items;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);
        setPadding(0, 0, 0, 0);
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
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) dp(46), 1);
            tv.setLayoutParams(lp);
            tv.setOnClickListener(v -> setSelected(idx, true));
            addView(tv);
        }
        updateTextColors();
    }

    public void setOnSelectedListener(OnSelectedListener l) {
        this.listener = l;
    }

    public void setOnDragListener(OnDragListener l) {
        this.dragListener = l;
    }

    // ================= 顶栏直接拖动 =================
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                dragStartPill = pillPos;
                fingerDragging = false;
                return false;   // 先让子视图(文字)处理, 点击有效
            case MotionEvent.ACTION_MOVE:
                if (!fingerDragging && Math.abs(ev.getX() - downX) > dp(8)) {
                    fingerDragging = true;   // 拖动接管
                    if (springAnim.isRunning()) springAnim.cancel();
                    return true;
                }
                return fingerDragging;
            default:
                return fingerDragging;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (springAnim.isRunning()) springAnim.cancel();
                fingerDragging = true;
                downX = ev.getX();
                dragStartPill = pillPos;
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (!fingerDragging) return true;
                float seg = getSegmentWidth();
                float np = dragStartPill + (ev.getX() - downX) / seg;
                np = Math.max(0f, Math.min(np, items.length - 1f));
                pillPos = np;
                pillVel = 0;
                targetPos = np;
                int nearest = Math.round(np);
                if (nearest != selected) {
                    selected = nearest;
                    updateTextColors();
                }
                invalidate();
                if (dragListener != null) dragListener.onDrag(np);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!fingerDragging) return true;
                fingerDragging = false;
                int nearest = Math.round(pillPos);
                nearest = Math.max(0, Math.min(nearest, items.length - 1));
                boolean changed = nearest != selected;
                selected = nearest;
                updateTextColors();
                targetPos = nearest;
                if (!springAnim.isRunning()) {
                    lastStep = System.nanoTime();
                    springAnim.start();
                }
                if (changed && listener != null) listener.onSelected(nearest);
                return true;
            }
        }
        return true;
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
        double omegaN = Math.sqrt(420.0);
        double target = targetPos >= 0 ? targetPos : selected;
        double[] r = springUnderdamped(pillPos, pillVel, target, dt, omegaN, 0.88);
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

        // ===== 玻璃面板(保留玻璃质感) =====
        RectF panel = new RectF(dp(2), dp(2), w - dp(2), h - dp(2));
        GlassRenderer.drawGlass(canvas, scene, this, panel, radius, shadowPaint);
        // 渐变描边(蓝青液态光晕)
        Path border = new Path();
        border.addRoundRect(panel, radius, radius, Path.Direction.CW);
        borderPaint.setShader(new LinearGradient(0, 0, w, h,
                new int[]{0x77FFFFFF, 0xAA99FFFF, 0x88FFB3FF, 0x77FFFFFF},
                new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawPath(border, borderPaint);

        // ===== 每个选项: 玻璃药丸背景(未选中项也有玻璃质感) =====
        float seg = w / items.length;
        float pillW = dp(96);   // 药丸宽度: 包住两个字
        float cy = h / 2f;
        Paint optGlass = new Paint(Paint.ANTI_ALIAS_FLAG);
        optGlass.setColor(0x4DFFFFFF);   // 半透明白玻璃
        Paint optStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        optStroke.setStyle(Paint.Style.STROKE);
        optStroke.setStrokeWidth(dp(1));
        optStroke.setColor(0x66FFFFFF);
        for (int i = 0; i < items.length; i++) {
            float cx = (i + 0.5f) * seg;
            RectF pill = new RectF(cx - pillW / 2f, dp(4), cx + pillW / 2f, h - dp(4));
            if (Math.abs(i - pillPos) > 0.5f) {
                // 非选中项: 玻璃药丸
                canvas.drawRoundRect(pill, radius, radius, optGlass);
                canvas.drawRoundRect(pill, radius, radius, optStroke);
            }
        }

        // ===== 视频12式滑动胶囊: 文字大小, 在段中心间连续滑动 =====
        float capsuleW = dp(96);
        float centerX = (pillPos + 0.5f) * seg;   // 在段中心间连续滑动
        RectF rect = new RectF(centerX - capsuleW / 2f, dp(4),
                centerX + capsuleW / 2f, h - dp(4));

        // 胶囊: 蓝青渐变(参考是纯蓝, 我们保留一点渐变更有质感)
        pillPaint.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                new int[]{0xFF4DA3FF, 0xFF007FFF, 0xFF0057B8},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, radius, radius, pillPaint);

        // 顶部细高光(液态感)
        pillGlossPaint.setShader(new LinearGradient(0, rect.top, 0, rect.top + h * 0.5f,
                0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.save();
        Path clip = new Path();
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);
        canvas.drawRect(rect.left, rect.top, rect.right, rect.top + h * 0.5f, pillGlossPaint);
        canvas.restore();

        // 柔和蓝色光晕
        canvas.save();
        canvas.translate(0, dp(2));
        Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
        sh.setColor(0x3D169AFF);
        Path shadowPath = new Path();
        shadowPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.drawPath(shadowPath, sh);
        canvas.restore();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
    }
}
