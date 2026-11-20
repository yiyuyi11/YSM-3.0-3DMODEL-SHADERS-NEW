// fpbench software rasterizer: z-buffered, perspective-correct textured triangles with an id buffer.
// Built on first use by raster.py (cc -O3 -shared -fPIC); no other dependencies.
#include <math.h>
#include <stdint.h>

static inline float edge(float ax, float ay, float bx, float by, float px, float py) {
    return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
}

// xy: n*3*2 screen pixels; iw: n*3 reciprocal view depth (1/w, larger = nearer); uv: n*3*2; shade: n*3;
// tex: n texture index (-1 = flat colour from flat[n*3]); texdata/texoff/texw/texh: RGBA8 textures.
// color: H*W*3 uint8 (pre-filled background); depth: H*W float (pre-filled 0); ids: H*W int32 (pre-filled -1);
// idbase: id written = idbase + triangle index. Texels with alpha < cutoff are discarded (entityCutout).
void raster(int n, const float *xy, const float *iw, const float *uv, const float *shade, const int32_t *tex,
            const float *flat, const uint8_t *texdata, const int64_t *texoff, const int32_t *texw, const int32_t *texh,
            int W, int H, uint8_t *color, float *depth, int32_t *ids, int32_t idbase, float cutoff) {
    for (int t = 0; t < n; t++) {
        const float *p = xy + t * 6;
        float x0 = p[0], y0 = p[1], x1 = p[2], y1 = p[3], x2 = p[4], y2 = p[5];
        float area = edge(x0, y0, x1, y1, x2, y2);
        if (fabsf(area) < 1e-12f) continue;
        const float *w = iw + t * 3;
        if (!(w[0] > 0 && w[1] > 0 && w[2] > 0)) continue;  // behind the near plane: dropped
        int minx = (int)floorf(fminf(x0, fminf(x1, x2))), maxx = (int)ceilf(fmaxf(x0, fmaxf(x1, x2)));
        int miny = (int)floorf(fminf(y0, fminf(y1, y2))), maxy = (int)ceilf(fmaxf(y0, fmaxf(y1, y2)));
        if (minx < 0) minx = 0;
        if (miny < 0) miny = 0;
        if (maxx > W - 1) maxx = W - 1;
        if (maxy > H - 1) maxy = H - 1;
        if (minx > maxx || miny > maxy) continue;
        float inv = 1.0f / area;
        const float *u = uv + t * 6;
        const float *s = shade + t * 3;
        int ti = tex[t];
        const uint8_t *td = ti >= 0 ? texdata + texoff[ti] : 0;
        int tw = ti >= 0 ? texw[ti] : 0, th = ti >= 0 ? texh[ti] : 0;
        for (int y = miny; y <= maxy; y++) {
            float py = y + 0.5f;
            for (int x = minx; x <= maxx; x++) {
                float px = x + 0.5f;
                float b0 = edge(x1, y1, x2, y2, px, py) * inv;
                float b1 = edge(x2, y2, x0, y0, px, py) * inv;
                float b2 = 1.0f - b0 - b1;
                if (b0 < 0 || b1 < 0 || b2 < 0) continue;
                float z = b0 * w[0] + b1 * w[1] + b2 * w[2];
                int at = y * W + x;
                if (z <= depth[at]) continue;
                float c0 = b0 * w[0] / z, c1 = b1 * w[1] / z, c2 = b2 * w[2] / z;  // perspective-correct weights
                float r, g, b;
                if (td) {
                    float tu = c0 * u[0] + c1 * u[2] + c2 * u[4];
                    float tv = c0 * u[1] + c1 * u[3] + c2 * u[5];
                    int ix = (int)floorf(tu * tw), iy = (int)floorf(tv * th);
                    if (ix < 0) ix = 0;
                    if (iy < 0) iy = 0;
                    if (ix >= tw) ix = tw - 1;
                    if (iy >= th) iy = th - 1;
                    const uint8_t *tx = td + ((int64_t)iy * tw + ix) * 4;
                    if (tx[3] < cutoff * 255.0f) continue;
                    r = tx[0]; g = tx[1]; b = tx[2];
                } else {
                    r = flat[t * 3] * 255.0f; g = flat[t * 3 + 1] * 255.0f; b = flat[t * 3 + 2] * 255.0f;
                }
                float l = c0 * s[0] + c1 * s[1] + c2 * s[2];
                r *= l; g *= l; b *= l;
                color[at * 3] = (uint8_t)(r > 255 ? 255 : r);
                color[at * 3 + 1] = (uint8_t)(g > 255 ? 255 : g);
                color[at * 3 + 2] = (uint8_t)(b > 255 ? 255 : b);
                depth[at] = z;
                ids[at] = idbase + t;
            }
        }
    }
}
