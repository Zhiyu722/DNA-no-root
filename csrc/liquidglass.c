/*
 * liquidglass.c —— 液态玻璃软件渲染实现(纯 C99)
 *
 * 绘制顺序(自下而上):
 *   0. 投影        —— 圆角矩形扩散阴影
 *   1. 折射采样    —— 背景按"边缘距离"做放大弯折(越靠边折射越强) → 透镜感
 *   2. 磨砂        —— 背景本身已是模糊源(调用方先 blur), 这里不再二次模糊
 *   3. 罩色        —— 线性渐变罩色(默认白 0.55 → 0.26)
 *   4. 实体感      —— 可选 body 罩层(顶栏等需要遮挡滚动内容)
 *   5. 描边        —— 发丝描边 + 上缘更亮(上 40%)
 *   6. 镜面        —— 左上角径向高光
 *   7. 抗锯齿      —— 用 SDF 覆盖率做边缘混合
 */
#include "liquidglass.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#ifndef LG_NO_THREADS
#include <pthread.h>
#define LG_USE_THREADS 1
#endif

/* 可用的渲染线程数(默认按 CPU 核数, 上限 8) */
static int lg_thread_count(void) {
#ifdef LG_USE_THREADS
    static int cached = -1;
    if (cached > 0) return cached;
    long n = sysconf(_SC_NPROCESSORS_ONLN);
    if (n < 1) n = 1;
    if (n > 8) n = 8;
    cached = (int)n;
    return cached;
#else
    return 1;
#endif
}

/* ------------------------------------------------------------------ */
/* 参数默认值(对齐 Compose 版配方)                                     */
/* ------------------------------------------------------------------ */

void lg_params_default(lg_params *p, int dark) {
    if (!p) return;
    memset(p, 0, sizeof(*p));
    p->corner_radius = 28.0f;
    p->blur_radius   = 32.0f;
    p->refraction    = 1.02f;
    p->edge_band     = 0.22f;
    p->edge_bend     = 0.60f;

    if (dark) {
        p->tint_top    = lg_argb(26,  255, 255, 255);  /* 白 0.10 */
        p->tint_bottom = lg_argb(8,   255, 255, 255);  /* 白 0.03 */
        p->border_color = lg_argb(56, 255, 255, 255);  /* 白 0.22 */
        p->top_alpha   = 0.32f;
        p->sheen_alpha = 0.14f;
    } else {
        p->tint_top    = lg_argb(104, 255, 255, 255);  /* 白 0.41 */
        p->tint_bottom = lg_argb(48,  255, 255, 255);  /* 白 0.19 */
        p->border_color = lg_argb(184, 255, 255, 255); /* 白 0.72 */
        p->top_alpha   = 0.70f;
        p->sheen_alpha = 0.35f;
    }
    p->border_width  = 1.0f;
    p->shadow_alpha  = 0.18f;
    p->shadow_offset = 6.0f;
    p->shadow_radius = 14.0f;
    p->shadow_grow   = 1.0f;
    p->body_alpha    = 0.0f;
    p->dark          = dark;
}

/* ------------------------------------------------------------------ */
/* 位图                                                                */
/* ------------------------------------------------------------------ */

uint32_t *lg_bitmap_create(int w, int h) {
    if (w <= 0 || h <= 0) return NULL;
    size_t n = (size_t)w * (size_t)h;
    uint32_t *px = (uint32_t *)malloc(n * sizeof(uint32_t));
    if (!px) return NULL;
    memset(px, 0, n * sizeof(uint32_t));
    return px;
}

void lg_bitmap_free(uint32_t *px) { free(px); }

void lg_fill(uint32_t *px, int w, int h, uint32_t argb) {
    if (!px) return;
    size_t n = (size_t)w * (size_t)h;
    for (size_t i = 0; i < n; i++) px[i] = argb;
}

/* ------------------------------------------------------------------ */
/* SDF                                                                 */
/* ------------------------------------------------------------------ */

float lg_sd_round_rect(float px, float py, float hw, float hh, float r) {
    float qx = fabsf(px) - (hw - r);
    float qy = fabsf(py) - (hh - r);
    float ax = qx > 0.f ? qx : 0.f;
    float ay = qy > 0.f ? qy : 0.f;
    float outside = sqrtf(ax * ax + ay * ay);
    float inside = fminf(fmaxf(qx, qy), 0.f);
    return outside + inside - r;
}

