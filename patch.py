#!/usr/bin/env python3
"""Trims ElegooSlicer's build down to the slicing engine for Bear Cave.

Usage: patch.py <ElegooSlicer source dir>

Leaves out only what slicing a 3MF/STL doesn't need on a phone:
desktop screens, STEP / SVG / Draco import, OCCT text, OpenVDB (resin
hollowing). Every replacement must match, or the script stops, so an
ElegooSlicer update can't silently change what we build.
"""
import os, re, shutil, sys

ES = sys.argv[1]
HERE = os.path.dirname(os.path.abspath(__file__))


def edit(rel, pairs, regex=False):
    p = os.path.join(ES, rel)
    s = open(p, encoding="utf-8").read()
    for old, new in pairs:
        if regex:
            s2, n = re.subn(old, new, s, flags=re.S)
        else:
            n = s.count(old)
            s2 = s.replace(old, new)
        if n == 0:
            sys.exit(f"patch.py: '{old[:70]}' not found in {rel}")
        s = s2
    open(p, "w", encoding="utf-8").write(s)
    print("patched", rel)


# ---- top-level CMakeLists: engine-only mode -------------------------------
edit("CMakeLists.txt", [
    ("find_package(CURL REQUIRED)\n", "if (NOT BC_ENGINE_ONLY)\nfind_package(CURL REQUIRED)\nendif()\n"),
    ("find_package(Freetype REQUIRED)\n", "if (NOT BC_ENGINE_ONLY)\nfind_package(Freetype REQUIRED)\nendif()\n"),
    ("add_library(libcurl INTERFACE)\ntarget_link_libraries(libcurl INTERFACE CURL::libcurl)",
     "add_library(libcurl INTERFACE)\nif (NOT BC_ENGINE_ONLY)\ntarget_link_libraries(libcurl INTERFACE CURL::libcurl)\nendif()"),
    ("find_package(OpenGL REQUIRED)", "if (NOT BC_ENGINE_ONLY)\nfind_package(OpenGL REQUIRED)\nendif()"),
    ("find_package(glfw3 REQUIRED)", "if (NOT BC_ENGINE_ONLY)\nfind_package(glfw3 REQUIRED)\nendif()"),
    ("find_package(DBus REQUIRED)", "if (NOT BC_ENGINE_ONLY)\nfind_package(DBus REQUIRED)\nendif()"),
    ('message(FATAL_ERROR "OpenVDB could not be found', 'message(STATUS "OpenVDB could not be found'),
])

# ---- src/CMakeLists: only the engine + our program -------------------------
src_cm = os.path.join(ES, "src", "CMakeLists.txt")
s = open(src_cm, encoding="utf-8").read()
s = "if (BC_ENGINE_ONLY)\n" \
    "  set(SLIC3R_ENC_CHECK OFF CACHE BOOL \"\" FORCE)\n" \
    "  add_subdirectory(dev-utils)\n" \
    "  find_package(libnoise REQUIRED)\n" \
    "  add_subdirectory(libslic3r)\n" \
    "  add_subdirectory(${BC_DIR} bcslice)\n" \
    "  return()\n" \
    "endif()\n" + s
open(src_cm, "w", encoding="utf-8").write(s)
print("patched src/CMakeLists.txt")

# ---- libslic3r: drop OCCT / Draco / Freetype --------------------------------
edit("src/libslic3r/CMakeLists.txt", [
    ("    Shape/TextShape.cpp\n    Shape/TextShape.hpp\n", ""),
    ("set(OpenCASCADE_DIR \"${CMAKE_PREFIX_PATH}/lib/cmake/occt\")\nfind_package(OpenCASCADE REQUIRED)\n"
     "target_include_directories(libslic3r SYSTEM PUBLIC ${OpenCASCADE_INCLUDE_DIR})\n", ""),
    ("find_package(draco REQUIRED)\n", ""),
    ("        ${OCCT_LIBS}\n", ""),
    ("        draco::draco\n", ""),
    ("        opencv_world\n", "        opencv_core\n        opencv_imgproc\n"),
])
edit("src/libslic3r/CMakeLists.txt", [
    (r"if\(NOT WIN32\)\n    # Link freetype for OCCT dependency.*?endif\(\)\nendif\(\)\n", ""),
], regex=True)

# ---- stand-ins for the left-out readers --------------------------------------
L = os.path.join(ES, "src", "libslic3r")
shutil.copy(os.path.join(HERE, "stubs", "STEP.hpp"), os.path.join(L, "Format", "STEP.hpp"))
shutil.copy(os.path.join(HERE, "stubs", "STEP.cpp"), os.path.join(L, "Format", "STEP.cpp"))
shutil.copy(os.path.join(HERE, "stubs", "DRC.cpp"), os.path.join(L, "Format", "DRC.cpp"))
shutil.copy(os.path.join(HERE, "stubs", "svg.cpp"), os.path.join(L, "Format", "svg.cpp"))
shutil.copy(os.path.join(HERE, "stubs", "OpenVDBUtils.hpp"), os.path.join(L, "OpenVDBUtils.hpp"))
print("copied stand-ins")

