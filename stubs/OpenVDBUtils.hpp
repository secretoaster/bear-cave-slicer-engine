// Bear Cave phone build: OpenVDB is left out. Only resin (SLA) hollowing
// used it; these stand-ins return nothing, so hollowing is simply skipped.
#ifndef OPENVDBUTILS_HPP
#define OPENVDBUTILS_HPP

#include <libslic3r/TriangleMesh.hpp>
#include <initializer_list>
#include <numeric>
#include <algorithm>
#include <memory>

namespace openvdb {
namespace math {
struct Coord {};
struct Vec3dArg { Vec3dArg(std::initializer_list<double>) {} };
struct Transform { Coord worldToIndexCellCentered(const Vec3dArg&) const { return {}; } };
} // namespace math
using math::Coord;
struct FloatGrid {
    using Ptr = std::shared_ptr<FloatGrid>;
    struct ConstAccessor { float getValue(const math::Coord&) const { return 0.f; } };
    ConstAccessor getConstAccessor() const { return {}; }
    const math::Transform& transform() const { static math::Transform t; return t; }
};
} // namespace openvdb

namespace Slic3r {
inline openvdb::FloatGrid::Ptr mesh_to_grid(const indexed_triangle_set&, const openvdb::math::Transform& = {},
                                            float = 1.f, float = 3.f, float = 3.f, int = 0) { return nullptr; }
inline indexed_triangle_set grid_to_mesh(const openvdb::FloatGrid&, double = 0.0, double = 0.0, bool = true) { return {}; }
inline openvdb::FloatGrid::Ptr redistance_grid(const openvdb::FloatGrid&, double, double = 3., double = 3.) { return nullptr; }
} // namespace Slic3r

#endif
