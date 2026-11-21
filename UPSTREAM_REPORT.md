# Draft bug report (not posted)

**For:** ElegooSlicer (all seven items) and OrcaSlicer (items 2, 3, 5 and 6; Orca already fixed 1 and 4)
**Found with:** Valgrind memcheck while running libslic3r headless on Linux and Android (arm64)

## Six places that are never given a starting value, and one thread-order bug

Eigen vectors/matrices and plain integers are not zeroed when declared, so these
start with whatever was left in memory.

1. `Print::m_origin` (src/libslic3r/Print.hpp)
   `Vec3d m_origin;` — OrcaSlicer already has `Vec3d m_origin {0, 0, 0};`, ElegooSlicer does not.
   The GUI always calls `set_plate_origin()`, so desktop users never see it. Any code that
   slices without a PartPlate (CLI-style tools, tests, embedded use) gets a random origin,
   which moves brim, tree support, wipe tower and the collision checks in `Print::validate`.
   On Android this produced different G-code from run to run with identical input.
   Fix: `Vec3d m_origin{ Vec3d::Zero() };`

2. `ModelVolume::m_cached_trans_matrix` (src/libslic3r/Model.hpp)
   Read on the first `get_convex_hull_2d()` call (compared against the new matrix and fed to
   `Geometry::Transformation` → JacobiSVD). Output is not affected today because the hull is
   also invalid on first call, but it is a read of unset memory every time a volume is checked.
   Fix: `mutable Transform3d m_cached_trans_matrix{ Transform3d::Identity() };`

3. `ModelVolume::mmuseg_ts` (src/libslic3r/Model.hpp)
   Compared against `mmu_segmentation_facets.timestamp()` in `get_extruders()`. If the leftover
   value ever matched, painted colours would be skipped. Rare, but unset.
   Fix: `mutable Timestamp mmuseg_ts{ ~Timestamp(0) };` (never matches a real timestamp)

4. `Arachne::WallToolPathsParams` (src/libslic3r/Arachne/WallToolPaths.hpp)
   `FillConcentric` and `FillConcentricInternal` build this struct on the stack without setting
   `min_length_factor` or `is_top_or_bottom_layer`. `WallToolPaths::removeSmallLines` then uses
   them to decide which short lines to drop, so concentric infill depends on leftover memory.
   This one changes the toolpaths. OrcaSlicer already gives every field a default; copy that:
   `float min_length_factor = 0.5f;` and `bool is_top_or_bottom_layer = false;`

5. `FakeWipeTower::pos` (src/libslic3r/Print.hpp)
   Only set when the print has a wipe tower, but `TimelapsePosPicker::construct_printable_area_by_printer()`
   always reads it, so single-filament prints place a phantom wipe-tower area at a random spot
   when working out where to park for timelapse.
   Fix: `Vec2f pos{ Vec2f::Zero() };`

6. `Extruder::m_E`, `Extruder::m_retracted` (src/libslic3r/Extruder.hpp)
   `reset()` skips both when the extruder is shared (single-extruder multi-material, e.g. the
   Centauri Carbon 2), but `position()`, `retracted()` and `get_single_retracted_length()`
   still return them. Seen through `GCode::placeholder_parser_process`.
   Fix: `double m_E = 0;` and `double m_retracted = 0;`

7. Mesh slicing order depends on thread timing (src/libslic3r/TriangleMeshSlicer.cpp)
   `slice_make_lines` and the slab variant cut facets on all cores and append each layer's
   `IntersectionLine`s under a mutex as threads finish, so the list order changes run to run.
   Loop chaining, `Layer::make_slices` (greedy `chain_points`) and the island print order all
   inherit it. Result: the same model gives different island order and travel moves per run
   (seen on Elegoo_stringhell.3mf: 3 different G-codes in 6 runs, a few seconds apart).
   Fix: after the parallel loop, sort each layer's lines by a fixed key (end points, then
   vertex/edge ids). Cheap, and keeps the parallel speed. After this, every run on 1, 2 or 8
   cores gives byte-identical G-code. Not yet tested on the official build, but the code is the same.

Confirmed in the official ElegooSlicer 1.5.3.5 Linux build (AppImage, `--slice 0` on
ElegooToleranceTest.stl under Valgrind): items 2, 3, 4 and 6 all report. Item 1 is hidden there
because the GUI sets the origin.

## How to see it
`valgrind --tool=memcheck --track-origins=yes` on a headless slice of any STL:
After the six fixes a full slice reports none, apart from reads inside TBB's own allocator
(tbbmalloc), which Valgrind can't follow. Repeat slices give byte-identical G-code.

Reported by Bear Cave Labs (Design202, LLC), found with Claude.