# ---- Upstream hardening: members Eigen leaves uninitialised (found with Valgrind) ----
for rel, old, new in [
    ("src/libslic3r/Model.hpp", "mutable Transform3d                 m_cached_trans_matrix;",
     "mutable Transform3d                 m_cached_trans_matrix{ Transform3d::Identity() };"),
    ("src/libslic3r/Print.hpp", "    Vec3d   m_origin;", "    Vec3d   m_origin{ Vec3d::Zero() };"),
    # all-ones never matches a real timestamp, so the paint colours are always worked out on first use
    ("src/libslic3r/Model.hpp", "    mutable Timestamp        mmuseg_ts;",
     "    mutable Timestamp        mmuseg_ts{ ~Timestamp(0) };"),
    # Concentric infill builds this struct without setting these two, so short-line removal read
    # leftover memory. Defaults are the ones OrcaSlicer now uses upstream.
    ("src/libslic3r/Arachne/WallToolPaths.hpp", "    float   min_length_factor;\n", "    float   min_length_factor = 0.5f;\n"),
    ("src/libslic3r/Arachne/WallToolPaths.hpp", "    bool    is_top_or_bottom_layer;\n", "    bool    is_top_or_bottom_layer = false;\n"),
    # Only set when there is a wipe tower, but the timelapse position picker always reads it.
    ("src/libslic3r/Print.hpp", "    // generate fake extrusion\n    Vec2f pos;\n", "    // generate fake extrusion\n    Vec2f pos{ Vec2f::Zero() };\n"),
    # reset() skips these when the extruder is shared (CC2 multi-material), but E()/position()/retracted() can still read them.
    ("src/libslic3r/Extruder.hpp", "    double       m_E;\n", "    double       m_E = 0;\n"),
    ("src/libslic3r/Extruder.hpp", "    double       m_retracted;\n", "    double       m_retracted = 0;\n"),
]:
    if new not in open(os.path.join(ES, rel), encoding="utf-8").read():
        edit(rel, [(old, new)])

# ---- Same G-code every run: the mesh is cut into layers on all cores, and each core appends its
# cut lines to the layer as it finishes, so the order depends on thread timing. Everything after
# (loop chaining, island order, travel) inherits that order. Sort each layer's lines into a fixed
# order once the parallel step is done.
_TMS = "src/libslic3r/TriangleMeshSlicer.cpp"
_t = open(os.path.join(ES, _TMS), encoding="utf-8").read()
if "bc_sort_lines" not in _t:
    edit(_TMS, [
        ("using IntersectionLines = std::vector<IntersectionLine>;\n",
         """using IntersectionLines = std::vector<IntersectionLine>;

// Bear Cave: fixed order for lines collected from parallel threads.
static void bc_sort_lines(std::vector<IntersectionLines> &layers)
{
    tbb::parallel_for(tbb::blocked_range<size_t>(0, layers.size()), [&layers](const tbb::blocked_range<size_t> &r) {
        for (size_t i = r.begin(); i < r.end(); ++ i)
            std::sort(layers[i].begin(), layers[i].end(), [](const IntersectionLine &l, const IntersectionLine &m) {
                return std::make_tuple(l.a.x(), l.a.y(), l.b.x(), l.b.y(), l.a_id, l.b_id, l.edge_a_id, l.edge_b_id, int(l.edge_type), int(l.flags)) <
                       std::make_tuple(m.a.x(), m.a.y(), m.b.x(), m.b.y(), m.a_id, m.b_id, m.edge_a_id, m.edge_b_id, int(m.edge_type), int(m.flags));
            });
    });
}
"""),
        ("""                slice_facet_at_zs(vertices, transform_vertex_fn, indices[face_idx], face_edge_ids[face_idx], zs, lines, lines_mutex);
            }
        }
    );
    return lines;""",
         """                slice_facet_at_zs(vertices, transform_vertex_fn, indices[face_idx], face_edge_ids[face_idx], zs, lines, lines_mutex);
            }
        }
    );
    bc_sort_lines(lines);
    return lines;"""),
        ("""                    slice_facet_with_slabs<false>(vertices, indices, face_idx, neighbors, edge_ids, num_edges, zs, lines_bottom, lines_mutex_bottom);
                }
            }
        }
    );
    return out;""",
         """                    slice_facet_with_slabs<false>(vertices, indices, face_idx, neighbors, edge_ids, num_edges, zs, lines_bottom, lines_mutex_bottom);
                }
            }
        }
    );
    for (SlabLines *sl : { &lines_top, &lines_bottom }) {
        bc_sort_lines(sl->at_slice);
        bc_sort_lines(sl->between_slices);
    }
    return out;"""),
    ])