/* ------------------------------------------------------------------ */
/* 混合工具: src over dst (straight alpha)                             */
/* ------------------------------------------------------------------ */

static inline uint32_t blend_over(uint32_t dst, int sa, int sr, int sg, int sb) {
    if (sa <= 0) return dst;
    if (sa >= 255) return lg_argb(255, sr, sg, sb);
    int da = lg_a(dst);
    int out_a = sa + da * (255 - sa) / 255;
    if (out_a <= 0) return 0;
    int r = (sr * sa + lg_r(dst) * da * (255 - sa) / 255) / out_a;
    int g = (sg * sa + lg_g(dst) * da * (255 - sa) / 255) / out_a;
    int b = (sb * sa + lg_b(dst) * da * (255 - sa) / 255) / out_a;
    return lg_argb(out_a, r, g, b);
}

/* ------------------------------------------------------------------ */
/* 类高斯模糊(3 次盒式, 可分离)                                        */
/* ------------------------------------------------------------------ */

#define LG_RECIP_SHIFT 24
static void box_blur_h(uint32_t *src, uint32_t *tmp, int w, int h, int r, int y0, int y1) {
    int win = 2 * r + 1;
    unsigned recip = (unsigned)((1u << LG_RECIP_SHIFT) / (unsigned)win);
    if (y0 < 0) y0 = 0;
    if (y1 > h - 1) y1 = h - 1;
    for (int y = y0; y <= y1; y++) {
        uint32_t *row = src + (size_t)y * w;
        uint32_t *out = tmp + (size_t)y * w;
        long sa = 0, sr = 0, sg = 0, sb = 0;
        for (int i = -r; i <= r; i++) {
            int xi = i < 0 ? 0 : (i >= w ? w - 1 : i);
            uint32_t c = row[xi];
            sa += lg_a(c); sr += lg_r(c); sg += lg_g(c); sb += lg_b(c);
        }
        for (int x = 0; x < w; x++) {
            out[x] = lg_argb((int)(((unsigned long)sa * recip) >> LG_RECIP_SHIFT),
                             (int)(((unsigned long)sr * recip) >> LG_RECIP_SHIFT),
                             (int)(((unsigned long)sg * recip) >> LG_RECIP_SHIFT),
                             (int)(((unsigned long)sb * recip) >> LG_RECIP_SHIFT));
            int xo = x - r, xn = x + r + 1;
            if (xo < 0) xo = 0;
            if (xn >= w) xn = w - 1;
            uint32_t co = row[xo], cn = row[xn];
            sa += lg_a(cn) - lg_a(co);
            sr += lg_r(cn) - lg_r(co);
            sg += lg_g(cn) - lg_g(co);
            sb += lg_b(cn) - lg_b(co);
        }
    }
}

static void box_blur_v(uint32_t *src, uint32_t *tmp, int w, int h, int r, int x0, int x1) {
    int win = 2 * r + 1;
    unsigned recip = (unsigned)((1u << LG_RECIP_SHIFT) / (unsigned)win);
    if (x0 < 0) x0 = 0;
    if (x1 > w - 1) x1 = w - 1;
    for (int x = x0; x <= x1; x++) {
        long sa = 0, sr = 0, sg = 0, sb = 0;
        for (int i = -r; i <= r; i++) {
            int yi = i < 0 ? 0 : (i >= h ? h - 1 : i);
            uint32_t c = src[(size_t)yi * w + x];
            sa += lg_a(c); sr += lg_r(c); sg += lg_g(c); sb += lg_b(c);
        }
        for (int y = 0; y < h; y++) {
            tmp[(size_t)y * w + x] =
                lg_argb((int)(((unsigned long)sa * recip) >> LG_RECIP_SHIFT),
                        (int)(((unsigned long)sr * recip) >> LG_RECIP_SHIFT),
                        (int)(((unsigned long)sg * recip) >> LG_RECIP_SHIFT),
                        (int)(((unsigned long)sb * recip) >> LG_RECIP_SHIFT));
            int yo = y - r, yn = y + r + 1;
            if (yo < 0) yo = 0;
            if (yn >= h) yn = h - 1;
            uint32_t co = src[(size_t)yo * w + x], cn = src[(size_t)yn * w + x];
            sa += lg_a(cn) - lg_a(co);
            sr += lg_r(cn) - lg_r(co);
            sg += lg_g(cn) - lg_g(co);
            sb += lg_b(cn) - lg_b(co);
        }
    }
}

