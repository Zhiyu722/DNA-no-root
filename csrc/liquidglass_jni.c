/*
 * liquidglass_jni.c —— 液态玻璃 C 库的 Android JNI 桥
 *
 * Java 侧对应类: com.zhiyu.dna.ui.LiquidGlass
 *
 * 典型用法(Java):
 *   int[] backdrop = ...;                       // 背景像素(ARGB_8888)
 *   LiquidGlass.blur(backdrop, w, h, 32);       // 得到磨砂源
 *   LiquidGlass.drawBackdrop(dst, w, h, t, false);
 *   LiquidGlass.drawGlass(dst, w, h, backdrop, w, h,
 *                         x, y, pw, ph, paramsFloat, paramsColor);
 *
 * 参数数组约定:
 *   float[6]  = { cornerRadius, blurRadius, refraction, edgeBand, edgeBend, borderWidth }
 *   float[6]  = { topAlpha, sheenAlpha, shadowAlpha, shadowOffset, shadowRadius, bodyAlpha }
 *   int[3]    = { tintTop, tintBottom, borderColor }   (ARGB)
 */
#include "liquidglass.h"

#include <jni.h>
#include <string.h>
#include <stdlib.h>

#define JNI_FN(name) Java_com_zhiyu_dna_ui_LiquidGlass_##name

static void fill_params(lg_params *p, jint dark,
                        const jfloat *f1, const jfloat *f2, const jint *c) {
    lg_params_default(p, dark ? 1 : 0);
    if (f1) {
        p->corner_radius = f1[0];
        p->blur_radius   = f1[1];
        p->refraction    = f1[2];
        p->edge_band     = f1[3];
        p->edge_bend     = f1[4];
        p->border_width  = f1[5];
    }
    if (f2) {
        p->top_alpha     = f2[0];
        p->sheen_alpha   = f2[1];
        p->shadow_alpha  = f2[2];
        p->shadow_offset = f2[3];
        p->shadow_radius = f2[4];
        p->body_alpha    = f2[5];
    }
    if (c) {
        p->tint_top     = (uint32_t)c[0];
        p->tint_bottom  = (uint32_t)c[1];
        p->border_color = (uint32_t)c[2];
    }
}

/* int[] -> 直接指针(int 与 Java 的 int[] 布局一致, 无需拷贝) */
static uint32_t *pin_int_array(JNIEnv *env, jintArray arr, jboolean *isCopy) {
    if (!arr) return NULL;
    return (uint32_t *)(*env)->GetPrimitiveArrayCritical(env, arr, isCopy);
}

static void unpin_int_array(JNIEnv *env, jintArray arr, uint32_t *p, jboolean isCopy) {
    if (arr && p) (*env)->ReleasePrimitiveArrayCritical(env, arr, p, isCopy ? JNI_ABORT : 0);
}

/* ---------------- 类高斯模糊 ---------------- */
JNIEXPORT void JNICALL
JNI_FN(nativeBlur)(JNIEnv *env, jclass cls, jintArray px, jint w, jint h, jint radius) {
    (void)cls;
    jboolean copy = JNI_FALSE;
    uint32_t *p = pin_int_array(env, px, &copy);
    if (p) {
        lg_blur(p, w, h, radius);
        unpin_int_array(env, px, p, copy);
    }
}

/* ---------------- 背景(渐变 + 柔光色块) ---------------- */
JNIEXPORT void JNICALL
JNI_FN(nativeDrawBackdrop)(JNIEnv *env, jclass cls, jintArray px, jint w, jint h,
                           jfloat t, jboolean dark) {
    (void)cls;
    jboolean copy = JNI_FALSE;
    uint32_t *p = pin_int_array(env, px, &copy);
    if (p) {
        lg_draw_backdrop(p, w, h, t, dark ? 1 : 0);
        unpin_int_array(env, px, p, copy);
    }
}

/* ---------------- 液态玻璃面板 ---------------- */
JNIEXPORT void JNICALL
JNI_FN(nativeDrawGlass)(JNIEnv *env, jclass cls,
                        jintArray dst, jint dw, jint dh,
                        jintArray backdrop, jint bw, jint bh,
                        jfloat x, jfloat y, jfloat w, jfloat h,
                        jfloatArray f1, jfloatArray f2, jintArray colors,
                        jint dark) {
    (void)cls;
    jboolean c1 = JNI_FALSE;
    uint32_t *pd = pin_int_array(env, dst, &c1);
    if (!pd) return;
    /* backdrop 可能与 dst 是同一数组, 这里再取一次指针(临界区可重入) */
    uint32_t *pb = NULL;
    jboolean c3 = JNI_FALSE;
    if (backdrop && backdrop != dst) pb = pin_int_array(env, backdrop, &c3);

    jfloat *pf1 = f1 ? (*env)->GetFloatArrayElements(env, f1, NULL) : NULL;
    jfloat *pf2 = f2 ? (*env)->GetFloatArrayElements(env, f2, NULL) : NULL;
    jint   *pc  = colors ? (*env)->GetIntArrayElements(env, colors, NULL) : NULL;

    lg_params p;
    fill_params(&p, dark, pf1, pf2, pc);

    if (backdrop == dst) {
        lg_draw_glass(pd, dw, dh, pd, bw, bh, x, y, w, h, &p);
    } else {
        lg_draw_glass(pd, dw, dh, pb, bw, bh, x, y, w, h, &p);
    }

    if (pf1) (*env)->ReleaseFloatArrayElements(env, f1, pf1, JNI_ABORT);
    if (pf2) (*env)->ReleaseFloatArrayElements(env, f2, pf2, JNI_ABORT);
    if (pc)  (*env)->ReleaseIntArrayElements(env, colors, pc, JNI_ABORT);
    if (pb) unpin_int_array(env, backdrop, pb, c3);
    unpin_int_array(env, dst, pd, c1);
}

/* ---------------- 版本号(便于确认 so 已加载) ---------------- */
JNIEXPORT jstring JNICALL
JNI_FN(nativeVersion)(JNIEnv *env, jclass cls) {
    (void)cls;
    return (*env)->NewStringUTF(env, "liquidglass-c 1.0 (C99 software renderer)");
}
