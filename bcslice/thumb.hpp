#pragma once
// Software picture of the model (thumbnail and app preview). AGPL v3.
#include "libslic3r/Point.hpp"
#include "libslic3r/GCode/ThumbnailData.hpp"
#include <string>

namespace Slic3r { class Model; }

namespace bc {
Slic3r::Vec3f parse_colour(const std::string &hex, Slic3r::Vec3f fallback);
// bottom_up = rows from the bottom, the order the G-code thumbnail writer expects
Slic3r::ThumbnailData render(const Slic3r::Model &model, unsigned w, unsigned h, Slic3r::Vec3f colour, bool bottom_up);
bool write_png(const Slic3r::ThumbnailData &top_down, const std::string &path);
}