#ifdef LG_USE_THREADS
typedef struct {
    uint32_t *src; uint32_t *tmp; int w, h, r;
    int a0, a1;      /* 行区间(h 向) */
    int b0, b1;      /* 列区间(v 向) */
    int vertical;    /* 0=横向 1=纵向 */
} lg_blur_job;

static void *lg_blur_worker(void *arg) {
    lg_blur_job *j = (lg_blur_job *)arg;
    if (j->vertical) box_blur_v(j->src, j->tmp, j->w, j->h, j->r, j->b0, j->b1);
    else             box_blur_h(j->src, j->tmp, j->w, j->h, j->r, j->a0, j->a1);
    return NULL;
}
#endif

void lg_blur(uint32_t *px, int w, int h, int radius) {
    if (!px || w <= 0 || h <= 0 || radius <= 0) return;
    uint32_t *tmp = (uint32_t *)malloc((size_t)w * h * sizeof(uint32_t));
    if (!tmp) return;
    /* 用 3 次盒式模糊逼近高斯; 单次半径由目标半径换算 */
    int r = radius / 3;
    if (r < 1) r = 1;

#ifdef LG_USE_THREADS
    int n = lg_thread_count();
    if (n > 1 && (long)w * h > 40000) {
        pthread_t th[16];
        lg_blur_job jobs[16];
        int perY = (h + n - 1) / n;
        int perX = (w + n - 1) / n;
        for (int pass = 0; pass < 3; pass++) {
            /* 横向 */
            int cnt = 0;
            for (int i = 0; i < n; i++) {
                jobs[cnt].src = px; jobs[cnt].tmp = tmp; jobs[cnt].w = w; jobs[cnt].h = h;
                jobs[cnt].r = r; jobs[cnt].vertical = 0;
                jobs[cnt].a0 = i * perY; jobs[cnt].a1 = jobs[cnt].a0 + perY - 1;
                if (jobs[cnt].a0 >= h) break;
                if (pthread_create(&th[cnt], NULL, lg_blur_worker, &jobs[cnt]) != 0) {
                    lg_blur_worker(&jobs[cnt]); th[cnt] = 0;
                }
                cnt++;
            }
            for (int i = 0; i < cnt; i++) if (th[i]) pthread_join(th[i], NULL);
            /* 纵向 */
            cnt = 0;
            for (int i = 0; i < n; i++) {
                jobs[cnt].src = tmp; jobs[cnt].tmp = px; jobs[cnt].w = w; jobs[cnt].h = h;
                jobs[cnt].r = r; jobs[cnt].vertical = 1;
                jobs[cnt].b0 = i * perX; jobs[cnt].b1 = jobs[cnt].b0 + perX - 1;
                if (jobs[cnt].b0 >= w) break;
                if (pthread_create(&th[cnt], NULL, lg_blur_worker, &jobs[cnt]) != 0) {
                    lg_blur_worker(&jobs[cnt]); th[cnt] = 0;
                }
                cnt++;
            }
            for (int i = 0; i < cnt; i++) if (th[i]) pthread_join(th[i], NULL);
        }
        free(tmp);
        return;
    }
#endif
    for (int pass = 0; pass < 3; pass++) {
        box_blur_h(px, tmp, w, h, r, 0, h - 1);
        box_blur_v(tmp, px, w, h, r, 0, w - 1);
    }
    free(tmp);
}

/* ------------------------------------------------------------------ */
/* 背景(渐变 + 柔光色块)                                               */
/* ------------------------------------------------------------------ */

typedef struct { float x, y, r, a; uint32_t c; float vx, vy; } blob_t;

