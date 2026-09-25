/*
 * liquidglass.h —— 液态玻璃(liquid glass)软件渲染库
 *
 * 纯 C99, 无外部依赖。把"磨砂 + 折射 + 罩色 + 亮边 + 镜面"这一套玻璃配方
 * 直接栅格化到 ARGB8888 像素缓冲, 可在 Android(JNI) / Linux / 任意平台使用。
 *
 * 配方参考 Compose 版实现:
 *   圆角 28dp / 模糊 32dp / 折射 1.02 / 罩白渐变 0.55→0.26
 *   描边 白0.72 / 上缘亮边 白0.70(上40%) / 左上镜面 0.35
 */
#ifndef LIQUIDGLASS_H
#define LIQUIDGLASS_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ------------------------------------------------------------------ */
/* 参数                                                                */
/* ------------------------------------------------------------------ */

typedef struct {
    float corner_radius;   /* 圆角半径(px) */
    float blur_radius;     /* 磨砂模糊半径(px); 0 = 不模糊 */
    float refraction;      /* 折射放大系数, 1.0 = 无折射, 1.02 = 轻微透镜 */
    float edge_band;       /* 边缘折射带宽度(相对短边, 0..0.5) */
    float edge_bend;       /* 边缘额外弯折强度(0..1) */

    uint32_t tint_top;     /* 罩色渐变-上(ARGB, alpha 为不透明度) */
    uint32_t tint_bottom;  /* 罩色渐变-下 */

    uint32_t border_color; /* 描边色(ARGB) */
    float border_width;    /* 描边宽度(px) */

    float top_alpha;       /* 上缘亮边强度(0..1), 作用于上 40% */
    float sheen_alpha;     /* 左上角镜面高光强度(0..1) */

    float shadow_alpha;    /* 投影强度(0..1), 0 = 不画投影 */
    float shadow_offset;   /* 投影下移(px) */
    float shadow_radius;   /* 投影扩散(px) */
    float shadow_grow;     /* 投影外扩(px) */

    float body_alpha;      /* 额外实体感(0..1): 顶栏等需要挡住下方滚动内容时用 */

    int dark;              /* 深色主题(影响默认值) */
} lg_params;

/* ------------------------------------------------------------------ */
/* 生命周期                                                            */
/* ------------------------------------------------------------------ */

/* 填充默认参数(与 Compose 版配方一致) */
void lg_params_default(lg_params *p, int dark);

/* 分配/释放 ARGB8888 位图(width*height 个 uint32_t, 行优先, 无 padding) */
uint32_t *lg_bitmap_create(int w, int h);
void lg_bitmap_free(uint32_t *px);

/* ------------------------------------------------------------------ */
/* 基础绘制                                                            */
/* ------------------------------------------------------------------ */

/* 圆角矩形有符号距离场: <0 在内部, 单位 px */
float lg_sd_round_rect(float px, float py, float hw, float hh, float r);

/* 类高斯模糊(3 次可分离盒式), px 原地修改; radius<=0 直接返回 */
void lg_blur(uint32_t *px, int w, int h, int radius);

/* 背景: 浅色渐变 + 缓慢漂移的柔光色块; t 为时间(秒) */
void lg_draw_backdrop(uint32_t *px, int w, int h, float t, int dark);

/* 直接填充一个颜色 */
void lg_fill(uint32_t *px, int w, int h, uint32_t argb);

/* ------------------------------------------------------------------ */
/* 核心: 绘制一块液态玻璃                                              */
/* ------------------------------------------------------------------ */

/*
 * dst       目标位图指针(ARGB8888), 尺寸 dst_w x dst_h
 * backdrop  背景位图(建议先 lg_blur 得到磨砂源), 可为 NULL(则只用罩色)
 * bd_w/bd_h backdrop 尺寸
 * x,y,w,h   面板矩形(px, 可以为小数, 边缘会做抗锯齿)
 * p         参数
 */
void lg_draw_glass(uint32_t *dst, int dst_w, int dst_h,
                   const uint32_t *backdrop, int bd_w, int bd_h,
                   float x, float y, float w, float h,
                   const lg_params *p);

/* 一次搞定: 生成背景 -> 模糊 -> 画玻璃面板(方便测试与独立使用) */
void lg_render_scene(uint32_t *dst, int w, int h, float t,
                     float px, float py, float pw, float ph,
                     const lg_params *p, int dark);

/* ------------------------------------------------------------------ */
/* 小工具                                                              */
/* ------------------------------------------------------------------ */

static inline uint32_t lg_argb(int a, int r, int g, int b) {
    return ((uint32_t)(a & 0xFF) << 24) | ((uint32_t)(r & 0xFF) << 16)
         | ((uint32_t)(g & 0xFF) << 8)  | (uint32_t)(b & 0xFF);
}
static inline int lg_a(uint32_t c) { return (int)((c >> 24) & 0xFF); }
static inline int lg_r(uint32_t c) { return (int)((c >> 16) & 0xFF); }
static inline int lg_g(uint32_t c) { return (int)((c >> 8) & 0xFF); }
static inline int lg_b(uint32_t c) { return (int)(c & 0xFF); }

/* 把输出写成 PPM(P6), 便于用 ffmpeg/图片查看器检查效果 */
int lg_write_ppm(const char *path, const uint32_t *px, int w, int h);

#ifdef __cplusplus
}
#endif
#endif /* LIQUIDGLASS_H */
