package com.zhiyu.dna.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 玻璃按钮: 主按钮为蓝色渐变 + 白色文字; 次按钮为玻璃质感。
 */
public class GlassButton extends FrameLayout {

    public static final int STYLE_PRIMARY = 0;
    public static final int STYLE_GLASS = 1;
    public static final int STYLE_DANGER = 2;

    private final TextView tv;

    public GlassButton(Context context, String text, int style) {
        super(context);
        int dp = (int) (context.getResources().getDisplayMetrics().density * 13);
        tv = new TextView(context);
        tv.setText(text);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(16);
        tv.setIncludeFontPadding(false);
        tv.setPadding(0, dp, 0, dp);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        addView(tv, lp);
        applyStyle(style);
    }

    public void setText(String t) {
        tv.setText(t);
    }

    public String getText() {
        return tv.getText().toString();
    }

    public void applyStyle(int style) {
        float density = getResources().getDisplayMetrics().density;
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(26 * density);
        if (style == STYLE_PRIMARY) {
            gd.setColors(new int[]{0xFF64C6FF, 0xFF169AFF, 0xFF1273E8});
            gd.setOrientation(GradientDrawable.Orientation.TL_BR);
            tv.setTextColor(Color.WHITE);
            tv.setShadowLayer(6 * density, 0, 2 * density, 0x55000000);
        } else if (style == STYLE_DANGER) {
            gd.setColors(new int[]{0xFFFF8A80, 0xFFF44336, 0xFFD32F2F});
            gd.setOrientation(GradientDrawable.Orientation.TL_BR);
            tv.setTextColor(Color.WHITE);
            tv.setShadowLayer(6 * density, 0, 2 * density, 0x55000000);
        } else {
            gd.setColor(0xAAFFFFFF);
            gd.setStroke((int) (1.2f * density), 0xCCFFFFFF);
            tv.setTextColor(0xFF1C242C);
        }
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(26 * density);
        mask.setColor(Color.WHITE);
        RippleDrawable rd = new RippleDrawable(
                ColorStateList.valueOf(0x33000000), gd, mask);
        setBackground(rd);
        setElevation(3 * density);
        setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.95f).scaleY(0.93f).setDuration(110).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(240)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
                    break;
            }
            return false;
        });
    }
}