void lg_draw_backdrop(uint32_t *px, int w, int h, float t, int dark) {
    if (!px || w <= 0 || h <= 0) return;

    uint32_t top, bottom;
    if (dark) { top = lg_argb(255, 7, 10, 18); bottom = lg_argb(255, 14, 17, 34); }
    else      { top = lg_argb(255, 243, 246, 253); bottom = lg_argb(255, 230, 237, 250); }

    /* 垂直渐变 */
    for (int y = 0; y < h; y++) {
        float f = (float)y / (float)(h > 1 ? h - 1 : 1);
        int r = (int)(lg_r(top) + (lg_r(bottom) - lg_r(top)) * f);
        int g = (int)(lg_g(top) + (lg_g(bottom) - lg_g(top)) * f);
        int b = (int)(lg_b(top) + (lg_b(bottom) - lg_b(top)) * f);
        uint32_t c = lg_argb(255, r, g, b);
        uint32_t *row = px + (size_t)y * w;
        for (int x = 0; x < w; x++) row[x] = c;
    }

    /* 柔光色块(缓慢漂移; 让玻璃的折射与磨砂看得见) */
    blob_t blobs[5];
    memset(blobs, 0, sizeof(blobs));
    const float base = (float)(w < h ? w : h);
    if (dark) {
        blobs[0].c = lg_argb(90,  58, 110, 255); blobs[1].c = lg_argb(74,  120, 70, 220);
        blobs[2].c = lg_argb(70,  60, 190, 220); blobs[3].c = lg_argb(60,  40, 200, 190);
        blobs[4].c = lg_argb(50, 255, 255, 255);
    } else {
        blobs[0].c = lg_argb(90, 143, 184, 255); blobs[1].c = lg_argb(74, 168, 140, 255);
        blobs[2].c = lg_argb(70, 158, 220, 255); blobs[3].c = lg_argb(64, 191, 212, 255);
        blobs[4].c = lg_argb(56, 255, 255, 255);
    }
    for (int i = 0; i < 5; i++) {
        blobs[i].r  = base * (0.32f + 0.07f * (float)i);
        blobs[i].a  = 1.0f;
        blobs[i].vx = 0.016f * (float)((i % 2) ? -1 : 1) * (1.0f + 0.3f * (float)i);
        blobs[i].vy = 0.012f * (float)((i % 3) ? -1 : 1);
        float ox = 0.16f + 0.18f * (float)i;
        float oy = 0.22f + 0.15f * (float)((i * 2) % 4);
        float fx = ox + blobs[i].vx * t;
        float fy = oy + blobs[i].vy * t;
        /* 环绕 */
        while (fx > 1.4f) fx -= 1.8f;
        while (fx < -0.4f) fx += 1.8f;
        while (fy > 1.4f) fy -= 1.8f;
        while (fy < -0.4f) fy += 1.8f;
        blobs[i].x = fx * (float)w;
        blobs[i].y = fy * (float)h;

        float r = blobs[i].r;
        int x0 = (int)(blobs[i].x - r), x1 = (int)(blobs[i].x + r);
        int y0 = (int)(blobs[i].y - r), y1 = (int)(blobs[i].y + r);
        if (x0 < 0) x0 = 0;
        if (y0 < 0) y0 = 0;
        if (x1 >= w) x1 = w - 1;
        if (y1 >= h) y1 = h - 1;
        int ba = lg_a(blobs[i].c), br = lg_r(blobs[i].c),
            bg = lg_g(blobs[i].c), bb = lg_b(blobs[i].c);
        for (int y = y0; y <= y1; y++) {
            float dy = (float)y - blobs[i].y;
            uint32_t *row = px + (size_t)y * w;
            for (int x = x0; x <= x1; x++) {
                float dx = (float)x - blobs[i].x;
                float dist = sqrtf(dx * dx + dy * dy);
                if (dist >= r) continue;
                float k = 1.0f - dist / r;
                k = k * k;                       /* 二次衰减, 更柔 */
                int a = (int)(ba * k);
                row[x] = blend_over(row[x], a, br, bg, bb);
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* 核心: 液态玻璃面板                                                  */
/* ------------------------------------------------------------------ */

/* 采样背景(双线性, 越界取边缘) */
static void sample_bilinear(const uint32_t *bd, int bw, int bh,
                            float sx, float sy, int *a, int *r, int *g, int *b) {
    if (!bd || bw <= 0 || bh <= 0) { *a = *r = *g = *b = 0; return; }
    if (sx < 0.f) sx = 0.f;
    if (sy < 0.f) sy = 0.f;
    if (sx > (float)(bw - 1)) sx = (float)(bw - 1);
    if (sy > (float)(bh - 1)) sy = (float)(bh - 1);
    int x0 = (int)floorf(sx), y0 = (int)floorf(sy);
    int x1 = x0 + 1, y1 = y0 + 1;
    if (x1 > bw - 1) x1 = bw - 1;
    if (y1 > bh - 1) y1 = bh - 1;
    float fx = sx - (float)x0, fy = sy - (float)y0;
    uint32_t c00 = bd[(size_t)y0 * bw + x0], c10 = bd[(size_t)y0 * bw + x1];
    uint32_t c01 = bd[(size_t)y1 * bw + x0], c11 = bd[(size_t)y1 * bw + x1];
    float w00 = (1 - fx) * (1 - fy), w10 = fx * (1 - fy);
    float w01 = (1 - fx) * fy,       w11 = fx * fy;
    *a = (int)(lg_a(c00) * w00 + lg_a(c10) * w10 + lg_a(c01) * w01 + lg_a(c11) * w11);
    *r = (int)(lg_r(c00) * w00 + lg_r(c10) * w10 + lg_r(c01) * w01 + lg_r(c11) * w11);
    *g = (int)(lg_g(c00) * w00 + lg_g(c10) * w10 + lg_g(c01) * w01 + lg_g(c11) * w11);
    *b = (int)(lg_b(c00) * w00 + lg_b(c10) * w10 + lg_b(c01) * w01 + lg_b(c11) * w11);
}

/* 增艳: 轻微提饱和 + 提亮(定点整数, 无浮点更省 CPU) */
static inline void vibrancy(int *r, int *g, int *b) {
    int rr = *r, gg = *g, bb = *b;
    int lum = (77 * rr + 150 * gg + 29 * bb) >> 8;      /* 0.299/0.587/0.114 */
    /* sat = 1.25(即 5/4), gain = +14 */
    rr = lum + (((rr - lum) * 5) >> 2) + 14;
    gg = lum + (((gg - lum) * 5) >> 2) + 14;
    bb = lum + (((bb - lum) * 5) >> 2) + 14;
    *r = rr < 0 ? 0 : (rr > 255 ? 255 : rr);
    *g = gg < 0 ? 0 : (gg > 255 ? 255 : gg);
    *b = bb < 0 ? 0 : (bb > 255 ? 255 : bb);
}

/* 投影(圆角矩形扩散阴影) */
static void lg_render_shadow(uint32_t *dst, int dst_w, int dst_h,
                             float x, float y, float w, float h,
                             const lg_params *p) {
    if (p->shadow_alpha <= 0.001f) return;
    const float cx = x + w * 0.5f, cy = y + h * 0.5f;
    const float hw = w * 0.5f, hh = h * 0.5f;
    float rad = p->corner_radius;
    if (rad > hw) rad = hw;
    if (rad > hh) rad = hh;
    float so = p->shadow_offset, sr = p->shadow_radius, sg_ = p->shadow_grow;
    int sx0 = (int)floorf(x - sr - sg_), sx1 = (int)ceilf(x + w + sr + sg_);
    int sy0 = (int)floorf(y - sr - sg_ + so), sy1 = (int)ceilf(y + h + sr + sg_ + so);
    if (sx0 < 0) sx0 = 0; if (sy0 < 0) sy0 = 0;
    if (sx1 > dst_w - 1) sx1 = dst_w - 1;
    if (sy1 > dst_h - 1) sy1 = dst_h - 1;
    int base_a = (int)(p->shadow_alpha * 255.0f);
    const float shw = hw + sg_, shh = hh + sg_, shrad = rad + sg_;
    for (int py = sy0; py <= sy1; py++) {
        uint32_t *row = dst + (size_t)py * dst_w;
        float fy2 = (float)py + 0.5f - (cy + so);
        float qy = fabsf(fy2) - (shh - shrad);
        for (int px = sx0; px <= sx1; px++) {
            float qx = fabsf((float)px + 0.5f - cx) - (shw - shrad);
            float ax = qx > 0.f ? qx : 0.f;
            float ay = qy > 0.f ? qy : 0.f;
            float d = sqrtf(ax * ax + ay * ay) + fminf(fmaxf(qx, qy), 0.f) - shrad;
            if (d > sr) continue;
            if (d < -sr) continue;
            float k = 1.0f - (d / sr);
            if (k < 0.f) k = 0.f;
            k = k * k;
            int a = (int)(base_a * k);
            if (a > 0) row[px] = blend_over(row[px], a, 0, 0, 0);
        }
    }
}

/* 渲染玻璃主体(不含投影)的 [ry0, ry1] 行区间; 供单线程/多线程共用 */
static void lg_render_body(uint32_t *dst, int dst_w, int dst_h,
                           const uint32_t *backdrop, int bd_w, int bd_h,
                           float x, float y, float w, float h,
                           const lg_params *p, int ry0, int ry1) {
    const float cx = x + w * 0.5f;
    const float cy = y + h * 0.5f;
    const float hw = w * 0.5f;
    const float hh = h * 0.5f;
    float rad = p->corner_radius;
    if (rad > hw) rad = hw;
    if (rad > hh) rad = hh;

    const float short_side = (w < h ? w : h);
    const float band = short_side * (p->edge_band > 0.f ? p->edge_band : 0.22f);
    const float refr = (p->refraction > 0.f ? p->refraction : 1.0f);

    /* ---------------- 玻璃本体(投影已由 lg_render_shadow 画好) ---------------- */
    float pad = 2.0f;                            /* 覆盖抗锯齿边缘 */
    int gx0 = (int)floorf(x - pad), gx1 = (int)ceilf(x + w + pad);
    int gy0 = ry0 > (int)floorf(y - pad) ? ry0 : (int)floorf(y - pad);
    int gy1 = ry1 < (int)ceilf(y + h + pad) ? ry1 : (int)ceilf(y + h + pad);
    if (gx0 < 0) gx0 = 0; if (gy0 < 0) gy0 = 0;
    if (gx1 > dst_w - 1) gx1 = dst_w - 1;
    if (gy1 > dst_h - 1) gy1 = dst_h - 1;
    if (gy0 > gy1) return;

    const int t_a = lg_a(p->tint_top),    t_r = lg_r(p->tint_top),
              t_g = lg_g(p->tint_top),    t_b = lg_b(p->tint_top);
    const int u_a = lg_a(p->tint_bottom), u_r = lg_r(p->tint_bottom),
              u_g = lg_g(p->tint_bottom), u_b = lg_b(p->tint_bottom);
    const int bd_a = lg_a(p->border_color), bd_r = lg_r(p->border_color),
              bd_g = lg_g(p->border_color), bd_b = lg_b(p->border_color);
    /* 描边宽度自适应: 至少 1px, 且不超过短边的 6% */
    float bw = p->border_width;
    if (bw < 1.0f) bw = (bw > 0.f) ? 1.0f : 0.f;
    {
        float cap = short_side * 0.06f;
        if (bw > cap) bw = cap;
    }
    const float body_a = p->body_alpha * 255.0f;
    const float sheen = p->sheen_alpha;
    const float sheen_cx = x + w * 0.10f;
    const float sheen_cy = y;
    float sheen_r = short_side;
    {
        float cap = short_side * 0.55f;
        if (sheen_r > cap) sheen_r = cap;
        if (sheen_r > 180.0f) sheen_r = 180.0f;
    }
    const float sheen_r2 = sheen_r * sheen_r;
    /* 内上缘柔和高光带: 玻璃顶部最亮, 向下渐隐(经典玻璃镜面) */
    float top_band = h * 0.22f;
    {
        float cap = short_side * 0.18f;      /* 上限: 短边的 18% */
        if (top_band > cap) top_band = cap;
        if (top_band > 56.0f) top_band = 56.0f;   /* 再设绝对上限, 大卡片也不出现宽白带 */
    }
    const float top_band_a = p->top_alpha * 0.55f * 255.0f;

    for (int py = gy0; py <= gy1; py++) {
        uint32_t *row = dst + (size_t)py * dst_w;
        float fy = (float)py + 0.5f - cy;
        float vfrac = ((float)py + 0.5f - y) / h;      /* 0..1 垂直位置 */
        if (vfrac < 0.f) vfrac = 0.f;
        if (vfrac > 1.f) vfrac = 1.f;

        for (int px = gx0; px <= gx1; px++) {
            float fx = (float)px + 0.5f - cx;
            float d = lg_sd_round_rect(fx, fy, hw, hh, rad);

            /* 覆盖率(抗锯齿) */
            float cov;
            if (d <= -1.0f) cov = 1.0f;
            else if (d >= 1.0f) cov = 0.0f;
            else cov = 0.5f - d;
            if (cov <= 0.f) continue;
            int cov_a = (int)(cov * 255.0f);

            /* --- 折射: 边缘权重 + 放大采样 --- */
            float edge = 1.0f - fminf(1.0f, (-d) / (band > 0.5f ? band : 0.5f));
            if (edge < 0.f) edge = 0.f;
            float k = 1.0f + (refr - 1.0f) * (0.35f + p->edge_bend * edge);
            float sx = cx + fx / k;
            float sy = cy + fy / k;

            int a, r, g, b;
            sample_bilinear(backdrop, bd_w, bd_h, sx, sy, &a, &r, &g, &b);
            if (a > 0) vibrancy(&r, &g, &b);

            /* --- 罩色(线性渐变 上→下) --- */
            int ia = (int)(t_a + (u_a - t_a) * vfrac);
            int ir = (int)(t_r + (u_r - t_r) * vfrac);
            int ig = (int)(t_g + (u_g - t_g) * vfrac);
            int ib = (int)(t_b + (u_b - t_b) * vfrac);
            if (body_a > 0.5f) {
                /* 实体感: 与渐变罩色做 alpha 合成(取较大的不透明度) */
                int ea = (int)body_a;
                ia = ia + ea * (255 - ia) / 255;
                ir = 255; ig = 255; ib = 255;
            }

            /* 罩色叠加到采样色上(straight alpha 近似) */
            if (ia > 0) {
                r = (r * (255 - ia) + ir * ia) / 255;
                g = (g * (255 - ia) + ig * ia) / 255;
                b = (b * (255 - ia) + ib * ia) / 255;
            }

            /* --- 内上缘柔和高光带 --- */
            if (top_band_a > 0.5f) {
                float ty = (float)py + 0.5f - y;
                if (ty >= 0.f && ty < top_band) {
                    float k = 1.0f - ty / top_band;
                    int ta = (int)(top_band_a * k * k);
                    if (ta > 0) {
                        r = (r * (255 - ta) + 255 * ta) / 255;
                        g = (g * (255 - ta) + 255 * ta) / 255;
                        b = (b * (255 - ta) + 255 * ta) / 255;
                    }
                }
            }

            /* --- 镜面: 左上角径向高光 --- */
            if (sheen > 0.001f) {
                float dx = (float)px + 0.5f - sheen_cx;
                float dy = (float)py + 0.5f - sheen_cy;
                float d2 = dx * dx + dy * dy;
                if (d2 < sheen_r2) {                 /* 平方域衰减: 无需开方 */
                    float kk = 1.0f - d2 / sheen_r2;
                    int sa = (int)(255.0f * sheen * kk * kk);
                    if (sa > 0) {
                        r = (r * (255 - sa) + 255 * sa) / 255;
                        g = (g * (255 - sa) + 255 * sa) / 255;
                        b = (b * (255 - sa) + 255 * sa) / 255;
                    }
                }
            }

            /* --- 描边 + 上缘更亮(上 40%) --- */
            if (bw > 0.f) {
                float ad = fabsf(d);
                if (ad <= bw) {
                    float bk = 1.0f - ad / bw;
                    float edge_a = (float)bd_a * bk;
                    float er = (float)bd_r, eg = (float)bd_g, eb = (float)bd_b;
                    /* 上 40% 叠加更亮的白边(经典玻璃高光) */
                    if (vfrac < 0.4f && p->top_alpha > 0.001f) {
                        float tf = 1.0f - vfrac / 0.4f;
                        float ta = 255.0f * p->top_alpha * tf * bk;
                        if (ta > 0) {
                            er = er + (255.0f - er) * (ta / (edge_a + ta + 0.001f));
                            eg = eg + (255.0f - eg) * (ta / (edge_a + ta + 0.001f));
                            eb = eb + (255.0f - eb) * (ta / (edge_a + ta + 0.001f));
                            edge_a = edge_a + ta;
                            if (edge_a > 255.f) edge_a = 255.f;
                        }
                    }
                    int ea = (int)edge_a;
                    if (ea > 0) {
                        r = (r * (255 - ea) + (int)er * ea) / 255;
                        g = (g * (255 - ea) + (int)eg * ea) / 255;
                        b = (b * (255 - ea) + (int)eb * ea) / 255;
                    }
                }
            }

            if (cov_a >= 255) {
                row[px] = lg_argb(255, r, g, b);
            } else {
                row[px] = blend_over(row[px], cov_a, r, g, b);
            }
        }
    }
}

/* ---------------- 多线程包装 ---------------- */

#ifdef LG_USE_THREADS
typedef struct {
    uint32_t *dst; int dst_w, dst_h;
    const uint32_t *bd; int bd_w, bd_h;
    float x, y, w, h;
    const lg_params *p;
    int y0, y1;
} lg_job;

static void *lg_job_run(void *arg) {
    lg_job *j = (lg_job *)arg;
    lg_render_body(j->dst, j->dst_w, j->dst_h, j->bd, j->bd_w, j->bd_h,
                   j->x, j->y, j->w, j->h, j->p, j->y0, j->y1);
    return NULL;
}
#endif

void lg_draw_glass(uint32_t *dst, int dst_w, int dst_h,
                   const uint32_t *backdrop, int bd_w, int bd_h,
                   float x, float y, float w, float h,
                   const lg_params *p) {
    if (!dst || dst_w <= 0 || dst_h <= 0 || !p || w <= 0.f || h <= 0.f) return;

    /* ---------- 投影(先画, 主体会盖在上面) ---------- */
    lg_render_shadow(dst, dst_w, dst_h, x, y, w, h, p);

#ifdef LG_USE_THREADS
    int n = lg_thread_count();
    if (n > 1 && h > 36.f) {
        pthread_t th[8];
        lg_job jobs[8];
        int total = (int)ceilf(h + 4.f);
        int per = (total + n - 1) / n;
        int ytop = (int)floorf(y - 2.f);
        int spawned = 0;
        for (int i = 0; i < n; i++) {
            int a = ytop + i * per;
            int b = a + per - 1;
            if (a > ytop + total) break;
            jobs[i].dst = dst; jobs[i].dst_w = dst_w; jobs[i].dst_h = dst_h;
            jobs[i].bd = backdrop; jobs[i].bd_w = bd_w; jobs[i].bd_h = bd_h;
            jobs[i].x = x; jobs[i].y = y; jobs[i].w = w; jobs[i].h = h; jobs[i].p = p;
            jobs[i].y0 = a; jobs[i].y1 = b;
            if (pthread_create(&th[i], NULL, lg_job_run, &jobs[i]) == 0) spawned++;
            else { lg_job_run(&jobs[i]); th[i] = 0; }
        }
        for (int i = 0; i < n; i++) if (th[i]) pthread_join(th[i], NULL);
        if (spawned > 0) return;
    }
#endif
    lg_render_body(dst, dst_w, dst_h, backdrop, bd_w, bd_h, x, y, w, h, p, 0, dst_h - 1);
}

/* ------------------------------------------------------------------ */
/* 一步渲染整幅场景(测试/独立使用)                                     */
/* ------------------------------------------------------------------ */

void lg_render_scene(uint32_t *dst, int w, int h, float t,
                     float px, float py, float pw, float ph,
                     const lg_params *p, int dark) {
    if (!dst || w <= 0 || h <= 0) return;
    /* 背景 */
    lg_draw_backdrop(dst, w, h, t, dark);
    /* 磨砂源: 复制一份再模糊 */
    uint32_t *bd = (uint32_t *)malloc((size_t)w * h * sizeof(uint32_t));
    if (!bd) return;
    memcpy(bd, dst, (size_t)w * h * sizeof(uint32_t));
    int r = (int)(p ? p->blur_radius : 32.0f);
    lg_blur(bd, w, h, r);
    /* 玻璃面板 */
    lg_draw_glass(dst, w, h, bd, w, h, px, py, pw, ph, p);
    free(bd);
}

/* ------------------------------------------------------------------ */
/* PPM 输出(便于检查效果)                                              */
/* ------------------------------------------------------------------ */

int lg_write_ppm(const char *path, const uint32_t *px, int w, int h) {
    if (!path || !px || w <= 0 || h <= 0) return -1;
    FILE *f = fopen(path, "wb");
    if (!f) return -1;
    fprintf(f, "P6\n%d %d\n255\n", w, h);
    unsigned char *line = (unsigned char *)malloc((size_t)w * 3);
    if (!line) { fclose(f); return -1; }
    for (int y = 0; y < h; y++) {
        const uint32_t *row = px + (size_t)y * w;
        for (int x = 0; x < w; x++) {
            line[x * 3 + 0] = (unsigned char)lg_r(row[x]);
            line[x * 3 + 1] = (unsigned char)lg_g(row[x]);
            line[x * 3 + 2] = (unsigned char)lg_b(row[x]);
        }
        fwrite(line, 1, (size_t)w * 3, f);
    }
    free(line);
    fclose(f);
    return 0;
}
