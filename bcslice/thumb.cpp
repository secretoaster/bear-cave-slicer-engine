// Software picture of the model for the G-code thumbnail (shown on the printer's screen)
// and the preview in the phone app. No GPU: a small z-buffer rasteriser with flat shading,
// drawn twice the size and shrunk for smooth edges.
//
// Copyright (C) 2026 Sung Hong / Bear Cave Labs. Built with Claude. AGPL v3.

#include "thumb.hpp"

#include "libslic3r/Model.hpp"
#include "libslic3r/miniz_extension.hpp"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <limits>

namespace bc {

using namespace Slic3r;

Vec3f parse_colour(const std::string &hex, Vec3f fallback)
{
    std::string h = hex;
    if (!h.empty() && h[0] == '#') h = h.substr(1);
    if (h.size() < 6) return fallback;
    try {
        unsigned v = std::stoul(h.substr(0, 6), nullptr, 16);
        return Vec3f(((v >> 16) & 255) / 255.f, ((v >> 8) & 255) / 255.f, (v & 255) / 255.f);
    } catch (...) { return fallback; }
}

ThumbnailData render(const Model &model, unsigned w, unsigned h, Vec3f colour, bool bottom_up)
{
    // all printable triangles in bed coordinates
    std::vector<Vec3f> tri;
    for (const ModelObject *mo : model.objects)
        for (const ModelInstance *mi : mo->instances) {
            if (!mi->printable) continue;
            Transform3d ti = mi->get_matrix();
            for (const ModelVolume *mv : mo->volumes) {
                if (!mv->is_model_part()) continue;
                Transform3d t = ti * mv->get_matrix();
                const indexed_triangle_set &its = mv->mesh().its;
                for (const stl_triangle_vertex_indices &f : its.indices)
                    for (int k = 0; k < 3; ++k)
                        tri.emplace_back((t * its.vertices[f[k]].cast<double>()).cast<float>());
            }
        }

    ThumbnailData out;
    out.set(w, h);
    std::fill(out.pixels.begin(), out.pixels.end(), 0);  // set() fills white; start transparent
    if (tri.empty()) return out;

    // view from the front-right, above: turn 35 degrees about Z, then tilt 30 degrees
    const float az = 35.f * float(M_PI) / 180.f, el = 30.f * float(M_PI) / 180.f;
    auto view = [&](const Vec3f &p) {
        float x = p.x() * std::cos(az) - p.y() * std::sin(az);
        float y = p.x() * std::sin(az) + p.y() * std::cos(az);
        // screen x, screen y (up), depth (bigger = nearer)
        return Vec3f(x, p.z() * std::cos(el) + y * std::sin(el), p.z() * std::sin(el) - y * std::cos(el));
    };
    std::vector<Vec3f> v(tri.size());
    Vec3f lo(1e30f, 1e30f, 1e30f), hi(-1e30f, -1e30f, -1e30f);
    for (size_t i = 0; i < tri.size(); ++i) {
        v[i] = view(tri[i]);
        lo = lo.cwiseMin(v[i]);
        hi = hi.cwiseMax(v[i]);
    }

    const int S = 2, W = int(w) * S, H = int(h) * S;  // draw at 2x
    const float margin = 0.08f;
    float span = std::max(hi.x() - lo.x(), hi.y() - lo.y());
    if (span <= 0) span = 1;
    const float scale = std::min(W, H) * (1.f - 2 * margin) / span;
    const float cx = (lo.x() + hi.x()) / 2, cy = (lo.y() + hi.y()) / 2;

    std::vector<float> depth(size_t(W) * H, -std::numeric_limits<float>::infinity());
    std::vector<float> shade(size_t(W) * H, -1.f);
    const Vec3f light = Vec3f(-0.35f, 0.55f, 0.75f).normalized();  // in view space: from the upper left, towards the viewer

    for (size_t i = 0; i + 2 < v.size(); i += 3) {
        Vec3f a = v[i], b = v[i + 1], c = v[i + 2];
        Vec3f n = (b - a).cross(c - a);
        float len = n.norm();
        if (len <= 0) continue;
        n /= len;
        if (n.z() < 0) n = -n;  // either winding: light the side we see
        float s = 0.30f + 0.70f * std::max(0.f, n.dot(light));
        // to pixels (y down)
        auto px = [&](const Vec3f &p) { return Vec3f((p.x() - cx) * scale + W / 2.f, H / 2.f - (p.y() - cy) * scale, p.z()); };
        Vec3f A = px(a), B = px(b), C = px(c);
        int x0 = std::max(0, (int)std::floor(std::min({A.x(), B.x(), C.x()})));
        int x1 = std::min(W - 1, (int)std::ceil(std::max({A.x(), B.x(), C.x()})));
        int y0 = std::max(0, (int)std::floor(std::min({A.y(), B.y(), C.y()})));
        int y1 = std::min(H - 1, (int)std::ceil(std::max({A.y(), B.y(), C.y()})));
        float area = (B.x() - A.x()) * (C.y() - A.y()) - (B.y() - A.y()) * (C.x() - A.x());
        if (std::abs(area) < 1e-9f) continue;
        for (int y = y0; y <= y1; ++y)
            for (int x = x0; x <= x1; ++x) {
                float fx = x + 0.5f, fy = y + 0.5f;
                float w0 = ((B.x() - fx) * (C.y() - fy) - (B.y() - fy) * (C.x() - fx)) / area;
                float w1 = ((C.x() - fx) * (A.y() - fy) - (C.y() - fy) * (A.x() - fx)) / area;
                float w2 = 1 - w0 - w1;
                if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                float z = w0 * A.z() + w1 * B.z() + w2 * C.z();
                size_t k = size_t(y) * W + x;
                if (z > depth[k]) { depth[k] = z; shade[k] = s; }
            }
    }

    // shrink 2x2 -> 1 with coverage as alpha
    for (unsigned y = 0; y < h; ++y)
        for (unsigned x = 0; x < w; ++x) {
            float r = 0, g = 0, bl = 0, cov = 0;
            for (int dy = 0; dy < S; ++dy)
                for (int dx = 0; dx < S; ++dx) {
                    float s = shade[size_t(y * S + dy) * W + (x * S + dx)];
                    if (s < 0) continue;
                    r += colour.x() * s; g += colour.y() * s; bl += colour.z() * s; cov += 1;
                }
            unsigned row = bottom_up ? (h - 1 - y) : y;
            unsigned char *p = out.pixels.data() + (size_t(row) * w + x) * 4;
            if (cov > 0) {
                // light grain (fixed per pixel, so the same model always gives the same picture)
                uint32_t hsh = (x * 73856093u) ^ (y * 19349663u) ^ 0x9e3779b9u;
                hsh ^= hsh >> 13; hsh *= 0x5bd1e995u; hsh ^= hsh >> 15;
                float grain = (float(hsh & 1023) / 1023.f - 0.5f) * 12.f;
                p[0] = (unsigned char)std::clamp(r / cov * 255.f + grain, 0.f, 255.f);
                p[1] = (unsigned char)std::clamp(g / cov * 255.f + grain, 0.f, 255.f);
                p[2] = (unsigned char)std::clamp(bl / cov * 255.f + grain, 0.f, 255.f);
                p[3] = (unsigned char)(cov / (S * S) * 255.f);
            }
        }
    return out;
}

bool write_png(const ThumbnailData &top_down, const std::string &path)
{
    size_t size = 0;
    void *png = tdefl_write_image_to_png_file_in_memory_ex(top_down.pixels.data(), top_down.width, top_down.height, 4, &size, MZ_DEFAULT_LEVEL, 0);
    if (!png) return false;
    FILE *f = std::fopen(path.c_str(), "wb");
    bool ok = f && std::fwrite(png, 1, size, f) == size;
    if (f) std::fclose(f);
    mz_free(png);
    return ok;
}

} // namespace bc
